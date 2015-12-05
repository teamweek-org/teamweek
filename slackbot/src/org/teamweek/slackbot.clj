(ns org.teamweek.slackbot
  (:require [clojure.core.async :as async]
            [aleph.http :as http]
            [manifold.stream :as stream]
            [cheshire.core :as json]
            [byte-streams])
  (:gen-class))

;; for testing
(defonce slack-token (or (System/getenv "SLACK_TOKEN")
                         (throw (ex-info "No SLACK_TOKEN provided" {}))) )

(defn connect-url [token]
  (str "https://slack.com/api/rtm.start?token=" token))

(defn keep-alive [ws-conn]
  (future (while true
            (Thread/sleep 5000)
            @(stream/put! ws-conn
                          (json/generate-string {:id (rand-int 1e6)
                                                 :type "ping"})))))

(defn route-messages [conn incoming-messages]
  (future (while true
            (let [msg (json/parse-string @(stream/take! (:ws-conn conn)) true)]
              (condp = (:type msg)
                "message" (when-not (= (:user msg) (:id (:self conn)))
                            (async/put! incoming-messages msg))
                (do #_ignore))))))

(defn websocket-client [url incoming-messages]
  (let [conn @(http/websocket-client url)
        first-msg (json/parse-string @(stream/take! conn))]
    (when-not (= first-msg {"type" "hello"})
      (throw (ex-info "Unable to connect" first-msg)))
    (keep-alive conn)

    conn))

(defn connect [token]
  (let [conn-data (-> (str "https://slack.com/api/rtm.start?token=" token)
                      (http/get)
                      deref
                      :body
                      byte-streams/to-string
                      (json/parse-string true))
        incoming-messages (async/chan)
        ws-conn (websocket-client (:url conn-data)
                                  incoming-messages)
        conn (assoc conn-data
                    :ws-conn ws-conn
                    :incoming-messages incoming-messages)]
    (route-messages conn incoming-messages)
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

(defn find-username [conn user-id]
  (if-let [username (some #(if (= (:id %) user-id)
                             (:name %))
                          (:users conn))]
    username
    (throw (ex-info "No such user" {:user-id user-id}))))

(comment
  (def conn (connect slack-token))

  conn

  (send-to-user! conn "ivan" "Send me a message!")


  (async/thread
    (loop [msg (async/<!! (:incoming-messages conn))]
      (when msg
        (let [user-id (:user msg)
              _ (assert (not= (:user msg) (:id (:self conn))))
              username (find-username conn user-id)
              dm? (.startsWith (:channel msg) "D")]
          (if dm?
            (send-to-user! conn username (format "Hi %s! You said \"%s\"" username (:text msg))))
          (recur (async/<!! (:incoming-messages conn)))))))

  )

(defn -main [& args]
  (println "Hello world!"))
