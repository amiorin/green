(ns green.tofu-test
  "Backend advices need no tofu binary — they only write backend.tf."
  (:require [clojure.test :refer [deftest is]]
            [green.tofu :as tofu])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmpdir []
  (str (Files/createTempDirectory "green-tofu" (make-array FileAttribute 0))))

(deftest local-backend-is-the-default
  (let [dir (tmpdir)
        advice (tofu/local-backend-advice (constantly dir))
        opts {:x 1}]
    (is (= opts (advice opts)) "before-advice passes opts through")
    (is (= "terraform {\n  backend \"local\" {\n  }\n}\n"
           (slurp (str dir "/backend.tf"))))))

(deftest s3-backend-advice-writes-attributes
  (let [dir (tmpdir)]
    ((tofu/s3-backend-advice (constantly dir)
                             {:bucket "my-state"
                              :key "green/node-1.tfstate"
                              :region "eu-west-1"
                              :encrypt true})
     {})
    (is (= (str "terraform {\n"
                "  backend \"s3\" {\n"
                "    bucket = \"my-state\"\n"
                "    encrypt = true\n"
                "    key = \"green/node-1.tfstate\"\n"
                "    region = \"eu-west-1\"\n"
                "  }\n"
                "}\n")
           (slurp (str dir "/backend.tf"))))))

(deftest backend-config-can-be-a-function-of-opts
  (let [dir (tmpdir)]
    ((tofu/gcs-backend-advice (constantly dir)
                              (fn [opts] {:bucket "state"
                                          :prefix (str "green/" (:node opts))}))
     {:node "n1"})
    (is (= (str "terraform {\n"
                "  backend \"gcs\" {\n"
                "    bucket = \"state\"\n"
                "    prefix = \"green/n1\"\n"
                "  }\n"
                "}\n")
           (slurp (str dir "/backend.tf"))))))
