(ns org.teamweek.slackbot
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
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

(def config (atom {}))

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
  (try
    (log/info "Attempting to connect to" token)
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
                        (log/info "Shutting down" (:url conn-data))
                        (reset! shutdown? true)
                        (async/close! incoming-messages)
                        (stream/close! ws-conn)
                        (log/info "Successfully shut down" (:url conn-data)))
          conn (assoc conn-data
                      :ws-conn ws-conn
                      :incoming-messages incoming-messages
                      :dm-pub dm-pub
                      :shutdown-fn shutdown-fn)]
      (route-messages conn incoming-messages shutdown?)
      (log/info "Successfully connected to" token)
      conn)
    (catch Exception e
      (log/error e "Failed to connect to" token)))
  )

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
  [conn token username questions]
  (try
    (send-to-user! conn username
                   (format "Greetings %s. I have a few questions for you." username))
    (let [dm-pub (:dm-pub conn)
          answer-chan (async/chan)
          dm-sub (async/sub dm-pub username answer-chan)
          qas (reduce
               (fn [question-answers question]
                 (log/infof "Sending \"%s\" to %s" (:question/text question) username)
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
      (send-to-user! conn username
                     (format "Thank you for your answers. You can view your teams data now or later at https://teamweek.org/search?t=%s"
                             token))
      (log/infof "Questionnaire completed for %s. %s questions answered" username (count qas))
      (async/unsub dm-pub username answer-chan)
      (async/close! answer-chan)
      qas)
    (catch Exception e
      (log/errorf e "Failed to send questions %s to %s" (pr-str questions) username)
      (log/error "Connection: %s" (pr-str conn))
      (throw e))))


(defn submit-answers [db-conn domain user answers]
  (try
    (log/infof "Submitting %s answers to %s for %s" (count answers) domain user)
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
                :question/_answers (:question-id answer)})
          es-uri (:es-uri @config)]
      @(d/transact db-conn tx)
      (doseq [answer answers]
        @(http/post (str es-uri domain "/answer/") {:body (json/encode {:text (:answer answer) :created (:ts answer) :member user})}))
      (log/infof "Successfully Submitted %s answers to %s for %s" (count answers) domain user))
    (catch Exception e
      (log/errorf e "Failed to submit answers %s for domain %s and user %s"
                  (pr-str answers) domain user)
      (throw e))))

(defjob QuestionnaireJob [job-data]
  (try
    (let [{:strs [domain db-conn]} (conversion/from-job-data job-data)
        _ (log/info "Scheduled job started for" domain)
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
      (do
        (log/infof "Sending quesitonnaire for %s (%s users and %s questions)" domain users questions)
        (dorun
         (pmap (fn [user]
                 (let [answers (send-questionnaire conn token user (sort-by :question/order questions))]
                   (submit-answers db-conn domain user answers)))
               users))
        ((:shutdown-fn conn)))
      (do (log/infof "Skipping questionnaire for %s (%s users, %s questions and the token is %s"
                     domain (count users) (count questions) token))))
    (catch Exception e
      (log/errorf e "Questionnaire job failed")
      (throw e))))

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
    (log/info "Scheduler for" domain "is running with schedule" cron-string)))

(defn init-start-jobs [scheduler db-conn]
  (log/info "Starting the initial schedulers")
  (let [db (d/db db-conn)]
    (doseq [{:keys [team/domain team/schedule]}
            (d/q '[:find [(pull ?team [:team/domain :team/schedule]) ...]
                   :where
                   [?team :team/domain]]
                 db)]
      (if schedule
        (do (log/info "Scheduling" domain "with schedule" schedule)
            (schedule-questionnaire-job scheduler
                                        db-conn
                                        domain
                                        schedule))
        (do (log/info "Skipping scheduling for" domain))))))

(defn schedule-new [scheduler db-conn db-before db-after]
  (try
    (let [new-domains (d/q '[:find [?new-domains ...]
                              :in $db-before $db-after
                              :where
                              [$db-after ?team :team/domain ?new-domains]
                              [(missing? $db-before ?team :team/domain)]]
                            db-before db-after)]
       (when-not (empty? new-domains)
         (log/info "New domains to schedule" (str/join ", " new-domains)))
       (doseq [domain new-domains]
         (if-let [schedule (:team/schedule (d/entity db-after [:team/domain domain]))]
           (do (log/infof "Scheduling new domain %s with cron %s" domain schedule)
               (schedule-questionnaire-job scheduler db-conn domain schedule))
           (do (log/infof "Skipping scheduler for domain %s" domain)))))
    (catch Exception e
      (log/error e "Failed to create new schedule"))))

