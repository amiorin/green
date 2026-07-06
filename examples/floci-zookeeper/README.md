# floci-zookeeper — a real ZooKeeper cluster, entirely on your machine

Unlike `examples/zookeeper` (fake HCL, nothing real), this example builds a
**real 3-node ZooKeeper ensemble**: OpenTofu talks to
[floci](https://github.com/floci-io/floci) — a local AWS emulator on
`localhost:4566` — which backs each `aws_instance` with a real Docker
container; Ansible then provisions ZooKeeper over SSH with an injected
public key; a health step asks every node `srvr` and requires one leader
and two followers.

```sh
./green create --dry-run   # print the run; touches nothing, needs nothing
./green create             # real cluster: tofu -> floci EC2 -> ansible -> health
./green delete             # delete.yml deprovisions over SSH, then tofu destroys
```

`create` starts floci itself if it isn't running (a `:before` advice —
idempotent), generates an SSH keypair under `work/ssh/`, then runs two
fan-out/join cycles: per-node tofu apply (3 parallel) → provision join
(collects IPs) → per-node `ansible-playbook create.yml` (3 parallel, each
with a single-host inventory and the full ensemble passed as extra-vars for
`zoo.cfg`) → health join (quorum check). A second `create` is a no-op
ansible run against the same cluster.
`delete` fans out 3 `wf/step` sub-workflows in parallel, each running
`delete-node.yml` to stop ZK on one node and then tofu-destroying the
instance — `wf/step` keeps the branches independent (without it the engine
would join the per-node `:zk/node` entries).

## Requirements

- Linux with Docker (the example connects straight to the instances'
  Docker-bridge IPs; Docker Desktop on macOS/Windows cannot route to them)
- `tofu`, `ansible-playbook`, `bb` on PATH — checked by a gate advice
  before anything runs
- Outbound network (floci image, AWS provider plugin, ZooKeeper tarball)

Optional but recommended: set `TF_PLUGIN_CACHE_DIR` (to an existing
directory) so the three per-node `tofu init`s share one download of the
AWS provider instead of fetching it three times.

## What to look at

- **`green.ansible` used like `green.tofu`**: the step is event-aware
  (`create.yml` / `delete-node.yml`), and the per-node inventory is
  attached as a `:before` advice (`inventory-advice`), exactly like tofu
  backends.
- **Two fan-out/join cycles on create**: per-node tofu → provision join →
  per-node ansible → health join. The full ensemble is passed as extra-vars
  so each node's `zoo.cfg` knows all peers from a single-host inventory.
- **`wf/step` for independent pipelines on delete**: each branch runs a
  sub-workflow (ansible-stop → tofu-destroy) for one node. Without
  `wf/step` the engine would join the per-node `:zk/node` entries because
  they arrive from different `:zk/ansible` parents.
- **Validation advices**: `:before-while` gates for the desired-state
  schema (pure — also runs under `--dry-run`), tool requirements, and
  per-step inputs; a `:filter-args` advice reads tofu state on delete to
  populate the node list; an `:around` retry advice polls the health check
  while the ensemble elects a leader.
- **The sole user-data** is a 3-line sshd bootstrap compensating for a
  floci bug (it starts sshd without creating its privilege-separation
  directory); every piece of real provisioning is Ansible over SSH.

## Cleanup

`./green delete` removes the cluster but deliberately leaves floci
running (the advice never stops what other state may live in). To remove
floci and the generated keys/inventory:

```sh
docker rm -f floci && docker volume rm floci-data
rm -rf work
```

See `PLAN.md` for the full design and the probe findings this example is
built on.
