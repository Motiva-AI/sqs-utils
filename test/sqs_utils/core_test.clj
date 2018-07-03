(ns sqs-utils.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async :refer [chan <!! >!! close! alt!!]]
            clojure.core.async.impl.protocols
            [clj-time.core :as t]
            [fink-nottle.sqs.channeled :as sqs.channeled]
            [sqs-utils.core :as su]
            [sqs-utils.test-utils :as test-utils]
            [environ.core :refer [env]]
            [wait-for.core :refer [wait-for]]
            [bond.james :as bond]))

(def test-queue-url (atom nil))

(defn sqs-config []
  {:access-key (env :aws-access-key-id)
   :secret-key (env :aws-secret-access-key)
   :endpoint   (env :sqs-endpoint)
   :region     (env :sqs-region)})

(defn wrap-queue [f]
  (reset! test-queue-url (test-utils/create-queue! (sqs-config)))
  (f)
  (test-utils/delete-queue! (sqs-config) @test-queue-url))

;; create and delete queue are async and the queue might not be available
;; between tests if we do :each - take note
(use-fixtures :each wrap-queue)

(deftest receive-one-test
  (let [creds (sqs-config)]
    (is (su/send-message creds @test-queue-url {:testing 3}))
    (is (= {:testing 3} (su/receive-one! creds @test-queue-url)))))

(deftest send-and-receive-message-test
  (let [c (chan)
        creds (sqs-config)]
    (is (su/send-message creds @test-queue-url {:testing 2}))
    (is (su/receive-loop! creds @test-queue-url c))
    (is (= {:testing 2}
           (:message (<!! c))))
    (is (su/send-message creds @test-queue-url {:testing 1}))
    (is (= {:testing 1}
           (:message (<!! c))))))

(deftest receipt-handle-present-when-not-auto-deleting
  (let [creds (sqs-config)]
    (testing "auto-delete is true"
      (let [c       (chan)
            stop-fn (su/receive-loop! creds @test-queue-url c {:auto-delete true})]
        (is (su/send-message creds @test-queue-url {:testing 4}))
        (let [result (<!! c)]
          (is (= {:testing 4} (:message result)))
          (is (not (contains? result :done-fn))))
        (stop-fn)))
    (testing "auto-delete is false"
      (let [c       (chan)
            stop-fn (su/receive-loop! creds @test-queue-url c {:auto-delete false})]
        (is (su/send-message creds @test-queue-url {:testing 5}))
        (let [result (<!! c)]
          (is (= {:testing 5} (:message result)))
          (is (contains? result :done-fn)))
        (stop-fn)))))

(deftest client-acknowledgement-works
  (let [creds (sqs-config)]
    (testing "acknowledged messages don't get resent"
      (let [c       (chan)
            stop-fn (su/receive-loop! creds @test-queue-url c {:auto-delete false
                                                               :visibility-timeout 5})]
        (is (su/send-message creds @test-queue-url {:testing 6}))
        (let [{:keys [message done-fn]} (<!! c)]
          (is (= {:testing 6} message))
          (is (some? done-fn))
          ;; call the function, then wait for it not to show up :P
          (done-fn)
          (is (alt!!
                c false
                (async/timeout 10000) true)))
        (stop-fn)))
    (testing "unacknowledged messages get resent"
      (let [c       (chan)
            stop-fn (su/receive-loop! creds @test-queue-url c {:auto-delete false
                                                               :visibility-timeout 5})]
        (is (su/send-message creds @test-queue-url {:testing 7}))
        (let [{:keys [message done-fn]} (<!! c)]
          (is (= {:testing 7} message))
          (is (some? done-fn))
          ;; don't call it, wait for the next one
          (wait-for
            #(alt!!
               c ([{:keys [message done-fn]} _]
                  (= message {:testing 7}))
               (async/timeout 500) false)
            :timeout 30))
        (stop-fn)))))

(deftest send-message-test
  (let [{:keys [endpoint] :as creds} (sqs-config)]
    (testing "Success case"
      (is (= [:id :body-md5]
             (keys (su/send-message creds @test-queue-url {:testing 28})))))

    (testing "Fail case"
      (is (thrown? clojure.lang.ExceptionInfo
                   (su/send-message creds
                                    (str endpoint "/queue/non-existing")
                                    {:testing 1}))))))

(deftest roundtrip-datetime-test
  (let [coll {:data [1 2 3]
              :timestamp (t/now)}
        creds (sqs-config)]
    (is (su/send-message creds @test-queue-url coll))
    (is (= coll (su/receive-one! creds @test-queue-url)))))

(deftest terminate-receive-loop
  (let [c (chan)
        creds (sqs-config)]
    (is (su/send-message creds @test-queue-url {:testing 4}))
    (let [kill-fn (su/receive-loop! creds @test-queue-url c)]
      (is (some? kill-fn))
      (is (= {:testing 4} (:message (<!! c))))
      ;; terminate the loop, close the channel
      (kill-fn)
      ;; send a message to the queue, which still exists
      (su/send-message creds @test-queue-url {:testing 5})
      ;; closed channel should return nil - TODO some other way to verify?
      (is (nil? (<!! c))))))

(deftest fink-nottle-error-handling-works
  (let [c              (chan)
        messages-chan1 (chan)
        creds          (sqs-config)]
    (bond/with-stub! [[sqs.channeled/receive! (constantly messages-chan1)]]
      (let [kill-fn (su/receive-loop! creds @test-queue-url c)]
        (is (some? kill-fn))
        (>!! messages-chan1 {:body :hello})
        (is (= :hello (:message (<!! c))))
        (is (= 1 (-> sqs.channeled/receive! bond/calls count)))
        ;; set up the next channel to return when receive is called
        (let [messages-chan2 (chan)]
          (bond/with-stub! [[sqs.channeled/receive! (constantly messages-chan2)]]
            ;; ensure it hasn't been called yet
            (is (= 0 (-> sqs.channeled/receive! bond/calls count)))
            ;; fire off an error
            (>!! messages-chan1 (ex-info "test message" {}))
            ;; receive should be called again
            (wait-for #(= 1 (-> sqs.channeled/receive! bond/calls count)))
            ;; first channel should be closed
            (is (clojure.core.async.impl.protocols/closed? messages-chan1))
            ;; second channel should be ok
            (is (not (clojure.core.async.impl.protocols/closed? messages-chan2)))
            ;; out-chan should be ok
            (is (not (clojure.core.async.impl.protocols/closed? c)))
            ;; everything should still work
            (>!! messages-chan2 {:body :still-works})
            (is (= :still-works (:message (<!! c))))

            ;; terminate the loop
            (let [stats (kill-fn)]
              (is (= 1 (:restart-count stats)))
              (is (instance? org.joda.time.DateTime (:restarted-at stats)))
              (is (= 3 (:count stats)))
              ;; everything should be closed
              (is (clojure.core.async.impl.protocols/closed? messages-chan1))
              (is (clojure.core.async.impl.protocols/closed? messages-chan2))
              (is (clojure.core.async.impl.protocols/closed? c)))))))))
