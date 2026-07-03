(ns green.advice-test
  (:require [clojure.test :refer [deftest is testing]]
            [green.workflow :as wf]))

(defn- log [o x] (update o :log (fnil conj []) x))

(defn- single-step-wf []
  (wf/workflow {:start :t/step
                :wire-fn (fn [_] [(fn [o] (log o :base))])}))

(deftest filter-return-and-lifo-stacking
  (let [base (single-step-wf)
        advised (-> base
                    (wf/advice-add :t/step :filter-return ::a #(log % :a))
                    (wf/advice-add :t/step :filter-return ::b #(log % :b)))]
    (testing "most recently added advice is outermost"
      (is (= [:base :a :b] (:log (wf/run advised {})))))
    (testing "the original workflow is untouched (workflow-scoped advice)"
      (is (= [:base] (:log (wf/run base {})))))))

(deftest advice-remove-by-id
  (let [advised (-> (single-step-wf)
                    (wf/advice-add :t/step :filter-return ::a #(log % :a))
                    (wf/advice-add :t/step :filter-return ::b #(log % :b))
                    (wf/advice-remove :t/step ::a))]
    (is (= [:base :b] (:log (wf/run advised {}))))))

(deftest re-adding-same-id-replaces-and-moves-to-top
  (let [advised (-> (single-step-wf)
                    (wf/advice-add :t/step :filter-return ::a #(log % :a1))
                    (wf/advice-add :t/step :filter-return ::b #(log % :b))
                    (wf/advice-add :t/step :filter-return ::a #(log % :a2)))]
    (is (= [:base :b :a2] (:log (wf/run advised {}))))))

(deftest override-replaces-base
  (let [advised (-> (single-step-wf)
                    (wf/advice-add :t/step :override ::o #(log % :override)))]
    (is (= [:override] (:log (wf/run advised {}))))))

(deftest around-controls-the-call
  (let [advised (-> (single-step-wf)
                    (wf/advice-add :t/step :around ::ar
                                   (fn [f o] (log (f (log o :in)) :out))))]
    (is (= [:in :base :out] (:log (wf/run advised {}))))))

(deftest filter-args-transforms-input
  (let [advised (-> (single-step-wf)
                    (wf/advice-add :t/step :filter-args ::fa #(assoc % :x 1)))]
    (is (= 1 (:x (wf/run advised {}))))))

(deftest before-and-after-run-for-side-effects
  (let [seen (atom [])
        advised (-> (single-step-wf)
                    (wf/advice-add :t/step :before ::b
                                   (fn [o] (swap! seen conj [:before (:log o)]) o))
                    (wf/advice-add :t/step :after ::a
                                   (fn [o] (swap! seen conj [:after (:log o)]) o)))
        res (wf/run advised {})]
    (testing "before sees pre-step opts, after sees the result; returns ignored"
      (is (= [:base] (:log res)))
      (is (= [[:before nil] [:after [:base]]] @seen)))))

(deftest throwing-advice-fails-the-step-through-the-contract
  (let [advised (-> (single-step-wf)
                    (wf/advice-add :t/step :before ::boom
                                   (fn [_] (throw (ex-info "advice boom" {})))))
        res (wf/run advised {})]
    (is (= 1 (:green/exit res)))
    (is (= "advice boom" (:green/err res)))
    (is (string? (:green/trace res)))))
