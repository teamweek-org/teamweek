(ns org.teamweek.webapp.endpoint.example
  (:require [compojure.core :refer :all]
            [clojure.java.io :as io]))

(defn example-endpoint [config]
  (context "/example" []
    (GET "/" []
      (io/resource "org/teamweek/webapp/endpoint/example/example.html"))))
