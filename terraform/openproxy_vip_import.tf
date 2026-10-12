# One-time imports: these were created in the AWS console on 2026-10-12.
# Run plan/apply once, then delete this file.

import {
  to = aws_vpc_security_group_ingress_rule.vrrp
  id = "sgr-0304bffb1ce3d499e"
}

import {
  to = aws_iam_policy.openproxy_vip
  id = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:policy/openproxy-vip-role"
}

import {
  to = aws_iam_role.openproxy_vip
  id = "openproxy-vip-role"
}

import {
  to = aws_iam_role_policy_attachment.openproxy_vip
  id = "openproxy-vip-role/arn:aws:iam::${data.aws_caller_identity.current.account_id}:policy/openproxy-vip-role"
}

import {
  to = aws_iam_instance_profile.openproxy_vip
  id = "openproxy-vip-role"
}
