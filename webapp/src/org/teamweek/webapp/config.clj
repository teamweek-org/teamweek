(ns org.teamweek.webapp.config
  (:require [environ.core :refer [env]]))

(def defaults
  {:http {:port 3000}
   :app {:datomic-uri "datomic:free://localhost:4334/teamweek"
         :es-uri "http://localhost:9200"}})

(def environ
  {:http {:port (some-> env :port Integer.)}
   :app {:datomic-uri (env :datomic-uri)
         :es-uri (env :es-uri)}})
