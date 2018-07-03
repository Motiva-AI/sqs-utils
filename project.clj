(defproject motiva/sqs-utils "0.3.0-SNAPSHOT"
  :description "Higher level SQS utilities for use in Motiva products"
  :url "https://github.com/Motiva-AI/sqs-utils"
  :license {:name "Proprietary" :url "https://motiva.ai"}

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/core.async "0.4.474"]
                 [io.nervous/fink-nottle "0.4.7"]
                 [org.clojure/tools.logging "0.4.1"]
                 [clj-time "0.14.4"]
                 [cheshire "5.8.0"]                     ;; json
                 [com.cognitect/transit-clj "0.8.309"]] ;; ser/de

  :repl-options {:init-ns user
                 :timeout 120000}

  :profiles {:dev {:source-paths   ["src" "dev/src"]
                   :test-paths     ["test"]
                   :resource-paths ["resources" "dev/resources"]

                   :dependencies [[circleci/bond "0.3.1"]
                                  [eftest "0.5.2"]
                                  [org.clojure/tools.namespace "0.2.11"]
                                  [viebel/codox-klipse-theme "0.0.5"]
                                  [motiva/wait-for "0.1.0"]
                                  [environ "1.1.0"]]

                   :plugins [[s3-wagon-private "1.3.1"]
                             [test2junit "1.3.3"]
                             [lein-eftest "0.5.2"]
                             [lein-codox "0.10.3"]
                             [lein-environ "1.1.0"]]

                   :codox {:metadata {:doc/format :markdown}}

                   :test2junit-output-dir "target/test2junit"
                   :test2junit-run-ant    true

                   :env {:sqs-endpoint          "http://localhost:9324"
                         :sqs-region            "us-east-2"
                         :aws-access-key-id     "local"
                         :aws-secret-access-key "local"}}}

  :repositories {"private" {:url           "s3p://maven-private-repo/releases/"
                            :no-auth       true
                            :sign-releases false}})
