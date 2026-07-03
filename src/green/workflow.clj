(ns green.workflow
  "The workflow engine: a graph of steps threaded by an opts map.

  - wire-fn: step -> [fn & next-steps] — the static happy-path graph.
    Multiple successors run in parallel.
  - next-fn (optional): (next-fn step default-next opts) -> seq of
    [next-step opts] pairs — dynamic routing, error branching, fan-out.
  - Steps report outcome via :green/exit (0 ok, >0 error), :green/err,
    :green/trace. Thrown exceptions are caught and converted.
  - Branches converging on the same step join: the join step runs once
    with the fork-point opts plus :green/branches (vector of branch results).
  - A branch failing inside a fork lets the in-flight siblings finish their
    current step, then the fork collapses: the join is skipped and the worst
    exit propagates, with all branch results under :green/branches."
  (:require [green.advice :as advice])
  (:import [java.io PrintWriter StringWriter]))

(defn workflow
  "Construct a workflow. :start is required; :end is an optional slice
  boundary (it runs, then the workflow stops); :wire-fn is required;
  :next-fn is optional."
  [{:keys [start end wire-fn next-fn]}]
  {:pre [(keyword? start) (fn? wire-fn)]}
  {::start start ::end end ::wire-fn wire-fn ::next-fn next-fn ::advice {}})

(defn advice-add
  "Return a workflow with advice `f` added on `step` (combinator `how`,
  explicit `id`). Pure — the original workflow is untouched."
  [wf step how id f]
  (update wf ::advice advice/add step how id f))

(defn advice-remove
  "Return a workflow with the advice registered under `id` on `step` removed."
  [wf step id]
  (update wf ::advice advice/remove-id step id))

;; --- static graph (for join scheduling) ---------------------------------

(defn- static-successors [wire-fn step]
  (try (rest (wire-fn step)) (catch Exception _ nil)))

(defn- static-graph [wire-fn start]
  (loop [g {} frontier [start]]
    (if-let [s (first frontier)]
      (if (contains? g s)
        (recur g (subvec frontier 1))
        (let [succ (vec (static-successors wire-fn s))]
          (recur (assoc g s succ) (into (subvec frontier 1) succ))))
      g)))

