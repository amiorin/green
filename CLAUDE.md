# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`green` is a babashka-compatible Clojure library for building idempotent
devops CLIs: desired state in EDN, workflows as step graphs threaded by a
map, Selmer-scaffolded config files, OpenTofu as the muscle. The full
specification lives in `index.html` (open it in a browser).

## Commands

```sh
bb test             # run the test suite under babashka
clojure -X:test     # run the test suite under the JVM

clojure -T:build jar       # build target/green-<version>.jar + pom
clojure -T:build install   # install into the local ~/.m2
clojure -T:build deploy    # deploy to Clojars (needs CLOJARS_USERNAME/CLOJARS_PASSWORD)
```

Run a single test namespace under the JVM: `clojure -X:test :nses '[green.advice-test]'`.
Under babashka, `bb.edn`'s `test` task explicitly requires and runs the
namespaces named there — add new test namespaces to both `:requires` and the
`run-tests` call. Under the JVM, `clojure -X:test` uses `deps.edn`'s `:test`
paths with `cognitect.test-runner` discovery; there is no namespace list to
maintain in `deps.edn`.

Dependency rule: `green` has not been published to Clojars yet. External
consumer docs and launcher snippets must use a git dependency with an explicit
`:git/sha`; do not recommend `:mvn/version` until a Clojars release exists.
In-repo examples may keep `:local/root "../.."` for development.

