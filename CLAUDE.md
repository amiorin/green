# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`green` is a babashka-compatible Clojure library for building idempotent
devops CLIs: desired state in EDN, workflows as step graphs threaded by a
map, Selmer-scaffolded config files, OpenTofu as the muscle. The full
specification lives in `index.html` (open it in a browser; the README's
reference to `doc/spec.html` is aliased to this same file).

## Commands

```sh
bb test             # run the test suite under babashka
clojure -X:test     # run the test suite under the JVM

clojure -T:build jar       # build target/green-<version>.jar + pom
clojure -T:build install   # install into the local ~/.m2
clojure -T:build deploy    # deploy to Clojars (needs CLOJARS_USERNAME/CLOJARS_PASSWORD)
```

Run a single test namespace under the JVM: `clojure -X:test :nses '[green.advice-test]'`.
Under babashka, `bb.edn`'s `test` task always runs the full suite listed in
`bb.edn` and `deps.edn`'s `:test` alias — add new test namespaces to both
`bb.edn` (`:requires` and the `run-tests` call) and, if it must run
standalone under the JVM, invoke `clojure.test/run-tests` directly.

Try the examples end-to-end:

```sh
cd examples/zookeeper
./green create --dry-run   # print the plan-of-record, touch nothing
./green create             # fake 3-node ZooKeeper cluster in ./work
./green delete

cd ../multi-zookeeper
./green create             # two clusters, one composed workflow, in parallel
./green delete
```

Each example's `./green` script is a self-contained babashka script that
pulls in `green` via `:local/root "../.."` — no separate build step needed
to try changes made to `src/`.

The end-to-end test suite (`test/green/zookeeper_test.clj`, `tofu_test.clj`)
drives real `tofu` over HCL containing only `locals`/`output` blocks — full
render/apply/destroy cycles, zero real infrastructure, but `tofu` must be on
`PATH`.

## Architecture

Five namespaces under `src/green/`, layered so each only depends on the ones
below it:

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
  workflows compose (`:in`/`:out` shape opts crossing the boundary) — see
  `examples/multi-zookeeper` for two clusters built from one workflow.
- **`advice.clj`** — Emacs `nadvice`-style combinators (`:around`,
  `:before`, `:after`, `:override`, `:before-while`, `:before-until`,
  `:after-while`, `:after-until`, `:filter-args`, `:filter-return`) for
  wrapping step functions without touching the wiring. Advice stacks in
  strict add order (most recently added is outermost) unless a `:depth`
  prop (-100..100) overrides placement, exactly like Emacs hook depths.
  Registries are plain immutable maps — advice is workflow-scoped, not
  global, and all operations (`add`, `remove-id`, `merge-entries`, ...)
  are pure.
  **Advice inheritance across `wf/step` embeds** is the trickiest part of
  the engine: a run stamps its effective registries into opts under a
  private key, the nested run merges them over its own via
  `merge-registry`/`merge-entries`, and this is transitive through nested
  embeds. Step names match flat at any depth — an ancestor's advice on a
  step name reaches an embedded sub-workflow's step of that name, and an
  ancestor entry with the same `:id` replaces a child's (e.g. swapping a
  sub-workflow's default backend advice from the parent). Use
  `wf/advice-plan` to inspect the composed stack for a step, with
  provenance (`:scope :step`/`:all`, `:level` = chain depth).
- **`scaffold.clj`** — a flat file-spec DSL: a seq of
  `{:template :ns/file :target "path" :data {...}}` maps rendered through
  Selmer from classpath resources. On `:green/event :delete` the same
  specs name what to remove (with empty-parent-dir pruning).
- **`tofu.clj`** — event-aware OpenTofu steps: `:create` → `init` +
  `apply` (merging `tofu output -json` back into opts under a namespaced
  key, never top-level), `:delete` → `init` + `destroy`. Backends are not
  hardwired — they're attached as `:before` advice
  (`local-backend-advice`/`s3-backend-advice`/`gcs-backend-advice`) that
  writes `backend.tf` before the step runs.
- **`dry-run.clj`** — dry-run is built on the advice facility rather than
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
- Every public workflow/advice-mutating function (`advice-add`, `step`,
  etc.) is pure — it returns a new workflow value rather than mutating one,
  so workflows are safe to branch and share.
