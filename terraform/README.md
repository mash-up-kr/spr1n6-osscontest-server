# OpenSQL HA 인프라

**한국어** | [English](README.en.md)

OpenSQL HA 클러스터를 올릴 DB 노드 3대를 서울 리전(`ap-northeast-2`)에 만든다. 범위는 서버까지이고, OpenSQL·Patroni·etcd·OpenProxy는 설치하지 않는다.

## 만드는 리소스

| 리소스 | 내용 |
|---|---|
| 네트워크 | 전용 VPC `10.40.0.0/16`, 퍼블릭 서브넷 `10.40.1.0/24` 하나, 인터넷 게이트웨이, 라우트 테이블 |
| EC2 3대 | `t3.small`, Rocky Linux 9, 암호화된 gp3 30GiB, 스왑 2GiB |
| Elastic IP | 노드마다 하나. 인스턴스를 껐다 켜도 접속 주소가 바뀌지 않는다 |
| 보안 그룹 | 아래 [포트](#포트) 참고 |
| OpenProxy VIP 권한 | IAM 정책 `openproxy-vip-role`, 역할·인스턴스 프로파일 `openproxy-vip-role`(db-02·db-03에 연결). 아래 [OpenProxy VIP](#openproxy-vip) 참고 |

| 노드 | 호스트 이름 | 사설 IP |
|---|---|---|
| 01 | `spr1n6-db-01` | `10.40.1.11` |
| 02 | `spr1n6-db-02` | `10.40.1.12` |
| 03 | `spr1n6-db-03` | `10.40.1.13` |

호스트 이름과 스왑은 첫 부팅 때 `cloud-init.yaml.tftpl`이 설정한다.

## 포트

| 포트 | 허용 출처 | 용도 |
|---|---|---|
| 22 | `ssh_cidrs` (기본 예시는 전체 허용) | SSH, 로컬 DB 터널 |
| 2379, 2380 | 같은 보안 그룹 | etcd 클라이언트, 피어 |
| 5432 | 같은 보안 그룹 | PostgreSQL, 복제 |
| 6432, 6433 | 같은 보안 그룹 | OpenProxy, OpenProxy 관리 |
| 8008 | 같은 보안 그룹 | Patroni REST |
| IP 프로토콜 112 | 같은 보안 그룹 | VRRP. OpenProxy 2대가 VIP 주인을 정하는 신호 |

DB 포트는 외부에 열지 않는다. 로컬에서는 SSH 터널로 접속한다.

## 실행

사전 준비

- apply할 IAM 사용자의 AWS CLI 프로필. 필요한 권한은 `iam-policy.json`에 있다.
- **서울 리전에 등록된 EC2 키 페어.** 키 페어는 계정·리전 단위라 다른 리전에 있는 키는 쓸 수 없다. 없으면 인스턴스 생성 단계에서 `InvalidKeyPair.NotFound`로 실패한다.

```bash
cp terraform.tfvars.example terraform.tfvars   # aws_profile, key_pair_name 채우기
terraform init
terraform plan -out=ha.tfplan
terraform apply ha.tfplan
terraform output public_ips
```

접속은 `ssh -i <키.pem> rocky@<public_ip>`로 한다. 다 쓰면 `terraform destroy`로 지운다. Elastic IP는 반납하기 전까지 요금이 나간다.

`terraform.tfvars`, `*.tfstate`, `*.tfplan`, `*.pem`은 커밋하지 않는다.

## OpenProxy VIP

앱은 VIP `10.40.1.100:6432` 하나로 붙는다. 평소에는 db-02 OpenProxy가 VIP를 갖고, db-02가 죽으면 db-03이 이어받는다.

- 주인은 OpenProxy의 VRRP가 정한다(유니캐스트, IP 프로토콜 112).
- AWS VPC는 VRRP로 옮긴 IP를 따라가지 않는다. 그래서 서버에 있는 스크립트가 AWS API로 보조 사설 IP를 ENI 사이로 옮긴다. 주인이 될 때는 `vip_promote.sh`가 VIP를 내 ENI로, 내려갈 때는 `vip_demote.sh`가 상대 ENI로 옮긴다. 스크립트는 `/home/opensql/etc/openproxy/`에 있고 이 Terraform 구성 밖이다.
- 이 구성은 그 스크립트가 쓰는 권한(`openproxy_vip.tf`)과 VRRP 보안 그룹 규칙만 만든다. 권한은 db-02·db-03 ENI의 보조 IP 할당·해제로 좁혀 두었다.
- VIP는 Terraform이 관리하지 않는다. `aws_instance`는 `secondary_private_ips` 변경을 무시한다.

**주의**

- db-02·db-03 인스턴스를 다시 만들면 ENI ID가 바뀐다. 정책은 Terraform이 다시 맞추지만, 서버의 `vip_demote.sh`에는 두 ENI ID가 적혀 있어서 함께 고쳐야 한다.

## 로컬 DB 접속

`scripts/tunnel.sh`나 `docker-compose.tunnel.yml`을 그대로 쓴다. 터널은 OpenProxy가 없는 db-01로 들어가 VIP로 이어진다. 그래서 OpenProxy가 넘어가도 터널은 끊기지 않는다.

```
SSH_HOST=<db-01 공인 IP>
SSH_USER=rocky
SSH_KEY_PATH=/절대경로/키.pem
TUNNEL_REMOTE_HOST=10.40.1.100
TUNNEL_REMOTE_PORT=6432
```

## 제약

- 3대 모두 `ap-northeast-2a`의 서브넷 하나에 있어 AZ 장애는 견디지 못한다.
- AWS VPC에서는 VRRP·gratuitous ARP만으로는 VIP가 옮겨지지 않는다. 그래서 위 [OpenProxy VIP](#openproxy-vip)처럼 AWS API로 옮긴다. VIP는 같은 서브넷 안에서만 옮길 수 있다.
- `iam-policy.json`은 개발 편의용이라 일부 권한이 `Resource: *`로 열려 있다. 최소 권한 정책이 아니다. IAM 권한만은 `openproxy-vip-*` 이름으로 좁혔다.
- DB 비밀번호·라이선스·개인키는 user-data, tfstate, 저장소 어디에도 넣지 않는다.
