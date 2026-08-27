[한국어](API_SPEC.md) | **English**

# API Specification

| Item                | Value                                     |
|---------------------|--------------------------------------------|
| Baseline migration  | `V20260823_002__add_worker_indexing_fields.sql` |
| Last updated        | 2026-08-26                               |

---

## 1. Assumptions

- There are three version counters: `latest_upload_version_no` (upload succeeded),
  `latest_embedding_version_no` (embedding succeeded), and `searchable_version_id` (search target).
- Indexing is asynchronous. The upload response does not wait for indexing.
- There are five job statuses: `PENDING` / `PROCESSING` / `RETRY_WAIT` / `COMPLETED` / `FAILED`.
- A delete request is recorded in `deleted_at`; the subsequent physical cleanup (deleting the chunks
  and the original file) is performed by the Worker after it receives the `DOCUMENT_DELETED` event,
  and is recorded in `purged_at`. There is no grace period.
- For demos, a demo user is seeded and then selected in the UI; token issuance is out of scope.

---

## 2. Decisions

- **Uploads go through the server as `multipart/form-data`.**
- **Files up to 20MB are accepted, and only PDF, DOCX, Markdown, HWP, and TXT formats.** Exceeding
  the size limit returns `413`; an unsupported format returns `415`.
- **The allowed format is determined by the file extension.** HWP has no standard MIME type, so the
  value clients send is inconsistent. The value stored in `mime_type` is the one determined from the
  extension.
- **The reindexing Outbox row is INSERTed directly by the API server.** It is published with a new
  `source_event_id`, and the original event is placed in `retry_of_event_id`.
- **Reindexing is allowed only for a version whose embedding failed.** Otherwise it returns `409`.
- **Re-uploading the same file still creates a new version.** This is signaled via
  `duplicateOfVersionNo` in the response.
- **A document belonging to another tenant returns `404`.** Its existence is not exposed at all.
- **The client polls for indexing progress.** It periodically calls the progress lookup endpoint.
- **The searchable version is updated to the latest whenever embedding finishes.** A manual override
  remains valid only until the next embedding completes.
- **Search results return the surrounding context of the matched chunk together, as
  `contextBefore`/`contextAfter`.**

---

## 3. Common conventions

- The authentication context is received via the `X-User-Id` header. The server looks up `app_user`
  with this value to obtain the tenant. The client never sends the tenant itself.
- The tenant is never exposed in the path; it is obtained from the authentication context.
- A version is specified by `versionNo`. `document_version.id` is never exposed.
- All timestamps are UTC ISO 8601 (`2026-08-15T04:12:09Z`).

### Pagination

```http
GET /api/v1/documents?limit=20&cursor=eyJpZCI6NDJ9
```

```json
{
  "items": [],
  "nextCursor": "eyJpZCI6MjJ9"
}
```

If `nextCursor` is `null`, it is the last page.

### Errors

```json
{
  "code": "DOCUMENT_NOT_FOUND",
  "message": "문서를 찾을 수 없습니다.",
  "traceId": "0af7651916cd43dd"
}
```

| Status | When used                                                    |
|--------|---------------------------------------------------------------|
| `202`  | When starting asynchronous processing, such as an upload or reindexing |
| `400`  | Request format or validation failure                          |
| `401`  | `X-User-Id` is missing, or refers to a nonexistent user        |
| `403`  | No permission within the same tenant                           |
| `404`  | A nonexistent document, a soft-deleted document, or a document belonging to another tenant |
| `409`  | A state conflict, such as requesting reindexing for a version whose embedding did not fail |
| `413`  | File size exceeded                                             |
| `415`  | Unsupported format                                              |
| `500`  | Internal server error                                           |
| `502`  | An external integration failure, such as embedding              |

The response `code` is always one of the following. The client should branch on this value, not the
status code.

