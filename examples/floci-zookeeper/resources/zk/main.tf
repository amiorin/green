terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

provider "aws" {
  region                      = "us-east-1"
  access_key                  = "test"
  secret_key                  = "test"
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true
  skip_region_validation      = true

  endpoints {
    ec2 = "{{endpoint}}"
  }
}

resource "aws_security_group" "zk" {
  name        = "zk-{{node.name}}"
  description = "zookeeper {{node.name}}"

  ingress {
    description = "zookeeper client"
    from_port   = 2181
    to_port     = 2181
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "zookeeper quorum"
    from_port   = 2888
    to_port     = 2888
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "zookeeper leader election"
    from_port   = 3888
    to_port     = 3888
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# floci starts sshd in the instance container without creating its
# privilege-separation directory, so sshd dies at boot. This sshd-only
# bootstrap is the single user-data exception in this example — all
# provisioning happens through Ansible over SSH.
resource "aws_instance" "zk" {
  ami                    = "{{ami}}"
  instance_type          = "{{instance-type}}"
  key_name               = "{{key-name}}"
  vpc_security_group_ids = [aws_security_group.zk.id]

  user_data = <<-EOT
    #!/bin/sh
    mkdir -p /run/sshd /var/empty/sshd
    pgrep -x sshd >/dev/null || /usr/sbin/sshd
  EOT

  tags = {
    Name = "{{node.name}}"
  }
}

output "id" {
  value = {{node.id}}
}

output "name" {
  value = "{{node.name}}"
}

output "ip" {
  value = aws_instance.zk.private_ip
}

output "instance_id" {
  value = aws_instance.zk.id
}
