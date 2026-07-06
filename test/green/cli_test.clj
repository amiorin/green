(ns green.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [green.cli :as cli]
            [green.workflow :as wf])
  (:import [java.io File]))

(defn- state-file [content]
  (let [f (File/createTempFile "green-state" ".edn")]
    (spit f content)
    (str f)))

(defn- probe-wf []
  (wf/workflow {:start :t/a
                :wire-fn (fn [s _]
                           (case s
                             :t/a [(fn [o] (update o :seen (fnil conj []) [:a (:green/event o) (:x o)]))
                                   :t/b]
                             :t/b [(fn [o] (update o :seen conj :b))]))}))

(deftest event-and-state-flow-into-the-workflow
  (let [res (cli/run-cli (probe-wf) ["create" "-f" (state-file "{:x 1}")])]
    (is (= 0 (:green/exit res)))
    (is (= [[:a :create 1] :b] (:seen res)))))

(deftest arbitrary-events-are-allowed
  (let [res (cli/run-cli (probe-wf) ["provision" "-f" (state-file "{:x 2}")])]
    (is (= [[:a :provision 2] :b] (:seen res)))))

(deftest slices-via-start-and-end
  (testing "--end is an inclusive boundary"
    (let [res (cli/run-cli (probe-wf)
                           ["create" "-f" (state-file "{:x 1}") "--end" "t/a"])]
      (is (= [[:a :create 1]] (:seen res)))))
  (testing "--start skips earlier steps"
    (let [res (cli/run-cli (probe-wf)
                           ["create" "-f" (state-file "{:x 1}") "--start" "t/b"])]
      (is (= [:b] (:seen res))))))

(deftest dry-run-flag-stamps-the-key
  (let [wf (wf/workflow {:start :t/a
                         :wire-fn (fn [_ _] [(fn [o] (assoc o :dry (:green/dry-run o)))])})]
    (is (true? (:dry (cli/run-cli wf ["create" "-f" (state-file "{}") "--dry-run"]))))
    (is (nil? (:dry (cli/run-cli wf ["create" "-f" (state-file "{}")]))))))

(deftest usage-errors-exit-2
  (testing "missing event"
    (let [res (cli/run-cli (probe-wf) [])]
      (is (= 2 (:green/exit res)))
      (is (re-find #"Usage" (:green/err res)))))
  (testing "missing state file"
    (let [res (cli/run-cli (probe-wf) ["create" "-f" "/nonexistent/green.edn"])]
      (is (= 2 (:green/exit res)))
      (is (re-find #"not found" (:green/err res))))))
