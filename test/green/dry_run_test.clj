(ns green.dry-run-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [green.dry-run :as dry-run]
            [green.workflow :as wf]))

(defn- effect-wf [effects]
  (-> (wf/workflow {:start :t/a
                    :wire-fn (fn [s _]
                               (case s
                                 :t/a [(fn [o] (swap! effects conj :a) o) :t/b]
                                 :t/b [(fn [o] (swap! effects conj :b) o)]))})
      (dry-run/advise [:t/a :t/b])))

(deftest dry-run-skips-advised-steps
  (let [effects (atom [])
        out (with-out-str
              (is (= 0 (:green/exit (wf/run (effect-wf effects)
                                            {:green/event :create
                                             :green/dry-run true})))))]
    (is (= [] @effects) "no side effects ran")
    (is (str/includes? out "would run :t/a"))
    (is (str/includes? out "would run :t/b"))))

(deftest without-the-flag-steps-run-normally
  (let [effects (atom [])
        res (wf/run (effect-wf effects) {:green/event :create})]
    (is (= 0 (:green/exit res)))
    (is (= [:a :b] @effects))))

(deftest dry-run-advice-is-removable-per-step
  (let [effects (atom [])
        w (-> (effect-wf effects)
              (wf/advice-remove :t/b ::dry-run/skip))]
    (with-out-str (wf/run w {:green/event :create :green/dry-run true}))
    (testing ":t/a skipped, :t/b ran"
      (is (= [:b] @effects)))))
