# One-time imports: these were created in the AWS console on 2026-10-12.
# Fill in the sgr- ID, run plan/apply once, then delete this file.
# Find the rule ID:
#   aws ec2 describe-security-group-rules --region ap-northeast-2 \
#     --filters Name=group-id,Values=<security_group_id output> \
#     --query "SecurityGroupRules[?IpProtocol=='112'].SecurityGroupRuleId" --output text

import {
  to = aws_vpc_security_group_ingress_rule.vrrp
  id = "sgr-REPLACE_ME"
}

import {
  to = aws_iam_policy.openproxy_vip
  id = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:policy/openproxy-vip-assign"
}

import {
  to = aws_iam_role.openproxy_vip
  id = "openproxy-vip-role"
}

import {
  to = aws_iam_role_policy_attachment.openproxy_vip
  id = "openproxy-vip-role/arn:aws:iam::${data.aws_caller_identity.current.account_id}:policy/openproxy-vip-assign"
}

import {
  to = aws_iam_instance_profile.openproxy_vip
  id = "openproxy-vip-role"
}
