# green

A babashka-compatible Clojure library for building idempotent devops CLIs:
desired state in EDN, workflows as step graphs threaded by a map, Selmer-scaffolded
configuration files, OpenTofu as the muscle.

**Docs:** [specification](https://amiorin.github.io/green/) ([repo](index.html)) · [source tour](https://amiorin.github.io/green/docco.html) ([repo](docco.html)).

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
  into an ordinary step — wire it, advise it, fan it out. Inherited advice is
  preserved even if `:in` rebuilds opts; custom `:in` functions should carry
  ambient keys such as `:green/event` and `:green/dry-run` when the sub-workflow
  needs them. See `examples/multi-zookeeper` for two clusters built from one
  cluster workflow.
- **Advice inheritance**: advice on a parent workflow reaches steps inside
  embedded sub-workflows — names match flat at any depth. At equal `:depth`,
  parent advice stacks outside child advice; re-adding a child's advice id
  from the parent replaces it (e.g. swap the child's default `::backend`).
  `wf/advice-plan` shows the composed stack for a step, with provenance.
- `green.scaffold/scaffold` renders **flat file specs** through Selmer; on
  `delete` the same specs name what to remove, pruning immediate empty parent
  directories.
- `green.tofu/tofu-step` runs `tofu init` + `apply` for any non-`:delete`
  event or `init` + `destroy` for `:delete`, and merges `tofu output -json`
  under `:tofu/outputs` by default (use a namespaced `:output-key` if you
  override it). Backends are explicit advices; the examples attach
  `local-backend-advice`, and `s3-backend-advice`/`gcs-backend-advice` are
  shipped alternatives.
- `green.dry-run/advise` + the `--dry-run` flag: advised steps print what
  they would do and are skipped.

## Scheduler algorithm in plain English

The scheduler is basically a small fork/join workflow runner.
In simple words:

1. Start with one live task
   - A "live" task means: "run step X with this opts map."
   - Initially there is only the workflow's `:start` step.
2. Keep looping while there is work
   - The scheduler keeps two piles:
     - `live`: branches still running
     - `finished`: branches that reached the end
3. Group live branches by step
   - If multiple branches are waiting at the same step, that may mean they need
     to join.
4. Decide which steps are ready
   - Before running a step, the scheduler asks:
     - "Could another currently-running branch still reach this same step later?"
   - If yes, it waits, so the branches can join together.
   - This uses the static workflow graph from `wire-fn`.
5. Run ready work in parallel
   - Ready steps are executed using `future`, so independent branches run
     concurrently.
6. After a step runs, choose what happens next
   - No next step: branch is finished.
   - One next step: continue normally.
   - Multiple next steps: this is a fork; create one branch per successor.
7. Handle joins
   - If branches from different origins arrive at the same step, the scheduler
     runs that step once.
   - It gives the join step:
     - the opts from the fork point
     - all branch results under `:green/branches`
8. Handle failures inside forks
   - If one branch fails, siblings already running finish their current step.
   - Then the whole fork collapses.
   - The join is skipped.
   - The final result carries the worst exit code and all branch results.
9. Finish
   - When there are no live branches left, the scheduler returns the final opts.
   - If multiple terminal branches exist, it returns the first failed one,
     otherwise the last successful one.

Short version: it repeatedly runs all safe-to-run steps in parallel, waits when
branches may need to join, joins converged branches once, and collapses forks
cleanly on failure.

## Install

`green` has not been published to Clojars yet. Use a git dep with an explicit
commit SHA:

```clojure
io.github.amiorin/green {:git/sha "REPLACE_WITH_COMMIT_SHA"}
```

In-repo examples use `:local/root "../.."` for development. Publishing for a
future Clojars release: `clojure -T:build jar` (or `install` / `deploy`; deploy
reads `CLOJARS_USERNAME`/`CLOJARS_PASSWORD`).

## Try it

Non-dry-run example runs require `tofu` on `PATH`; `--dry-run` only prints.

- `examples/zookeeper` — dynamic fan-out/join, scaffold + tofu,
  backend-as-advice, dry-run.
- `examples/multi-zookeeper` — workflow composition with `wf/step` and advice
  inheritance across the composed boundary.
- `examples/once` — a Basecamp ONCE-style single-VPS PaaS: provider-swap
  advice for compute, a real fork/join
  (`compute ∥ smtp → dns → smtp-post → (ansible-local ∥ ansible-remote)`),
  threaded opts, per-step tofu state, and scaffold-only Ansible config. See
  `examples/once/SPEC.md` for the full walkthrough.
- `examples/multi-once` — the `once` workflow composed the way
  `multi-zookeeper` composes clusters: one `once-wf` run per
  `:once/deployments` entry, with the parent swapping the inherited
  `::provider` advice for a data-driven pick (DigitalOcean vs OCI per
  deployment) and the inherited `::backend` advice for S3, isolated by a
  per-deployment + per-step key. The S3 backend is demonstration-only
  (`create` needs a real bucket; `--dry-run` runs offline).

```sh
cd examples/zookeeper
./green create --dry-run   # print what would run, touch nothing
./green create             # fake 3-node ZooKeeper cluster in ./work
./green delete

cd ../multi-zookeeper
./green create --dry-run   # inherited dry-run advice across wf/step
./green create             # two clusters, one composed workflow, in parallel
./green delete

cd ../once
./green create --dry-run   # compute/DNS/SMTP/smtp-post/Ansible steps are skipped
./green create             # fake ONCE-style VPS + DNS/SMTP + smtp-post + Ansible scaffolds
./green delete

cd ../multi-once
./green create --dry-run   # two ONCE boxes from one once-wf; dry-run touches nothing
./green create             # NOTE: S3 backend is demonstration-only — needs a real bucket
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
