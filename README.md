# Tmax OpenSQL 기반 AI 문서 관리 시스템

![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1.0-6DB33F?logo=springboot&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)
![Java](https://img.shields.io/badge/Java-21_LTS-437291?logo=openjdk&logoColor=white)
![Spring Data JPA](https://img.shields.io/badge/Spring_Data_JPA-Hibernate_7-59666C?logo=hibernate&logoColor=white)
![Tmax OpenSQL](https://img.shields.io/badge/Tmax_OpenSQL-v3.0-0B4DA2)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17.8-4169E1?logo=postgresql&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)

> 2026 오픈소스 개발자 대회 · 티맥스티베로 기업 과제

문서를 올려 두면 필요할 때 **의미로 찾아 주는** 문서 관리 시스템입니다. 파일명이나 정확한 단어를 기억하지 못해도, 묻고 싶은 내용을 그대로 적으면 관련된 대목을 찾아 줍니다.

---

## 개요

문서화는 팀의 컨텍스트를 쌓고 공유하는 중요한 협업 장치입니다. 그러나 문서가 쌓일수록 파일의 버전을 관리하고, AI가 읽을 수 있는 형태로 바꾸고, 잘못 처리된 작업을 복구하는 일이 오히려 팀 리소스를 갉아먹는 병목이 될 때가 많습니다.

이 시스템은 문서와 관련된 비효율적인 일을 사람이 하지 않도록 만듭니다. 파일을 올리면 그 뒤는 신경 쓰지 않아도 됩니다. 업로드가 인덱싱을 트리거하고, 실패한 작업은 스스로 재시도합니다.

PDF·DOCX·Markdown·HWP·TXT의 5가지 확장자를 허용하며, 같은 문서를 다시 올리면 새 버전으로 쌓입니다. 변경 사항을 되돌리고 싶다면 이전 버전을 찾아 검색 대상으로 쉽게 롤백할 수 있고, 같은 문서에 내용이 같은 파일을 새 버전으로 올리면 중복이라고 알려 줍니다. 문서마다 누가 볼 수 있는지를 사용자와 테넌트 단위로 정합니다.

데모 서비스는 [해당 링크](https://spr1n6-osscontest-web.vercel.app/)에서 사용해보실 수 있습니다.

---

## 핵심 기능

### 의미 기반 검색

올린 문서를 문단 단위로 나눠 1536차원 벡터로 색인하고, HNSW 인덱스로 가장 가까운 대목을 찾습니다. 검색어와 글자가 겹치지 않아도 의미가 유사하면 조회됩니다. 여기에 형태소 분석과 BM25 기반 키워드 검색을 결합해, **합성 QA 평가 풀 기준 Recall@10 을 0.31 에서 0.78 까지** 끌어올렸습니다.

찾은 대목의 앞뒤 문단을 함께 돌려주는 옵션이 있습니다. 문단 하나만 떼어 보면 맥락을 알 수 없는 경우가 많기 때문입니다.

자세한 설계와 평가 과정은 [검색 설계와 평가](docs/SEARCH.md)에 정리했습니다.

### 유실 없는 비동기 인덱싱

업로드 응답은 인덱싱을 기다리지 않습니다. 대신 문서를 저장하는 트랜잭션과 **같은 원자 단위로** 인덱싱 요청 이벤트를 남깁니다.
이 이벤트는 DB 트리거가 만듭니다. 애플리케이션이 발행을 빠뜨릴 수 없고, 저장이 롤백되면 이벤트도 함께 사라집니다.

남은 이벤트는 릴레이가 카프카로 옮기고 워커가 받아 처리합니다. **처리에 실패한 작업은 간격을 두고 스스로 재시도**합니다. 진행 상태를 UI에서 준실시간으로 확인할 수 있고, 자동 재시도로도 복구되지 않은 작업은 버튼으로 직접 재시도를 요청할 수 있습니다.

### 테넌트와 문서 단위 접근 제어

문서는 파일을 업로드한 사람의 소속 내에서만 접근 가능합니다. 새 문서를 올리면 올린 사람이 관리자가 되고, 같은 소속 구성원은 해당 문서를 읽을 수 있는 상태로 초기화됩니다. 별도 설정이 없어도 팀 내에서는 바로 공유되고, 밖으로는 새어나가지 않는 구조입니다.

손쉽게 권한을 넓히거나 좁힐 수 있습니다. 특정 구성원에게만 편집을 맡기거나, 소속 전체에 열어 둔 문서를 몇 사람만 보도록 되돌릴 수 있습니다. 한 사람에게 여러 경로로 권한이 주어지면 가장 넓은 쪽이 적용됩니다.

다른 소속의 문서는 목록에도 검색 결과에도 나오지 않으며, 링크를 직접 알아내 열어도 "권한 없음" 대신 "문서가 존재하지 않음"으로 응답하여 문서가 존재한다는 사실 자체가 새어 나가지 않게 보장합니다.

### 저장 데이터 암호화

사용자 이름과 원본 파일명을 DB에 암호화해 저장합니다. 키는 애플리케이션이 DB 커넥션마다 세션 설정으로 넘기므로 DB에는 남지 않습니다.
Tmax OpenCrypto 환경에서는 국산 암호화 알고리즘인 ARIA-256을, 해당 알고리즘이 없는 환경에서는 AES-256을 씁니다.

### MCP 서버

문서 검색과 목록 조회를 MCP 도구로 노출합니다. Claude 등 AI 클라이언트가 이 서버에 직접 붙어, 쌓아 둔 문서를 근거로 답할 수 있습니다.
REST API와 같은 권한 검사를 거쳐, 에이전트 역시도 사용자와 동등한 인가를 거치도록 제어합니다. 활용 방법은 [MCP 서버 붙이기](#mcp-서버-붙이기)에 있습니다.

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

## MCP 서버 붙이기

검색과 문서 조회를 MCP(Model Context Protocol) 도구로 노출합니다. Claude Code 같은 클라이언트가 표준 프로토콜로 바로 호출할 수 있습니다. Spring AI MCP(Streamable HTTP)로 구현했습니다.

### 제공 도구

| 도구 | 하는 일 |
|---|---|
| `search_documents` | 질의어로 하이브리드 검색(벡터 유사도 + 키워드 매칭)합니다. 매칭된 청크와 앞뒤 문맥, 소속 문서 정보를 함께 반환합니다 |
| `list_documents` | 테넌트에 속한 문서 목록을 커서 기반 페이지네이션으로 조회합니다 |
| `get_document` | `documentId`로 검색 없이 문서 상세를 바로 조회합니다 |

파라미터는 [API 명세 10장](docs/API_SPEC.md)에 있습니다.

### 사용법

MCP 클라이언트 설정에 엔드포인트와 인증 헤더를 등록합니다.

```json
{
  "mcpServers": {
    "search": {
      "type": "http",
      "url": "http://localhost:8080/mcp",
      "headers": { "X-Search-User-Id": "본인_app_user_id" }
    }
  }
}
```

- 엔드포인트는 `/mcp`이고 Streamable HTTP로 통신합니다.
- `X-Search-User-Id` 헤더로 테넌트를 식별하므로 반드시 넣어야 합니다. 값이 없거나 숫자가 아니면 인증 실패로 응답합니다. REST API가 쓰는 `X-User-Id`와는 다른 헤더입니다.

연결하면 LLM이 자연어 요청(예: "예산 관련 문서 찾아줘")을 알아서 도구 호출로 바꿔 씁니다.

---

## 저장소 구성

시스템을 구성하는 소스 코드 저장소는 총 4개입니다. 업로드한 문서는 아래의 과정을 통해 최종 검색 가능한 상태가 됩니다.

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

이 저장소는 `server`입니다.

---

## 기술 스택

| 구분         | 기술                          | 버전     |
|------------|-----------------------------|--------|
| Language   | Kotlin                      | 2.3.21 |
| Runtime    | Java (JVM)                  | 21 LTS |
| Framework  | Spring Boot                 | 4.1.0  |
| ORM        | Spring Data JPA / Hibernate | 4.1.0 / 7.4.1 |
| Build      | Gradle (Kotlin DSL)         | 9.5.1  |
| DB (dev)   | Tmax OpenSQL + pgvector     | v3.0 / 0.8.1 |
| DB (local) | PostgreSQL + pgvector       | 17.8 / 0.8.1 |
| Driver     | PostgreSQL JDBC             | 42.7.11 |

---

## 문서

- [기여 안내](CONTRIBUTING.md) — 브랜치, 커밋 메시지, PR
- [개발 가이드](docs/DEVELOPMENT.md) — 프로파일별 개발 방식, 마이그레이션, 환경 변수
- [검색 설계와 평가](docs/SEARCH.md) — 하이브리드 검색 설계, 평가 방법, 고도화 결과
- [코드 컨벤션](docs/CODE_CONVENTIONS.md)
- [API 명세](docs/API_SPEC.md)

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
