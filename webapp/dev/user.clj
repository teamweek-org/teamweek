(ns user
  (:require [clojure.repl :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as component]
            [eftest.runner :as eftest]
            [meta-merge.core :refer [meta-merge]]
            [reloaded.repl :refer [system init start stop go reset]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [org.teamweek.webapp.config :as config]
            [org.teamweek.webapp.system :as system]
            [clj-http.client :as http]
            [datomic.api :as d]
            [cheshire.core :as json]))

(def dev-config
  {:app {:middleware [wrap-stacktrace]}})

(def config
  (meta-merge config/defaults
              config/environ
              dev-config))

(defn new-system []
  (into (system/new-system config)
        {}))

(ns-unmap *ns* 'test)

(defn test []
  (eftest/run-tests (eftest/find-tests "test") {:multithread? false}))

(when (io/resource "local.clj")
  (load "local"))

(reloaded.repl/set-init! new-system)

(comment

  (d/create-database "datomic:free://localhost:4334/teamweek")

  (http/put "http://localhost:9200/teamweek-org" {:body (slurp (io/resource "mappings.json"))})

  (http/post "http://localhost:9200/teamweek-org/answer/" {:body (json/encode {:text "#teamweek rocks!" :created (java.util.Date.) :member "ivan"})})

  (http/get "http://localhost:9200/teamweek-org/_search")

  (http/get "http://localhost:9200/teamweek-org/_search" {:body (json/encode {:query {:query_string {:analyze_wildcard "true"
                                                                                             :query "#TEamWeEk"}}})})

  )