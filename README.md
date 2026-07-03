# green

A babashka-compatible Clojure library for building idempotent devops CLIs:
desired state in EDN, workflows as step graphs threaded by a map, Selmer-scaffolded
configuration files, OpenTofu as the muscle.

**Read the full specification: [doc/spec.html](doc/spec.html).**

## The model in one glance

```clojure
(require '[green.workflow :as wf] '[green.cli :as cli])

(defn wire-fn [step]                    ;; static graph: step -> [fn & successors]
  (case step
    :zk/start   [start-step :zk/node]
    :zk/node    [node-step  :zk/zoo-cfg]
    :zk/zoo-cfg [zoo-cfg-step]))

(defn next-fn [step default-next opts]  ;; dynamic router: fan-out, error routing
  (cond
    (pos? (:green/exit opts 0)) []
    (= step :zk/start) (for [n (:zk/servers opts)] [:zk/node (assoc opts :zk/node n)])
    :else (map (fn [s] [s opts]) default-next)))

(def workflow (wf/workflow {:start :zk/start :wire-fn wire-fn :next-fn next-fn}))

(cli/exec workflow)                     ;; ./green create | ./green delete
```

- A **step** is a function `opts -> opts`, named by a qualified keyword.
- Outcomes are Unix-style: `:green/exit` (0 ok), `:green/err`, `:green/trace`.
- Multiple wire-fn successors (or next-fn pairs) run **in parallel**; branches
  converging on a step **join** it once, with results under `:green/branches`.
- **Advice** (Emacs-style, workflow-scoped): wrap or override steps by name
  without touching the wiring.
- `green.scaffold/scaffold` renders **flat file specs** through Selmer; on
  `delete` the same specs name what to remove.
- `green.tofu/tofu-step` runs `tofu apply`/`destroy` per `:green/event` and
  merges `tofu output -json` back into opts.

## Try it

```sh
cd examples/zookeeper
./green create      # fake 3-node ZooKeeper cluster in ./work
./green delete
```

## Tests

```sh
bb test             # under babashka
clojure -X:test     # under the JVM
```

The end-to-end suite drives real `tofu` over HCL that contains only `locals`
and `output` blocks — full render/apply/destroy cycles, zero real infrastructure.
