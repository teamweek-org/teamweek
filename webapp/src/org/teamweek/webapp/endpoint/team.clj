(ns org.teamweek.webapp.endpoint.team
  (:require [compojure.core :refer :all]
            [clj-http.client :as http]
            [cheshire.core :refer [parse-string]]
            [datomic.api :as d]))


(defn filter-user-data
  "Extract useful data from a user"
  [usr]
  {:name (:name usr)
   :email (get-in usr [:profile :email])
   :bot? (:is_bot usr)})

(defn get-team-data
  "Get team data based on a given token"
  [token]
  (let [req (http/get (str "https://slack.com/api/rtm.start?token=" token))
        team-data (parse-string (:body req) true)
        domain (get-in team-data [:team :domain])
        name (get-in team-data [:team :name])
        members (remove #(or (= (:name %) "slackbot")(:bot? %))
                  (map filter-user-data (:users team-data)))]
    {:domain domain
     :name name
     :members members}))

(defn team-endpoint [config]
  (context "/team" []
   (GET "/" req
     (str ring.middleware.anti-forgery/*anti-forgery-token*))
   (POST "/" req
     (prn (:form-params req))
     "Hola")))
