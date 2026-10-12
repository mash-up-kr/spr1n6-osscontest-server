# OpenSQL HA Infrastructure

[한국어](README.md) | **English**

Creates the three DB nodes for the OpenSQL HA cluster in the Seoul region (`ap-northeast-2`). Scope ends at the servers; OpenSQL, Patroni, etcd and OpenProxy are not installed.

## Resources

| Resource | Details |
|---|---|
| Network | Dedicated VPC `10.40.0.0/16`, one public subnet `10.40.1.0/24`, internet gateway, route table |
| EC2 × 3 | `t3.small`, Rocky Linux 9, encrypted gp3 30GiB, 2GiB swap |
| Elastic IP | One per node, so the address survives stop/start |
| Security group | See [Ports](#ports) |
| OpenProxy VIP permissions | IAM policy `openproxy-vip-role`, role and instance profile `openproxy-vip-role` (attached to db-02 and db-03). See [OpenProxy VIP](#openproxy-vip) |

| Node | Hostname | Private IP |
|---|---|---|
| 01 | `spr1n6-db-01` | `10.40.1.11` |
| 02 | `spr1n6-db-02` | `10.40.1.12` |
| 03 | `spr1n6-db-03` | `10.40.1.13` |

Hostname and swap are set on first boot by `cloud-init.yaml.tftpl`.

## Ports

| Port | Allowed from | Purpose |
|---|---|---|
| 22 | `ssh_cidrs` (example opens to all) | SSH, local DB tunnel |
| 2379, 2380 | Same security group | etcd client, peer |
| 5432 | Same security group | PostgreSQL, replication |
| 6432, 6433 | Same security group | OpenProxy, OpenProxy admin |
| 8008 | Same security group | Patroni REST |
| IP protocol 112 | Same security group | VRRP, used by the two OpenProxy nodes to elect the VIP owner |

No DB port is exposed publicly. Local access goes through an SSH tunnel.

## Run

Prerequisites

- An AWS CLI profile for the IAM user running apply. Required permissions are in `iam-policy.json`.
- **An EC2 key pair registered in the Seoul region.** Key pairs are per account and region; without one, instance creation fails with `InvalidKeyPair.NotFound`.

```bash
cp terraform.tfvars.example terraform.tfvars   # fill in aws_profile, key_pair_name
terraform init
terraform plan -out=ha.tfplan
terraform apply ha.tfplan
terraform output public_ips
```

Connect with `ssh -i <key.pem> rocky@<public_ip>`. Remove everything with `terraform destroy` when done; Elastic IPs are billed until released.

Do not commit `terraform.tfvars`, `*.tfstate`, `*.tfplan` or `*.pem`.

## OpenProxy VIP

Applications connect to a single VIP, `10.40.1.100:6432`. Normally db-02's OpenProxy holds it; if db-02 fails, db-03 takes over.

- OpenProxy's VRRP (unicast, IP protocol 112) decides the owner.
- An AWS VPC does not follow an IP moved by VRRP alone, so scripts on the servers move the secondary private IP between ENIs with the AWS API. `vip_promote.sh` moves it to the node's own ENI when it becomes owner; `vip_demote.sh` moves it to the peer's ENI when it steps down. The scripts live in `/home/opensql/etc/openproxy/`, outside this module.
- This module only creates the permissions those scripts use (`openproxy_vip.tf`) and the VRRP security group rule. The permissions are limited to assigning and unassigning secondary IPs on the db-02 and db-03 ENIs.
- Terraform does not manage the VIP. `aws_instance` ignores changes to `secondary_private_ips`.

**Before the first apply (once)**

These resources were created in the console on 2026-10-12, so the import blocks in `openproxy_vip_import.tf` bring them into state.

1. Find the security group rule ID (`sgr-…`) and replace `sgr-REPLACE_ME` in `openproxy_vip_import.tf`. The lookup command is in the file's comment.
2. Apply the updated `iam-policy.json` to the IAM user that runs apply. The new statements are `ManageOpenProxyVipIam`, `PassOpenProxyVipRoleToEc2` and `AttachInstanceProfiles`.
3. In `terraform plan`, expect only the 5 imports and small in-place updates such as tags or descriptions on the policy, role and profile. The same profile is already attached to db-02 and db-03 from the console, so `iam_instance_profile` should show no change. Do not apply if any instance or existing rule would be destroyed or replaced.
4. After apply, delete `openproxy_vip_import.tf`.

**Note**

- Recreating db-02 or db-03 changes its ENI ID. Terraform updates the policy, but `vip_demote.sh` on the servers lists both ENI IDs and must be updated too.

## Local DB access

Use `scripts/tunnel.sh` or `docker-compose.tunnel.yml` as is. The tunnel enters db-01, which runs no OpenProxy, and ends at the VIP, so it survives an OpenProxy failover.

```
SSH_HOST=<db-01 public IP>
SSH_USER=rocky
SSH_KEY_PATH=/absolute/path/key.pem
TUNNEL_REMOTE_HOST=10.40.1.100
TUNNEL_REMOTE_PORT=6432
```

## Limitations

- All three nodes share one subnet in `ap-northeast-2a`, so an AZ outage is not tolerated.
- VRRP or gratuitous ARP alone does not move a VIP in an AWS VPC, so it is moved with the AWS API as described in [OpenProxy VIP](#openproxy-vip). The VIP can only move within the same subnet.
- `iam-policy.json` is a development convenience policy with some `Resource: *` actions, not a least-privilege policy. Only the IAM permissions are narrowed to the `openproxy-vip-*` names.
- Keep DB passwords, licenses and private keys out of user-data, tfstate and the repository.
