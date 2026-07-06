# oci-mock — fake compute for the `compute` step (Oracle Cloud).
# Same uniform contract as digitalocean-mock, different values behind it:
# note :sudoer is "opc" (OCI's default admin user), not "root". Swapping the
# ::provider advice to this module changes real downstream values (the
# ansible-local ssh User) without any other step knowing which cloud ran.
locals {
  provider = "oci"
  name     = "{{host.name}}"
  region   = "{{host.region}}"
  ip       = "198.51.100.20"
  sudoer   = "opc"
  user     = "deploy"
  uid      = 1000

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
