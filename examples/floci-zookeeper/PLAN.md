# Plan: examples/floci-zookeeper — a real ZooKeeper cluster on floci

Status: agreed design, implementation starting. This file records the plan;
it will be updated if probing floci invalidates an assumption.

## Goal

A new example, `examples/floci-zookeeper`, that provisions a **real 3-node
ZooKeeper cluster** locally: OpenTofu (AWS provider pointed at
[floci](https://github.com/floci-io/floci), a local AWS emulator on
`localhost:4566`) creates one Docker-backed EC2 instance per node, and
**Ansible over SSH** provisions the ZooKeeper software. No user-data.

## Settled decisions

- **No user-data.** All provisioning happens through Ansible over SSH with an
  injected public key (`aws_key_pair` from a locally generated keypair).
- **Ansible becomes a library feature**: new `src/green/ansible.clj` modeled
  on `tofu.clj`, not example-local code.
- **Playbooks per event**: `create.yml` for any non-`:delete` event,
  `delete.yml` for `:delete` (stop/remove ZK while the instances still
  exist, before tofu destroys them).
- **Published host ports** (floci maps security-group ports to host ports in
  the 30000–30999 range) are surfaced **via tofu output variables**.
- **Instance containers are assumed to have outbound network** (apt/download
  works).
- **Starting floci is a `:before` advice** (`::floci` on the start step):
  idempotent — if `localhost:4566` is healthy it no-ops, otherwise it
  `docker run`s `floci/floci` with the Docker socket mounted and a persistent
  volume, and waits for health. **Stopping is never automatic**: the cluster
  lives inside floci, and floci's default storage is in-memory, so stopping
  it between `create` and `delete` would strand the local tofu state. The
  README documents manual cleanup (`docker rm -f floci`).

## New library namespace: `green/ansible.clj` (mirrors `tofu.clj`)

- `ansible-step` — event-aware, like `tofu-step`: non-`:delete` events run
  `ansible-playbook create.yml`, `:delete` runs `delete.yml`; playbook names
  overridable via opts. Takes `{:dir ...}` plus SSH settings (user, private
  key, host-key checking off for emulated hosts). Assocs the play recap back
  into opts under a namespaced key (default `:ansible/recap`, caller can
  override, keep it namespaced). Non-zero `ansible-playbook` exit maps to
  Unix-style `:green/exit`/`:green/err` like every other step.
- `inventory-advice` — the analog of `local-backend-advice`: inventories are
  not hardwired; a `:before` advice writes the inventory file (from a fn of
  opts → hosts) before the step runs.
- Unit test `test/green/ansible_test.clj` in the spirit of `tofu_test.clj`
  (no real ansible run needed for the advice/selection logic).

## Example structure (mirrors `examples/zookeeper`)

- `green.edn` — `:zk/workdir`, `:zk/servers` (id, name, ip), AMI alias
  (e.g. `ami-ubuntu2404`), SSH user.
- Selmer templates under `resources/zk/`:
  - `main.tf` — AWS provider with floci endpoints + dummy credentials and
    skip-validation flags; `aws_key_pair`; security group opening
    2181/2888/3888; `aws_instance` (with `private_ip` if floci honors it);
    outputs: private IP, published SSH port, published client port.
  - `inventory.ini`, `create.yml`, `delete.yml`, `zoo.cfg` — Ansible
    material; `zoo.cfg` is templated from the collected node IPs.
- Workflow:
  - `:zk/start` → fan-out one `:zk/node` per server (scaffold `main.tf` +
    `tofu-step`, per-node local backend/state) → join `:zk/ansible`
    (collect all IPs/ports from `:green/branches`, scaffold inventory +
    playbooks, run `create.yml`) → `:zk/health`.
  - **Delete routing via `next-fn`**: on `:delete` the order reverses —
    `:zk/ansible` (running `delete.yml`) comes first, then the per-node
    tofu destroys. First example to demonstrate event-based dynamic routing.
  - `:zk/health` — real verification: connect to each node's published
    client port, send the `srvr` four-letter word, assert one leader and
    two followers.

## Advices

- `::floci` — `:before` on `:zk/start`: ensure floci is running (see above).
- `::schema` — `:before-while` gate validating the `green.edn` shape
  (servers vector, unique ids, required keys) with a clear message.
- `::requirements` — `:before-while` gate: `tofu` and `ansible-playbook` on
  PATH, docker usable.
- `::inputs` — per-step input validation (e.g. `:zk/node` present on node
  steps, branch outputs present at the join).
- Retry `:around` advice on `:zk/health` — poll until quorum, with timeout
  (ZK takes a few seconds to elect a leader).
- `dry-run/advise` on all effectful steps; `--dry-run` stays the offline
  path and touches nothing.

## Probe findings (floci 1.5.30, settled)

1. **`private_ip` is not honored** — floci assigns the Docker bridge IP and
   ignores the request. Consequence: `green.edn` carries no IPs; Ansible
   templates `zoo.cfg` from the observed IPs.
2. **The AWS API does not expose published host ports** (not in
   describe-instances, not in tags — only floci's logs). But on Linux the
   bridge IPs are directly routable from the host and
   `aws_instance.private_ip` reports the real container IP — so SSH,
   quorum traffic, and the health check all use the private IP straight
   from the tofu outputs. No host-port plumbing at all. (Non-Linux Docker
   Desktop cannot route to bridge IPs; documented limitation.)
3. **Outbound network works** from instance containers (https + apt OK).
4. **floci bug: sshd dies at boot on every AMI** — floci starts sshd without
   creating its privilege-separation directory (`/run/sshd` on
   Debian/Ubuntu, `/var/empty/sshd` on Amazon Linux). Fix chosen by the
   user: a minimal, sshd-only user-data bootstrap (mkdir the privsep dirs +
   start sshd), verified working. This is the sole user-data exception; all
   provisioning stays in Ansible.
5. Selmer and Jinja2 both use `{{ }}`: playbooks and `zoo.cfg.j2` are
   **static files** (not Selmer-scaffolded) so the braces never collide;
   only data-dependent files (per-node `main.tf`, the inventory) are
   generated.

## Finish line

- `green.ansible` unit-tested (`test/green/ansible_test.clj`: playbook
  selection, PLAY RECAP parsing, INI inventory rendering, inventory
  advice), added to both `bb.edn` (`:requires` + `run-tests`) and JVM
  discovery. No heavyweight floci e2e is wired into the suite — that would
  make `bb test` require Docker + a running floci and take minutes. This
  matches the repo pattern: only the fake-HCL `zookeeper_test.clj` runs
  real `tofu`; the other examples (`multi-zookeeper`, `once`, `multi-once`)
  are verified by driving `./green`. floci-zookeeper is likewise verified
  end-to-end by `./green create`/`delete` against a real floci.
- `bb test` and `clojure -X:test` green.
- CLAUDE.md updated (new namespace, new example, commands block).

## Verified end-to-end (2026-07-06, floci 1.5.30, tofu + AWS provider 6.53)

- `./green create --dry-run` — 3-way fan-out, join, health; touches nothing.
- schema gate rejects a malformed `green.edn` with `:green/exit 2`.
- `./green create` — three parallel tofu applies create Docker-backed EC2
  instances, join into one `ansible-playbook create.yml` run, health step
  reports `zk3=leader, zk1/zk2=follower`. Cross-node replication confirmed
  (a znode written via `zkCli` on one node read back on another).
- create is idempotent (a second run re-provisions cleanly, exit 0).
- `./green delete` — `delete.yml` deprovisions over SSH, then the per-node
  destroys run; 0 leftover instance containers.

## Known floci quirks worked around

- sshd privsep dir missing → sshd-only user-data bootstrap.
- floci marks instances `running` before sshd accepts connections →
  `::wait-ssh` `:before` advice polls port 22 per node.
- `zkServer.sh status` races the server post-restart → the start task's
  `failed_when` treats "already running" as success (health verifies for
  real).
- AWS provider needs `skip_requesting_account_id = true` (no STS/IAM on
  floci) and must not pass the removed `skip_requested_account_id`.
