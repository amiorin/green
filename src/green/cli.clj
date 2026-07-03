(ns green.cli
  "CLI plumbing: `./green <event> [-f|--file green.edn] [--start step]
  [--end step]`. The first positional argument is the lifecycle event,
  stamped into opts as :green/event. --start/--end run a slice of the graph."
  (:require [babashka.cli :as cli]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [green.workflow :as wf]))

(def ^:private cli-spec
  {:file {:alias :f :default "green.edn" :desc "Desired state EDN file"}
   :start {:coerce :keyword :desc "Override the workflow start step"}
   :end {:coerce :keyword :desc "Override the workflow end step (slice boundary)"}})

(def usage
  "Usage: green <event> [-f|--file green.edn] [--start step] [--end step]")

(defn run-cli
  "Parse `args`, load the desired state, stamp :green/event, run `workflow`.
  Returns the final opts map (:green/exit 2 on usage/state-file errors)."
  ([workflow] (run-cli workflow *command-line-args*))
  ([workflow args]
   (try
     (let [{:keys [args opts]} (cli/parse-args (vec args) {:spec cli-spec})
           event (first args)]
       (if-not event
         {:green/exit 2 :green/err usage}
         (let [file (io/file (:file opts))]
           (if-not (.exists file)
             {:green/exit 2 :green/err (str "desired state file not found: " file)}
             (let [state (edn/read-string (slurp file))
                   workflow (cond-> workflow
                              (:start opts) (assoc :green.workflow/start (:start opts))
                              (:end opts) (assoc :green.workflow/end (:end opts)))]
               (wf/run workflow (assoc state :green/event (keyword event))))))))
     (catch Throwable t
       {:green/exit 2 :green/err (or (ex-message t) (str (class t)))}))))

(defn exec
  "Run and exit the process with :green/exit, printing :green/err and
  :green/trace to stderr. For use from the project's babashka script."
  ([workflow] (exec workflow *command-line-args*))
  ([workflow args]
   (let [{:green/keys [exit err trace]} (run-cli workflow args)]
     (when err
       (binding [*out* *err*]
         (println err)
         (when trace (println trace))))
     (System/exit (or exit 0)))))
