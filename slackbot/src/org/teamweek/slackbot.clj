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
            [clojurewerkz.quartzite.schedule.simple :as simple-scheduler] ;; TODO remove
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
               (send-to-user! conn username question)
               (conj question-answers
                     {:ts (java.util.Date.)
                      :team (:name (:team conn))
                      :domain (:domain (:team conn))
                      :user username
                      :question question
                      :answer (:text (async/<!! answer-chan))}))
             []
             questions)]
    (async/unsub dm-pub username answer-chan)
    (async/close! answer-chan)
    qas))


(defjob NoOpJob [ctx]
  (comment "Does nothing!"))

(defjob QuestionnaireJob [ctx]
  )

(defn schedule-questionnaire-job [scheduler domain cron-string]
  (let [job (jobs/build
             (jobs/of-type QuestionnaireJob)
             (jobs/using-job-data {:domain domain})
             (jobs/with-identity (jobs/key (str "jobs." domain))))
        trigger (triggers/build
                 (triggers/with-identity (triggers/key (str "triggers." domain)))
                 (triggers/with-schedule
                   (cron/schedule
                    (cron/cron-schedule cron-string))))]
    (scheduler/schedule scheduler job trigger)))

(defn init-start-jobs [scheduler db]
  (doseq [{:keys [domain schedule]} (d/q '[:find (pull ?team [:team/domain :team/schedule])
                                           :where
                                           [?team :team/domain]]
                                         db)]
    (schedule-questionnaire-job scheduler
                                domain
                                schedule)))

(defn -main [db-uri]
  (let [scheduler (scheduler/start (scheduler/initialize))
        db-conn (d/connect db-uri)
        db (d/db db-conn)]
    (init-start-jobs scheduler db))
  (println "Hello world!"))

(comment
  (def conn (connect slack-token))

  ((:shutdown-fn conn))

  (send-to-user! conn "jonas" "ping!")


  (async/thread
    (let [answers (send-questionnaire conn "jonas" ["How are you today?"
                                                    "What are your plans for tomorrow?"])]
      (send-to-user! conn "jonas" (str "You answered: " (pr-str answers)))))

  )
