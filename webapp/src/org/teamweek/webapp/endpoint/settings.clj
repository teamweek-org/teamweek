(ns org.teamweek.webapp.endpoint.settings
  (:require [compojure.core :refer :all]))

(defn settings-endpoint [config]
  (context "/settings" []
   (GET "/" []
     "Hello World")))
