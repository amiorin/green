(ns green.advice
  "Emacs-style advice for step functions: the standard combinators only,
  LIFO stacking (most recently added outermost), removal by explicit id.
  Registries are plain maps of step-name -> vector of {:id :how :fn :seq},
  so all operations are pure — advice is workflow-scoped, not global.
  The :seq field is a caller-assigned monotonic add-order number; it lets
  a step-scoped registry and a separate all-steps registry be merged and
  sorted into one strict add-order stack (see green.workflow/run-step).")

(defn add
  "Register advice `f` on `step` with combinator `how` under `id`, tagged
  with add-order `seq`. Re-adding an existing id replaces it and moves it
  to the top of the stack (given a fresh, larger `seq`)."
  [registry step how id f seq]
  (update registry step
          (fnil (fn [entries]
                  (conj (filterv #(not= id (:id %)) entries)
                        {:id id :how how :fn f :seq seq}))
                [])))

(defn remove-id
  "Remove the advice registered under `id` on `step`."
  [registry step id]
  (update registry step (fn [entries] (filterv #(not= id (:id %)) (or entries [])))))

(defn add-global
  "Like `add`, but for a flat (not step-keyed) vector of entries — used for
  advice that applies to every step."
  [entries how id f seq]
  (conj (filterv #(not= id (:id %)) entries) {:id id :how how :fn f :seq seq}))

(defn remove-global-id
  "Remove the global advice registered under `id`."
  [entries id]
  (filterv #(not= id (:id %)) entries))

(defn- wrap [how a base]
  (case how
    :around        (fn [opts] (a base opts))
    :override      (fn [opts] (a opts))
    :before        (fn [opts] (a opts) (base opts))
    :after         (fn [opts] (let [ret (base opts)] (a ret) ret))
    :filter-args   (fn [opts] (base (a opts)))
    :filter-return (fn [opts] (a (base opts)))))

(defn compose
  "Wrap `base` with `entries` in LIFO order: the last-added advice is outermost."
  [base entries]
  (reduce (fn [g {:keys [how fn]}] (wrap how fn g)) base entries))
