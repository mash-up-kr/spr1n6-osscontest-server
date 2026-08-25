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

## 문서

- [개발 가이드](docs/DEVELOPMENT.md) — 프로파일별 개발 방식, 마이그레이션, 협업 규칙
- [API 설계](docs/API-DESIGN.md)
- [코드 컨벤션](docs/CODE_CONVENTIONS.md)

---

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
