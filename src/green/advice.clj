(ns green.advice
  "Emacs-style advice for step functions: the full set of nadvice
  combinators, LIFO stacking (most recently added outermost) with an
  optional :depth override, removal by explicit id.
  Registries are plain maps of step-name -> vector of
  {:id :how :fn :seq :depth}, so all operations are pure — advice is
  workflow-scoped, not global. A workflow embedded via
  green.workflow/step inherits its ancestors' advice at run time by
  merging registries (see `merge-entries`); the values stay pure.
  The :seq field is a caller-assigned monotonic add-order number; it lets
  a step-scoped registry and a separate all-steps registry be merged and
  sorted into one strict add-order stack (see green.workflow/run-step).
  :depth (-100..100, default 0) overrides add order the way Emacs hook
  depths do: lower pushes the advice outward, higher pushes it inward;
  at equal depth the most recently added is outermost.")

(def hows
  "The supported combinators, matching Emacs nadvice. With FUNCTION the
  advice, OLDFUN the prior chain, and r the opts map:

    :around        (FUNCTION OLDFUN r)
    :before        (FUNCTION r) then (OLDFUN r)
    :after         (OLDFUN r) then (FUNCTION r), returns OLDFUN's value
    :override      (FUNCTION r), OLDFUN never runs
    :before-while  (and (FUNCTION r) (OLDFUN r))
    :before-until  (or  (FUNCTION r) (OLDFUN r))
    :after-while   (let [ret (OLDFUN r)] (if (green-true? ret) (FUNCTION r) ret))
    :after-until   (let [ret (OLDFUN r)] (if (green-true? ret) ret (FUNCTION r)))
    :filter-args   (OLDFUN (FUNCTION r))
    :filter-return (FUNCTION (OLDFUN r))

  Typical use cases for each `how`:
    :around        dry-run, retry, timing/tracing, locks; call OLDFUN
                   zero, one, or many times
    :override      stubs, tests, or environment-specific replacements
    :before        setup/prerequisites such as backend files, dirs, locks,
                   or validation
    :after         audit, metrics, notifications, or cleanup without
                   changing OLDFUN's result
    :before-while  precondition gates: continue only while guards pass
    :before-until  fast paths/no-ops: cached or already-converged result
                   skips OLDFUN
    :after-while   success-only follow-ups such as verification,
                   registration, or notifications
    :after-until   failure recovery/fallback: rollback, repair, or supply
                   an alternate result
    :filter-args   normalize/scope inputs: defaults, derived paths,
                   sub-maps, redaction
    :filter-return normalize/enrich outputs: derived data, temp-key
                   cleanup, redaction, result-shape adaptation

  For after-while/after-until, a Green step result is true only when its
  :green/exit is 0 (missing means 0); a positive :green/exit is false.
  Non-map returns keep ordinary Clojure truthiness for compose-level use."
  #{:around :before :after :override
    :before-while :before-until :after-while :after-until
    :filter-args :filter-return})

(defn- entry [how id f seq props]
  {:pre [(contains? hows how)
         (<= -100 (:depth props 0) 100)]}
  {:id id :how how :fn f :seq seq :depth (:depth props 0)})

(defn add
  "Register advice `f` on `step` with combinator `how` under `id`, tagged
  with add-order `seq`. `props` may carry :depth. Re-adding an existing id
  replaces it and moves it to the top of the stack for its depth (given a
  fresh, larger `seq`)."
  [registry step how id f seq props]
  (update registry step
          (fnil (fn [entries]
                  (conj (filterv #(not= id (:id %)) entries)
                        (entry how id f seq props)))
                [])))

(defn remove-id
  "Remove the advice registered under `id` on `step`."
  [registry step id]
  (update registry step (fn [entries] (filterv #(not= id (:id %)) (or entries [])))))

(defn add-global
  "Like `add`, but for a flat (not step-keyed) vector of entries — used for
  advice that applies to every step."
  [entries how id f seq props]
  (conj (filterv #(not= id (:id %)) entries) (entry how id f seq props)))

(defn remove-global-id
  "Remove the global advice registered under `id`."
  [entries id]
  (filterv #(not= id (:id %)) entries))

(defn merge-entries
  "Stack `outer` entries (inherited from an enclosing workflow) outside
  `inner` ones: outer seqs are rebased by `offset` so they sort above
  every inner seq, and an outer entry replaces an inner one with the same
  :id. :depth still overrides placement at compose time."
  [inner outer offset]
  (let [outer (mapv #(update % :seq + offset) outer)
        replaced? (set (map :id outer))]
    (into (filterv #(not (replaced? (:id %))) (or inner [])) outer)))

(defn merge-registry
  "Merge an outer step-keyed registry over an inner one, step by step,
  with `merge-entries`."
  [inner outer offset]
  (reduce-kv (fn [reg step entries]
               (update reg step merge-entries entries offset))
             (or inner {}) outer))

(defn- green-true?
  "Truth predicate for Green step returns: success is true, failure is false.
  Compose can also be used directly with non-map values, where ordinary
  Clojure truthiness is preserved."
  [ret]
  (if (map? ret)
    (zero? (or (:green/exit ret) 0))
    (boolean ret)))

(defn- wrap [how a base]
  (case how
    :around        (fn [opts] (a base opts))
    :override      (fn [opts] (a opts))
    :before        (fn [opts] (a opts) (base opts))
    :after         (fn [opts] (let [ret (base opts)] (a opts) ret))
    :before-while  (fn [opts] (and (a opts) (base opts)))
    :before-until  (fn [opts] (or (a opts) (base opts)))
    :after-while   (fn [opts]
                     (let [ret (base opts)]
                       (if (green-true? ret) (a opts) ret)))
    :after-until   (fn [opts]
                     (let [ret (base opts)]
                       (if (green-true? ret) ret (a opts))))
    :filter-args   (fn [opts] (base (a opts)))
    :filter-return (fn [opts] (a (base opts)))))

(defn ordered
  "Entries sorted innermost-first — the order `compose` wraps them:
  higher :depth is more inward; at equal depth the most recently added
  (largest :seq) is outermost."
  [entries]
  (sort-by (juxt #(- (:depth % 0)) :seq) entries))

(defn compose
  "Wrap `base` with `entries`, ordered like Emacs nadvice: lower :depth is
  more outward; at equal depth the most recently added (largest :seq) is
  outermost. The reduce builds inside-out over `ordered` (innermost-first)
  entries."
  [base entries]
  (reduce (fn [g {:keys [how fn]}] (wrap how fn g)) base (ordered entries)))
