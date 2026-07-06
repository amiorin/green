(ns green.progress
  "Progress reporting, built on the advice facility. Attach with `advise`
  to print step start/end with elapsed time; reads `:green/step` from opts."
  (:require [green.workflow :as wf]))

(defn- logln [& xs]
  (locking *out*
    (apply println xs)
    (flush)))

(defn progress
  "An :around advice that prints step name on entry and elapsed time on exit.
  Reads :green/step from opts (stamped by the engine)."
  [f opts]
  (let [step (:green/step opts)
        event (name (:green/event opts :create))]
    (logln (str ">>> " step " (" event ")"))
    (let [t0 (System/currentTimeMillis)
          result (f opts)
          ms (- (System/currentTimeMillis) t0)]
      (logln (str "<<< " step " (" ms "ms)"))
      result)))

(defn advise
  "Attach progress advice to every step. Returns the advised workflow."
  [wf]
  (wf/advice-add-all wf :around ::progress progress))