| `code`                             | Status | Meaning                                              |
|------------------------------------|--------|-------------------------------------------------------|
| `INVALID_REQUEST`                  | `400` | The request format is invalid                          |
| `EMPTY_FILE`                       | `400` | An empty file was uploaded                              |
| `INVALID_QUERY`                    | `400` | The search query text is empty                          |
| `PRINCIPAL_NOT_FOUND`              | `400` | There is no principal to grant the permission to        |
| `OWNER_PERMISSION_NOT_REVOCABLE`   | `400` | Owner permission cannot be revoked                       |
| `UNAUTHENTICATED`                  | `401` | The authentication information is invalid                |
| `FORBIDDEN`                        | `403` | No permission for the given document                     |
| `DOCUMENT_NOT_FOUND`               | `404` | The document could not be found                          |
| `DOCUMENT_VERSION_NOT_FOUND`       | `404` | The document version could not be found                  |
| `PERMISSION_NOT_FOUND`             | `404` | No permission granted on the document could be found      |
| `SEARCHABLE_VERSION_NOT_READY`     | `409` | Only a version whose indexing has finished can be set as the searchable version |
| `INDEXING_RETRY_NOT_ALLOWED`       | `409` | Only a job in the failed state can be retried             |
| `INDEXING_RETRY_ALREADY_REQUESTED` | `409` | A reindexing request is already pending                   |
| `UNSUPPORTED_FILE_TYPE`            | `415` | Unsupported file format                                    |
| `INTERNAL_ERROR`                   | `500` | A server error occurred                                    |
| `UPSTREAM_ERROR`                   | `502` | An error occurred while processing the search query        |

---

## 4. Endpoint list

| Method   | Path                                                                        |
|----------|------------------------------------------------------------------------------|
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

MCP tools are covered in section 10.

---

## 5. Documents

### Create a document + upload version 1

```http
POST /api/v1/documents
Content-Type: multipart/form-data
```

| Part    | Type   | Required | Description                                |
|---------|--------|:--------:|----------------------------------------------|
| `file`  | file   | O        | The original file                            |
| `title` | string | X        | If omitted, the filename with its extension stripped |

`file` must be 20MB or smaller, and its extension must be one of the following. The stored
`mimeType` is determined by the extension.

| Extension          | `mimeType`                                                                |
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

### Document list

```http
GET /api/v1/documents
```

| Parameter        | Type    | Default | Description                              |
|------------------|---------|---------|--------------------------------------------|
| `limit`          | integer | 20      | Max 100                                    |
| `cursor`         | string  |         | Cursor for the next page                   |
| `q`              | string  |         | Partial match on title                     |
| `indexingStatus` | enum    |         | Filter by the indexing status of the latest version |
| `searchable`     | boolean |         | Filter by whether the document is searchable |

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

### Document detail

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

If `searchableVersionNo` is `null`, the document is not yet searchable.

`404`

### Delete a document

```http
DELETE /api/v1/documents/{documentId}
```

```
204 No Content
```

Only `deleted_at` is recorded. This publishes a `DOCUMENT_DELETED` event, and the Worker deletes the
original file and its chunks and then records `purged_at`.

`404`

### Rename

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

Does not trigger reindexing.

`400` `404`

---

## 6. Versions

### Upload a new version

```http
POST /api/v1/documents/{documentId}/versions
Content-Type: multipart/form-data
```

| Part   | Type | Required | Description       |
|--------|------|:--------:|----------------------|
| `file` | file | O        | The original file    |

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

`duplicateOfVersionNo` is populated when a previous version has the same `content_hash`. The
comparison is made against all versions, not just the immediately preceding one.

`400` `404` `413` `415`

### Version list

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

The `indexing` field of each item follows the same convention as the progress lookup.

`404`

### Version detail

```http
GET /api/v1/documents/{documentId}/versions/{versionNo}
```

This is the version-list item shape with `sourceMetadata` and `extractedMetadata` added.

`404`

### Download the original file

```http
GET /api/v1/documents/{documentId}/versions/{versionNo}/content
```

```
200 OK
Content-Type: application/pdf
Content-Disposition: attachment; filename*=UTF-8''%EC%82%AC%EC%97%85%EA%B3%84%ED%9A%8D%EC%84%9C_v3.pdf
```

`404`

### Change the searchable version

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

Only a version whose embedding has completed can be specified. Once the next embedding completes,
it moves back to the latest version.

`400` `404` `409`

---

## 7. Indexing

### Progress lookup

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

