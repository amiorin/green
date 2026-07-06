# smtp-mock — models a transactional email provider (Resend/SES).
# Creates a sending identity for notifications.<domain>.<tld> and hands back
# both the SMTP credentials and the DNS records to publish for auth. Real
# `tofu` over locals/output only.
locals {
  subdomain = "{{subdomain}}"

  # SPF / DKIM / DMARC, all anchored under the notifications subdomain
  records = [
    { type = "TXT", name = local.subdomain, value = "v=spf1 include:smtp-mock.local ~all" },
    { type = "CNAME", name = "resend._domainkey.${local.subdomain}", value = "resend._domainkey.smtp-mock.local" },
    { type = "TXT", name = "_dmarc.${local.subdomain}", value = "v=DMARC1; p=none;" }
  ]
}

output "id" { value = "{{id}}" }
output "records" { value = local.records }
output "smtp_username" { value = "{{username}}" }
output "smtp_password" { value = "{{password}}" }
output "smtp_server" { value = "smtp.smtp-mock.local" }
output "smtp_port" { value = 587 }
output "smtp_use_starttls" { value = true }
