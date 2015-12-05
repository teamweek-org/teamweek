(ns org.teamweek.webapp.endpoint.search
  (:require [compojure.core :refer :all]))

(defn search-endpoint [config]
  (context "/search" []
   (GET "/" [] "Hello World")))