(defn- reaches?
  "Can a branch currently at `from` (about to run it) later arrive at `to`,
  following static wire-fn edges?"
  [g from to]
  (loop [seen #{} frontier (vec (get g from))]
    (if (empty? frontier)
      false
      (let [s (peek frontier) frontier (pop frontier)]
        (cond
          (= s to) true
          (seen s) (recur seen frontier)
          :else (recur (conj seen s) (into frontier (get g s))))))))

;; --- running one step ----------------------------------------------------

(defn- stack-trace [^Throwable t]
  (let [sw (StringWriter.)]
    (.printStackTrace t (PrintWriter. sw true))
    (str sw)))

(defn- run-step [wf step opts]
  (try
    (let [decl ((::wire-fn wf) step)
          f (first decl)]
      (when-not (fn? f)
        (throw (ex-info (str "no function wired for step " step) {:step step})))
      (let [ret ((advice/compose f (get-in wf [::advice step])) opts)]
        (when-not (map? ret)
          (throw (ex-info (str "step " step " returned a non-map: " (pr-str ret))
                          {:step step})))
        (cond-> ret (nil? (:green/exit ret)) (assoc :green/exit 0))))
    (catch Throwable t
      (assoc opts
             :green/exit (or (:green/exit (ex-data t)) 1)
             :green/err (or (ex-message t) (str (class t)))
             :green/trace (stack-trace t)))))

(defn- next-pairs
  "Successor [step opts] pairs for `step` after it produced `opts`.
  The end step is a hard boundary; without next-fn an error halts."
  [wf step opts]
  (if (= step (::end wf))
    []
    (let [dn (seq (rest ((::wire-fn wf) step)))]
      (if-let [nf (::next-fn wf)]
        (vec (nf step dn opts))
        (if (pos? (:green/exit opts 0))
          []
          (mapv (fn [s] [s opts]) dn))))))

;; --- the scheduler --------------------------------------------------------

;; Live entries: {:step k :opts m :parent id :forks [frame…]} where a fork
;; frame is {:id fork-id :opts fork-point-opts}. :parent identifies the
;; run-unit that produced the entry, so same-step entries from one fan-out
;; run individually while entries converging from different origins join.
;; Finished branches are {:opts m :forks [frame…]}; the frames let a failure
;; collapse its enclosing fork.

(defn- children [uid opts pairs forks]
  (cond
    (empty? pairs)
    {:terminals [{:opts opts :forks forks}]}

    (= 1 (count pairs))
    (let [[s o] (first pairs)]
      {:new-entries [{:step s :opts o :parent uid :forks forks}]})

    :else
    (let [frame {:id uid :opts opts}]
      {:new-entries (mapv (fn [[s o]]
                            {:step s :opts o :parent uid :forks (conj forks frame)})
                          pairs)})))

(defn- run-unit [wf {:keys [kind step entry entries]}]
  (let [uid (gensym "green-unit")]
    (try
      (case kind
        :single
        (let [{:keys [opts forks]} entry
              opts' (run-step wf step opts)]
          (children uid opts' (next-pairs wf step opts') forks))

        :join
        (let [branch-opts (mapv :opts entries)
              forks (or (some #(when (seq (:forks %)) (:forks %)) entries) [])
              fork-opts (if (seq forks) (:opts (peek forks)) (first branch-opts))
              forks' (if (seq forks) (pop forks) forks)
              worst (apply max (map #(:green/exit % 0) branch-opts))]
          (if (pos? worst)
            (let [bad (first (filter #(pos? (:green/exit % 0)) branch-opts))]
              {:terminals [{:opts (assoc fork-opts
                                         :green/exit worst
                                         :green/err (:green/err bad)
                                         :green/trace (:green/trace bad)
                                         :green/branches branch-opts)
                            :forks forks'}]})
            (let [opts' (run-step wf step (assoc fork-opts :green/branches branch-opts))]
              (children uid opts' (next-pairs wf step opts') forks')))))
      (catch Throwable t
        (let [base (or (:opts entry) (:opts (first entries)) {})]
          {:terminals [{:opts (assoc base
                                     :green/exit 1
                                     :green/err (or (ex-message t) (str (class t)))
                                     :green/trace (stack-trace t))
                        :forks (or (:forks entry) (:forks (first entries)) [])}]})))))

(defn- collapse-doomed
  "While a finished branch failed inside a fork, collapse that fork: absorb
  its live entries (their current opts are their branch results — siblings
  finished their step, nothing new starts) and its finished branches, skip
  the join, and emit a terminal carrying the worst exit and :green/branches.
  Cascades outward through nested forks."
  [live finished]
  (loop [live live finished finished]
    (if-let [bad (first (filter #(and (pos? (:green/exit (:opts %) 0))
                                      (seq (:forks %)))
                                finished))]
      (let [{fork-id :id fork-opts :opts} (peek (:forks bad))
            member? (fn [x] (boolean (some #(= fork-id (:id %)) (:forks x))))
            group (into (filterv member? finished) (filterv member? live))
            branch-opts (mapv :opts group)
            worst (apply max (map #(:green/exit % 0) branch-opts))
            worst-opts (first (filter #(= worst (:green/exit % 0)) branch-opts))]
        (recur (filterv (complement member?) live)
               (conj (filterv (complement member?) finished)
                     {:opts (assoc fork-opts
                                   :green/exit worst
                                   :green/err (:green/err worst-opts)
                                   :green/trace (:green/trace worst-opts)
                                   :green/branches branch-opts)
                      :forks (pop (:forks bad))})))
      [live finished])))

(defn- finalize [finished]
  (let [terminals (mapv :opts finished)]
    (cond
      (empty? terminals) {:green/exit 0}
      (= 1 (count terminals)) (first terminals)
      :else (or (first (filter #(pos? (:green/exit % 0)) terminals))
                (last terminals)))))

(defn run
  "Run the workflow from its start step with `opts` as the initial state.
  Returns the final opts map; its :green/exit is the workflow's exit code."
  [wf opts]
  (let [g (static-graph (::wire-fn wf) (::start wf))]
    (loop [live [{:step (::start wf) :opts opts :parent ::root :forks []}]
           finished []]
      (if (empty? live)
        (finalize finished)
        (let [by-step (group-by :step live)
              ;; hold a step back while another live branch can still reach it
              blocked? (fn [s]
                         (some #(and (not= (:step %) s) (reaches? g (:step %) s))
                               live))
              ready (or (seq (remove blocked? (keys by-step)))
                        (keys by-step))
              waiting (remove (set ready) (keys by-step))
              units (mapcat (fn [s]
                              (let [es (by-step s)]
                                (if (or (= 1 (count es))
                                        (apply = (map :parent es)))
                                  ;; single entry, or same-origin fan-out:
                                  ;; run each individually
                                  (map (fn [e] {:kind :single :step s :entry e}) es)
                                  [{:kind :join :step s :entries es}])))
                            ready)
              results (mapv deref (mapv (fn [u] (future (run-unit wf u))) units))
              [live' finished'] (collapse-doomed
                                 (-> []
                                     (into (mapcat by-step waiting))
                                     (into (mapcat :new-entries results)))
                                 (into finished (mapcat :terminals results)))]
          (recur live' finished'))))))
