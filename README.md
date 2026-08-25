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

# 검색

## 1. 설계

벡터 검색(pgvector + HNSW)과 키워드 검색(Postgres 전문 검색)을 결합한 하이브리드 검색으로 설계했다.

## 2. 평가 풀 추출

라벨링 없이 청크 하나당 질문 하나를 LLM으로 자동 생성하는 합성(synthetic) QA 방식을 썼다. 단순 프롬프트의 함정(질문이 원문 단어를 그대로 재사용해 키워드 검색만으로 항상 맞아버리는 문제, 저품질 질문)을 막기 위해 논문 근거로 3단계 파이프라인(요약 → 원문 고유명사 비재사용 질문 생성 → 답변 가능성 검증)을 구성했다. 최종 52개 질의-정답 쌍을 확보했다.

논문 출처 : Bai, F., Harrigian, K., Stremmel, J., Hassanzadeh, H., Saeedi, A., & Dredze, M. (2024). *Give me Some Hard Questions: Synthetic Data Generation for Clinical QA*. Machine Learning for Health (ML4H) Findings.  

## 3. 평가

- **ANN 정확도**: HNSW 근사 검색이 exact search와 얼마나 겹치는지(Recall@K) 측정
- **검색 정합성**: 평가 풀로 Recall@10 / MRR / 지연시간 측정
- **인덱스 사용 검증**: `EXPLAIN (ANALYZE)`로 HNSW/GIN 인덱스가 실제로 타는지 확인

측정 과정에서 자연어 질문에 대해 키워드 검색이 종종 0건 매칭되는 결함을 실제 사례로 발견했다. 원인은 기존 방식(`plainto_tsquery` + `ts_rank_cd`)이 한국어 형태소 분석과 IDF(단어 희귀도) 둘 다 지원하지 않는다는 것으로 진단했다.

## 4. 평가 결과 바탕 고도화

### 검색 정확도 고도화 (BM25, Reranking)

- **형태소 분석 + BM25**: openSql이 pg_search를 지원하지 않으므로, 한국어 형태소 분석기(Nori)로 토큰화하고, IDF를 포함한 BM25 랭킹을 애플리케이션 레이어에서 직접 계산해 벡터 검색과 RRF로 결합하도록 재구성했다.
- **Reranking**: RRF 상위 후보를 LLM으로 재정렬하는 기능도 구현해 검증했으나, 정답률이 오히려 떨어지고 지연시간이 늘어 현재는 비활성화 상태로 남겨두고 고도화를 진행할 예정이다.

### 성능 고도화 (캐싱, 인덱스)

- **질의 임베딩 캐싱**: 동일 질의 반복 시 임베딩 API 호출을 생략한다 — 반복 질의 기준 약 3.9초 → 0.4~0.5초로 약 8배 단축을 확인했다.
- **HNSW 인덱스**: 실제 코퍼스(청크 28,926건) 기준 ANN(인덱스 사용, 1-30ms)과 Exact(인덱스 미사용 전수비교, 290-730ms)를 비교해 약 25~40배 빠름을 확인했다.
- **GIN 인덱스**: 키워드 후보 회수 쿼리가 `EXPLAIN`상 실제로 인덱스를 타는 것을 확인했다(매칭 시 6 ms 수준).

## 5. 결과

같은 평가 풀 기준으로 검색 정합성이 크게 개선됐다.

|  | 기존(ts_rank_cd) | 고도화 이후 |
| --- | --- | --- |
| Recall@10 | 0.31 | 0.67~0.78 |
| MRR | 0.191 | 0.39~0.42 |

## 6. MCP

검색·문서 조회 기능을 MCP(Model Context Protocol) 서버로도 노출해, LLM 클라이언트가 표준 프로토콜로 바로 호출할 수 있게 했다. Spring AI MCP(Streamable HTTP)로 구현했다.

### 제공 tool

- **search_documents**: 질의어로 문서를 하이브리드 검색(벡터 유사도 + 키워드 매칭 결합)한다. 매칭된 청크와 앞뒤 문맥, 소속 문서 정보를 함께 반환한다.
- **list_documents**: 현재 tenant에 속한 문서 목록을 커서 기반 페이지네이션으로 조회한다.
- **get_document**: `documentId`로 검색 없이 문서 상세 정보를 바로 조회한다.

### 사용법

MCP 클라이언트(Claude Code 등) 설정에 서버 엔드포인트와 인증 헤더를 등록한다.

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

- 엔드포인트: `/mcp` (Streamable HTTP)
- 헤더: `X-Search-User-Id: <사용자 ID>` — 이 값으로 tenant를 식별하므로 필수. 없거나 숫자가 아니면 인증 실패로 응답한다.

연결 후에는 LLM이 자연어 요청(예: "예산 관련 문서 찾아줘")을 위 tool 호출로 알아서 변환해 사용한다.

<!--
## 라이선스

TODO: 라이선스 확정 후 주석 해제

이 프로젝트는 (라이선스명)을 따릅니다. 자세한 내용은 [LICENSE](LICENSE)를 참고하세요.

### 사용된 오픈소스

| 프로젝트 | 라이선스 |
|---|---|
| Spring Boot | Apache-2.0 |
| Kotlin | Apache-2.0 |
| PostgreSQL | PostgreSQL License |
| PostgreSQL JDBC Driver | BSD-2-Clause |
| Hibernate ORM | Apache-2.0 |

Tmax OpenSQL은 티맥스티베로의 상용 제품이며, 본 저장소에는 포함되어 있지 않습니다.
-->


