#!/usr/bin/env bash
#
# 지정한 문서의 청크를 배포 DB에서 JSONL로 내려받는다. build_qa_set.py 의 입력이다.
#
#   ./scripts/eval/dump_chunks.sh --doc-ids 130,131 > /tmp/chunks.jsonl
#   ./scripts/eval/dump_chunks.sh --doc-ids 130 --min-chars 500 --limit 40 > /tmp/chunks.jsonl
#
# 로컬에 psql 이 없고 API 에도 청크 목록 엔드포인트가 없어서, SSH 로 배포 호스트에 붙어
# postgres 이미지의 psql 을 일회성으로 띄워 쓴다. 호스트에는 psql 바이너리가 없다.
#
# 주의: SQL 에서 content 를 자르지 않는다. 이 DB 는 left()/substring() 이 한글을 바이트
# 기준으로 끊어 "invalid byte sequence for encoding UTF8" 로 실패한다. 전체 content 를
# 받아서 파이썬 쪽에서 자른다.
set -euo pipefail

SSH_HOST="${SSH_HOST:-43.201.101.133}"
SSH_USER="${SSH_USER:-rocky}"
SSH_KEY_PATH="${SSH_KEY_PATH:-$HOME/.ssh/opensql.pem}"
REMOTE_DIR="${REMOTE_DIR:-spr1n6-osscontest-server}"
DOC_IDS=""
MIN_CHARS=300
LIMIT=0

usage() {
  cat >&2 <<'USAGE'
사용법: dump_chunks.sh --doc-ids <id[,id...]> [옵션]

  --doc-ids <목록>    대상 문서 id. 쉼표로 여러 개. (필수)
  --min-chars <n>     이 길이 미만의 청크는 건너뛴다 (기본 300).
                      너무 짧은 청크로는 답할 수 있는 질문이 안 나온다.
  --limit <n>         문서당 최대 청크 수. 0 이면 전부 (기본 0).
  --list              문서 id 와 제목, 청크 수만 출력하고 끝낸다.

환경변수로 접속 정보를 덮어쓸 수 있다: SSH_HOST, SSH_USER, SSH_KEY_PATH, REMOTE_DIR
USAGE
  exit 1
}

LIST_ONLY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --doc-ids) DOC_IDS="${2:-}"; shift 2 ;;
    --min-chars) MIN_CHARS="${2:-}"; shift 2 ;;
    --limit) LIMIT="${2:-}"; shift 2 ;;
    --list) LIST_ONLY=1; shift ;;
    -h|--help) usage ;;
    *) echo "알 수 없는 옵션: $1" >&2; usage ;;
  esac
done

# 배포 호스트에서 psql 한 번 돌린다. .env.deploy 의 접속 정보는 호스트 밖으로 나오지 않는다.
remote_psql() {
  ssh -i "$SSH_KEY_PATH" -o BatchMode=yes "$SSH_USER@$SSH_HOST" \
    "cd $REMOTE_DIR && set -a && . ./.env.deploy && set +a && \
     docker run --rm --network host -e PGPASSWORD=\"\$DB_PASSWORD\" postgres:17-alpine \
     psql -h 127.0.0.1 -U \"\$DB_USERNAME\" -d \"\$DB_NAME\" -t -A -v ON_ERROR_STOP=1 -c \"$1\""
}

if [[ "$LIST_ONLY" == 1 ]]; then
  remote_psql "SELECT d.id || E'\t' || count(c.id) || E'\t' || d.title \
               FROM document d LEFT JOIN document_chunk c ON c.document_id = d.id \
               GROUP BY d.id, d.title HAVING count(c.id) > 0 ORDER BY d.id DESC"
  exit 0
fi

[[ -n "$DOC_IDS" ]] || { echo "error: --doc-ids 가 필요하다. 문서 목록은 --list 로 본다." >&2; usage; }
[[ "$DOC_IDS" =~ ^[0-9]+(,[0-9]+)*$ ]] || { echo "error: --doc-ids 는 숫자와 쉼표만 쓴다: $DOC_IDS" >&2; exit 1; }

# 문서당 균등하게 뽑는다. 한 문서가 청크 2,833개씩 있는 경우가 있어서, LIMIT 없이 돌리면
# 그 문서 하나가 평가셋을 다 차지한다.
if [[ "$LIMIT" -gt 0 ]]; then
  SELECTOR="SELECT * FROM (
              SELECT c.id AS chunk_id, c.document_id, c.chunk_no, d.title, c.content,
                     row_number() OVER (PARTITION BY c.document_id ORDER BY c.chunk_no) AS rn
              FROM document_chunk c JOIN document d ON d.id = c.document_id
              WHERE c.document_id IN ($DOC_IDS) AND length(c.content) >= $MIN_CHARS
            ) s WHERE s.rn <= $LIMIT"
else
  SELECTOR="SELECT c.id AS chunk_id, c.document_id, c.chunk_no, d.title, c.content
            FROM document_chunk c JOIN document d ON d.id = c.document_id
            WHERE c.document_id IN ($DOC_IDS) AND length(c.content) >= $MIN_CHARS"
fi

# row_to_json 으로 한 행당 JSON 한 줄. content 안의 줄바꿈이 \n 으로 이스케이프되므로
# 줄 단위 파싱이 깨지지 않는다.
remote_psql "SELECT row_to_json(t) FROM ($SELECTOR) t"