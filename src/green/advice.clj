(ns green.advice
  "Emacs-style advice for step functions: the full set of nadvice
  combinators, LIFO stacking (most recently added outermost) with an
  optional :depth override, removal by explicit id.
  Registries are plain maps of step-name -> vector of
  {:id :how :fn :seq :depth}, so all operations are pure — advice is
  workflow-scoped, not global.
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
    :after-while   (and (OLDFUN r) (FUNCTION r))
    :after-until   (or  (OLDFUN r) (FUNCTION r))
    :filter-args   (OLDFUN (FUNCTION r))
    :filter-return (FUNCTION (OLDFUN r))"
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

(defn- wrap [how a base]
  (case how
    :around        (fn [opts] (a base opts))
    :override      (fn [opts] (a opts))
    :before        (fn [opts] (a opts) (base opts))
    :after         (fn [opts] (let [ret (base opts)] (a opts) ret))
    :before-while  (fn [opts] (and (a opts) (base opts)))
    :before-until  (fn [opts] (or (a opts) (base opts)))
    :after-while   (fn [opts] (and (base opts) (a opts)))
    :after-until   (fn [opts] (or (base opts) (a opts)))
    :filter-args   (fn [opts] (base (a opts)))
    :filter-return (fn [opts] (a (base opts)))))

(defn compose
  "Wrap `base` with `entries`, ordered like Emacs nadvice: lower :depth is
  more outward; at equal depth the most recently added (largest :seq) is
  outermost. The reduce builds inside-out, so entries are sorted
  innermost-first (descending :depth, ascending :seq)."
  [base entries]
  (->> entries
       (sort-by (juxt #(- (:depth % 0)) :seq))
       (reduce (fn [g {:keys [how fn]}] (wrap how fn g)) base)))
