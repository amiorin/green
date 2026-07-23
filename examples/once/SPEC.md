# `once` example — spec

A green example modeling a single-machine PaaS in the style of Basecamp
**ONCE** (<https://github.com/basecamp/once>): one VPS that runs a
dockerized website operated by ONCE, fronted by DNS and backed by a
transactional email sender. Green provisions the infrastructure with
OpenTofu and scaffolds the Ansible config that would configure the box —
it does **not** run Ansible itself.

## What this teaches (vs the existing examples)

- **zookeeper** — fan-out/join, scaffold + tofu, backend-as-advice, dry-run.
- **multi-zookeeper** — workflow composition (`wf/step`), advice inheritance.
- **once** — **provider-swap advice on `compute`** (the headline: same output
  contract, different cloud behind a single `:before` advice, exactly like
  `tofu.clj`'s `local-backend-advice`/`s3-backend-advice`), plus a genuine
  **fork/join** (`compute ∥ smtp → dns`) and **opts threaded front-to-back**
  (`smtp.:id` produced first, consumed last).

## Division of labor

- **tofu** is the only muscle — it runs over fake HCL (`locals`/`output`
  only, like `test/green/zookeeper_test.clj`) for `compute`, `smtp`,
  `dns`, `smtp-post`.
- **green** threads `opts` and scaffolds config files.
- **Ansible** is *described but not run*: the `ansible-local` and `ansible-remote`
  steps are pure scaffold steps that render config files. Nothing invokes
  `ansible-playbook`. The rendered playbook is what would install Docker +
  Basecamp ONCE and configure the website.

## Desired state (`green.edn`)

```clojure
{:once/workdir "work"

 ;; → compute  (the VPS; provider is chosen by ::provider advice, not here)
 :once/host    {:name "vps-1" :region "fra1" :size "s-1vcpu-1gb"}

 ;; → compute  (public keys seeded into the VPS authorized_keys)
 :once/ssh     {:compute "ssh-ed25519 AAAA…compute"   ; ansible connects as :sudoer
                :deploy  "ssh-ed25519 AAAA…deploy"}    ; GitHub Action deploys as :user

 ;; → smtp + dns + ansible  (the sites Basecamp ONCE runs on the box)
 :once/website [{:name "campfire"  :image "37signals/campfire:latest"
                 :hostname "chat.example.com"}
                {:name "writebook" :image "37signals/writebook:latest"
                 :hostname "books.example.com"}]}
```

`:once/website` is a **seq** — ONCE hosts several sites on the one VPS.
There is no green fan-out for it (the `app`/`proxy` steps are gone); the seq
is data. **Domains are discovered from it, not declared separately** — there
is no `:once/domain` key:

- `dns` iterates the hostnames → one A-record per `:hostname`, all → the same
  `:ip`, and derives each zone (`chat.example.com` → `example.com`).
- `smtp` derives its sending subdomain — always
  **`notifications.<domain>.<tld>`** — from the hostnames' apex
  (`chat.example.com` → `notifications.example.com`); its `:records`
  (SPF/DKIM/DMARC) all hang under that subdomain.
- `ansible-remote` renders every site in the playbook.

| key | consumed by | for |
|---|---|---|
| `:once/host` | `compute` | instance name/region/size → emits `:ip :name :sudoer :user :uid` |
| `:once/ssh` | `compute`, `ansible-local` | two pub keys → VPS `authorized_keys`; `:compute` key authorizes `:sudoer` (ansible), `:deploy` key authorizes `:user` (GitHub Action) |
| `:once/website` (seq) | `smtp`, `dns`, `ansible-local`, `ansible-remote` | sending domain (apex); A-record per hostname; sites ONCE deploys |

### The two SSH keys

- **`:compute`** authorizes `:sudoer` (root). Ansible connects with it — so
  `ansible-local`'s inventory names this key for the `:sudoer` connection.
- **`:deploy`** authorizes `:user` (`deploy`). A GitHub Action SSHes in as
  `:user` with it to pull + run a new Docker image — ONCE's continuous
  deployment path.

Both public keys are seeded into the VPS `authorized_keys` by the `compute`
step; only the public halves live in `green.edn` (private keys stay with the
operator / in CI secrets).

Provider and backend are **not** in the EDN — they're picked by the
`::provider` / `::backend` advice, which is the point of the swap-via-advice
design.

## Events

Two events, stamped by `cli/exec` from the command line, exactly like the
zookeeper example:

```sh
./green create   # provision + render config
./green delete   # tear down + remove config
```

Every step is event-aware:

- **tofu steps** (`compute`, `smtp`, `dns`, `smtp-post`) — `tofu.clj` already
  branches on the event: any non-`:delete` event → `init` + `apply`,
  `:delete` → `init` + `destroy`.
- **scaffold steps** (`ansible-local`, `ansible-remote`) — the same specs render on
  `:create` and name what to remove on `:delete` (with empty parent-dir
  pruning).

> Delete semantics to settle when building: teardown wants the reverse of
> the create order (config/ansible first, then `dns`/`smtp`, `compute`
> last). `smtp-post` on `:delete` is likely a no-op (or destroys the
> verification resource). Because everything is mocked, ordering is about
> faithfulness, not real dependency safety.

## Workflow graph

```
compute ─┐                              ┌─► ansible-local
         ├─► dns ──► smtp-post ─────────┤
smtp ────┘                              └─► ansible-remote
```

- `compute` and `smtp` are the two parallel provisioning legs (a fork).
- `dns` **joins** both — it needs `compute.:ip` and `smtp.:records`.
- `smtp-post` then **forks** to `ansible-local` and `ansible-remote`, which run in
  parallel: independent scaffold steps (inventory vs playbook), no data
  dependency between them, both terminal leaves (no join after).
- The `dns → smtp-post` edge is a **correctness constraint**, not cosmetic:
  the email-auth records must be published before verification is triggered.

## Steps

| step | kind | consumes | produces / role |
|---|---|---|---|
| **compute** | tofu | desired state | VPS; **provider swappable via `::provider` advice** |
| **smtp** | tofu | desired state | SMTP identity |
| **dns** | tofu | `compute.:ip`, `smtp.:records`, `:once/website` | A-record per hostname + email-auth records into cloudflare-mock; **no outputs (sink)** |
| **smtp-post** | tofu | `smtp.:id` | triggers `resend_domain_verification`; runs after `dns` |
| **ansible-local** | scaffold | `compute` outputs, `:once/ssh` | render a `~/.ssh/config` `Host` entry for the VPS into `work/ansible-local/` (no command) |
| **ansible-remote** | scaffold | threaded `opts` | render remote playbook (Docker + ONCE install + website config) into `work/ansible-remote/` (no command) |

### Output contracts

**compute** — uniform across every provider (this is what makes the
provider-swap advice clean; downstream steps never learn which cloud
produced them):

```clojure
:tofu/outputs {:ip     "203.0.113.10"   ; public IP  → dns A-records
               :name   "vps-1"          ; instance identity
               :sudoer "root"           ; privileged user → ansible connection / bootstrap
               :user   "deploy"         ; unprivileged user the containers run as
               :uid    1000}            ; its uid → /storage ownership
```

**smtp** — behaves like a real transactional email provider (Resend/SES):
hands back both SMTP creds and the DNS records to publish for
authentication. The identity is always the **`notifications.<domain>.<tld>`**
subdomain (apex from `:once/website`), so every `:records` entry is anchored
there.

```clojure
:tofu/outputs {:id                "snd-abc123"
               :records           [{:type "TXT"   :name "notifications.example.com"        :value "v=spf1 …"}
                                   {:type "CNAME" :name "…dkim._domainkey.notifications.example.com" :value "…"}
                                   {:type "TXT"   :name "_dmarc.notifications.example.com"  :value "…"}]
               :smtp_username     "…"
               :smtp_password     "…"
               :smtp_server       "smtp.smtp-mock.local"
               :smtp_port         587
               :smtp_use_starttls true}
```

**dns** — no `output` block; `:tofu/outputs` is `{}` for this step.

## Advice

Two advice-driven swaps, both following `tofu.clj`'s backend-as-advice
pattern (a `:before` advice that scaffolds HCL before the tofu step runs).

### `::provider` — which cloud (headline)

`compute` is provider-agnostic; a `:before` advice under id `::provider`
scaffolds the provider-specific HCL before the step runs. Written as
example-local functions (like `node-dir`), **not** as library additions to
`green.tofu` — provider choice is a project concern. Two providers, each a
fake tofu module (like the other mocks) emitting the **same** compute output
contract `{:ip :name :sudoer :user :uid}`:

- **`digitalocean-mock`** (shipped attached)
- **`oci-mock`** (Oracle Cloud)

Swap between them by re-adding under the same `::provider` id (the
multi-zookeeper backend-swap idiom) — downstream steps never learn which
cloud produced the host.

### `::backend` — where the state lives (isolated per step)

The tofu **backend is also advice** — the same `tofu.clj` idiom
(`local-backend-advice`/`s3-backend-advice`/`gcs-backend-advice` write
`backend.tf.json`). Each of the four tofu steps (`compute`, `smtp`, `dns`,
`smtp-post`) gets its **own isolated state** rather than sharing one. The
isolation key is a **per-step identifier** — the last segment of each step's
working dir (`work/server`, `work/smtp`, `work/dns`, `work/smtp-post`).
Both the `dir-fn` (local file layout) and the backend `config` derive from
that same identifier, so no two steps ever collide.

- **local** (shipped default) — isolation is the **directory**: each step's
  `terraform.tfstate` sits under its own `work/<step>/`.
- **s3** — the bucket is shared, so isolation **must** come from a distinct
  `:key` per step. `s3-backend-advice` takes `config` as a **function of
  opts** (`resolve-config`, `tofu.clj:73`), so the advice derives the key,
  e.g.

  ```clojure
  (tofu/s3-backend-advice
    dir-fn
    (fn [opts]
      {:bucket "once-tfstate"
       :key    (str "once/" (step-id opts) "/terraform.tfstate")  ; ← per step
       :region "eu-west-1"}))
  ```

  giving `once/server/…`, `once/smtp/…`, `once/dns/…`,
  `once/smtp-post/…` — four separate state objects. A single shared `:key`
  would clobber; deriving it from `step-id` is what guarantees isolation.
- **gcs** — same, isolate via a per-step `:prefix`.

Swapping backends is the re-add-under-`::backend` move; whichever backend is
active, the per-step identifier keeps the four states apart.

## Working-dir layout

Dir names are the **generic resource** they hold (`server`, `smtp`), not the
step name:

```
work/
  server/     backend.tf.json  main.tf         terraform.tfstate   (compute step; oci-mock | digitalocean-mock)
  smtp/       backend.tf.json  main.tf         terraform.tfstate   (smtp step)
  dns/        backend.tf.json  main.tf         terraform.tfstate   (dns step; cloudflare-mock, no outputs)
  smtp-post/  backend.tf.json  main.tf         terraform.tfstate   (smtp-post step)
  ansible-local/   …config…                                   (ansible-local step; scaffold only, no state)
  ansible-remote/  …playbook…                                 (ansible-remote step; scaffold only, no state)
```

Each tofu step computes its own `:dir` (e.g. `work/server`) and passes it
to `tofu/tofu-step`; the `::backend` advice writes `backend.tf.json` into that
same dir — the two agree on the path, exactly as `node-step` and the
node backend advice do in zookeeper.

## Mocks

Every "real" cloud/service is a fake tofu module — real `tofu` over HCL with
only `locals`/`output`, like `test/green/zookeeper_test.clj`.

- **oci-mock** / **digitalocean-mock** — the `compute` step's target, one
  selected by the `::provider` advice. Each emits the uniform compute
  contract `{:ip :name :sudoer :user :uid}` and seeds the `:once/ssh` keys
  into `authorized_keys`.
- **cloudflare-mock** — the `dns` step's target. A fake tofu module
  (`resources/cloudflare/main.tf`), input-only (`locals` + apply, no
  `output`), that "creates" the A-records (`hostname → :ip`) and the
  email-auth records (`← smtp.:records`).
- **smtp-mock** — the `smtp`/`smtp-post` target. A fake tofu module
  modeling Resend: `smtp` creates the domain identity for
  `notifications.<domain>.<tld>` (emits `:id`, `:records` anchored under that
  subdomain, SMTP creds); `smtp-post` triggers `resend_domain_verification`
  with `:id`.

## Ansible files

Both steps only render files — no execution. Their names mark **whose
machine** they configure:

- **ansible-local** — configures the **operator's own machine** to reach the
  new VPS: a `~/.ssh/config` `Host` entry built from compute's outputs and
  the `:compute` key.

  ```sshconfig
  Host vps-1                 # compute :name
    HostName 203.0.113.10    # compute :ip
    User root                # compute :sudoer
    IdentityFile ~/.ssh/…    # :once/ssh :compute
  ```

  To stay idempotent and reversible (green's ethos), render the fragment into
  `work/ansible-local/config` and wire it into `~/.ssh/config` via an
  `Include` line — `create` adds it, `delete` removes it, no destructive
  rewrite of the user's ssh config.

- **ansible-remote** — configures the **box itself**: the remote playbook
  rendered into `work/ansible-remote/` (tasks: install Docker, install
  Basecamp ONCE, run the sites with `SMTP_*` env + hostnames).

## Open questions

_(none)_
