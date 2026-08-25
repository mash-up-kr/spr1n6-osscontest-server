# API 명세

| 항목        | 값                                        |
|-----------|------------------------------------------|
| 기준 마이그레이션 | `V20260823_002__add_worker_indexing_fields.sql` |
| 최종 수정     | 2026-08-26                               |

---

## 1. 전제

- 버전 카운터는 셋이다. `latest_upload_version_no`(업로드 성공),
  `latest_embedding_version_no`(임베딩 성공), `searchable_version_id`(검색 대상).
- 인덱싱은 비동기다. 업로드 응답은 인덱싱을 기다리지 않는다.
- Job 상태는 다섯이다. `PENDING` / `PROCESSING` / `RETRY_WAIT` / `COMPLETED` / `FAILED`.
- 삭제 요청은 `deleted_at`에 기록하고, 그 뒤의 물리 정리(청크와 원본 파일 삭제)는 Worker 가
  `DOCUMENT_DELETED` 이벤트를 받아 수행한 뒤 `purged_at`에 기록한다. 유예 기간은 없다.
- 시연은 데모 유저를 시드한 뒤 UI에서 선택하는 방식이며, 토큰 발급은 범위에서 제외한다.

---

## 2. 결정 사항

- **업로드는 `multipart/form-data`로 서버를 거친다.**
- **파일은 20MB 까지, PDF · DOCX · Markdown · HWP · TXT 만 받는다.** 크기를 넘으면 `413`,
  형식이 다르면 `415` 이다.
- **허용 형식은 확장자로 판정한다.** HWP 는 표준 MIME 이 없어 클라이언트가 보내는 값이
  일정하지 않다. `mime_type` 에는 확장자로 정한 값을 저장한다.
- **재인덱싱 Outbox 행은 API 서버가 직접 INSERT 한다.** 새 `source_event_id`로 발행하고
  `retry_of_event_id`에 원본 이벤트를 넣는다.
- **재인덱싱은 임베딩이 실패한 버전에만 허용한다.** 그 외에는 `409`이다.
- **같은 파일을 다시 올려도 버전을 만든다.** 응답의 `duplicateOfVersionNo`로 알린다.
- **다른 테넌트의 문서는 `404`이다.** 존재 자체를 노출하지 않는다.
- **인덱싱 진행 상태는 클라이언트가 폴링한다.** 진행 상태 조회 엔드포인트를 주기적으로
  호출한다.
- **검색 대상 버전은 임베딩이 끝날 때마다 최신으로 갱신된다.** 수동 지정은 다음 임베딩이
  완료될 때까지 유효하다.
- **검색 결과는 매칭된 청크의 앞뒤 문맥을 `contextBefore`/`contextAfter`로 함께 반환한다.**

---

## 3. 공통 규약

- 인증 컨텍스트는 `X-User-Id` 헤더로 받는다. 서버가 이 값으로 `app_user`를 조회해 테넌트를
  얻는다. 클라이언트는 테넌트를 보내지 않는다.
- 테넌트는 경로에 노출하지 않고 인증 컨텍스트에서 얻는다.
- 버전은 `versionNo`로 지정한다. `document_version.id`는 노출하지 않는다.
- 시각은 전부 UTC ISO 8601(`2026-08-15T04:12:09Z`)이다.

### 페이징

```http
GET /api/v1/documents?limit=20&cursor=eyJpZCI6NDJ9
```

```json
{
  "items": [],
  "nextCursor": "eyJpZCI6MjJ9"
}
```

`nextCursor`가 `null`이면 마지막 페이지다.

### 에러

```json
{
  "code": "DOCUMENT_NOT_FOUND",
  "message": "문서를 찾을 수 없습니다.",
  "traceId": "0af7651916cd43dd"
}
```

| 상태    | 쓰는 경우                               |
|-------|-------------------------------------|
| `202` | 업로드·재인덱싱처럼 비동기 처리를 시작한 경우           |
| `400` | 요청 형식·검증 실패                         |
| `401` | `X-User-Id`가 없거나 존재하지 않는 유저인 경우     |
| `403` | 같은 테넌트 안에서 권한이 없는 경우                |
| `404` | 없는 문서, 소프트 삭제된 문서, 다른 테넌트의 문서       |
| `409` | 임베딩이 실패하지 않은 버전에 재인덱싱을 요청하는 등 상태 충돌 |
| `413` | 파일 크기 초과                            |
| `415` | 지원하지 않는 형식                          |
| `500` | 서버 내부 오류                            |
| `502` | 임베딩 등 외부 연동 실패                      |

