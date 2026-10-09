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

## Local DB access

Use `scripts/tunnel.sh` or `docker-compose.tunnel.yml` as is. The tunnel enters db-02, where OpenProxy runs, and ends at OpenProxy on that host.

```
SSH_HOST=<db-02 public IP>
SSH_USER=rocky
SSH_KEY_PATH=/absolute/path/key.pem
TUNNEL_REMOTE_HOST=127.0.0.1
TUNNEL_REMOTE_PORT=6432
```

## Limitations

- All three nodes share one subnet in `ap-northeast-2a`, so an AZ outage is not tolerated.
- VRRP or gratuitous-ARP VIPs do not work as-is in an AWS VPC. A private VIP needs a secondary private IP moved via the EC2 API, and whatever moves it needs `ec2:AssignPrivateIpAddresses` and `ec2:UnassignPrivateIpAddresses`. Not included here. The VIP candidate `10.40.1.100` is only an output value and is not allocated.
- `iam-policy.json` is a development convenience policy with some `Resource: *` actions, not a least-privilege policy.
- Keep DB passwords, licenses and private keys out of user-data, tfstate and the repository.
