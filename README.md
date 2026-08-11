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

| 구분 | 기술 | 버전 |
|---|---|---|
| Language | Kotlin | 2.3 |
| Runtime | Java (JVM) | 21 LTS |
| Framework | Spring Boot | 4.1.0 |
| ORM | Spring Data JPA / Hibernate | 7.x |
| Build | Gradle (Kotlin DSL) | 9.x |
| DB (dev) | Tmax OpenSQL | v3.0 |
| DB (local) | PostgreSQL | 17.8 |
| Driver | PostgreSQL JDBC | 42.7.x |

---

## 시작하기

### 요구 사항

- JDK 21 이상
- Docker & Docker Compose

### local

Docker로 PostgreSQL 17.8 컨테이너를 띄우고 애플리케이션이 여기에 접속합니다. 과제 환경과 동일한 버전으로 고정되어 있습니다.

```bash
docker compose up -d
./gradlew bootRun --args='--spring.profiles.active=local'
```

### dev

리모트 Tmax OpenSQL에 접속합니다. 접속 정보는 `.env`로 관리합니다.
 
```bash
cp .env.example .env
# .env를 열어 값을 채웁니다. 값은 팀 내부에서 공유받으세요.
 
./gradlew bootRun --args='--spring.profiles.active=dev'
```

---

## 프로파일 구성

`local`과 `dev`는 DB 위치만 다릅니다. 애플리케이션 코드는 동일합니다.

| 항목 | `local` | `dev` |
|---|---|---|
| DB | Docker PostgreSQL 17.8 | 리모트 Tmax OpenSQL v3.0 |
| 접속 정보 | `application-local.yml` | `.env` |
| SQL 로깅 | ON | OFF |

---

## 환경 변수

`dev` 프로파일에서 필요한 값입니다. `.env.example`를 복사해 사용합니다.

| 변수명 | 필수 | 설명 |
|---|:---:|---|
| `DB_HOST` | O | OpenSQL 호스트 |
| `DB_PORT` | X | 포트 (기본 5432) |
| `DB_NAME` | O | 데이터베이스명 |
| `DB_USERNAME` | O | 접속 계정 |
| `DB_PASSWORD` | O | 접속 비밀번호 |

`.env`와 실제 접속 정보는 커밋하지 않습니다.

---

## 협업 규칙

### 브랜치

| 브랜치 | 용도 |
|---|---|
| `main` | 통합 브랜치 |
| `feat/*` | 기능 개발 |
| `fix/*` | 버그 수정 |
| `refactor/*` | 리팩터링 |
| `docs/*` | 문서 작업 |
| `chore/*` | 설정, 빌드, 의존성 |

작업 브랜치는 `main`에서 분기하고, 완료 후 PR로 `main`에 머지합니다.

### 커밋 메시지

제목은 `<type>: <subject>` 형식을 따릅니다. 바디는 자유롭게 작성합니다.

```
feat: 문서 업로드 API 구현
fix: 대용량 PDF 파싱 시 OOM 수정
docs: README 프로파일 구성 추가
refactor: DocumentService 책임 분리
test: 문서 검색 통합 테스트 추가
chore: Spring Boot 4.1.0 업그레이드
```

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