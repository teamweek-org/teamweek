(ns org.teamweek.slackbot
  (:require [clojure.core.async :as async]
            [datomic.api :as d]
            [aleph.http :as http]
            [manifold.stream :as stream]
            [cheshire.core :as json]
            [clojurewerkz.quartzite.scheduler :as scheduler]
            [clojurewerkz.quartzite.jobs :as jobs :refer (defjob)]
            [clojurewerkz.quartzite.triggers :as triggers]
            [clojurewerkz.quartzite.schedule.cron :as cron]
            [clojurewerkz.quartzite.conversion :as conversion]
            [taoensso.timbre :as log]
            [byte-streams])
  (:gen-class))

;; for testing
(defonce slack-token (or (System/getenv "SLACK_TOKEN")
                         (throw (ex-info "No SLACK_TOKEN provided" {}))) )

(defn connect-url [token]
  (str "https://slack.com/api/rtm.start?token=" token))

(defn keep-alive [ws-conn shutdown?]
  (future (while (not @shutdown?)
            (Thread/sleep 5000)
            @(stream/put! ws-conn
                          (json/generate-string {:id (rand-int 1e6)
                                                 :type "ping"})))))

(defn find-username [conn user-id]
  (if-let [username (some #(if (= (:id %) user-id)
                             (:name %))
                          (:users conn))]
    username
    (throw (ex-info "No such user" {:user-id user-id}))))

(defn direct-message? [msg]
  (.startsWith (:channel msg) "D"))

(defn route-messages [conn incoming-messages shutdown?]
  (future (while (not @shutdown?)
            (let [msg (json/parse-string @(stream/take! (:ws-conn conn)) true)]
              (condp = (:type msg)
                "message" (when (and (not= (:user msg) (:id (:self conn)))
                                     (direct-message? msg))
                            (async/put! incoming-messages
                                        (assoc msg
                                               :username (find-username conn (:user msg)))))
                (do #_ignore))))))

(defn websocket-client [url shutdown?]
  (let [conn @(http/websocket-client url)
        first-msg (json/parse-string @(stream/take! conn))]
    (when-not (= first-msg {"type" "hello"})
      (throw (ex-info "Unable to connect" first-msg)))
    (keep-alive conn shutdown?)
    conn))

(defn connect [token]
  (let [conn-data (-> (str "https://slack.com/api/rtm.start?token=" token)
                      (http/get)
                      deref
                      :body
                      byte-streams/to-string
                      (json/parse-string true))
        shutdown? (atom false)
        incoming-messages (async/chan)
        dm-pub (async/pub incoming-messages
                          (fn topic-fn [msg]
                            (:username msg)))
        ws-conn (websocket-client (:url conn-data)
                                  shutdown?)
        shutdown-fn (fn []
                      (println "Shutting down" (:url conn-data))
                      (reset! shutdown? true)
                      (async/close! incoming-messages)
                      (stream/close! ws-conn)
                      (println "ok."))
        conn (assoc conn-data
                    :ws-conn ws-conn
                    :incoming-messages incoming-messages
                    :dm-pub dm-pub
                    :shutdown-fn shutdown-fn)]
    (route-messages conn incoming-messages shutdown?)
    conn))

(defn find-user-dm-id [conn user]
  (let [user-id (some #(if (= (:name %) user)
                         (:id %))
                      (:users conn))
        dm-id (some #(if (= (:user %) user-id)
                       (:id %))
                    (:ims conn))]
    (or dm-id
        (throw (ex-info "No such user" {:user user})))))

(defn send-to-user! [conn user msg]
  @(stream/put! (:ws-conn conn)
                (json/generate-string {:id (rand-int 1e6)
                                       :type "message"
                                       :channel (find-user-dm-id conn user)
                                       :text msg})))

(defn find-channel-id [conn channel]
  (if-let [id (some #(if (= (:name %) channel)
                       (:id %))
                    (:channels conn))]
    id
    (throw (ex-info "No such channel" {:channel channel}))))

(defn send-to-channel! [conn channel msg]
  @(stream/put! (:ws-conn conn)
                (json/generate-string {:id (rand-int 1e6)
                                       :type "message"
                                       :channel (find-channel-id conn channel)
                                       :text msg})))

(defn send-questionnaire
  [conn username questions]
  (send-to-user! conn username
                 (format "Greetings %s. I have a few questions for you." username))
  (let [dm-pub (:dm-pub conn)
        answer-chan (async/chan)
        dm-sub (async/sub dm-pub username answer-chan)
        qas (reduce
             (fn [question-answers question]
               (send-to-user! conn username (:question/text question))
               (conj question-answers
                     {:ts (java.util.Date.)
                      :team (:name (:team conn))
                      :domain (:domain (:team conn))
                      :user username
                      :question-id (:db/id question)
                      :answer (:text (async/<!! answer-chan))}))
             []
             questions)]
    (async/unsub dm-pub username answer-chan)
    (async/close! answer-chan)
    qas))


(defn submit-answers [db-conn domain user answers]
  ;; TODO insert into datomic db
  (let [db (d/db db-conn)
        user-eid (d/q '[:find ?member .
                        :in $ ?domain ?user
                        :where
                        [?team :team/domain ?domain]
                        [?team :team/members ?member]
                        [?member :member/name ?user]]
                      db domain user)
        tx (for [answer answers]
             {:db/id (d/tempid :db.part/user)
              :answer/author user-eid
              :answer/text (:answer answer)
              :answer/ts (:ts answer)
              :question/_answers (:question-id answer)})]
    (prn tx)
    @(d/transact db-conn tx)))

(defjob QuestionnaireJob [job-data]
  (let [{:strs [domain db-conn]} (conversion/from-job-data job-data)
        db (d/db db-conn)
        token (:team/token (d/entity db [:team/domain domain]))
        users (d/q '[:find [?name ...]
                     :in $ ?domain
                     :where
                     [?team :team/domain ?domain]
                     [?team :team/members ?member]
                     [?member :member/name ?name]]
                   db domain)
        questions (d/q '[:find [(pull ?question [:db/id :question/text :question/order]) ...]
                         :in $ ?domain
                         :where
                         [?team :team/domain ?domain]
                         [?team :team/questions ?question]]
                       db domain)
        conn (when (and token
                        (not-empty users)
                        (not-empty questions))
               (connect token))]
    (if conn
      ;; TODO in parallell, but the job is not finished before all has answered/timeout
      (do (doseq [user users]
            (let [answers (send-questionnaire conn user (sort-by :question/order questions))]
              (submit-answers db-conn domain user answers)))
          ((:shutdown-fn conn)))
      (do (println "Nothing to do")
          (println "Token" token) ;; TODO proper logging!
          (println "Users" users)
          (println "Questions" questions)))))

