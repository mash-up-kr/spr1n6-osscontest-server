[한국어](DEVELOPMENT.md) | **English**

# Development guide

This document is for contributors. If you just want to spin up the app and try it, [Getting started in the README](../README.en.md#getting-started) is enough.

---

## Environments

| Profile | Purpose | DB | Compose file |
|---|---|---|---|
| `local` | Development and tests | Container PostgreSQL | `docker-compose.local.yml` |
| `dev` | Testing against the DB cluster (HA tests, feature demos) | DB cluster (SSH tunnel → OpenProxy) | `docker-compose.dev.yml` |

With either profile you can run the app with gradle, or bring up server and worker with compose too. When you only touch server code, running just the infrastructure in containers and the app with gradle is the fastest. Tests run against the `local` infrastructure.

---

## `local` profile

Docker runs PostgreSQL 17.8 and MinIO, and the application connects to them. The image tag is pinned to `0.8.1-pg17` so it matches the DB cluster (Tmax OpenSQL v3.0, based on PostgreSQL 17.8 + pgvector 0.8.1). Tests run against this DB and MinIO too.

```bash
docker compose -f docker-compose.local.yml up -d postgres minio minio-init
./gradlew bootRun --args='--spring.profiles.active=local'
```

To run server and worker as containers too, bring it up without service names.

```bash
docker compose -f docker-compose.local.yml up -d --build
```

No `.env` file is needed. DB, encryption key and MinIO values are fixed for local use in `application-local.yml` and `docker-compose.local.yml`. Only external API keys are passed in.

| Variable | Required | Description |
|---|:--:|---|
| `OPENAI_API_KEY` | X | Embeddings. Without it the server starts, but indexing and vector search don't work |
| `COHERE_API_KEY` | X | Search result reranking. Without it results come back in RRF order |

- With gradle, they are read only from the shell environment (`export OPENAI_API_KEY=...`). The `local` profile does not read `.env`.
- With compose, they are read from the shell environment or the `.env` in the project root. Even if a dev `.env` exists, only these two values are taken from it.

Original files are stored in MinIO. The `aidocs-documents` bucket is created when the container starts, so no extra setup is needed. You can browse it in the console at `http://localhost:9001`.

The accounts and ports for connecting to the DB and MinIO directly are in `docker-compose.local.yml`.

Flyway runs automatically on startup.

---

## `dev` profile

Connects to the DB cluster (Tmax OpenSQL + Patroni) through OpenProxy. The address is the OpenProxy VIP `10.40.1.100:6432`. It is not reachable from outside the VPC, so you connect through an SSH tunnel via `db-01`. Connection details live in `.env`.

`tunnel.sh start` does not start a DB. It opens a passage from port `15432` on your machine to the VIP. The app treats that port as a local DB, so the tunnel must be open before you start the app.

```bash
cp .env.dev.example .env
# Fill in the password, key and pem path in .env. Get the values and the pem file from the team.

docker compose -f docker-compose.dev.yml up -d minio minio-init   # same MinIO as the worker (host port 9010)
./scripts/tunnel.sh start               # opens the tunnel and keeps it in the background
./gradlew bootRun --args='--spring.profiles.active=dev'
```

The `dev` profile sends `SET SERVER ROLE TO 'default'` on every connection, so it does not work against plain PostgreSQL.

### Managing the tunnel

The tunnel keeps running in the background even after you close the terminal. Once it's open you don't need to reopen it when you restart the app. It disappears when you reboot, so run `start` again. Running `start` while it's already open does not open a second one.

```bash
./scripts/tunnel.sh status
./scripts/tunnel.sh stop
```

If the app fails with `Connection refused`, the tunnel is usually closed. Check `status` first.

Keep the pem file outside the project folder and set its permissions with `chmod 600`. Put the file path in `.env`, not the key itself, and use an absolute path or one starting with `~/` so it works from any directory.

The default local port is `15432`. The `local` profile's Docker PostgreSQL uses 5432, so opening the tunnel on 5432 too would either fail while the container is up or connect you to the wrong DB.

### Conserving connections

The `aidocs_session` pool allows at most 14 DB connections per OpenProxy. One server holds 10 of them, so effectively only one server across the team can be connected to the cluster at a time. Don't run a gradle `dev` app and the dev stack's server together either. Connections over the limit wait 10 seconds and then fail with `AllServersDown` (`58000`). Shut the app down when you're not using it.

Search uses the separate `aidocs_ro` pool (Replica). Its limit is 8, and one server opens up to 8.

To see who is connected:

```sql
SELECT usename, application_name, client_addr, state, count(*)
  FROM pg_stat_activity
 GROUP BY 1,2,3,4 ORDER BY count DESC;
```

Connections through OpenProxy show the OpenProxy server's address as `client_addr`, so you can't tell apps apart by it.

### Applying migrations

Automatic Flyway runs are off in the `dev` profile. Starting the app does not change the cluster's schema.

Turn it on only when you need to apply migrations:

```bash
./gradlew bootRun --args='--spring.profiles.active=dev --spring.flyway.enabled=true'
```

For migrations that take longer than 60 seconds, also set `DB_SOCKET_TIMEOUT=0`.

Always tell the team before applying. By default the [infrastructure owner](https://github.com/mingdodev) does it. If someone else needs to, announce it to the team first. **If you edit a migration file after it has been applied, everyone else on the team will fail to start the app with a checksum mismatch.**

### dev stack

Runs server, worker and MinIO as containers. The DB is reached through the SSH tunnel opened by the `db-tunnel` container, so you don't need `tunnel.sh` on the host.

```bash
cp .env.dev.example .env   # fill in the values
docker compose -f docker-compose.dev.yml up -d --build
```

| Container | Connection |
|---|---|
| `server` | `db-tunnel:6432` / `aidocs_session` pool, `dev` profile |
| `worker` | `db-tunnel:6432` / `aidocs` pool |
| `minio` | `minio:9000` between containers, `localhost:9010` from the host (console `9011`) |

When you are on the VPC through a VPN, clear `COMPOSE_PROFILES` and set `COMPOSE_DB_HOST=10.40.1.100` in `.env`. The stack then connects to the VIP directly without `db-tunnel`.

### Node monitoring dashboard

Shows the role (Leader/Replica), state and replication lag of the three DB nodes in Grafana. It carries the same information as `patronictl list` and refreshes every 2 seconds. It runs separately from the dev stack.

```bash
docker compose -f docker-compose.monitoring.yml up -d
# http://localhost:3030  the home page is the "OpenSQL HA 노드 현황" dashboard
docker compose -f docker-compose.monitoring.yml down
```

The data comes from the metrics Patroni exposes on each node (`8008/metrics`). Port 8008 is open only inside the cluster, so the `patroni-tunnel` container reaches it through an SSH tunnel via db-01. It uses the same `SSH_*` values in `.env` as the dev stack and installs nothing on the DB servers.

| Panel | Content |
|---|---|
| Top numbers | Leader node, streaming replica count, responding node count, TL, max lag |
| 노드 현황 (node status) | Member, IP, role, state, TL, lag and Postgres start time per node |
| 노드별 상태 변화 (state changes) | Colored bands showing when each node's state changed |
| Replica Lag | Bytes each replica is behind the leader |

The three nodes are a fixed list. A node whose Patroni is stopped or whose server is down stays on the dashboard as a red `응답 없음` (no response). `patronictl list` shows it as `stopped` and drops the row about 30 seconds later. The rules that compute role, state and lag are in `monitoring/prometheus/rules.yml`.

Lag is the leader's written WAL position minus the replica's received WAL position. Nodes are scraped up to 2 seconds apart, so under heavy writes the lag can look larger by that much.

The dashboard is `monitoring/grafana/dashboards/opensql-ha-nodes.json`. Edits made in the UI are not saved, so change the JSON file. The ports are opened only on this Mac.

---

## Compose files

| File | Purpose |
|---|---|
| `docker-compose.local.yml` | `local`: PostgreSQL, MinIO, server, worker. Tests use only PostgreSQL and MinIO |
| `docker-compose.dev.yml` | `dev`: SSH tunnel to the DB cluster, MinIO, server, worker |
| `docker-compose.monitoring.yml` | Node monitoring: SSH tunnel for Patroni metrics, Prometheus, Grafana |

---

## Profile configuration

`local` and `dev` differ in DB location, how migrations run, and the encryption algorithm. The application code is the same.

| Item | `local` | `dev` |
|---|---|---|
| DB | Docker PostgreSQL 17.8 + pgvector 0.8.1 | DB cluster (Tmax OpenSQL v3.0 + pgvector 0.8.1) |
| Connection path | Direct | SSH tunnel → OpenProxy VIP |
| Connection settings | `application-local.yml` | `.env` |
| Connection init | None | `SET SERVER ROLE TO 'default'` |
| Search connection | Shares the main connection | Separate `aidocs_ro` pool (Replica, up to 8) |
| Encryption algorithm | AES-256 | ARIA-256 |
| CORS allow list | `application-local.yml` | `.env` |
| SQL logging | ON | OFF |
| Flyway | Runs automatically on startup | Run manually |

---

## Encryption

`app_user.name` and `document_version.original_filename` are stored encrypted. The algorithm is set by `app.encryption_cipher`; if unset, ARIA-256 is used.

ARIA exists only in Tmax OpenCrypto 1.0, so `dev` uses ARIA-256. Local PostgreSQL's `pgcrypto` doesn't have it, so `local` uses AES-256. In the local container, `pgcrypto` is installed by `scripts/local-db/00-extensions.sql`. The cluster must already have the `opencrypto` extension installed.

The ciphertext records which algorithm produced it, so `app_decrypt` can decrypt values stored either way. AES-256 ciphertext stored before the switch to ARIA still reads correctly.

`DB_ENCRYPTION_KEY` is set as `app.encryption_key` at the start of every transaction, for that transaction only. So code that reads or writes encrypted columns must run inside `@Transactional`. If the key changes, existing ciphertext can no longer be decrypted, so set the same value on every instance.

---

## Environment variables

Copy `.env.dev.example` to `.env`. The gradle `dev` profile and the dev stack read the same file.

| Variable | Required | Description |
|---|:--:|---|
| `DB_HOST` | dev | DB host. `127.0.0.1` because it goes through the tunnel |
| `DB_PORT` | dev | Port. Same as `TUNNEL_LOCAL_PORT` (`15432`) |
| `DB_NAME` | dev | OpenProxy pool name `aidocs_session` |
| `DB_SEARCH_NAME` | X | OpenProxy pool name for the search connection (default `aidocs_ro`) |
| `DB_USERNAME` | O | Account `aidocs` |
| `DB_PASSWORD` | O | Password |
| `DB_ENCRYPTION_KEY` | O | High-entropy Base64 key set as `app.encryption_key` in every transaction |
| `DB_ENCRYPTION_CIPHER` | X | Encryption algorithm (default `aria256`) |
| `DB_SOCKET_TIMEOUT` | X | Seconds to wait for a query response (default 60). Raise it, or set `0` (no limit), only when running a long migration manually |
| `SSH_HOST` | O | Server the tunnel goes through (`db-01`, `13.124.25.248`) |
| `SSH_PORT` | X | SSH port (default 22) |
| `SSH_USER` | O | SSH account (`rocky`) |
| `SSH_KEY_PATH` | O | Path to the pem file. The dev stack accepts absolute paths only |
| `TUNNEL_LOCAL_PORT` | X | Local port `tunnel.sh` opens (default 15432) |
| `TUNNEL_REMOTE_HOST` | X | Address as seen from the server (default OpenProxy VIP `10.40.1.100`) |
| `TUNNEL_REMOTE_PORT` | X | Port as seen from the server (default `6432`) |
| `STORAGE_ENDPOINT` | dev | MinIO address as seen from the host (`http://localhost:9010`) |
| `STORAGE_REGION` | O | Region. MinIO ignores it, but the SDK uses it for signing, so it must be set |
| `STORAGE_ACCESS_KEY` | O | Access key |
| `STORAGE_SECRET_KEY` | O | Secret key |
| `STORAGE_BUCKET` | O | Bucket for original files |
| `OPENAI_API_KEY` | O | OpenAI API key for embeddings (`text-embedding-3-small`, 1536 dimensions) |
| `CORS_ALLOWED_ORIGINS` | O | Origins allowed by CORS. Pass several separated by commas |
| `COHERE_API_KEY` | X | Search result reranking. Without it, RRF order is used |
| `SERVER_HOST_PORT` · `MINIO_HOST_PORT` · `MINIO_CONSOLE_HOST_PORT` | X | Dev stack host ports (8080 / 9010 / 9011) |
| `GRAFANA_HOST_PORT` · `PROMETHEUS_HOST_PORT` | X | Monitoring stack host ports (3030 / 9090) |
| `GRAFANA_ADMIN_PASSWORD` | X | Grafana `admin` password (default `admin`). Viewing needs no login |
| `WORKER_IMAGE` | X | Worker image (default `ghcr.io/mash-up-kr/spr1n6-osscontest-worker:latest`) |

`dev` in the Required column means it's needed only when running with gradle. In the dev stack, compose sets the in-container values.

Do not commit `.env`, real connection details, or the pem file.

---

## Collaboration rules

### Branches

| Branch | Purpose |
|---|---|
| `main` | Integration branch |
| `feat/*` | Feature development |
| `fix/*` | Bug fixes |
| `refactor/*` | Refactoring |
| `docs/*` | Documentation work |
| `chore/*` | Config, build, dependencies |

Branch off from `main` for your work, then merge back into `main` via PR once done.

### Commit messages

The subject line follows the `<type>: <subject>` format. The body can be written freely.

```
feat: 문서 업로드 API 구현
fix: 대용량 PDF 파싱 시 OOM 수정
docs: README 프로파일 구성 추가
refactor: DocumentService 책임 분리
test: 문서 검색 통합 테스트 추가
chore: Spring Boot 4.1.0 업그레이드
```

---

## Related documents

- [Contributing](../CONTRIBUTING.en.md) — branches, commit messages, PRs
- [Code conventions](CODE_CONVENTIONS.en.md)
- [API specification](API_SPEC.en.md)
