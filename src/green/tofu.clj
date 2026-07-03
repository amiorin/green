(ns green.tofu
  "Event-aware OpenTofu steps: create -> init + apply, delete -> init +
  destroy. After apply, `tofu output -json` is merged into opts under a
  namespaced key (:tofu/outputs by default). The backend is not hardwired:
  attach `local-backend-advice` as a :before advice to write the local
  filesystem backend config (always local in v1)."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]))

(defn- tofu! [dir & args]
  (apply sh/sh "tofu" (concat args [:dir dir])))

(defn- fail [opts {:keys [exit err out]} cmd]
  (assoc opts
         :green/exit exit
         :green/err (str "tofu " cmd " failed: "
                         (or (not-empty err) (not-empty out) "(no output)"))))

(defn outputs
  "Parse `tofu output -json` in `dir` into a plain map of keyword -> value."
  [dir]
  (let [{:keys [exit out err]} (tofu! dir "output" "-json")]
    (when (pos? exit)
      (throw (ex-info (str "tofu output failed: " err) {:dir dir})))
    (into {}
          (map (fn [[k v]] [(keyword k) (get v "value")]))
          (json/parse-string out))))

(defn tofu-step
  "Run OpenTofu in `dir` according to :green/event. On success, apply merges
  the outputs under `output-key` (default :tofu/outputs) — never top-level."
  [opts {:keys [dir output-key] :or {output-key :tofu/outputs}}]
  (let [delete? (= :delete (:green/event opts))
        init (tofu! dir "init" "-input=false" "-no-color")]
    (if (pos? (:exit init))
      (fail opts init "init")
      (let [cmd (if delete?
                  ["destroy" "-auto-approve" "-input=false" "-no-color"]
                  ["apply" "-auto-approve" "-input=false" "-no-color"])
            res (apply tofu! dir cmd)]
        (cond
          (pos? (:exit res)) (fail opts res (first cmd))
          delete? (assoc opts :green/exit 0)
          :else (assoc opts :green/exit 0 output-key (outputs dir)))))))

(defn local-backend-advice
  "Build a :before advice that writes the local filesystem backend config
  into the directory returned by (dir-fn opts) before the step runs."
  [dir-fn]
  (fn [opts]
    (let [dir (io/file (dir-fn opts))]
      (.mkdirs dir)
      (spit (io/file dir "backend.tf")
            "terraform {\n  backend \"local\" {}\n}\n"))
    opts))