응답의 `code`는 다음 중 하나다. 클라이언트는 상태 코드가 아니라 이 값으로 분기한다.

| `code`                             | 상태    | 뜻                                 |
|------------------------------------|-------|-----------------------------------|
| `INVALID_REQUEST`                  | `400` | 요청 형식이 올바르지 않음                    |
| `EMPTY_FILE`                       | `400` | 빈 파일 업로드                          |
| `INVALID_QUERY`                    | `400` | 검색 질의문이 비어 있음                     |
| `PRINCIPAL_NOT_FOUND`              | `400` | 권한을 부여할 대상이 없음                    |
| `OWNER_PERMISSION_NOT_REVOCABLE`   | `400` | 소유자 권한은 회수할 수 없음                  |
| `UNAUTHENTICATED`                  | `401` | 인증 정보가 올바르지 않음                    |
| `FORBIDDEN`                        | `403` | 해당 문서에 대한 권한이 없음                  |
| `DOCUMENT_NOT_FOUND`               | `404` | 문서를 찾을 수 없음                       |
| `DOCUMENT_VERSION_NOT_FOUND`       | `404` | 문서 버전을 찾을 수 없음                    |
| `PERMISSION_NOT_FOUND`             | `404` | 문서에 부여된 권한을 찾을 수 없음               |
| `SEARCHABLE_VERSION_NOT_READY`     | `409` | 인덱싱이 끝난 버전만 검색 대상으로 지정 가능        |
| `INDEXING_RETRY_NOT_ALLOWED`       | `409` | 실패 상태인 작업만 재시도 가능                 |
| `INDEXING_RETRY_ALREADY_REQUESTED` | `409` | 이미 대기 중인 재인덱싱 요청이 있음              |
| `UNSUPPORTED_FILE_TYPE`            | `415` | 지원하지 않는 파일 형식                     |
| `INTERNAL_ERROR`                   | `500` | 서버 오류                             |
| `UPSTREAM_ERROR`                   | `502` | 검색어 처리 중 오류                       |

---

## 4. 엔드포인트 목록

| 메서드      | 경로                                                                         |
|----------|----------------------------------------------------------------------------|
| `POST`   | `/api/v1/documents`                                                        |
| `GET`    | `/api/v1/documents`                                                        |
| `GET`    | `/api/v1/documents/{documentId}`                                           |
| `PATCH`  | `/api/v1/documents/{documentId}`                                           |
| `DELETE` | `/api/v1/documents/{documentId}`                                           |
| `POST`   | `/api/v1/documents/{documentId}/versions`                                  |
| `GET`    | `/api/v1/documents/{documentId}/versions`                                  |
| `GET`    | `/api/v1/documents/{documentId}/versions/{versionNo}`                      |
| `GET`    | `/api/v1/documents/{documentId}/versions/{versionNo}/content`              |
| `PUT`    | `/api/v1/documents/{documentId}/searchable-version`                        |
| `GET`    | `/api/v1/documents/{documentId}/versions/{versionNo}/indexing`             |
| `POST`   | `/api/v1/documents/{documentId}/versions/{versionNo}/indexing/retry`       |
| `POST`   | `/api/v1/search`                                                           |
| `GET`    | `/api/v1/documents/{documentId}/permissions`                               |
| `PUT`    | `/api/v1/documents/{documentId}/permissions`                               |
| `DELETE` | `/api/v1/documents/{documentId}/permissions/{principalType}/{principalId}` |

MCP 도구는 10장에 있다.

---

## 5. 문서

### 문서 생성 + 1번 버전 업로드

```http
POST /api/v1/documents
Content-Type: multipart/form-data
```

| 파트      | 타입     | 필수 | 설명                 |
|---------|--------|----|--------------------|
| `file`  | file   | O  | 원본 파일              |
| `title` | string | X  | 없으면 파일명에서 확장자를 뗀 값 |

