(ns green.tofu
  "Event-aware OpenTofu steps: any non-:delete event (conventionally
  :create) -> init + apply, :delete -> init + destroy. After apply,
  `tofu output -json` is merged into opts under a namespaced key
  (:tofu/outputs by default). The backend is not hardwired: attach a
  :before advice built by `backend-advice`, `local-backend-advice`,
  `s3-backend-advice`, or `gcs-backend-advice` to write backend.tf before
  the tofu command runs."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]))

(def ^:private init-args ["init" "-input=false" "-no-color"])
(def ^:private apply-args ["apply" "-auto-approve" "-input=false" "-no-color"])
(def ^:private destroy-args ["destroy" "-auto-approve" "-input=false" "-no-color"])

(defn- tofu! [dir & args]
  (apply sh/sh "tofu" (concat args [:dir dir])))

(defn- action-args [delete?]
  (if delete? destroy-args apply-args))

(defn- failed? [{:keys [exit]}]
  (pos? exit))

(defn- fail [opts {:keys [exit err out]} cmd]
  (assoc opts
         :green/exit exit
         :green/err (str "tofu " cmd " failed: "
                         (or (not-empty err) (not-empty out) "(no output)"))))

(defn- parse-outputs [out]
  (into {}
        (map (fn [[k v]] [(keyword k) (get v "value")]))
        (json/parse-string out)))

(defn outputs
  "Parse `tofu output -json` in `dir` into a plain map of keyword -> value."
  [dir]
  (let [{:keys [exit out err]} (tofu! dir "output" "-json")]
    (when (pos? exit)
      (throw (ex-info (str "tofu output failed: " err) {:dir dir})))
    (parse-outputs out)))

(defn tofu-step
  "Run OpenTofu in `dir` according to :green/event. On success, apply merges
  the outputs under `output-key` (default :tofu/outputs) — never top-level."
  [opts {:keys [dir output-key] :or {output-key :tofu/outputs}}]
  (let [delete? (= :delete (:green/event opts))
        init (apply tofu! dir init-args)]
    (if (failed? init)
      (fail opts init "init")
      (let [cmd (action-args delete?)
            res (apply tofu! dir cmd)]
        (cond
          (failed? res) (fail opts res (first cmd))
          delete? (assoc opts :green/exit 0)
          :else (assoc opts :green/exit 0 output-key (outputs dir)))))))

(defn- hcl-value [v]
  (cond
    (keyword? v) (pr-str (name v))
    (boolean? v) (str v)
    (number? v) (str v)
    :else (pr-str (str v))))

(defn- hcl-attribute [[k v]]
  (str "    " (name k) " = " (hcl-value v) "\n"))

(defn- backend-hcl [type config]
  (str "terraform {\n  backend \"" type "\" {\n"
       (apply str (map hcl-attribute (sort-by key config)))
       "  }\n}\n"))

(defn- resolve-config [config opts]
  (if (fn? config) (config opts) config))

(defn- write-backend! [dir type config]
  (.mkdirs dir)
  (spit (io/file dir "backend.tf") (backend-hcl type config)))

(defn backend-advice
  "Build a :before advice that writes a backend config into the directory
  returned by (dir-fn opts) before the step runs. `type` is the backend
  name (\"local\", \"s3\", \"gcs\", …); `config` is a flat map of backend
  attributes, or a function of opts returning one."
  [dir-fn type config]
  (fn [opts]
    (write-backend! (io/file (dir-fn opts)) type (resolve-config config opts))
    opts))

(defn local-backend-advice
  "Backend advice for the local filesystem backend."
  ([dir-fn] (local-backend-advice dir-fn {}))
  ([dir-fn config] (backend-advice dir-fn "local" config)))

(defn s3-backend-advice
  "Backend advice for the S3 backend, e.g.
  {:bucket \"my-state\" :key \"green/node-1.tfstate\" :region \"eu-west-1\"}."
  [dir-fn config]
  (backend-advice dir-fn "s3" config))

(defn gcs-backend-advice
  "Backend advice for the GCS backend, e.g.
  {:bucket \"my-state\" :prefix \"green/node-1\"}."
  [dir-fn config]
  (backend-advice dir-fn "gcs" config))
