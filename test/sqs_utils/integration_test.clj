(ns sqs-utils.integration-test
  (:require  [clojure.test :refer :all]
             [clojure.core.async
              :as async
              :refer [chan <!!]]
             [clj-time.core :as t]
             [environ.core :as env]
             [sqs-utils.core :as sqs-utils]
             [sqs-utils.test-utils :as sqs-test-utils])
  (:import [java.util UUID]))

(defn sqs-config []
  {:access-key (env/env :integration-aws-access-key)
   :secret-key (env/env :integration-aws-secret-key)
   :endpoint   "https://sqs.us-west-2.amazonaws.com"
   :region     "us-west-2"})

(def standard-queue-url
   (env/env :standard-integration-test-queue-url))

(def fifo-queue-url
  (env/env :fifo-integration-test-queue-url))

(defn wrap-purge-integration-queues
  [f]
  (let [config (sqs-config)]
    ;; From AWS: Only one PurgeQueue operation is allowed every 60 seconds
    (sqs-test-utils/purge-queue! config standard-queue-url)
    (sqs-test-utils/purge-queue! config fifo-queue-url)
    (Thread/sleep 5000)
    (f)))

(use-fixtures :once wrap-purge-integration-queues)

(defn uuid [] (UUID/randomUUID))

(deftest ^:integration receive-one-test
  (doseq [format [:json :transit]]
    (testing "standard queue"
      (let [coll {:testing (str (uuid))}]
        (is (sqs-utils/send-message (sqs-config) standard-queue-url coll {:format format}))
        (is (= coll (sqs-utils/receive-one! (sqs-config) standard-queue-url)))))
    (testing "fifo queue"
      (let [coll {:testing (str (uuid))}]
        (is (sqs-utils/send-fifo-message (sqs-config) fifo-queue-url coll {:message-group-id 3
                                                                           :format format}))
        (is (= coll (sqs-utils/receive-one! (sqs-config) fifo-queue-url)))))))

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
          (is (empty? (:restarts stats))))))))

(deftest ^:integration send-and-receive-fifo-message-test
  (testing "fifo queue"
    (let [c (chan)
          msg1 {:testing (str (uuid))}
          msg2 {:testing (str (uuid))}]
      (is (sqs-utils/send-fifo-message (sqs-config) fifo-queue-url msg1 {:message-group-id 2}))
      (let [stop-fn (sqs-utils/receive-loop! (sqs-config) fifo-queue-url c)]
        (is (= msg1 (:message (<!! c))))
        (is (sqs-utils/send-fifo-message (sqs-config) fifo-queue-url msg2 {:message-group-id 1}))
        (is (= msg2 (:message (<!! c))))
        (let [stats (stop-fn)]
          (is (empty? (:restarts stats)))))))

  (testing  "fifo queue with deduplication"
    (let  [c    (chan)
           msg1 {:testing 1}
           msg2 {:testing 2}
           msg3 {:testing 3}]
      (is  (sqs-utils/send-fifo-message  (sqs-config) fifo-queue-url msg1 {:message-group-id 1
                                                                           :deduplication-id 1}))
      (is  (sqs-utils/send-fifo-message  (sqs-config) fifo-queue-url msg2 {:message-group-id 1
                                                                           :deduplication-id 1}))
      (is  (sqs-utils/send-fifo-message  (sqs-config) fifo-queue-url msg3 {:message-group-id 1
                                                                           :deduplication-id 2}))
      (let  [stop-fn  (sqs-utils/receive-loop!  (sqs-config) fifo-queue-url c)]
        (is  (= msg1 (:message (<!! c))))
        ;; second message doesn't exist because it has been de-duped
        (is  (= msg3 (:message (<!! c))))
        (let  [stats  (stop-fn)]
          (is  (empty?  (:restarts stats))))))))

