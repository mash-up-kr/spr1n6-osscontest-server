output "instance_ids" { value = { for k, v in aws_instance.db : k => v.id } }
output "private_ips" { value = { for k, v in aws_instance.db : k => v.private_ip } }
output "public_ips" { value = { for k, v in aws_eip.db : k => v.public_ip } }
output "security_group_id" { value = aws_security_group.db.id }

output "vpc_id" { value = aws_vpc.ha.id }
output "subnet_id" { value = aws_subnet.db.id }
output "proxy_vip_candidate" {
  value       = "10.40.1.100"
  description = "Reserved in plan only; NOT assigned in AWS. Needs AWS API-based reassignment."
}
