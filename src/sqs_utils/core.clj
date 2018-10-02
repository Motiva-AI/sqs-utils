(ns sqs-utils.core
  (:require [clojure.core.async
             :as async
             :refer [chan go-loop <! >! <!! >!! thread]]
            [clojure.tools.logging :as log]
            [clj-time.core :as t]
            [sqs-utils.serde :as serde]
            [fink-nottle.sqs :as sqs]
            [fink-nottle.sqs.tagged :as sqs.tagged]
            [fink-nottle.sqs.channeled :as sqs.channeled]))

;; auto ser/de transit messages

(defmethod sqs.tagged/message-in  :transit [_ body]
  (serde/transit-read body))
(defmethod sqs.tagged/message-out :transit [_ body]
  (serde/transit-write body))

;; CRUD ;;;;;;

(defn receive-one!
  [sqs-config queue-url]
  (let [{:keys [body] :as message}
        (<!! (sqs.channeled/receive! sqs-config queue-url {:maximum 1}))]
    (<!! (sqs/processed! sqs-config queue-url message))
    body))

(defn receive-loop!
  "A background loop to receive SQS messages from a queue indefinitely.

   Arguments:
   sqs-config  - A map of the following keys, used for interacting with SQS:
      access-key - AWS access key ID
      secret-key - AWS secret access key
      endpoint   - SQS queue endpoint - usually an HTTPS based URL
      region     - AWS region
   queue-url - URL of the queue
   out-chan  - async channel where messages will be passed into
   opts      - an optional map containing the following keys:

      auto-delete        - boolean, if true, immediately delete the message,
                           if false, forward a `done` function and leave the
                           message intact.

      visibility-timeout - how long (in seconds) a message can go unacknowledged
                           before delivery is retried.

  auto-delete defaults to true, visibility-timeout defaults to 60 seconds.

  Returns a kill function - call the function to terminate the loop."
  ([sqs-config queue-url out-chan]
   (receive-loop! sqs-config queue-url out-chan {}))

  ([sqs-config queue-url out-chan {:keys [auto-delete visibility-timeout restart-delay-seconds]
                                   :or   {auto-delete           true
                                          visibility-timeout    60
                                          restart-delay-seconds 1}
                                   :as   opts}]
   (let [loop-state (atom {:messages
                           (sqs.channeled/receive!
                             sqs-config queue-url {:visibility-timeout visibility-timeout})
                           :running true
                           :stats   {:count         0
                                     :started-at    (t/now)
                                     :restart-count 0
                                     :restarted-at  nil
                                     :queue-url     queue-url}})]

     (letfn [(restart-loop []
               ;; Make a fresh sqs.channeled/receive! call and replace the
               ;; existing messages channel with the new one.
               (log/infof "Restarting receive-loop for %s" queue-url)
               (let [messages (:messages @loop-state)]
                 (swap! loop-state
                        (fn [state]
                          (-> state
                              (assoc :messages
                                     (sqs.channeled/receive!
                                       sqs-config queue-url
                                       {:visibility-timeout visibility-timeout}))
                              (update-in [:stats :restart-count] inc)
                              (assoc-in [:stats :restarted-at] (t/now)))))
                 (async/close! messages)))

             (stop-loop []
               ;; Set running to false causing the loop to exit, close the
               ;; channels - closing out-chan signals exit to the client code.
               (when (:running @loop-state)
                 (log/infof "Terminating receive-loop for %s" queue-url)
                 (swap! loop-state assoc :running false)
                 (async/close! (:messages @loop-state))
                 (async/close! out-chan))
               (:stats @loop-state))

             (secs-between [d1 d2]
               ;; Utility for calculating an interval in seconds.
               (t/in-seconds (t/interval d1 d2)))

             (update-stats [state]
               ;; Keep track of useful information which may be useful for
               ;; debugging errors.
               (let [{:keys [started-at]} (:stats state)
                     now                  (t/now)]
                 (-> state
                     (update-in [:stats :count] inc)
                     (assoc-in [:stats :this-pass-started-at] now)
                     (assoc-in [:stats :loop-duration]
                               (secs-between (-> state :stats :started-at) now)))))]

       (go-loop []
         ;; start by updating our loop statistics
         (swap! loop-state update-stats)

         (try
           (let [{:keys [body attrs] :as message} (<! (:messages @loop-state))]
             (cond
               (nil? message) ;; closed, this loop is dead
               (stop-loop)

               ;; we have a message, is it an error?
               (isa? (class message) clojure.lang.IExceptionInfo)
               ;; fink-nottle closes the messages channel on error, so we must
               ;; restart
               (let [{:keys [this-pass-started-at] :as stats} (:stats @loop-state)]
                 (log/warn message "Received an error from fink-nottle"
                           (assoc stats :last-wait-duration (secs-between this-pass-started-at
                                                                          (t/now))))
                 ;; Adding a restart delay so that this doesn't go into an
                 ;; infinite loop if the queue listener is failing to start
                 ;; continuously.
                 (<! (async/timeout (int (* restart-delay-seconds 1000))))
                 (restart-loop))

               ;; it's a well formed actionable message
               :else
               (let [done-fn #(<!! (sqs/processed! sqs-config queue-url message))
                     msg     (cond-> {:message body}
                               (not auto-delete) (assoc :done-fn done-fn))]
                 (if body
                   (>! out-chan msg)
                   (log/warnf "Queue %s received a nil body message: %s" queue-url message))
                 (when auto-delete
                   ;; TODO handle these in a batch-delete in another thread
                   (done-fn)))))

           (catch Exception e
             ;; this shouldn't happen, and it isn't from fink-nottle, so raise
             ;; an alarm
             (log/errorf e "Failed receiving message for %s" (:stats @loop-state))))

         (if (:running @loop-state)
           (recur)
           (log/warnf "Receive-loop terminated for %s" (:stats @loop-state))))

       ;; return a kill function
       stop-loop))))

