locals {
  nodes            = toset(["01", "02", "03"])
  node_private_ips = { "01" = "10.40.1.11", "02" = "10.40.1.12", "03" = "10.40.1.13" }
  common_tags      = { Project = "spr1n6-opensql-ha", ManagedBy = "Terraform" }
}

resource "aws_security_group" "db" {
  name_prefix = "spr1n6-db-"
  description = "OpenSQL HA node communication; SSH restricted"
  vpc_id      = aws_vpc.ha.id
  tags        = merge(local.common_tags, { Name = "spr1n6-db-sg" })
}

resource "aws_vpc_security_group_ingress_rule" "ssh" {
  for_each          = toset(var.ssh_cidrs)
  security_group_id = aws_security_group.db.id
  ip_protocol       = "tcp"
  from_port         = 22
  to_port           = 22
  cidr_ipv4         = each.value
  description       = "SSH and local DB tunnel"
}

resource "aws_vpc_security_group_ingress_rule" "intra_cluster" {
  for_each                     = toset(["2379", "2380", "5432", "6432", "6433", "8008"])
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = aws_security_group.db.id
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.value)
  to_port                      = tonumber(each.value)
  description                  = "Cluster internal TCP ${each.value}"
}

resource "aws_vpc_security_group_egress_rule" "all_ipv4" {
  security_group_id = aws_security_group.db.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
  description       = "Outbound (restrict for production)"
}

resource "aws_instance" "db" {
  for_each                    = local.nodes
  ami                         = var.ami_id
  instance_type               = var.instance_type
  subnet_id                   = aws_subnet.db.id
  private_ip                  = local.node_private_ips[each.key]
  vpc_security_group_ids      = [aws_security_group.db.id]
  key_name                    = var.key_pair_name
  associate_public_ip_address = var.associate_public_ip_address
  user_data = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    hostname = "spr1n6-db-${each.key}"
  })
  metadata_options {
    http_tokens = "required"
  }
  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_gb
    encrypted             = true
    delete_on_termination = true
    tags                  = local.common_tags
  }
  tags = merge(local.common_tags, { Name = "spr1n6-db-${each.key}" })
}

resource "aws_eip" "db" {
  for_each = local.nodes
  domain   = "vpc"
  instance = aws_instance.db[each.key].id
  tags     = merge(local.common_tags, { Name = "spr1n6-db-${each.key}" })
}
