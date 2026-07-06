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
idempotent), generates an SSH keypair under `work/ssh/`, applies one tofu
config per node in parallel, joins into a single `ansible-playbook
create.yml` run over all observed IPs, and polls the ensemble's health.
A second `create` is a no-op ansible run against the same cluster.
`delete` reverses the order: `delete.yml` stops and removes ZooKeeper
while the instances still exist, then the per-node destroys fan out.

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
  (`create.yml` / `delete.yml`), and the inventory is attached as a
  `:before` advice (`inventory-advice`), exactly like tofu backends.
- **Event-based dynamic routing** in `next-fn`: on `:delete` the ansible
  step runs *before* the node destroys — the only example where the graph
  order depends on the event.
- **Validation advices**: `:before-while` gates for the desired-state
  schema (pure — also runs under `--dry-run`), tool requirements, and
  per-step inputs; a `:filter-args` advice normalizes the observed node
  list; an `:around` retry advice polls the health check while the
  ensemble elects a leader.
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
