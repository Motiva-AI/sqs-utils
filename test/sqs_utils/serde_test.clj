(ns sqs-utils.serde-test
  (:require [sqs-utils.serde :as serde]
            [clojure.test :refer :all]
            [clj-time.core :as t]))

(deftest roundtrip-transit-test
  (testing "Basic map"
    (let [coll {:testing 1}]
      (is (= coll
             (serde/transit-read (serde/transit-write coll))))))
  (testing "With Date type"
    (let [coll {:timestamp (t/now)}]
      (is (= coll
             (serde/transit-read (serde/transit-write coll)))))))