`file`은 20MB 이하이며 확장자가 다음 중 하나여야 한다. 저장되는 `mimeType` 은 확장자로 정한다.

| 확장자                | `mimeType`                                                                |
|--------------------|---------------------------------------------------------------------------|
| `.pdf`             | `application/pdf`                                                         |
| `.docx`            | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` |
| `.md`, `.markdown` | `text/markdown`                                                           |
| `.hwp`             | `application/x-hwp`                                                       |
| `.txt`             | `text/plain`                                                              |

```json
202 Accepted

{
  "documentId": 42,
  "versionNo": 1,
  "duplicateOfVersionNo": null,
  "indexing": {
    "status": "PENDING"
  }
}
```

`400` `413` `415`

### 문서 목록

```http
GET /api/v1/documents
```

| 파라미터             | 타입      | 기본 | 설명                |
|------------------|---------|----|-------------------|
| `limit`          | integer | 20 | 최대 100            |
| `cursor`         | string  |    | 다음 페이지 커서         |
| `q`              | string  |    | 제목 부분 일치          |
| `indexingStatus` | enum    |    | 최신 버전의 인덱싱 상태로 필터 |
| `searchable`     | boolean |    | 검색 가능 여부로 필터      |

```json
200 OK

{
  "items": [
    {
      "id": 42,
      "title": "2026 사업계획서",
      "latestUploadVersionNo": 3,
      "latestEmbeddingVersionNo": 2,
      "searchableVersionNo": 2,
      "latestVersionIndexingStatus": "PROCESSING",
      "createdAt": "2026-07-02T01:30:00Z"
    }
  ],
  "nextCursor": "eyJpZCI6MjJ9"
}
```

### 문서 상세

```http
GET /api/v1/documents/{documentId}
```

```json
200 OK

{
  "id": 42,
  "title": "2026 사업계획서",
  "latestUploadVersionNo": 3,
  "latestEmbeddingVersionNo": 2,
  "searchableVersionNo": 2,
  "latestVersionIndexingStatus": "PROCESSING",
  "createdAt": "2026-07-02T01:30:00Z"
}
```

`searchableVersionNo`가 `null`이면 아직 검색되지 않는 문서다.

`404`

### 문서 삭제

```http
DELETE /api/v1/documents/{documentId}
```

```
204 No Content
```

`deleted_at`만 기록한다. 이때 `DOCUMENT_DELETED` 이벤트가 발행되고, Worker 가 원본 파일과
청크를 지운 뒤 `purged_at`을 기록한다.

`404`

### 제목 변경

```http
PATCH /api/v1/documents/{documentId}
Content-Type: application/json

{ "title": "2026 사업계획서 (최종)" }
```

```json
200 OK

{
  "id": 42,
  "title": "2026 사업계획서 (최종)"
}
```

재인덱싱하지 않는다.

`400` `404`

---

## 6. 버전

### 새 버전 업로드

```http
POST /api/v1/documents/{documentId}/versions
Content-Type: multipart/form-data
```

| 파트     | 타입   | 필수 | 설명    |
|--------|------|----|-------|
| `file` | file | O  | 원본 파일 |

```json
202 Accepted

{
  "documentId": 42,
  "versionNo": 3,
  "duplicateOfVersionNo": 1,
  "indexing": {
    "status": "PENDING"
  }
}
```

`duplicateOfVersionNo`는 같은 `content_hash`인 이전 버전이 있을 때 채운다. 직전 버전만이 아니라
모든 버전과 비교한다.

`400` `404` `413` `415`

### 버전 목록

```http
GET /api/v1/documents/{documentId}/versions
```

```json
200 OK

