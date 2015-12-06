(ns org.teamweek.webapp.endpoint.index-test
  (:require [clojure.test :refer :all]
            [org.teamweek.webapp.endpoint.index :as index]))

(def handler
  (index/index-endpoint {}))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 1))))
