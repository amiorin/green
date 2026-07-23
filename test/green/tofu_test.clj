(ns green.tofu-test
  "Backend advices need no tofu binary — they only write backend.tf.json."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [green.tofu :as tofu])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmpdir []
  (str (Files/createTempDirectory "green-tofu" (make-array FileAttribute 0))))

(defn- backend-config [dir]
  (json/parse-string (slurp (io/file dir "backend.tf.json"))))

(deftest local-backend-is-the-default
  (let [dir (tmpdir)
        advice (tofu/local-backend-advice (constantly dir))
        opts {:x 1}]
    (is (= opts (advice opts)) "before-advice passes opts through")
    (is (= {"terraform" {"backend" {"local" {}}}}
           (backend-config dir)))))

(deftest s3-backend-advice-writes-attributes
  (let [dir (tmpdir)]
    ((tofu/s3-backend-advice (constantly dir)
                             {:bucket "my-state"
                              :key "green/node-1.tfstate"
                              :region "eu-west-1"
                              :encrypt true})
     {})
    (is (= {"terraform"
            {"backend"
             {"s3" {"bucket" "my-state"
                     "encrypt" true
                     "key" "green/node-1.tfstate"
                     "region" "eu-west-1"}}}}
           (backend-config dir)))))

(deftest backend-config-retains-native-json-shapes
  (let [dir (tmpdir)]
    ((tofu/backend-advice (constantly dir)
                          :test
                          {:keyword :value
                           :enabled true
                           :retries 3
                           :nested {:endpoint "https://example.test"}
                           :items [:one "two"]
                           :unset nil})
     {})
    (is (= {"terraform"
            {"backend"
             {"test" {"keyword" "value"
                      "enabled" true
                      "retries" 3
                      "nested" {"endpoint" "https://example.test"}
                      "items" ["one" "two"]
                      "unset" nil}}}}
           (backend-config dir)))))

(deftest backend-config-can-be-a-function-of-opts
  (let [dir (tmpdir)]
    ((tofu/gcs-backend-advice (constantly dir)
                              (fn [opts] {:bucket "state"
                                          :prefix (str "green/" (:node opts))}))
     {:node "n1"})
    (is (= {"terraform"
            {"backend"
             {"gcs" {"bucket" "state"
                     "prefix" "green/n1"}}}}
           (backend-config dir)))))
