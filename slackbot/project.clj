(defproject slackbot "0.1.0-SNAPSHOT"
  :description "Teamweek slackbot"
  :url "https://teamweek.org"
  :license {:name "Mozilla Public License, version 2.0"
            :url "https://www.mozilla.org/en-US/MPL/2.0/"}
  :pedantic? true
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.2.374"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [com.datomic/datomic-free "0.9.5344"]
                 [aleph "0.4.1-beta3"]
                 [cheshire "5.5.0"]
                 [clojurewerkz/quartzite "2.0.0"]
                 [com.taoensso/timbre "4.1.4"]]
  :main ^:skip-aot org.teamweek.slackbot
  :profiles {:uberjar {:aot :all}})
