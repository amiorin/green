# digitalocean-mock — fake compute for the `compute` step.
# Real `tofu` over locals/output only (no real cloud). Chosen by the
# ::provider advice; emits the uniform compute contract that every provider
# must satisfy: {:ip :name :sudoer :user :uid}.
locals {
  provider = "digitalocean"
  name     = "{{host.name}}"
  region   = "{{host.region}}"
  ip       = "203.0.113.10"
  sudoer   = "root"
  user     = "deploy"
  uid      = 1000

  # both public keys are seeded into the VPS authorized_keys
  authorized_keys = [
    "{{ssh.compute}}",
    "{{ssh.deploy}}",
  ]
}

output "ip" { value = local.ip }
output "name" { value = local.name }
output "sudoer" { value = local.sudoer }
output "user" { value = local.user }
output "uid" { value = local.uid }