{
  "items": [
    {
      "versionNo": 3,
      "originalFilename": "사업계획서_v3.pdf",
      "mimeType": "application/pdf",
      "fileSize": 2481920,
      "uploadedAt": "2026-08-15T04:12:09Z",
      "indexing": {
        "status": "PROCESSING",
        "attemptCount": 1
      },
      "searchable": false
    },
    {
      "versionNo": 2,
      "originalFilename": "사업계획서_v2.pdf",
      "mimeType": "application/pdf",
      "fileSize": 2390144,
      "uploadedAt": "2026-08-10T02:00:00Z",
      "indexing": {
        "status": "COMPLETED",
        "chunkCount": 178
      },
      "searchable": true
    }
  ],
  "nextCursor": null
}
```

각 항목의 `indexing`은 진행 상태 조회와 같은 기준이다.

`404`

### 버전 상세

```http
GET /api/v1/documents/{documentId}/versions/{versionNo}
```

버전 목록의 항목에 `sourceMetadata`와 `extractedMetadata`를 더한 형태다.

`404`

### 원본 다운로드

```http
GET /api/v1/documents/{documentId}/versions/{versionNo}/content
```

```
200 OK
Content-Type: application/pdf
Content-Disposition: attachment; filename*=UTF-8''%EC%82%AC%EC%97%85%EA%B3%84%ED%9A%8D%EC%84%9C_v3.pdf
```

`404`

### 검색 대상 버전 변경

```http
PUT /api/v1/documents/{documentId}/searchable-version
Content-Type: application/json

{ "versionNo": 2 }
```

```json
200 OK

{
  "searchableVersionNo": 2
}
```

임베딩이 완료된 버전만 지정할 수 있다. 다음 임베딩이 완료되면 최신 버전으로 다시 옮겨간다.

`400` `404` `409`

---

## 7. 인덱싱

### 진행 상태 조회

```http
GET /api/v1/documents/{documentId}/versions/{versionNo}/indexing
```

```json
200 OK

{
  "versionNo": 3,
  "status": "PROCESSING",
  "phase": "EMBEDDING",
  "attemptCount": 1,
  "chunkCount": null,
  "startedAt": "2026-08-15T04:12:11Z",
  "completedAt": null,
  "lastErrorMessage": null
}
```

해당 버전의 가장 최근 `outbox_event`에 대응하는 `indexing_job` 행이다. 워커가 아직 그 이벤트를
소비하지 않았으면 `status`는 `PENDING`이다.
`phase`는 워커가 기록한 현재 처리 단계이며, 잡 생성 전이거나 단계가 기록되지 않았으면 `null`이다.
`COMPLETED` 와 `FAILED` 가 종료 상태이며, 클라이언트는 여기서 폴링을 멈춘다.

`404`

### 재인덱싱 요청

```http
POST /api/v1/documents/{documentId}/versions/{versionNo}/indexing/retry
```

```json
202 Accepted

{
  "versionNo": 3,
  "indexing": {
    "status": "PENDING"
  }
}
```

임베딩이 실패한 버전에만 허용한다. 그 외에는 `409`이다.
아직 워커가 소비하지 않은 재인덱싱 이벤트가 남아 있는 경우에도 `409`이다.

`404` `409`

---

## 8. 검색

### 검색 실행

```http
POST /api/v1/search
Content-Type: application/json

