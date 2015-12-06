(ns org.teamweek.webapp.endpoint.views
  (:require [ring.middleware.anti-forgery :refer (*anti-forgery-token*)]))

(defn team-page [req]
  (str "T" *anti-forgery-token*))
