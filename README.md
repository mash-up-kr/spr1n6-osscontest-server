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

Docker로 PostgreSQL 17.8과 MinIO 컨테이너를 띄우고 애플리케이션이 여기에 접속합니다. 과제 환경과 동일한 버전으로 고정되어 있습니다.

```bash
docker compose up -d
./gradlew bootRun --args='--spring.profiles.active=local'
```

원본 파일은 MinIO에 저장합니다. 컨테이너가 뜰 때 `aidocs-documents` 버킷이 함께 만들어지므로 별도 준비가 필요 없고, `http://localhost:9001` 콘솔에서 확인할 수 있습니다.

DB와 MinIO에 직접 붙어볼 때 쓰는 계정과 포트는 `docker-compose.yml`에 있습니다.

### dev

리모트 Tmax OpenSQL에 접속합니다. DB 포트는 외부에 열려 있지 않아 SSH 터널을 거칩니다. 접속 정보는 `.env`로 관리합니다.

`tunnel.sh start`는 DB를 띄우는 것이 아니라 내 컴퓨터의 `15432` 포트를 리모트 DB까지 이어주는 통로를 여는 명령입니다. 앱은 이 포트를 로컬 DB처럼 보고 접속하므로, 앱을 실행하기 전에 터널이 먼저 열려 있어야 합니다.

```bash
cp .env.example .env
# .env를 열어 값을 채웁니다. 값과 pem 파일은 팀 내부에서 공유받으세요.

./scripts/tunnel.sh start   # 터널을 열어둔다. 백그라운드로 유지된다
./gradlew bootRun --args='--spring.profiles.active=dev'
```

dev 프로파일은 Flyway 자동 실행이 꺼져 있습니다. 앱을 띄워도 리모트 DB의 스키마는 바뀌지 않습니다.

리모트 마이그레이션이 필요한 경우에는 아래 명령으로 적용합니다.

```bash
./gradlew bootRun --args='--spring.profiles.active=dev --spring.flyway.enabled=true'
```

적용할 때는 반드시 팀에 공유합니다. 기본적으로 [인프라 담당자](https://github.com/mingdodev)가 수행하고, 다른 팀원이 수행해야 한다면 미리 팀에 알린 뒤 진행합니다. 한 번 적용된 마이그레이션 파일을 나중에 수정하면 나머지 팀원 전원이 체크섬 불일치로 앱을 띄울 수 없게 됩니다.
터널은 백그라운드에서 계속 떠 있어서 터미널을 닫아도 유지됩니다. 한 번 열어두면 앱을 껐다 켜도 다시 열 필요가 없고, 컴퓨터를 재부팅하면 사라지므로 다시 `start` 합니다. 이미 열려 있을 때 `start`를 또 해도 중복으로 열리지 않습니다.

터널 상태는 `status`로 확인하고, 작업이 끝나면 `stop`으로 닫습니다.

```bash
./scripts/tunnel.sh status
./scripts/tunnel.sh stop
```

앱이 `Connection refused`로 뜬다면 대개 터널이 닫힌 경우이니 `status`부터 확인합니다.

pem 파일은 프로젝트 폴더 밖에 두고 `chmod 600`으로 권한을 맞춥니다. `.env`에는 키 값이 아니라 파일 경로를 적고, 실행 위치와 무관하도록 절대경로나 `~/`로 시작하는 경로를 씁니다.

로컬 포트 기본값은 `15432`입니다. `local` 프로파일의 Docker PostgreSQL이 5432를 쓰기 때문에, 터널까지 5432로 열면 컨테이너가 떠 있을 때 터널이 열리지 않거나 의도한 것과 다른 DB에 붙게 됩니다.

dev 환경의 컨테이너는 별도 파일로 띄웁니다.

```bash
docker compose -f docker-compose.dev.yml up -d
```

| 파일 | 용도 | 담긴 것 |
|---|---|---|
| `docker-compose.yml` | 로컬 개발 | PostgreSQL, MinIO |
| `docker-compose.dev.yml` | dev 환경 | MinIO |

dev DB는 리모트 Tmax OpenSQL이라 compose에 없습니다. 접속 정보는 앱과 같은 `.env`에서 읽고, 값이 없으면 컨테이너가 뜨지 않습니다. 앱과 릴레이, 카프카, 워커는 각 이미지가 준비되면 이 파일에 추가합니다.

---

## 프로파일 구성

`local`과 `dev`는 DB 위치와 마이그레이션 실행 방식만 다릅니다. 애플리케이션 코드는 동일합니다.

| 항목 | `local` | `dev` |
|---|---|---|
| DB | Docker PostgreSQL 17.8 | 리모트 Tmax OpenSQL v3.0 |
| 접속 경로 | 직접 | SSH 터널 |
| 접속 정보 | `application-local.yml` | `.env` |
| SQL 로깅 | ON | OFF |
| Flyway | 기동 시 자동 실행 | 수동 실행 |

---

## 환경 변수

`dev` 프로파일에서 필요한 값입니다. `.env.example`를 복사해 사용합니다.

| 변수명 | 필수 | 설명 |
|---|:---:|---|
| `DB_HOST` | O | DB 호스트. 터널을 거치므로 `127.0.0.1` |
| `DB_PORT` | X | 포트 (기본 5432). `TUNNEL_LOCAL_PORT`와 같은 값 |
| `DB_NAME` | O | 데이터베이스명 |
| `DB_USERNAME` | O | 접속 계정 |
| `DB_PASSWORD` | O | 접속 비밀번호 |
| `SSH_HOST` | O | 터널을 붙일 서버 주소 |
| `SSH_PORT` | X | SSH 포트 |
| `SSH_USER` | O | SSH 계정 |
| `SSH_KEY_PATH` | O | pem 파일 경로 (절대경로 또는 `~/`) |
| `TUNNEL_LOCAL_PORT` | X | 로컬에서 열 포트 (기본 15432) |
| `TUNNEL_REMOTE_HOST` | X | 서버에서 본 DB 주소 (기본 127.0.0.1) |
| `TUNNEL_REMOTE_PORT` | X | 서버에서 본 DB 포트 (기본 5432) |
| `STORAGE_ENDPOINT` | O | 오브젝트 스토리지 주소 |
| `STORAGE_REGION` | O | 리전. MinIO는 무시하지만 SDK가 서명에 쓰므로 값이 있어야 한다 |
| `STORAGE_ACCESS_KEY` | O | 액세스 키 |
| `STORAGE_SECRET_KEY` | O | 시크릿 키 |
| `STORAGE_BUCKET` | O | 원본 파일을 담을 버킷 |

`.env`와 실제 접속 정보, pem 파일은 커밋하지 않습니다.

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