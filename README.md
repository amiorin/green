# green

A babashka-compatible Clojure library for building idempotent devops CLIs:
desired state in EDN, workflows as step graphs threaded by a map, Selmer-scaffolded
configuration files, OpenTofu as the muscle.

**Read the full specification: [index.html](index.html).**

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
- **Advice** (Emacs `nadvice`-style, workflow-scoped): wrap, override, or
  filter steps by name (or all steps) without touching the wiring. At equal
  `:depth`, the most recently added advice is outermost; lower `:depth` runs
  farther outside. The advice `how` values cover specific use cases:
  `:around` (dry-run/retry/timing/locks), `:override` (replace or stub),
  `:before` (setup/prerequisites), `:after` (audit/metrics/cleanup),
  `:before-while` (precondition gates), `:before-until` (fast-path/no-op),
  `:after-while` (success-only follow-up), `:after-until` (recovery/fallback),
  `:filter-args` (normalize/scope input), and `:filter-return`
  (normalize/enrich output).
- **Composition**: `(wf/step sub-workflow {:in … :out …})` turns a workflow
  into an ordinary step — wire it, advise it, fan it out. See
  `examples/multi-zookeeper` for two clusters built from one cluster workflow.
- **Advice inheritance**: advice on a parent workflow reaches steps inside
  embedded sub-workflows — names match flat at any depth. At equal `:depth`,
  parent advice stacks outside child advice; re-adding a child's advice id
  from the parent replaces it (e.g. swap the child's default `::backend`).
  `wf/advice-plan` shows the composed stack for a step, with provenance.
- `green.scaffold/scaffold` renders **flat file specs** through Selmer; on
  `delete` the same specs name what to remove.
- `green.tofu/tofu-step` runs `tofu init` + `apply` for any non-`:delete`
  event or `init` + `destroy` for `:delete`, and merges `tofu output -json`
  back into opts. Backends are advices:
  `local-backend-advice` (default), `s3-backend-advice`, `gcs-backend-advice`.
- `green.dry-run/advise` + the `--dry-run` flag: advised steps print what
  they would do and are skipped.

## Install

As a git dep (once pushed to GitHub) or from Clojars:

```clojure
io.github.amiorin/green {:git/sha "…"}        ;; git dep
io.github.amiorin/green {:mvn/version "0.1.0"} ;; Clojars, after deploy
```

Publishing: `clojure -T:build jar | install | deploy` (deploy reads
`CLOJARS_USERNAME`/`CLOJARS_PASSWORD`).

## Try it

```sh
cd examples/zookeeper
./green create --dry-run   # print what would run, touch nothing
./green create             # fake 3-node ZooKeeper cluster in ./work
./green delete

cd ../multi-zookeeper
./green create             # two clusters, one composed workflow, in parallel
./green delete
```

## Tests

```sh
bb test             # under babashka
clojure -X:test     # under the JVM
```

The end-to-end suite drives real `tofu` (when it is on `PATH`) over HCL that
contains only `locals` and `output` blocks — full render/apply/destroy cycles,
zero real infrastructure.
