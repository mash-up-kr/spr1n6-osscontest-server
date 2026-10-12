# OpenProxy VIP (10.40.1.100) between db-02 and db-03.
# VRRP decides the owner; OpenProxy's pre_promote_script / pre_demote_script on the
# nodes move the secondary private IP between the two ENIs with the AWS API.
# The scripts live on the servers, not in this module.

locals {
  vip_nodes = toset(["02", "03"])
}

resource "aws_vpc_security_group_ingress_rule" "vrrp" {
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = aws_security_group.db.id
  ip_protocol                  = "112"
  description                  = "VRRP between OpenProxy"
}

data "aws_caller_identity" "current" {}

data "aws_iam_policy_document" "openproxy_vip_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "openproxy_vip" {
  statement {
    sid     = "MoveOpenProxyVipBetweenDb02AndDb03"
    actions = ["ec2:AssignPrivateIpAddresses", "ec2:UnassignPrivateIpAddresses"]
    resources = [
      for k in sort(tolist(local.vip_nodes)) :
      "arn:aws:ec2:${var.aws_region}:${data.aws_caller_identity.current.account_id}:network-interface/${aws_instance.db[k].primary_network_interface_id}"
    ]
  }
}

resource "aws_iam_policy" "openproxy_vip" {
  name   = "openproxy-vip-assign"
  policy = data.aws_iam_policy_document.openproxy_vip.json
  tags   = local.common_tags
}

resource "aws_iam_role" "openproxy_vip" {
  name               = "openproxy-vip-role"
  assume_role_policy = data.aws_iam_policy_document.openproxy_vip_assume.json
  tags               = local.common_tags
}

resource "aws_iam_role_policy_attachment" "openproxy_vip" {
  role       = aws_iam_role.openproxy_vip.name
  policy_arn = aws_iam_policy.openproxy_vip.arn
}

resource "aws_iam_instance_profile" "openproxy_vip" {
  name = "openproxy-vip-role"
  role = aws_iam_role.openproxy_vip.name
  tags = local.common_tags
}
