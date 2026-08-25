# Tmax OpenSQL 기반 AI 문서 관리 시스템

![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1.0-6DB33F?logo=springboot&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)
![Java](https://img.shields.io/badge/Java-21_LTS-437291?logo=openjdk&logoColor=white)
![Spring Data JPA](https://img.shields.io/badge/Spring_Data_JPA-Hibernate_7-59666C?logo=hibernate&logoColor=white)
![Tmax OpenSQL](https://img.shields.io/badge/Tmax_OpenSQL-v3.0-0B4DA2)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17.8-4169E1?logo=postgresql&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)

> 2026 오픈소스 개발자 대회 · 티맥스티베로 기업 과제

---

## 개요

문서를 올려 두면 필요할 때 **의미로 찾아 주는** 문서 관리 시스템입니다. 파일명이나 정확한 단어를 기억하지 못해도, 묻고 싶은 내용을 그대로 적으면 관련된 대목을 찾아 줍니다.

PDF·DOCX·Markdown·HWP·TXT 를 올릴 수 있고, 같은 문서를 다시 올리면 새 버전으로 쌓입니다. 어느 버전을 검색 대상으로 삼을지는 직접 고를 수 있고, 내용이 같은 파일을 올리면 중복이라고 알려 줍니다. 문서마다 누가 볼 수 있는지를 사용자와 테넌트 단위로 정합니다.

**데모** — https://spr1n6-osscontest-web.vercel.app/

---

## 핵심 기능

### 의미 기반 검색

올린 문서를 문단 단위로 나눠 1536차원 벡터로 색인하고, HNSW 인덱스로 가장 가까운 대목을 찾습니다. 검색어와 글자가 겹치지 않아도 뜻이 통하면 걸립니다.

찾은 대목의 앞뒤 문단을 함께 돌려주는 옵션이 있습니다. 문단 하나만 떼어 보면 맥락을 알 수 없는 경우가 많기 때문입니다.

### 유실 없는 비동기 인덱싱

업로드 응답은 인덱싱을 기다리지 않습니다. 대신 문서를 저장하는 트랜잭션과 **같은 원자 단위로** 인덱싱 요청 이벤트를 남깁니다.

이 이벤트는 DB 트리거가 만듭니다. 애플리케이션이 발행을 빠뜨릴 수 없고, 저장이 롤백되면 이벤트도 함께 사라집니다. 남은 이벤트는 릴레이가 카프카로 옮기고 워커가 받아 처리합니다.

### 저장 데이터 암호화

사용자 이름과 원본 파일명을 DB에 암호화해 저장합니다. 키는 애플리케이션이 DB 연결마다 세션 설정으로 넘기므로 DB 에 남지 않습니다.

Tmax OpenCrypto 가 있는 환경에서는 ARIA-256 을, 없으면 AES-256 을 씁니다. 암호문에 알고리즘 정보가 들어 있어 두 방식으로 저장된 값이 섞여 있어도 그대로 읽힙니다.

### MCP 서버

문서 검색과 목록 조회를 MCP 도구로 노출합니다. Claude 같은 AI 클라이언트가 이 서버에 직접 붙어, 쌓아 둔 문서를 근거로 답할 수 있습니다.

---

## 시작하기

### 요구 사항

- Docker & Docker Compose (v2.24 이상)
- OpenAI API 키 — 문서 인덱싱과 검색 질의의 임베딩에 사용합니다

### 실행

```bash
cp .env.example .env
# .env를 열어 OPENAI_API_KEY를 채웁니다. 나머지는 기본값으로 동작합니다.

docker compose up -d --build
```

PostgreSQL, MinIO, Kafka, API 서버, 릴레이, 워커가 함께 뜹니다. 스키마는 서버가 기동하면서 Flyway로 적용합니다.

서버가 준비되면 다음이 `UP`을 반환합니다.

```bash
curl localhost:8080/actuator/health
```

### 데모 데이터

이 프로젝트에는 토큰 발급이 없고 `X-User-Id` 헤더로 신원을 받습니다. 데모 사용자를 넣지 않으면 모든 요청이 401로 실패합니다.

```bash
docker compose exec -T db psql \
  -U "${DB_USERNAME:-aidocs}" -d "${DB_NAME:-aidocs}" \
  -v key="${DB_ENCRYPTION_KEY:-local-only-throwaway-key}" -v cipher=aes256 \
  < scripts/seed-demo.sql
```

여러 번 실행해도 안전합니다. `.env`에 값을 채웠다면 `set -a; . ./.env; set +a` 로 불러온 뒤 실행하거나, 위 명령의 기본값 자리에 그 값을 직접 적어 주세요.

### 접속

| 대상 | 주소 |
|---|---|
| API | `http://localhost:8080` |
| MinIO 콘솔 | `http://localhost:9011` |
| Kafka UI | `http://localhost:8081` |

### 종료

```bash
docker compose down        # 컨테이너만 내립니다
docker compose down -v     # 데이터까지 지웁니다
```

### 환경 변수

채워야 하는 값은 `OPENAI_API_KEY` 하나입니다. 나머지는 비워 두면 기본값이 들어갑니다.

| 변수명 | 필수 | 설명 |
|---|:--:|---|
| `OPENAI_API_KEY` | O | 임베딩 생성에 사용합니다 |
| `DB_ENCRYPTION_KEY` | X | 사용자명·파일명 암호화 키. 기본값은 로컬 전용입니다 |
| `SERVER_HOST_PORT` | X | API 포트 (기본 8080) |
| `CORS_ALLOWED_ORIGINS` | X | 허용할 origin (기본 `http://localhost:5173`) |

전체 목록은 [`.env.example`](.env.example)에 있습니다.

---

## 저장소 구성

업로드한 문서가 검색 가능해지기까지 네 저장소가 이어 달립니다.

```
web  ──업로드──▶  server  ──Outbox──▶  relay  ──Kafka──▶  worker
                    ▲                                        │
                    └────────── 청크·임베딩 저장 ─────────────┘
```

| 저장소 | 역할 |
|---|---|
| [server](https://github.com/mash-up-kr/spr1n6-osscontest-server) | API 서버. 업로드·권한·검색을 담당하고 Outbox 이벤트를 남깁니다 |
| [relay](https://github.com/mash-up-kr/spr1n6-osscontest-relay) | Outbox 행을 읽어 카프카로 발행합니다 |
| [worker](https://github.com/mash-up-kr/spr1n6-osscontest-worker) | 문서를 청크로 나누고 임베딩해 저장합니다 |
| [web](https://github.com/mash-up-kr/spr1n6-osscontest-web) | React SPA |

이 저장소는 `server` 입니다.

---

## 기술 스택

| 구분         | 기술                          | 버전     |
|------------|-----------------------------|--------|
| Language   | Kotlin                      | 2.3    |
| Runtime    | Java (JVM)                  | 21 LTS |
| Framework  | Spring Boot                 | 4.1.0  |
| ORM        | Spring Data JPA / Hibernate | 7.x    |
| Build      | Gradle (Kotlin DSL)         | 9.x    |
| DB (dev)   | Tmax OpenSQL                | v3.0   |
| DB (local) | PostgreSQL                  | 17.8   |
| Driver     | PostgreSQL JDBC             | 42.7.x |

---

## 문서

- [기여 안내](CONTRIBUTING.md) — 브랜치, 커밋 메시지, PR
- [개발 가이드](docs/DEVELOPMENT.md) — 프로파일별 개발 방식, 마이그레이션, 환경 변수
- [API 설계](docs/API-DESIGN.md)
- [코드 컨벤션](docs/CODE_CONVENTIONS.md)

---

## 라이선스

이 프로젝트는 MIT 라이선스를 따릅니다. 자세한 내용은 [LICENSE](LICENSE)를 참고하세요.

### 사용된 오픈소스

| 프로젝트 | 라이선스 |
|---|---|
| Spring Boot | Apache-2.0 |
| Kotlin | Apache-2.0 |
| PostgreSQL | PostgreSQL License |
| PostgreSQL JDBC Driver | BSD-2-Clause |
| Hibernate ORM | Apache-2.0 |
| pgvector | PostgreSQL License |
| Apache Kafka | Apache-2.0 |
| MinIO | AGPL-3.0 |

Tmax OpenSQL은 티맥스티베로의 상용 제품이며, 본 저장소에는 포함되어 있지 않습니다.
