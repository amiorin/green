# smtp-post — runs AFTER dns has published the records. Triggers the
# provider's domain verification for the identity created by `smtp`, using
# its :id. The dns -> smtp-post edge is a correctness constraint: you cannot
# verify records that are not published yet.
locals {
  domain_id = "{{id}}"
  action    = "resend_domain_verification"
}

output "verification" {
  value = {
    id     = local.domain_id
    action = local.action
    status = "verifying"
  }
}
