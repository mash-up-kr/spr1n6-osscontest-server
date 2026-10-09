# OpenSQL HA — Terraform phase 1 (AWS Seoul)

Creates three EC2 instances **only**, plus Elastic IPs, dedicated VPC, single public subnet, internet gateway, route table, security group, and 2GiB swap per instance. Does not install OpenSQL, Patroni, etcd or OpenProxy.

## Settings
- AMI `ami-0ed6cd3fecc849a03` (verify AMI owner/architecture before apply)
- `t3.small`, 30GiB encrypted gp3 each
- Rocky Linux 9.7, hostnames `spr1n6-db-01/02/03`
- Private IPs `10.40.1.11/.12/.13`; future proxy VIP candidate `10.40.1.100` **not allocated**
- All nodes in `ap-northeast-2a`, one subnet: **not AZ-resilient**
- Elastic IP per node so the SSH/tunnel address survives stop/start (e.g. power-off failover tests). Run `terraform destroy` when done; EIPs keep billing until released.
- SSH allowed from `ssh_cidrs` (default example opens to all, key-only auth). DB, etcd, Patroni ports are restricted to the cluster security group.
- Local access goes through an SSH tunnel to OpenProxy on db-02 (`127.0.0.1:<proxy port>`); no DB port is exposed publicly. App node and its inbound rules are out of scope for this phase.

## Run
1. `cp terraform.tfvars.example terraform.tfvars`
2. Set your existing AWS EC2 **key pair name** (not .pem path), `ssh_cidrs`, and AWS CLI profile.
3. `terraform init && terraform fmt -check && terraform validate && terraform plan`
4. `terraform apply`
5. `terraform output`; SSH as `rocky` with your existing PEM.
6. Destroy test resources with `terraform destroy` when done.

## Networking and VRRP caveat
AWS VPC does **not** support ordinary LAN-style floating VIP via gratuitous ARP/VRRP alone. For a private VIP, use an AWS-assigned **secondary private IP** and move it between db-02/db-03 ENIs via EC2 API, typically driven by a failover hook. This requires runtime IAM permissions and failure fencing; **not implemented in phase 1**. A private VIP is **not reachable directly from a local laptop**. For development use SSH tunnel/VPN or later provide a public entrypoint (e.g. NLB). Public subnet + public EC2 IP does not expose the private VIP.

Avoid placing database credentials/licenses/private keys in Terraform state, source control or user-data. Verify license's CPU/core/thread constraints. `t3.small` has limited RAM; swap is not a replacement for RAM.

## IAM
`iam-policy.json` includes permissions for creating/destroying EC2, SG, VPC, subnet, IGW and routes. It is a development convenience policy with some broad `Resource: *` actions, not a production least-privilege policy. Additional IAM permission `ec2:AssignPrivateIpAddresses` and `ec2:UnassignPrivateIpAddresses` (and possibly `ec2:DescribeNetworkInterfaces`) will be needed by the **runtime failover actor** if implementing AWS API-based VIP transfer later; do not give these to all DB processes by default.