(defn send-message*
  "Send a message to a queue."
  [sqs-config queue-url payload {:keys [message-group-id
                                        deduplication-id]}]
  (let [resp (<!! (sqs/send-message!
                    sqs-config
                    queue-url
                    (cond-> {:body payload :fink-nottle/tag :transit}
                      message-group-id (assoc :message-group-id (str message-group-id))
                      deduplication-id (assoc :message-deduplication-id (str deduplication-id)))))]
    ;; sqs/send-message! returns Exceptions into the channel
    (if (instance? Exception resp)
      (throw resp)
      resp)))

(defn send-message
  "Send a message to a standard queue."
  [sqs-config queue-url payload]
  ;; Note that standard queues don't support message-group-id
  (send-message* sqs-config queue-url payload {}))

(defn send-fifo-message
  "Send a message to a FIFO queue.

   Options:
   message-group-id - a tag that specifies the group that this message
                      belongs to. Messages belonging to the same group
                      are guaranteed FIFO
   deduplication-id -  token used for deduplication of sent messages"
  [sqs-config
   queue-url
   payload
   {message-group-id :message-group-id
    deduplication-id :deduplication-id
    :as options}]
  (send-message* sqs-config queue-url payload options))

;; Controls ;;;;;;;;;;;;;;;;;

(defn handle-queue
  "Set up a loop that listens to a queue and process incoming messages.

   Arguments:
   sqs-config  - A map of the following keys, used for interacting with SQS:
      access-key - AWS access key ID
      secret-key - AWS secret access key
      endpoint   - SQS queue endpoint - usually an HTTPS based URL
      region     - AWS region
   queue-url  - URL of the queue
   handler-fn - a function which will be passed the incoming message. If
                auto-delete is false, a second argument will be passed a `done`
                function to call when finished processing.
   opts       - an optional map containing the following keys:
      num-handler-threads - how many threads to run (defaults: 4)

      auto-delete        - boolean, if true, immediately delete the message,
                           if false, forward a `done` function and leave the
                           message intact. (defaults: true)

      visibility-timeout - how long (in seconds) a message can go unacknowledged
                           before delivery is retried. (defaults: 60)

  See http://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html
  for more information about visibility timeout.

  Returns:
  a kill function - call the function to terminate the loop."
  ([sqs-config queue-url handler-fn {:keys [num-handler-threads auto-delete visibility-timeout]
                                     :or   {num-handler-threads 4
                                            auto-delete         true
                                            visibility-timeout  60}
                                     :as   opts}]
   (log/infof "Starting receive loop for %s with num-handler-threads: %d, auto-delete: %s, visibility-timeout: %d"
              queue-url num-handler-threads auto-delete visibility-timeout)
   (let [receive-chan (chan)
         stop-fn      (receive-loop! sqs-config
                                     queue-url
                                     receive-chan
                                     {:auto-delete        auto-delete
                                      :visibility-timeout visibility-timeout})]
     (dotimes [_ num-handler-threads]
       (thread
         (loop []
           (when-let [coll (<!! receive-chan)]
             (try
               (if auto-delete
                 (handler-fn (:message coll))
                 (handler-fn (:message coll) (:done-fn coll)))
               (catch Throwable t
                 (log/error t "SQS handler function threw an error")))
             (recur)))))
     stop-fn))
  ([sqs-config queue-url handler-fn]
   (handle-queue sqs-config queue-url handler-fn {})))

