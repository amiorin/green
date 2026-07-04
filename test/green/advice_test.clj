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

(defn- two-step-wf []
  (wf/workflow {:start :t/a
               :wire-fn (fn [s]
                          (case s
                            :t/a [(fn [o] (log o :a)) :t/b]
                            :t/b [(fn [o] (log o :b))]))}))

(deftest advice-add-all-applies-to-every-step
  (let [advised (-> (two-step-wf)
                    (wf/advice-add-all :filter-return ::tag #(log % :all)))]
    (is (= [:a :all :b :all] (:log (wf/run advised {}))))))

(deftest advice-add-all-interleaves-in-strict-add-order-with-per-step
  (let [advised (-> (two-step-wf)
                    (wf/advice-add-all :filter-return ::g1 #(log % :g1))
                    (wf/advice-add :t/a :filter-return ::pa #(log % :pa))
                    (wf/advice-add-all :filter-return ::g2 #(log % :g2)))
        res (wf/run advised {})]
    (testing ":t/a ran with both global entries plus its own, in add order"
      (is (= [:a :g1 :pa :g2] (subvec (:log res) 0 4))))
    (testing ":t/b, with no per-step advice, only saw the globals in add order"
      (is (= [:b :g1 :g2] (subvec (:log res) 4))))))

(deftest advice-remove-all-removes-the-global-entry
  (let [advised (-> (two-step-wf)
                    (wf/advice-add-all :filter-return ::g #(log % :g))
                    (wf/advice-remove-all ::g))]
    (is (= [:a :b] (:log (wf/run advised {}))))))

(deftest advice-add-all-workflow-is-untouched-by-later-adds
  (let [base (two-step-wf)
        g1 (wf/advice-add-all base :filter-return ::g1 #(log % :g1))
        g2 (wf/advice-add-all g1 :filter-return ::g2 #(log % :g2))]
    (is (= [:a :g1 :b :g1] (:log (wf/run g1 {}))) "g1 is untouched by adding g2")
    (is (= [:a :g1 :g2 :b :g1 :g2] (:log (wf/run g2 {}))))))
