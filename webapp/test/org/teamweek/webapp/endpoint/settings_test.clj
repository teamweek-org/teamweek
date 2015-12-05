(ns org.teamweek.webapp.endpoint.settings-test
  (:require [clojure.test :refer :all]
            [org.teamweek.webapp.endpoint.settings :as settings]))

(def handler
  (settings/settings-endpoint {}))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 1))))
