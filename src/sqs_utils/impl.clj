(ns sqs-utils.impl
  (:require [clj-sqs-extended.aws.sqs :as sqs]
            [clojure.core.async :refer [<! >! go-loop chan]]))

;; basics

(defn sqs-ext-config [{access-key   :access-key
                       secret-key   :secret-key
                       sqs-endpoint :endpoint
                       region       :region}]
  {:access-key    access-key
  :secret-key     secret-key
  :s3-endpoint    nil ;; TODO
  :s3-bucket-name nil
  :sqs-endpoint   sqs-endpoint
  :region         region})

(defn sqs-ext-client [sqs-config]
  (-> sqs-config
      (sqs-ext-config)
      (sqs/sqs-ext-client)))

(defn- multiplex
  [chs]
  (let [c (chan)]
    (doseq [ch chs]
      (go-loop []
        (let [v (<! ch)]
          (>! c v)
          (when (some? v)
            (recur)))))
    c))

(defn receive!
  [sqs-config queue-url opts & n]
  (let [opts (merge {:maximum 10 :wait-seconds 20} opts)
        n (or (first n) 1)]
    (if (= 1 n)
      (sqs/receive-to-channel (sqs-ext-client sqs-config) queue-url opts)
      (multiplex
        (loop [chs [] n n]
          (if (= n 0)
            chs
            (let [ch (sqs/receive-to-channel (sqs-ext-client sqs-config) queue-url opts)]
              (recur (conj chs ch) (dec n)))))))))

(defn processed!
  [sqs-config queue-url message]
  (sqs/delete-message! (sqs-ext-client sqs-config) queue-url message))

(defn send-message!
  [sqs-config
   queue-url
   payload
   {:keys [format]
    :or {format :transit}
    :as options}]
  (sqs/send-message (sqs-ext-client sqs-config) queue-url payload options))

(defn send-fifo-message!
  [sqs-config
   queue-url
   payload
   {:keys [message-group-id
           deduplication-id
           format]
    :or {format :transit}
    :as options}]
  (sqs/send-fifo-message
    (sqs-ext-client sqs-config)
    queue-url
    payload
    message-group-id
    options))