(defn schedule-questionnaire-job [scheduler db-conn domain cron-string]
  (let [job (jobs/build
             (jobs/of-type QuestionnaireJob)
             (jobs/using-job-data {:domain domain
                                   :db-conn db-conn})
             (jobs/with-identity (jobs/key (str "jobs." domain))))
        trigger (triggers/build
                 (triggers/with-identity (triggers/key (str "triggers." domain)))
                 (triggers/with-schedule
                   (cron/schedule
                    (cron/cron-schedule cron-string))))]
    (scheduler/schedule scheduler job trigger)
    (println "Scheduler is running")))

(defn init-start-jobs [scheduler db-conn]
  (let [db (d/db db-conn)]
    (doseq [{:keys [team/domain team/schedule]}
            (d/q '[:find [(pull ?team [:team/domain :team/schedule]) ...]
                   :where
                   [?team :team/domain]]
                 db)]
      (println "Scheduling" domain schedule)
      (schedule-questionnaire-job scheduler
                                   db-conn
                                   domain
                                   schedule))) )

(defn schedule-new [schduler db-before db-after]
  (let [new-domains (d/q '[:find [?new-domains ...]
                           :in $db-before $db-after
                           :where
                           [$db-after ?team :team/domain ?new-domains]
                           [(missing? $db-before ?team :team/domain)]]
                         db-before db-after)]
    (prn "New domains:" new-domains)))

(defn schedule-removed [scheduler db-before db-after]
  (let [removed-domains (d/q '[:find [?removed-domains ...]
                               :in $ $db-after
                               :where
                               [$ ?team :team/domain ?removed-domains]
                               [(missing? $db-after ?team :team/domain)]]
                             db-before db-after)]
    (prn "Removed domains:" removed-domains)))

(defn schedule-updates [scheduler db-before db-after]
  (let [updated-crons (d/q '[:find ?domain ?schedule-after
                             :in $db-before $db-after
                             :where
                             [$db-after  ?team :team/domain ?domain]
                             [$db-before ?team :team/schedule ?schedule-before]
                             [$db-after  ?team :team/schedule ?schedule-after]
                             [(not= ?schedule-before ?schedule-after)]]
                           db-before db-after)]
    (prn "Updated crons:" updated-crons)))

(defn listen-for-team-updates [scheduler db-conn]
  (let [queue (d/tx-report-queue db-conn)]
    (while true
      (let [{:keys [db-before
                    db-after
                    tx-data]} (.take queue)]
        ;; No figure out if we need to
        ;; add/remove a scheduled job
        ;; or update the cron job for some
        (schedule-new scheduler db-before db-after)
        (schedule-removed scheduler db-before db-after)
        (schedule-updates scheduler db-before db-after)
        (println "============================================")))))

(defn -main [db-uri]
  (log/merge-config! {:level :info
                      :output-fn (partial log/default-output-fn {:stacktrace-fonts {}})})
  (let [scheduler (scheduler/start (scheduler/initialize))
        db-conn (d/connect db-uri)]
    (init-start-jobs scheduler db-conn)
    (listen-for-team-updates scheduler db-conn)))

(comment
  (def conn (connect slack-token))

  ((:shutdown-fn conn))

  (send-to-user! conn "jonas" "ping!")


  (async/thread
    (let [answers (send-questionnaire conn "jonas" ["How are you today?"
                                                    "What are your plans for tomorrow?"])]
      (send-to-user! conn "jonas" (str "You answered: " (pr-str answers)))))

  ;; db stuff

  (def db-uri "datomic:free://localhost:4334/teamweek")
  (-main db-uri)


  (d/delete-database db-uri)
  (d/create-database db-uri)

  (def db-conn (d/connect db-uri))

  (d/q '[:find (pull ?answer [:answer/author :answer/text :answer/ts])
         :where
         [?answer :answer/text]]
       (d/db db-conn))


  (d/transact db-conn (read-string (slurp "../webapp/resources/schema.edn")))

  (future (listen-for-team-updates nil db-conn))


  (defn create-team [domain slack-token schedule-string members questions]
    {:db/id (d/tempid :db.part/user)
     :team/domain domain
     :team/token slack-token
     :team/schedule schedule-string
     :team/members (for [[name email] members]
                     {:member/name name
                      :member/email email})
     :team/questions (for [text questions]
                       {:question/text text})})

  (def every-minute "0 * * * * ?")

  (d/transact db-conn [(create-team "teamweek-org" slack-token every-minute
                                    [#_["jonas" "jonas@gmail"]
                                     ["ivan" "ivan@gmail"]]
                                    ["How are you today?"
                                     "What are your plans for tomorrow?"])])

  (d/transact db-conn [[:db.fn/retractEntity [:team/domain "teamweek-org"]]])




  )
