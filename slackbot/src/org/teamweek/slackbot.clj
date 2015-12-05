(ns org.teamweek.slackbot
  (:require [aleph.http :as http]
            [manifold.stream :as stream]
            [cheshire.core :as json]
            [byte-streams])
  (:gen-class))

;; for testing
(defonce slack-token (or (System/getenv "SLACK_TOKEN")
                         (throw (ex-info "No SLACK_TOKEN provided" {}))) )

(defn connect-url
  "Given a slack token, returns a websocket url"
  [token]
  (-> (str "https://slack.com/api/rtm.start?token=" token)
      (http/get)
      deref
      :body
      byte-streams/to-string
      json/parse-string
      (get "url")))

(comment
  (let [conn @(http/websocket-client (connect-url slack-token))]
    @(stream/take! conn))

  )

(defn -main [& args]
  (println "Hello world!"))
