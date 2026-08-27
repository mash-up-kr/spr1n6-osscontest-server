[한국어](CONTRIBUTING.md) | **English**

# Contributing guide

This describes the procedure to follow when working in this repository. The standards for writing code are in [docs/CODE_CONVENTIONS.en.md](docs/CODE_CONVENTIONS.en.md), and how to set up your development environment is in [docs/DEVELOPMENT.en.md](docs/DEVELOPMENT.en.md).

---

## Branches

| Branch | Purpose |
|--------------|-------------|
| `main` | Integration branch |
| `feat/*` | Feature development |
| `fix/*` | Bug fixes |
| `refactor/*` | Refactoring |
| `docs/*` | Documentation work |
| `chore/*` | Config, build, dependencies |

Branch off of `main` for your work, and merge back into `main` via a PR when done.

## Commit messages

The subject line follows the `<type>: <subject>` format. The body is free-form.

```
feat: 문서 업로드 API 구현
fix: 대용량 PDF 파싱 시 OOM 수정
docs: README 프로파일 구성 추가
refactor: DocumentService 책임 분리
test: 문서 검색 통합 테스트 추가
chore: Spring Boot 4.1.0 업그레이드
```

Don't mix cleanup commits (comments, naming, dead code) with commits that change behavior. Mixing them makes it impossible to tell, during review, whether something was moved or actually changed.

## Pull Request

Follow `.github/pull_request_template.md`. In the body, describe what the problem was, how to reproduce it, what was fixed, and how it was verified.

Split PRs by root cause. Only bundle changes together when they can't stand on their own without each other.

Record any deliberation over design or alternatives considered in the PR body, not in code comments. Comments should stay focused on what the code does.

## Migrations

Changes to the shared DB's schema affect the whole team. **Never modify a migration file once it has been applied.** Doing so leaves every other team member unable to start the app, due to a checksum mismatch.

Follow [Applying migrations in the development guide](docs/DEVELOPMENT.en.md#applying-migrations) for the application procedure.
