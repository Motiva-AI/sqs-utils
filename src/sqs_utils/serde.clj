(ns sqs-utils.serde
  (:require [cognitect.transit :as transit]
            [cheshire.core :as json])
  (:import [org.joda.time DateTime]
           [java.io ByteArrayOutputStream ByteArrayInputStream]))

(def joda-time-writer
  (transit/write-handler
    "joda-time"
    (fn [v] (.toString v))))

(def joda-time-reader
  (transit/read-handler
    (fn [r] (DateTime/parse r))))

(defn transit-write [x]
  (let [baos (ByteArrayOutputStream.)
        w    (transit/writer baos :json
                             {:handlers {DateTime joda-time-writer}})
        _    (transit/write w x)
        ret  (.toString baos)]
    (.reset baos)
    ret))

(defn transit-read [s]
  (-> (.getBytes s)
      (ByteArrayInputStream.)
      (transit/reader :json
                      {:handlers {"joda-time" joda-time-reader}})
      (transit/read)))
