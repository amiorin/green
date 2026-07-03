locals {
  id   = {{node.id}}
  name = "{{node.name}}"
  ip   = "{{node.ip}}"
}

output "id" {
  value = local.id
}

output "name" {
  value = local.name
}

output "ip" {
  value = local.ip
}
