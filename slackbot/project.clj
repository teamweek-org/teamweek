(defproject slackbot "0.1.0-SNAPSHOT"
  :description "Teamweek slackbot"
  :url "https://teamweek.org"
  :license {:name "Mozilla Public License, version 2.0"
            :url "https://www.mozilla.org/en-US/MPL/2.0/"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [aleph "0.4.1-beta3"]
                 [cheshire "5.5.0"]]
  :main ^:skip-aot org.teamweek.slackbot
  :profiles {:uberjar {:aot :all}})