{
  "query": "3개월 이내 해지 시 위약금",
  "topK": 10,
  "contextWindow": 1,
  "rerank": false
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `query` | string | O | 검색 질의문 |
| `topK` | integer | X | 기본 10, 최대 50 |
| `contextWindow` | integer | X | 매칭 청크 앞뒤로 함께 반환할 청크 수. 기본 0(미포함), 최대 5 |
| `rerank` | boolean | X | 결과를 재정렬 모델(Cohere Rerank)로 다듬을지 여부. 기본 false. 정확도↑ 지연시간↑ |

```json
200 OK

{
  "items": [
    {
      "chunkId": 981,
      "documentId": 42,
      "title": "2026 사업계획서",
      "content": "이 경우 위약금은 계약금의 10%를 초과할 수 없다.",
      "contextBefore": [
        "본 계약을 체결일로부터 3개월 이내에 해지하는 경우,"
      ],
      "contextAfter": [
        "다만 천재지변으로 인한 해지는 예외로 한다."
      ],
      "score": 0.0421,
      "pageFrom": 12,
      "pageTo": 12,
      "sectionPath": "제3장 > 해지 조항"
    }
  ]
}
```

`contextBefore`/`contextAfter`는 매칭된 청크를 기준으로 `chunk_no`가 앞·뒤로 이어지는 청크 원문
배열이다. 원문 순서대로 정렬되어 있어 `contextBefore` + `content` + `contextAfter` 순으로 이어
읽을 수 있다. 길이는 최대 `contextWindow`이며, 문서 경계에 걸리면 그보다 짧거나 빈 배열이다.

`score`는 벡터 유사도 순위와 키워드 매칭 순위를 합산한 값이다.

**`contextWindow`는 앞뒤 문맥 깊이를 파라미터로 받는다.**

`400` `401`

---

## 9. 권한

### 권한 목록

```http
GET /api/v1/documents/{documentId}/permissions
```

```json
200 OK

{
  "items": [
    {
      "principalType": "USER",
      "principalId": "17",
      "permission": "WRITE"
    },
    {
      "principalType": "TENANT",
      "principalId": "1",
      "permission": "READ"
    }
  ]
}
```

`403` `404`

### 권한 부여·변경

```http
PUT /api/v1/documents/{documentId}/permissions
Content-Type: application/json

{ "principalType": "USER", "principalId": "17", "permission": "WRITE" }
```

```json
200 OK

{
  "principalType": "USER",
  "principalId": "17",
  "permission": "WRITE"
}
```

이미 있으면 `permission`을 바꾼다.

`400` `403` `404`

### 권한 회수

```http
DELETE /api/v1/documents/{documentId}/permissions/{principalType}/{principalId}
```

```
204 No Content
```

`403` `404`

---

## 10. MCP 도구

AI 클라이언트가 붙어 쓰는 인터페이스다. 엔드포인트는 `/mcp`이고 Streamable HTTP 로 통신한다.

REST 와 같은 권한 검사를 거친다. 다만 신원은 `X-Search-User-Id` 헤더로 받는다. REST 의 `X-User-Id` 와는 다른 헤더이며, 값이 없거나 숫자가 아니면 인증 실패로 응답한다. 도구가 돌려주는 문서는 그 사용자가 REST 로 조회했을 때 볼 수 있는 것과 같다.

세 도구 모두 읽기 전용이다. 문서를 만들거나 고치거나 지우지 않는다.

### search_documents

질의어로 문서를 검색한다. 매칭된 청크와 앞뒤 문맥, 소속 문서 정보를 함께 돌려준다.

| 파라미터            | 타입     | 필수 | 설명                                       |
|-----------------|--------|:--:|------------------------------------------|
| `query`         | string | O  | 검색 질의문                                   |
| `topK`          | int    | X  | 반환할 최대 결과 수 (기본 10, 최대 50)               |
| `contextWindow` | int    | X  | 매칭 청크 앞뒤로 함께 반환할 청크 수 (기본 0, 최대 5)       |
| `efSearch`      | int    | X  | HNSW 탐색 폭 (기본 100, 1~500). 클수록 정확하고 느린다 |

응답은 `POST /api/v1/search` 의 결과 배열과 같은 형태다.

### list_documents

테넌트에 속한 문서 목록을 조회한다. 커서 기반 페이지네이션을 지원한다.

| 파라미터             | 타입      | 필수 | 설명                                                    |
|------------------|---------|:--:|-------------------------------------------------------|
| `limit`          | int     | X  | 반환할 최대 문서 수 (기본 20, 최대 100)                           |
| `cursor`         | string  | X  | 이전 응답의 `nextCursor`                                   |
| `q`              | string  | X  | 제목 검색어                                                |
| `indexingStatus` | string  | X  | 최신 버전의 인덱싱 상태로 필터 (`PENDING`/`PROCESSING`/`RETRY_WAIT`/`COMPLETED`/`FAILED`) |
| `searchable`     | boolean | X  | 검색 대상 버전이 지정된 문서만 거를지 여부                              |

응답은 `GET /api/v1/documents` 와 같은 형태다.

### get_document

`documentId` 로 문서 상세를 직접 조회한다. 검색을 거치지 않는다.

| 파라미터         | 타입   | 필수 | 설명     |
|--------------|------|:--:|--------|
| `documentId` | long | O  | 문서 식별자 |

응답은 `GET /api/v1/documents/{documentId}` 와 같은 형태다.