(defn schedule-removed [scheduler db-conn db-before db-after]
  (try
    (let [removed-domains (d/q '[:find [?removed-domains ...]
                                 :in $ $db-after
                                 :where
                                 [$ ?team :team/domain ?removed-domains]
                                 [(missing? $db-after ?team :team/domain)]]
                               db-before db-after)]
      (when-not (empty? removed-domains)
        (log/info "Domains removed" (str/join ", " removed-domains)))
      (doseq [domain removed-domains]
        (log/info "Unscheduling" domain)
        (scheduler/delete-job scheduler (jobs/key (str "jobs." domain)))))
    (catch Exception e
      (log/error e "Failed to remove scheduler"))))

(defn schedule-updates [scheduler db-conn db-before db-after]
  (try
    (let [updated-crons (d/q '[:find ?domain ?schedule-after
                                :in $db-before $db-after
                                :where
                                [$db-after  ?team :team/domain ?domain]
                                [$db-before ?team :team/schedule ?schedule-before]
                                [$db-after  ?team :team/schedule ?schedule-after]
                                [(not= ?schedule-before ?schedule-after)]]
                              db-before db-after)]
       (when-not (empty? updated-crons)
         (log/info "Updated schedules" (pr-str updated-crons)))
       (doseq [[domain new-schedule] updated-crons]
         (log/info "Unscheduling" domain)
         (scheduler/delete-job scheduler (jobs/key (str "jobs." domain)))
         (if-not (empty? new-schedule)
           (do (log/infof "Scheduling domain %s with schedule %s" domain new-schedule)
               (schedule-questionnaire-job scheduler db-conn domain new-schedule))
           (do (log/info "Skipping scheduler for domain" domain)))))
    (catch Exception e
      (log/errorf e "Failed to update schedule"))) )

(defn listen-for-team-updates [scheduler db-conn]
  (let [queue (d/tx-report-queue db-conn)]
    (while true
      (let [{:keys [db-before
                    db-after
                    tx-data]} (.take queue)]
        (schedule-new scheduler db-conn db-before db-after)
        (schedule-removed scheduler db-conn db-before db-after)
        (schedule-updates scheduler db-conn db-before db-after)))))

(defn -main [db-uri es-uri]
  (log/merge-config! {:level :info
                      :output-fn (partial log/default-output-fn {:stacktrace-fonts {}})})
  (log/info "Starting the slackbot with" db-uri)
  (swap! config assoc "es-uri" es-uri "db-uri" db-uri)
  (let [scheduler (scheduler/start (scheduler/initialize))
        db-conn (d/connect db-uri)]
    (init-start-jobs scheduler db-conn)
    (future (listen-for-team-updates scheduler db-conn))))

(comment

  ;; for testing
  (defonce slack-token (or (System/getenv "SLACK_TOKEN")
                           (throw (ex-info "No SLACK_TOKEN provided" {}))) )


  ;; db stuff

  (def db-uri "datomic:free://localhost:4334/teamweek")
  (def es-uri "http://localhost:9200/")

  (-main db-uri es-uri)


  (d/delete-database db-uri)
  (d/create-database db-uri)

  (def db-conn (d/connect db-uri))

  (d/q '[:find ?answer ;(pull ?answer [:answer/author :answer/text :answer/ts])
         :where
         [?answer :answer/text]]
       (d/db db-conn))


  @(d/transact db-conn (read-string (slurp "../webapp/resources/schema.edn")))

  (future (listen-for-team-updates nil db-conn))


  (defn create-team [domain slack-token schedule-string members questions]
    {:db/id (d/tempid :db.part/user)
     :team/domain domain
     :team/token slack-token
     :team/schedule schedule-string
     :team/members (for [[name email] members]
                     {:member/name name
                      :member/email email})
     :team/questions (for [{:keys [text order]} questions]
                       {:question/text text
                        :question/order order})})

  (def every-minute "0 * * * * ?")

  @(d/transact db-conn [(create-team "teamweek-org" slack-token every-minute
                                     [["jonas" "jonas@gmail"]
                                      ["notauser" "notauser"]]
                                     [{:text "How are you today?"
                                       :order 1}
                                      {:text "What are your plans for tomorrow?"
                                       :order 2}])])

  @(d/transact db-conn [[:db.fn/retractEntity [:team/domain "teamweek-org"]]])


  @(d/transact db-conn [[:db/add [:team/domain "teamweek-org"] :team/schedule every-minute]])
  @(d/transact db-conn [[:db/add [:team/domain "teamweek-org"] :team/token "badtoken"]])




  )
