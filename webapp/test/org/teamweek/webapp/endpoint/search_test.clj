(ns org.teamweek.webapp.endpoint.search-test
  (:require [clojure.test :refer :all]
            [org.teamweek.webapp.endpoint.search :as search]))

(def handler
  (search/search-endpoint {}))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 1))))
