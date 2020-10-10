(ns sqs-utils.test-utils
  "Utilities and helpers for dev and test use"
  (:require [clojure.tools.logging :as log]
            [sqs-utils.impl :as impl]
            [clj-sqs-extended.aws.sqs :as sqs])
  (:import [java.util UUID]))

(defn random-queue-name []
  (str "test-queue-" (UUID/randomUUID)))

(defn create-queue!
  ([sqs-config queue-name]
   (let [url (sqs/create-standard-queue! (impl/sqs-ext-client sqs-config) queue-name)]
     (log/info "Created queue at" url)
     url))
  ([sqs-config]
   (create-queue! sqs-config (random-queue-name))))

(defn delete-queue! [sqs-config queue-url]
  (log/info "Deleting queue at" queue-url)
  (sqs/delete-queue! (impl/sqs-ext-client sqs-config) queue-url))

(defn purge-queue!
  [sqs-config queue-url]
  (log/info "Purging queue at" queue-url)
  (sqs/purge-queue! (impl/sqs-ext-client sqs-config) queue-url))

(defn init-queue
  "This should NOT be invoked in production.

  Create a single queue returning a map in the format suitable for
  rebinding to the configuration map function.

  If not supplied, n-threads = 1, timeout = 1800"
  ([sqs-config queue]
   (init-queue sqs-config queue 1 1800))
  ([sqs-config queue n-threads timeout]
   (init-queue sqs-config queue n-threads timeout 1))
  ([sqs-config queue n-threads timeout n-consumers]
   {(keyword queue)
    {:url                (create-queue! sqs-config (name queue))
     :threads            n-threads
     :consumers          n-consumers
     ;; TODO remove visibility-timeout
     :visibility-timeout timeout}}))

