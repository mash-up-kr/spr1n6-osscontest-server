[한국어](DEVELOPMENT.md) | **English**

# Development guide

This document is for contributors. If you just want to spin up the app and try it, [Getting started in the README](../README.en.md#getting-started) is enough.

---

## Choosing a development mode

| Mode | Running the app | DB | When to use |
|---|---|---|---|
| `local` profile | gradle | Containerized PostgreSQL | When developing and modifying server code |
| `dev` profile | gradle | Shared Tmax OpenSQL (SSH tunnel) | When you need to verify behavior against the real DB |
| Full stack | Containers | Containerized PostgreSQL | When checking that things work end-to-end through the relay and worker |

If you're only touching server code, `local` is the fastest option — you don't need to rebuild the app as a container.

---

## `local` profile

Docker spins up PostgreSQL 17.8 and MinIO, and the application connects to these. The image tag is pinned to `0.8.1-pg17` to match the version used in production (Tmax OpenSQL v3.0, based on PostgreSQL 17.8 + pgvector 0.8.1).

```bash
docker compose -f docker-compose.local.yml up -d
./gradlew bootRun --args='--spring.profiles.active=local'
```

Original files are stored in MinIO. The `aidocs-documents` bucket is created automatically when the container starts, so no extra setup is needed, and you can check it from the console at `http://localhost:9001`.

The credentials and ports used to connect directly to the DB and MinIO are in `docker-compose.local.yml`.

Flyway runs automatically on startup.

---

## `dev` profile

Connects to the shared Tmax OpenSQL instance. The DB port isn't exposed externally, so the connection goes through an SSH tunnel. Connection details are managed via `.env`.

`tunnel.sh start` doesn't spin up a DB — it opens a passage from port `15432` on your machine to the remote DB. The app connects to this port as if it were a local DB, so the tunnel must be open before you start the app.

```bash
cp .env.dev.example .env
# Open .env and fill in the values. Get the values and the pem file from your team.

./scripts/tunnel.sh start   # opens the tunnel. Stays up in the background
./gradlew bootRun --args='--spring.profiles.active=dev'
```

MinIO is started with a separate file.

```bash
docker compose -f docker-compose.dev.yml up -d
```

### Managing the tunnel

The tunnel runs in the background and stays up even if you close the terminal. Once opened, you don't need to reopen it when you restart the app — but it disappears on a computer reboot, so you'll need to `start` it again. Running `start` again while it's already open won't open a duplicate.

```bash
./scripts/tunnel.sh status
./scripts/tunnel.sh stop
```

If the app fails with `Connection refused`, it's usually because the tunnel is closed — check `status` first.

Keep the pem file outside the project folder and set its permissions with `chmod 600`. In `.env`, write the file path rather than the key value itself, and use an absolute path or one starting with `~/` so it doesn't depend on where you run things from.

The default local port is `15432`. Since the `local` profile's Docker PostgreSQL uses 5432, opening the tunnel on 5432 as well would either prevent the tunnel from opening while the container is running, or connect you to the wrong DB.

### Conserving connections

The shared DB has `max_connections` set to 100. A single app instance holds 10 connections at all times under HikariCP's default settings, so it fills up quickly once several teammates run it simultaneously. Please shut down the app when you're not using it.

Here's how to check who's connected.

```sql
SELECT usename, application_name, client_addr, state, count(*)
  FROM pg_stat_activity
 GROUP BY 1,2,3,4 ORDER BY count DESC;
```

Connections through the tunnel all show `client_addr` as `127.0.0.1`, so they can't be told apart from each other.

### Applying migrations

The `dev` profile has automatic Flyway execution turned off. Starting the app doesn't change the shared DB's schema.

Turn it on and run it only when an application is actually needed.

```bash
./gradlew bootRun --args='--spring.profiles.active=dev --spring.flyway.enabled=true'
```

Always notify the team before applying one. By default, this is done by the [infra owner](https://github.com/mingdodev); if another teammate needs to do it, let the team know in advance before proceeding. **If a migration file that has already been applied is later modified, every other teammate will hit a checksum mismatch and be unable to start the app.**

---

## Full stack

Used to verify that things work end-to-end through the relay and worker. Run it the same way as [Getting started in the README](../README.en.md#getting-started).

Confirm that upload → relay → worker → search passes end-to-end at least once before deploying to the server.

If you need to connect to the shared DB, layer the tunnel overlay on top. In this case, migrations affect the whole team, so follow the application rules above.

```bash
cp .env.tunnel.example .env.tunnel
docker compose -f docker-compose.yml -f docker-compose.tunnel.yml \
  --env-file .env.tunnel up -d
```

There are some things a containerized PostgreSQL can't verify: ARIA-256 encryption, the Patroni HA setup, and behavior unique to Tmax OpenSQL. ARIA only exists in Tmax OpenCrypto and doesn't run under local `pgcrypto`, so the base setup runs the same code with AES-256 instead.

---

## Compose files

| File | Purpose |
|---|---|
| `docker-compose.yml` | Full stack. Runs the DB as a container alongside everything else |
| `docker-compose.tunnel.yml` | Overlay. Replaces `db` with an SSH tunnel to the shared DB |
| `docker-compose.deploy.yml` | Overlay. Relays TCP to the host DB and terminates HTTPS with Caddy |
| `docker-compose.local.yml` | Infrastructure for the `local` profile (PostgreSQL, MinIO) |
| `docker-compose.dev.yml` | Infrastructure for the `dev` profile (MinIO) |

The base form is kept self-contained, and special cases are layered on top as overlays.

---

## Profile configuration

`local` and `dev` differ in DB location, how migrations are run, and the encryption algorithm. The application code itself is identical.

| Item | `local` | `dev` |
|---|---|---|
| DB | Docker PostgreSQL 17.8 + pgvector 0.8.1 | Shared Tmax OpenSQL v3.0 + pgvector 0.8.1 |
| Connection path | Direct | SSH tunnel |
| Connection details | `application-local.yml` | `.env` |
| Encryption algorithm | AES-256 | ARIA-256 |
| CORS allowlist | `application-local.yml` | `.env` |
| SQL logging | ON | OFF |
| Flyway | Runs automatically on startup | Run manually |

---

## Encryption

`app_user.name` and `document_version.original_filename` are stored encrypted. The algorithm is determined by the `app.encryption_cipher` session setting, and defaults to ARIA-256 if not specified.

ARIA is only available in Tmax OpenCrypto 1.0, so `dev` uses ARIA-256; it isn't available in local PostgreSQL's `pgcrypto`, so `local` uses AES-256. `pgcrypto` on the local container is installed by `scripts/local-db/00-extensions.sql`. The `opencrypto` extension must already be installed on the dev DB beforehand.

The ciphertext embeds algorithm information, so `app_decrypt` can decrypt a value regardless of which algorithm stored it. Ciphertext stored as AES-256 before the switch to ARIA still reads correctly.

`DB_ENCRYPTION_KEY` is passed via the `app.encryption_key` session setting on each DB connection that HikariCP creates when the application starts. If the key changes, existing ciphertext can no longer be decrypted, so during operation the same value must be kept securely and set identically across all application instances.

---

## `dev` profile environment variables

Copy `.env.dev.example` to use.

| Variable | Required | Description |
|---|:--:|---|
| `DB_HOST` | Yes | DB host. Since it goes through the tunnel, this is `127.0.0.1` |
| `DB_PORT` | No | Port (default 5432). Same value as `TUNNEL_LOCAL_PORT` |
| `DB_NAME` | Yes | Database name |
| `DB_USERNAME` | Yes | Connection account |
| `DB_PASSWORD` | Yes | Connection password |
| `DB_ENCRYPTION_KEY` | Yes | High-entropy Base64 key injected into the DB connection's `app.encryption_key` |
| `DB_ENCRYPTION_CIPHER` | No | Encryption algorithm (default `aria256`). Switch to `aes256` only in environments that can't use ARIA |
| `SSH_HOST` | Yes | Address of the server to tunnel into |
| `SSH_PORT` | No | SSH port |
| `SSH_USER` | Yes | SSH account |
| `SSH_KEY_PATH` | Yes | Path to the pem file (absolute or `~/`) |
| `TUNNEL_LOCAL_PORT` | No | Port to open locally (default 15432) |
| `TUNNEL_REMOTE_HOST` | No | DB address as seen from the server (default 127.0.0.1) |
| `TUNNEL_REMOTE_PORT` | No | DB port as seen from the server (default 5432) |
| `STORAGE_ENDPOINT` | Yes | Object storage address |
| `STORAGE_REGION` | Yes | Region. MinIO ignores this, but the SDK uses it for signing, so it must have a value |
| `STORAGE_ACCESS_KEY` | Yes | Access key |
| `STORAGE_SECRET_KEY` | Yes | Secret key |
| `STORAGE_BUCKET` | Yes | Bucket to hold original files |
| `OPENAI_API_KEY` | Yes | OpenAI API key used to convert search queries into 1536-dimensional `text-embedding-3-small` vectors |
| `CORS_ALLOWED_ORIGINS` | Yes | Origins allowed for CORS. Pass multiple values comma-separated. The app won't start without a value here |

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
