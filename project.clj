(defproject motiva/sqs-utils "0.10.0-SNAPSHOT"
  :description "Higher level SQS utilities for use in Motiva products"
  :url "https://github.com/Motiva-AI/sqs-utils"
  :license {:name "MIT License"
            :url  "https://opensource.org/licenses/MIT"}

  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "1.3.610"]
                 [org.clojure/core.cache "1.0.207"]
                 [io.nervous/fink-nottle "0.4.7"]
                 ;; Cognitect Labs' aws-api project
                 ;; [com.cognitect.aws/api "0.8.289"]
                 ;; [com.cognitect.aws/endpoints "1.1.11.526"]
                 ;; [com.cognitect.aws/sqs "697.2.391.0"]
                 [org.clojure/tools.logging "1.1.0"]
                 [clj-time "0.15.2"]
                 [cheshire "5.10.0"]                     ;; json
                 [com.cognitect/transit-clj "1.0.324"]] ;; ser/de

  :repl-options {:init-ns user
                 :timeout 120000}

  :profiles {:dev {:source-paths   ["src" "dev/src"]
                   ;; "test" is included by default - adding it here confuses
                   ;; circleci.test which runs everything twice.
                   :test-paths     []
                   :resource-paths ["resources" "dev/resources"]

                   :dependencies [[circleci/bond "0.4.0"]
                                  [circleci/circleci.test "0.4.3"]
                                  [eftest "0.5.9"]
                                  [org.clojure/tools.namespace "1.0.0"]
                                  [viebel/codox-klipse-theme "0.0.5"]
                                  [motiva/wait-for "0.3.0"]
                                  [motiva/inspect "0.1.1"]
                                  [environ "1.2.0"]]

                   :plugins [[s3-wagon-private "1.3.2"]
                             [lein-eftest "0.5.3"]
                             [lein-codox "0.10.5"]
                             [lein-environ "1.1.0"]]

                   :codox {:metadata {:doc/format :markdown}}

                   :env {:sqs-endpoint                        "http://localhost:9324"
                         :sqs-region                          "us-east-2"
                         :standard-integration-test-queue-url ""
                         :fifo-integration-test-queue-url     ""
                         :aws-access-key-id                   "local"
                         :aws-secret-access-key               "local"
                         :integration-aws-access-key          ""
                         :integration-aws-secret-key          ""}}}

  :aliases {"test"   ["run" "-m" "circleci.test/dir" :project/test-paths]
            "tests"  ["run" "-m" "circleci.test"]
            "retest" ["run" "-m" "circleci.test.retest"]}

  :repositories [["private" {:url           "s3p://maven-private-repo/releases/"
                             :no-auth       true
                             :sign-releases false}]
                 ["releases" {:url           "https://clojars.org/repo"
                              :username      "motiva-ai"
                              :password      :env
                              :sign-releases false}]])
