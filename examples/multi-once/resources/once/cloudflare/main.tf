# cloudflare-mock — the `dns` step's target. Input-only: it "publishes"
# records but exports nothing (a sink). It joins two upstreams — an A-record
# per website hostname (all -> the compute :ip) and the email-auth records
# (<- smtp :records). Real `tofu` over locals only, no outputs.
locals {
  a_records = [
{% for h in hostnames %}    { name = "{{h}}", type = "A", value = "{{ip}}" },
{% endfor %}  ]

  email_records = [
{% for r in email %}    { name = "{{r.name}}", type = "{{r.type}}", value = "{{r.value|safe}}" },
{% endfor %}  ]
}
