(ns sqs-utils.integration-test
  (:require  [clojure.test :refer :all]
             [clojure.core.async
              :as async
              :refer [chan <!! >!! close!]]
             [clj-time.core :as t]
             [environ.core :as env]
             [sqs-utils.core :as sqs-utils]
             [sqs-utils.test-utils :as sqs-test-utils])
  (:import [java.util UUID]))

(defn uuid [] (UUID/randomUUID))

(defn sqs-config []
  {:access-key (env/env :integration-aws-access-key)
   :secret-key (env/env :integration-aws-secret-key)
   :endpoint   "https://sqs.us-west-2.amazonaws.com"
   :region     "us-west-2"})

(def standard-queue-url
  "https://sqs.us-west-2.amazonaws.com/658488433453/standard_integration_test")

(def fifo-queue-url
  "https://sqs.us-west-2.amazonaws.com/658488433453/fifo_integration_test.fifo")

(deftest ^:integration receive-one-test
  (testing "standard queue"
    (let [coll {:testing (str (uuid))}]
      (is (sqs-utils/send-message (sqs-config) standard-queue-url coll))
      (is (= coll (sqs-utils/receive-one! (sqs-config) standard-queue-url)))))
  (testing "fifo queue"
    (let [coll {:testing (str (uuid))}]
      (is (sqs-utils/send-fifo-message (sqs-config) fifo-queue-url 3 coll))
      (is (= coll (sqs-utils/receive-one! (sqs-config) fifo-queue-url))))))

(deftest ^:integration send-and-receive-message-test
  (testing "standard queue"
    (let [c (chan)
          msg1 {:testing (str (uuid))}
          msg2 {:testing (str (uuid))}]
      (is (sqs-utils/send-message (sqs-config) standard-queue-url msg1))
      (let [stop-fn (sqs-utils/receive-loop! (sqs-config) standard-queue-url c)]
        (is (= msg1 (:message (<!! c))))
        (is (sqs-utils/send-message (sqs-config) standard-queue-url msg2))
        (is (= msg2 (:message (<!! c))))
        (let [stats (stop-fn)]
          (is (empty? (:restarts stats)))))))
  (testing "fifo queue"
    (let [c (chan)
          msg1 {:testing (str (uuid))}
          msg2 {:testing (str (uuid))}]
      (is (sqs-utils/send-fifo-message (sqs-config) fifo-queue-url 2 msg1))
      (let [stop-fn (sqs-utils/receive-loop! (sqs-config) fifo-queue-url c)]
        (is (= msg1 (:message (<!! c))))
        (is (sqs-utils/send-fifo-message (sqs-config) fifo-queue-url 1 msg2))
        (is (= msg2 (:message (<!! c))))
        (let [stats (stop-fn)]
          (is (empty? (:restarts stats))))))))

(deftest ^:integration roundtrip-datetime-test
  (let [coll {:data [1 2 3]
              :uuid (str (uuid))
              :timestamp (t/now)}]
    (is (sqs-utils/send-message (sqs-config) standard-queue-url coll))
    (is (= coll (sqs-utils/receive-one! (sqs-config) standard-queue-url)))))

(defn wrap-purge-queues
  [f]
  (let [config (sqs-config)]
    (sqs-test-utils/purge-queue! config standard-queue-url)
    (sqs-test-utils/purge-queue! config fifo-queue-url)
    (Thread/sleep 5000)
    (f)
    (sqs-test-utils/purge-queue! config standard-queue-url)
    (sqs-test-utils/purge-queue! config fifo-queue-url)))

(use-fixtures :once wrap-purge-queues)
