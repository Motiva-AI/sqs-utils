(ns sqs-utils.impl
  (:require [fink-nottle.sqs.channeled :as sqs.channeled]
            [fink-nottle.sqs.tagged :as sqs.tagged]
            [fink-nottle.sqs :as sqs]
            [sqs-utils.serde :as serde]
            [cheshire.core :as json]
            [clojure.core.async :as async :refer [<!!]]
            [clojure.core.async.lab :as async.lab]))

;; auto ser/de transit messages

(defmethod sqs.tagged/message-in  :transit [_ body]
  (serde/transit-read body))
(defmethod sqs.tagged/message-out :transit [_ body]
  (serde/transit-write body))

;; similarly json

(defmethod sqs.tagged/message-in :json [_ body]
  (json/parse-string body true))
(defmethod sqs.tagged/message-out :json [_ body]
  (json/generate-string body))

;; basics

(defn receive!
  [sqs-config queue-url opts & n]
  (let [opts (merge {:maximum 10 :wait-seconds 20} opts)]
    (if-let [n (first n)]
      (apply async.lab/multiplex
             (loop [chs [] n n]
               (if (= n 0)
                 chs
                 (let [ch (sqs.channeled/receive! sqs-config queue-url opts)]
                   (recur (conj chs ch) (dec n))))))
      (sqs.channeled/receive! sqs-config queue-url opts))))

(defn processed!
  [sqs-config queue-url message]
  (sqs/processed! sqs-config queue-url message))

(defn send-message!
  [sqs-config queue-url payload {:keys [message-group-id
                                        deduplication-id
                                        format]
                                 :or {format :transit}}]
  (let [resp (<!! (sqs/send-message!
                    sqs-config
                    queue-url
                    (cond-> {:body payload :fink-nottle/tag format}
                      message-group-id (assoc :message-group-id (str message-group-id))
                      deduplication-id (assoc :message-deduplication-id (str deduplication-id)))))]
    ;; sqs/send-message! returns Exceptions into the channel
    (if (instance? Exception resp)
      (throw resp)
      resp)))
