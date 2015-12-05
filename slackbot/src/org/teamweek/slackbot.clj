(ns org.teamweek.slackbot
  (:require [aleph.http :as http]
            [manifold.stream :as stream]
            [cheshire.core :as json]
            [byte-streams])
  (:gen-class))

;; for testing
(defonce slack-token (or (System/getenv "SLACK_TOKEN")
                         (throw (ex-info "No SLACK_TOKEN provided" {}))) )

(defn connect-url [token]
  (str "https://slack.com/api/rtm.start?token=" token))

(defn connect
  "Given a slack token, returns a websocket url"
  [token]
  )

(defn websocket-client [url]
  (let [conn @(http/websocket-client url)
        first-msg (json/parse-string @(stream/take! conn))]
    (when-not (= first-msg {"type" "hello"})
      (throw (ex-info "Unable to connect" first-msg)))
    conn))

(defn keep-alive [ws-conn])

(defn connect [token]
  (let [conn-data (-> (str "https://slack.com/api/rtm.start?token=" token)
                      (http/get)
                      deref
                      :body
                      byte-streams/to-string
                      json/parse-string)
        ws-conn (websocket-client (get conn-data "url"))]
    (assoc conn-data "ws-conn" ws-conn)))

(defn take! [conn]
  (json/parse-string @(stream/take! (get conn "url"))))

(defn put! [conn msg]
  @(stream/put! (get conn "url") (json/generate-string msg)))

(defn find-user-dm-id [conn user]
  (let [user-id (some #(if (= (get % "name") user)
                         (get % "id"))
                      (get conn "users"))
        dm-id (some #(if (= (get % "user") user-id)
                       (get % "id"))
                    (get conn "ims"))]
    (or dm-id
        (throw (ex-info "No such user" {:user user})))))

(defn send-to-user! [conn user msg]
  @(stream/put! (get conn "ws-conn")
                (json/generate-string {:id (rand-int 1e6)
                                       :type "message"
                                       :channel (find-user-dm-id conn user)
                                       :text msg})))

(defn find-channel-id [conn channel]
  (if-let [id (some #(if (= (get % "name") channel)
                       (get % "id"))
                    (get conn "channels"))]
    id
    (throw (ex-info "No such channel" {:channel channel}))))

(defn send-to-channel! [conn channel msg]
  @(stream/put! (get conn "ws-conn")
                (json/generate-string {:id (rand-int 1e6)
                                       :type "message"
                                       :channel "#general"
                                       :text msg})))

(comment
  (def conn (connect slack-token))

  (send-to-user! conn "ivan" "Hello :)")

  )

(defn -main [& args]
  (println "Hello world!"))
