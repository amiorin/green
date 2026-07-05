(ns green.dry-run
  "Dry-run support, built on the advice facility rather than hardwired into
  steps. Attach `advice` (an :around) to side-effecting steps — or `advise`
  a whole list of them; when :green/dry-run is set in opts (the CLI's
  --dry-run flag stamps it) the step is skipped with a note instead of run."
  (:require [green.workflow :as wf]))

(defn- logln [& xs]
  (locking *out*
    (apply println xs)
    (flush)))

(defn advice
  "Build an :around advice for `step`: when :green/dry-run is set, print
  what would have run and skip the base function; otherwise call through."
  [step]
  (fn [f opts]
    (if (:green/dry-run opts)
      (do (logln (str "dry-run: would run " step
                       " (" (name (:green/event opts :create)) ")"))
          (assoc opts :green/exit 0))
      (f opts))))

(defn advise
  "Attach dry-run advice to every step in `steps` under id ::skip.
  Returns the advised workflow; remove per step with
  (wf/advice-remove wf step :green.dry-run/skip)."
  [wf steps]
  (reduce (fn [w s] (wf/advice-add w s :around ::skip (advice s))) wf steps))
