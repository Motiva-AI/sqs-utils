(ns sqs-utils.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async :refer [chan <!! >!! close! alt!! alts!!]]
            [clojure.core.async.impl.protocols]
            [clj-sqs-extended.aws.sqs :as sqs]
            [clj-time.core :as t]
            [sqs-utils.core :as su]
            [sqs-utils.impl]
            [sqs-utils.test-utils :as test-utils]
            [environ.core :refer [env]]
            [wait-for.core :refer [wait-for]]
            [inspect.core :refer [inspect]]
            [bond.james :as bond])
  (:import [java.lang NoSuchMethodError Exception]))

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

;; (use-fixtures :each wrap-queue)

(defmacro with-queue [& body]
  `(wrap-queue (fn [] ~@body)))

(deftest receive-one-test
  (doseq [format [:json :transit]]
    (with-queue
      (let [creds (sqs-config)]
        (is (su/send-message creds @test-queue-url {:testing 3} {:format format}))
        (is (= {:testing 3} (su/receive-one! creds @test-queue-url)))))))

(deftest send-and-receive-message-test
  (doseq [format [:json :transit]]
    (with-queue
      (let [c (chan)
            creds (sqs-config)]
        (is (su/send-message creds @test-queue-url {:testing 2} {:format format}))
        (let [stop-fn (su/receive-loop! creds @test-queue-url c)]
          (is (fn? stop-fn))
          (is (= {:testing 2}
                 (:message (<!! c))))
          (is (su/send-message creds @test-queue-url {:testing 1}))
          (is (= {:testing 1}
                 (:message (<!! c))))
          (stop-fn))))))

(deftest done-fn-present-when-not-auto-deleting
  (doseq [format [:json :transit]]
    (with-queue
      (let [creds (sqs-config)]
        (testing "auto-delete is true"
          (let [c       (chan)
                stop-fn (su/receive-loop! creds @test-queue-url c {:auto-delete true})]
            (is (su/send-message creds @test-queue-url {:testing 4} {:format format}))
            (let [result (<!! c)]
              (is (= {:testing 4} (:message result)))
              (is (not (contains? result :done-fn))))
            (stop-fn)))
        (testing "auto-delete is false"
          (let [c       (chan)
                stop-fn (su/receive-loop! creds @test-queue-url c {:auto-delete false})]
            (is (su/send-message creds @test-queue-url {:testing 5} {:format format}))
            (let [result (<!! c)]
              (is (= {:testing 5} (:message result)))
              (is (contains? result :done-fn)))
            (stop-fn)))))))

(deftest client-acknowledgement-works
  (doseq [format [:json :transit]]
    (let [visibility-timeout 1
          test-queue-url     (atom nil)
          creds              (sqs-config)]
      ;; setup a test queue with specified visibility-timeout
      (reset! test-queue-url (sqs/create-standard-queue!
                               (sqs-utils.impl/sqs-ext-client creds)
                               (test-utils/random-queue-name)
                               {:visibility-timeout-in-seconds visibility-timeout}))

      (testing "acknowledged messages don't get resent"
        (let [c       (chan)
              stop-fn (su/receive-loop! creds @test-queue-url c {:auto-delete false})]
          (is (su/send-message creds @test-queue-url {:testing 6} {:format format}))
          (let [{:keys [message done-fn]} (<!! c)]
            (is (= {:testing 6} message))
            (is (some? done-fn))
            ;; call the function, then wait for it not to show up :P
            (done-fn)
            (is (alt!!
                  c                     false
                  (async/timeout
                    (* (inc visibility-timeout)
                       1000))           true)))
          (stop-fn)))

      (testing "unacknowledged messages get resent"
        (let [c       (chan)
              stop-fn (su/receive-loop! creds @test-queue-url c {:auto-delete false})]
          (is (su/send-message creds @test-queue-url {:testing 7} {:format format}))
          (let [{:keys [message done-fn]} (<!! c)]
            (is (= {:testing 7} message))
            (is (some? done-fn))
            ;; don't call it, wait for the next one
            (wait-for
              #(alt!!
                 c ([{:keys [message done-fn]} _]
                    (= message {:testing 7}))
                 (async/timeout 500) false)
              :timeout 5))
          (stop-fn)))

      ;; teardown
      (test-utils/delete-queue! creds @test-queue-url))))

(deftest send-message-test
  (doseq [format [:json :transit]]
    (with-queue
      (let [{:keys [endpoint] :as creds} (sqs-config)]
        (testing "Success case"
          (is (string? (su/send-message creds @test-queue-url {:testing 28} {:format format}))))

        (testing "Fail case"
          (is (thrown-with-msg?
                Exception
                #"NonExistentQueue"
                (su/send-message creds
                                 (str endpoint "/queue/non-existing")
                                 {:testing 1}
                                 {:format format}))))))))

(deftest send-fifo-message-test
  (doseq [format [:json :transit]]
    (with-queue
      (testing "Fail case, missing :message-group-id"
        (is (thrown? java.lang.AssertionError
                     (su/send-fifo-message
                       (sqs-config)
                       @test-queue-url
                       {:testing 1}
                       {:format format})))))))

(deftest roundtrip-datetime-test
  (with-queue
    (let [coll {:data [1 2 3]
                :timestamp (t/now)}
          creds (sqs-config)]
      (is (su/send-message creds @test-queue-url coll))
      (is (= coll (su/receive-one! creds @test-queue-url))))))

(deftest terminate-receive-loop
  (doseq [format [:json :transit]]
    (with-queue
      (let [c (chan)
            creds (sqs-config)]
        (is (su/send-message creds @test-queue-url {:testing 4} {:format format}))
        (let [stop-fn (su/receive-loop! creds @test-queue-url c)]
          (is (some? stop-fn))
          (is (= {:testing 4} (:message (<!! c))))
          ;; terminate the loop, close the channel
          (stop-fn)
          ;; send a message to the queue, which still exists
          (su/send-message creds @test-queue-url {:testing 5} {:format format})
          ;; closed channel should return nil - TODO some other way to verify?
          (is (nil? (<!! c))))))))

(deftest handle-queue-test
  (testing "Simple case without options"
    (with-queue
      (let [creds   (sqs-config)
            message {:testing 3}

            c       (chan)
            handler-fn (fn [& args] (>!! c args))

            stop-fn (su/handle-queue creds @test-queue-url handler-fn)]

        (is (su/send-message creds @test-queue-url message))
        (is (= [message] (<!! c)))

        (stop-fn))))

  (testing "Auto-delete off"
    (with-queue
      (let [creds   (sqs-config)
            message {:testing 3}

            c       (chan)
            handler-fn (fn [& args] (>!! c args))

            stop-fn (su/handle-queue creds
                                     @test-queue-url
                                     handler-fn
                                     {:auto-delete false})]

        (is (su/send-message creds @test-queue-url message))
        (let [[out-message out-done-fn] (<!! c)]
          (is (=  message out-message))
          (is (fn? out-done-fn)))

        (stop-fn)))))

(deftest receiving-error-handling-works
  (with-queue
    (let [c              (chan)
          messages-chan1 (chan)
          creds          (sqs-config)]
      (bond/with-stub! [[sqs/receive-to-channel (constantly messages-chan1)]]
        (let [stop-fn (su/receive-loop! creds @test-queue-url c)]
          (try
            (is (fn? stop-fn))
            (>!! messages-chan1 {:body :hello})
            (is (= :hello (:message (<!! c))))
            (is (= 1 (-> sqs/receive-to-channel bond/calls count)))
            ;; set up the next channel to return when receive is called
            (let [messages-chan2 (chan)]
              (bond/with-stub! [[sqs/receive-to-channel (constantly messages-chan2)]]
                ;; ensure it hasn't been called yet
                (is (= 0 (-> sqs/receive-to-channel bond/calls count)))
                ;; fire off an error
                (>!! messages-chan1 (ex-info "fail test message" {}))
                ;; receive should be called again
                (wait-for #(= 1 (-> sqs/receive-to-channel bond/calls count)))
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
                (let [stats (stop-fn)]
                  (is (= 1 (:restart-count stats)))
                  (is (instance? org.joda.time.DateTime (:restarted-at stats)))
                  (is (= 3 (:count stats)))
                  ;; everything should be closed
                  (is (clojure.core.async.impl.protocols/closed? messages-chan1))
                  (is (clojure.core.async.impl.protocols/closed? messages-chan2))
                  (is (clojure.core.async.impl.protocols/closed? c)))))
            (catch Throwable t
              (stop-fn)
              (throw t))))))))

(deftest multiple-instances-of-receive-loop-test
  (with-queue
    (let [creds   (sqs-config)
          message {:testing 3}

          n       10
          c       (chan)

          stop-fns (doall
                    (for [_ (range n)]
                      (su/receive-loop!
                        creds
                        @test-queue-url
                        c)))]

      (is (= n (count stop-fns)))

      (is (su/send-message creds @test-queue-url message))
      (is (= message (:message (<!! c))))

      ;; teardown
      (println  "tearing down...")
      (doseq [stop-fn stop-fns]
        (stop-fn))
      (close! c))))

