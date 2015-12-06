(ns org.teamweek.webapp.middleware.datomic
  (:require [datomic.api :as d]))


;; Bobby Calderwood
;; https://gist.github.com/bobby/3150938

(defn wrap-datomic
  "A Ring middleware that provides a request-consistent database connection and
  value for the life of a request."
  [handler uri]
  (fn [request]
    (let [conn (d/connect uri)]
      (handler (assoc request
                 :conn conn
                 :db   (d/db conn))))))