Try the examples end-to-end:

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
./green create --dry-run   # print what would run, touch nothing
./green create             # fake ONCE-style VPS, DNS/SMTP, smtp-post, and Ansible scaffolds
./green delete
```

Each example's `./green` script is a self-contained babashka script that
pulls in `green` via `:local/root "../.."` — no separate build step needed
to try changes made to `src/`. `examples/once/SPEC.md` documents the ONCE-style
example: provider-swap advice for compute, a
`compute ∥ smtp → dns → smtp-post → (ansible-local ∥ ansible-remote)`
fork/join, threaded opts, per-step tofu state, and scaffold-only Ansible config.

The end-to-end ZooKeeper suite (`test/green/zookeeper_test.clj`) drives real
`tofu` over HCL containing only `locals`/`output` blocks — full
render/apply/destroy cycles, zero real infrastructure — and skips when `tofu`
is not on `PATH`. `test/green/tofu_test.clj` covers backend advice without
invoking `tofu`.

## Architecture

Six main namespaces under `src/green/`:

- **`workflow.clj`** — the engine. A **step** is a plain function
  `opts -> opts`, named by a qualified keyword. A `wire-fn` (`step ->
  [fn & next-steps]`) is the static happy-path graph; an optional `next-fn`
  (`step default-next opts -> [[next-step opts] ...]`) does dynamic
  routing — fan-out, conditional branching, error short-circuiting.
  Multiple successors run in parallel (real `future`s); branches that
  converge on the same step **join** it once, with per-branch results
  under `:green/branches`. If any branch fails mid-fork, in-flight
  siblings finish their current step and the fork collapses without
  running the join, propagating the worst exit.
  Outcomes are Unix-style: `:green/exit` (0 ok, >0 error), `:green/err`,
  `:green/trace`; thrown exceptions inside a step are caught and converted
  automatically.
  `wf/step` turns a whole workflow into an ordinary step function so
  workflows compose (`:in`/`:out` shape opts crossing the boundary). The
  engine preserves inherited advice even when `:in` rebuilds opts from
  scratch; a custom `:in` should carry ambient keys such as `:green/event`
  and `:green/dry-run` if the sub-workflow needs them. See
  `examples/multi-zookeeper` for two clusters built from one workflow.

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
     - If multiple branches are waiting at the same step, that may mean they
       need to join.
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
     - When there are no live branches left, the scheduler returns the final
       opts.
     - If multiple terminal branches exist, it returns the first failed one,
       otherwise the last successful one.

  Short version: it repeatedly runs all safe-to-run steps in parallel, waits
  when branches may need to join, joins converged branches once, and collapses
  forks cleanly on failure.
- **`advice.clj`** — Emacs `nadvice`-style combinators (`:around`,
  `:before`, `:after`, `:override`, `:before-while`, `:before-until`,
  `:after-while`, `:after-until`, `:filter-args`, `:filter-return`) for
  wrapping step functions without touching the wiring. `wf/advice-add`
  targets one step; `wf/advice-add-all` targets every step. Advice stacks
  in strict add order across both registries (most recently added is
  outermost) unless a `:depth` prop (-100..100) overrides placement,
  exactly like Emacs hook depths. Use cases by `how`: `:around` = dry-run,
  retry, timing, locks; `:override` = replace/stub; `:before` = setup or
  prerequisites; `:after` = audit/metrics/cleanup; `:before-while` =
  precondition gates; `:before-until` = fast-path/no-op; `:after-while` =
  success-only follow-up; `:after-until` = recovery/fallback; `:filter-args`
  = normalize/scope input; `:filter-return` = normalize/enrich output.
  Registries are plain immutable values — advice is workflow-scoped, not
  process-global, and all operations (`add`, `remove-id`, `merge-entries`,
  ...) are pure.
  **Advice inheritance across `wf/step` embeds** is the trickiest part of
  the engine: a run stamps its effective registries into opts under a
  private key, the nested run merges them over its own via
  `merge-registry`/`merge-entries`, and this is transitive through nested
  embeds. Step names match flat at any depth — an ancestor's advice on a
  step name reaches an embedded sub-workflow's step of that name. At equal
  `:depth`, ancestor advice stacks outside child advice; an ancestor entry
  with the same `:id` replaces a child's (e.g. swapping a sub-workflow's
  default backend advice from the parent). Use
  `wf/advice-plan` to inspect the composed stack for a step, with
  provenance (`:scope :step`/`:all`, `:level` = chain depth).
- **`scaffold.clj`** — a flat file-spec DSL: a seq of
  `{:template :ns/file :target "path" :data {...}}` maps rendered through
  Selmer from classpath resources. On `:green/event :delete` the same
  specs name what to remove (with immediate empty parent-directory
  pruning).
- **`tofu.clj`** — event-aware OpenTofu steps: any non-`:delete` event
  (conventionally `:create`) → `init` + `apply` (assoc'ing
  `tofu output -json` back into opts under `:tofu/outputs` by default;
  callers can supply `:output-key` and should keep it namespaced),
  `:delete` → `init` + `destroy`. Backends are not
  hardwired — they're attached as `:before` advice
  (`local-backend-advice`/`s3-backend-advice`/`gcs-backend-advice`) that
  writes `backend.tf` before the step runs.
- **`dry_run.clj`** — dry-run is built on the advice facility rather than
  hardwired into steps: `dry-run/advise` attaches `:around` advice (id
  `::skip`) to a list of steps; when `:green/dry-run` is set (stamped by
  the CLI's `--dry-run` flag) the step prints what it would do and is
  skipped instead of run.
- **`cli.clj`** — the thinnest layer: parses `<event> [-f|--file green.edn]
  [--start step] [--end step] [--dry-run]`, loads the desired-state EDN
  file, stamps `:green/event`, and calls `wf/run`. `cli/exec` is what a
  project's `./green` babashka script calls; `cli/run-cli` is the
  testable, non-exiting version.

## Conventions

- All cross-step state is a single `opts` map threaded through the graph;
  steps read and return it, never mutate anything external without going
  through `opts`.
- Namespaced keywords under `:green/*` are reserved for the engine's own
  control keys (`:green/exit`, `:green/err`, `:green/trace`,
  `:green/event`, `:green/dry-run`, `:green/branches`); everything
  project- or library-specific uses its own namespace
  (`:zk/servers`, `:tofu/outputs`, `:green.scaffold/written`, ...).
- Every public workflow constructor/advice-transforming function (`workflow`,
  `advice-add`, `advice-add-all`, `step`, etc.) is pure — it returns a new
  workflow value or step function rather than mutating one, so workflows are
  safe to branch and share.
- Ignore `dist/`: it is generated output, not source. Do not read, edit, or
  use files under `dist/` as documentation; update the source files instead.