This is the `indexing_job` row corresponding to the version's most recent `outbox_event`. If the
worker has not yet consumed that event, `status` is `PENDING`.
`phase` is the current processing stage recorded by the worker, and is `null` if the job hasn't been
created yet or the stage hasn't been recorded.
`COMPLETED` and `FAILED` are terminal states, and the client stops polling once it reaches one of
them.

`404`

### Request reindexing

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

Allowed only for a version whose embedding failed. Otherwise it returns `409`.
It also returns `409` if a reindexing event that the worker has not yet consumed is still pending.

`404` `409`

---

## 8. Search

### Run a search

```http
POST /api/v1/search
Content-Type: application/json

{
  "query": "3개월 이내 해지 시 위약금",
  "topK": 10,
  "contextWindow": 1,
  "rerank": true
}
```

| Field | Type | Required | Description |
|---|---|:---:|---|
| `query` | string | O | The search query text |
| `topK` | integer | X | Default 10, max 50 |
| `contextWindow` | integer | X | Number of chunks to return before/after the matched chunk. Default 0 (not included), max 5 |
| `rerank` | boolean | X | Whether to refine results with a reranking model (Cohere Rerank). Default true. Raises accuracy, raises latency. If `COHERE_API_KEY` is not set, results fall back to RRF order regardless of this value |

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

`contextBefore`/`contextAfter` are arrays of the original chunk text whose `chunk_no` immediately
precedes/follows the matched chunk. They are ordered as in the source document, so they can be read
continuously in the order `contextBefore` + `content` + `contextAfter`. Their length is at most
`contextWindow`; if it runs into a document boundary, the array is shorter, or empty.

`score` is the combined value of the vector-similarity rank and the keyword-match rank.

**`contextWindow` takes the depth of surrounding context as a parameter.**

`400` `401`

---

## 9. Permissions

### Permission list

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

### Grant or change a permission

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

If one already exists, `permission` is updated.

`400` `403` `404`

### Revoke a permission

```http
DELETE /api/v1/documents/{documentId}/permissions/{principalType}/{principalId}
```

```
204 No Content
```

`403` `404`

---

## 10. MCP Tools

This is the interface AI clients connect to and use. The endpoint is `/mcp`, and it communicates
over Streamable HTTP.

It goes through the same permission checks as REST. However, identity is received via the
`X-Search-User-Id` header. This is a different header from REST's `X-User-Id`, and if the value is
missing or not numeric, the response is an authentication failure. The documents a tool returns are
the same ones that user would see when querying via REST.

All three tools are read-only. They do not create, modify, or delete documents.

### search_documents

Searches documents by query text. Returns the matched chunks together with their surrounding
context and the owning document's information.

| Parameter       | Type   | Required | Description                                     |
|-----------------|--------|:--------:|----------------------------------------------------|
| `query`         | string | O        | The search query text                              |
| `topK`          | int    | X        | Maximum number of results to return (default 10, max 50) |
| `contextWindow` | int    | X        | Number of chunks to return before/after the matched chunk (default 0, max 5) |
| `efSearch`      | int    | X        | HNSW search width (default 100, 1-500). Larger is more accurate but slower |

The response has the same shape as the result array from `POST /api/v1/search`.

### list_documents

Retrieves the list of documents belonging to the tenant. Supports cursor-based pagination.

| Parameter        | Type    | Required | Description                                                    |
|------------------|---------|:--------:|-------------------------------------------------------------------|
| `limit`          | int     | X        | Maximum number of documents to return (default 20, max 100)       |
| `cursor`         | string  | X        | The `nextCursor` from the previous response                       |
| `q`              | string  | X        | Title search term                                                  |
| `indexingStatus` | string  | X        | Filter by the indexing status of the latest version (`PENDING`/`PROCESSING`/`RETRY_WAIT`/`COMPLETED`/`FAILED`) |
| `searchable`     | boolean | X        | Whether to filter to only documents that have a searchable version set |

The response has the same shape as `GET /api/v1/documents`.

### get_document

Looks up document detail directly by `documentId`. Does not go through search.

| Parameter    | Type | Required | Description         |
|--------------|------|:--------:|------------------------|
| `documentId` | long | O        | The document identifier |

The response has the same shape as `GET /api/v1/documents/{documentId}`.
