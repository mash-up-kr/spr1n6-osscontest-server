**한국어** | [English](CONTRIBUTING.en.md)

# 기여 가이드

이 저장소에서 작업할 때 지키는 절차입니다. 코드를 쓰는 기준은 [docs/CODE_CONVENTIONS.md](docs/CODE_CONVENTIONS.md)에, 개발 환경을 세팅하는 방법은 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)에 따로 있습니다.

---

## 기여 흐름

1. [이슈](https://github.com/mash-up-kr/spr1n6-osscontest-server/issues/new/choose)를 먼저 올립니다. 버그는 Bug report, 기능 제안은 Feature request 양식을 씁니다.
2. 작업하려는 이슈에 댓글로 알린 뒤 `main`에서 브랜치를 만듭니다. 브랜치 이름에 이슈 번호를 넣습니다. (`fix/123-upload-timeout`)
3. PR 본문의 Related Issue에 `Closes #123`을 적습니다. 머지되면 이슈가 함께 닫힙니다.

오타나 문서 수정처럼 작은 변경은 이슈 없이 바로 PR을 올려도 됩니다.

보안 취약점은 이슈로 올리지 않고 [보안 정책](SECURITY.md)에 따라 비공개로 신고합니다.

## 브랜치

| 브랜치          | 용도          |
|--------------|-------------|
| `main`       | 통합 브랜치      |
| `feat/*`     | 기능 개발       |
| `fix/*`      | 버그 수정       |
| `refactor/*` | 리팩터링        |
| `docs/*`     | 문서 작업       |
| `chore/*`    | 설정, 빌드, 의존성 |

작업 브랜치는 `main`에서 분기하고, 완료 후 PR로 `main`에 머지합니다.

## 커밋 메시지

제목은 `<type>: <subject>` 형식을 따릅니다. 바디는 자유롭게 작성합니다.

```
feat: 문서 업로드 API 구현
fix: 대용량 PDF 파싱 시 OOM 수정
docs: README 프로파일 구성 추가
refactor: DocumentService 책임 분리
test: 문서 검색 통합 테스트 추가
chore: Spring Boot 4.1.0 업그레이드
```

정리(주석 · 이름 · 죽은 코드) 커밋과 동작을 바꾸는 커밋을 섞지 않습니다. 섞이면 리뷰에서 이동인지 변경인지 가릴 수 없습니다.

## Pull Request

`.github/pull_request_template.md`를 따릅니다. 본문에는 무엇이 문제였는지, 어떻게 재현되는지, 무엇을 고쳤는지, 어떻게 검증했는지를 적습니다.

원인이 다른 수정은 PR을 나눕니다. 서로 없으면 안 되는 변경만 하나로 묶습니다.

설계를 두고 고민한 내용이나 대안을 검토한 과정은 코드 주석이 아니라 PR 본문에 남깁니다. 주석은 코드가 무엇을 하는지에 집중합니다.

## 마이그레이션

DB 클러스터의 스키마를 바꾸는 변경은 팀 전체에 영향을 줍니다. **한 번 적용된 마이그레이션 파일은 수정하지 않습니다.** 수정하면 나머지 팀원 전원이 체크섬 불일치로 앱을 띄울 수 없게 됩니다.

적용 절차는 [개발 가이드의 마이그레이션 적용](docs/DEVELOPMENT.md#마이그레이션-적용)을 따릅니다.
