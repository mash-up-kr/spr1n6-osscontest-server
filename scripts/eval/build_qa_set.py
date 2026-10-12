#!/usr/bin/env python3
"""
청크 JSONL 을 받아 LLM 으로 질의-정답 쌍(평가 답안지)을 만든다.

dump_chunks.sh 로 뽑은 청크를 입력으로 받아 search_test.py / rerank_ab.py 가 그대로 읽는
형식([{chunk_id, document_id, question}, ...])으로 낸다.

기존 52건 답안지와 같은 3단계 파이프라인을 쓴다(docs/SEARCH.md 2절, Bai et al. 2024).

  1. 요약    청크를 2~3문장으로 줄인다.
  2. 질문    요약만 보고 질문을 만든다. 원문을 안 보여 주는 게 핵심이다 — 원문을 주면
             모델이 그 문장의 단어를 그대로 베껴서, 키워드 검색만으로 항상 맞는
             공짜 질문이 나온다. 그러면 벡터 검색의 기여를 측정할 수 없다.
  3. 검증    원문과 질문을 함께 주고 "이 원문만으로 답할 수 있나"를 묻는다. 아니면 버린다.

여기에 LLM 을 믿지 않는 기계적 검사를 하나 더 얹는다. 질문과 원문이 길게 겹치는
n-gram 을 공유하면(--max-ngram-overlap) 3단계를 통과했어도 버린다.

사용법:
    set -a; . ./.env.integration; set +a          # OPENAI_API_KEY 를 셸로 불러온다
    ./scripts/eval/dump_chunks.sh --doc-ids 130 --limit 40 > /tmp/chunks.jsonl
    python3 scripts/eval/build_qa_set.py --chunks /tmp/chunks.jsonl --count 30 \\
        --out scripts/eval/qa_set_mine.json --audit /tmp/qa_audit.json

    python3 scripts/eval/rerank_ab.py --qa scripts/eval/qa_set_mine.json \\
        --url https://43-201-101-133.sslip.io/api/v1/search --rate-limit 5
"""
import argparse
import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

API_URL = "https://api.openai.com/v1/chat/completions"

SUMMARY_PROMPT = """다음 회의록·문서 발췌를 2~3문장으로 요약해라.
사실만 남기고, 무엇에 대한 내용인지 분명히 드러나게 써라.

발췌:
{content}"""

QUESTION_PROMPT = """아래 요약을 읽고, 그 내용을 근거로 답할 수 있는 질문 하나만 만들어라.

규칙:
- 질문 하나만 출력한다. 번호·설명·따옴표 없이 질문 문장만 쓴다.
- 요약에 나온 고유명사·숫자·날짜를 그대로 베끼지 마라. 일반적인 표현으로 바꿔 물어라.
- 요약을 안 읽은 사람도 무엇을 묻는지 알 수 있게, 문맥이 담긴 완전한 문장으로 써라.
- "이 문서에 따르면" 같은 표현은 쓰지 마라.

요약:
{summary}"""

VERIFY_PROMPT = """아래 원문만 근거로 질문에 답할 수 있는지 판정해라.

원문에 답이 명확히 있으면 YES, 없거나 추측이 필요하면 NO 를 출력해라.
YES 나 NO 한 단어만 출력한다.

원문:
{content}

질문:
{question}"""


class OpenAIClient:
    def __init__(self, api_key, model, timeout=90, max_retries=4):
        self.api_key = api_key
        self.model = model
        self.timeout = timeout
        self.max_retries = max_retries

    def complete(self, prompt, max_tokens=400):
        body = json.dumps({
            "model": self.model,
            "messages": [{"role": "user", "content": prompt}],
            "max_completion_tokens": max_tokens,
        }).encode()
        request = urllib.request.Request(
            API_URL, data=body,
            headers={"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"},
        )
        delay = 2.0
        for attempt in range(self.max_retries):
            try:
                with urllib.request.urlopen(request, timeout=self.timeout) as response:
                    payload = json.load(response)
                return payload["choices"][0]["message"]["content"].strip()
            except urllib.error.HTTPError as e:
                detail = e.read().decode(errors="replace")[:300]
                # 429(속도 제한)와 5xx 는 기다렸다 다시 시도한다. 400 대는 프롬프트나 모델
                # 이름이 틀린 것이므로 바로 알려 주고 멈춘다.
                if e.code == 429 or e.code >= 500:
                    if attempt == self.max_retries - 1:
                        raise RuntimeError(f"OpenAI {e.code} 재시도 소진: {detail}") from e
                    time.sleep(delay)
                    delay *= 2
                    continue
                raise RuntimeError(f"OpenAI {e.code}: {detail}") from e
            except (urllib.error.URLError, TimeoutError) as e:
                if attempt == self.max_retries - 1:
                    raise RuntimeError(f"OpenAI 연결 실패: {e}") from e
                time.sleep(delay)
                delay *= 2
        raise RuntimeError("도달 불가")


def normalize(text):
    """n-gram 비교용. 공백과 문장부호를 털어 낸다."""
    return re.sub(r"[^0-9A-Za-z가-힣]+", "", text)


def max_shared_ngram(question, content):
    """질문과 원문이 공유하는 가장 긴 연속 문자열 길이. 베껴 쓴 질문을 걸러낸다."""
    q, c = normalize(question), normalize(content)
    if not q or not c:
        return 0
    best = 0
    for i in range(len(q)):
        # 이미 찾은 최장 길이보다 긴 것만 확인하면 되므로 j 는 best+1 에서 시작한다.
        j = best + 1
        while i + j <= len(q) and q[i:i + j] in c:
            best = j
            j += 1
    return best


def build_one(client, chunk, max_overlap, content_limit):
    """청크 하나에서 질문 하나를 만든다. 실패하면 reason 을 담아 돌려준다."""
    content = chunk["content"][:content_limit]
    trace = {"chunk_id": chunk["chunk_id"], "document_id": chunk["document_id"],
             "chunk_no": chunk.get("chunk_no"), "title": chunk.get("title")}
    try:
        summary = client.complete(SUMMARY_PROMPT.format(content=content), max_tokens=300)
        trace["summary"] = summary

        question = client.complete(QUESTION_PROMPT.format(summary=summary), max_tokens=200)
        question = question.strip().strip('"').strip()
        # 모델이 여러 줄을 내놓으면 첫 줄만 쓴다.
        question = question.splitlines()[0].strip() if question else ""
        trace["question"] = question
        if not question:
            trace["reason"] = "질문이 비어 있음"
            return None, trace

        overlap = max_shared_ngram(question, content)
        trace["max_overlap"] = overlap
        if overlap > max_overlap:
            trace["reason"] = f"원문과 {overlap}자 연속 일치(한도 {max_overlap}) — 키워드로 공짜 정답"
            return None, trace

        verdict = client.complete(
            VERIFY_PROMPT.format(content=content, question=question), max_tokens=10)
        trace["verdict"] = verdict
        if not verdict.upper().startswith("YES"):
            trace["reason"] = f"답변 가능성 검증 실패(응답: {verdict[:40]})"
            return None, trace

        return {"chunk_id": chunk["chunk_id"], "document_id": chunk["document_id"],
                "question": question}, trace
    except RuntimeError as e:
        trace["reason"] = f"API 오류: {e}"
        return None, trace


def main():
    parser = argparse.ArgumentParser(
        description="청크에서 평가용 질의-정답 쌍을 만든다",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument("--chunks", required=True, help="dump_chunks.sh 가 낸 JSONL")
    parser.add_argument("--out", required=True, help="답안지를 저장할 경로")
    parser.add_argument("--audit", help="버려진 것까지 포함한 전체 기록을 저장할 경로")
    parser.add_argument("--count", type=int, default=30, help="목표 질의 수")
    parser.add_argument("--model", default=os.environ.get("QA_MODEL", "gpt-4o-mini"),
                        help="질문 생성에 쓸 OpenAI 모델")
    parser.add_argument("--max-per-doc", type=int, default=8,
                        help="문서 하나에서 뽑을 최대 질의 수. 한 문서가 답안지를 독차지하는 것을 막는다")
    parser.add_argument("--max-ngram-overlap", type=int, default=12,
                        help="질문과 원문이 이 길이를 넘게 연속 일치하면 버린다")
    parser.add_argument("--content-limit", type=int, default=4000,
                        help="청크가 이보다 길면 앞부분만 LLM 에 넘긴다")
    parser.add_argument("--workers", type=int, default=4, help="동시 요청 수")
    parser.add_argument("--seed", type=int, default=42, help="청크 표본 추출 시드")
    args = parser.parse_args()

    api_key = os.environ.get("OPENAI_API_KEY", "").strip()
    if not api_key:
        print("error: OPENAI_API_KEY 가 없다. `set -a; . ./.env.integration; set +a` 로 불러와라.",
              file=sys.stderr)
        return 1

    chunks = []
    with open(args.chunks, encoding="utf-8") as f:
        for line_no, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                chunks.append(json.loads(line))
            except json.JSONDecodeError as e:
                print(f"경고: {args.chunks}:{line_no} 파싱 실패({e}). 건너뛴다.", file=sys.stderr)
    if not chunks:
        print(f"error: {args.chunks} 에 읽을 청크가 없다.", file=sys.stderr)
        return 1

    # 문서별 상한을 지키면서 섞는다. 청크 수가 많은 문서에 쏠리지 않게 한다.
    rng = random.Random(args.seed)
    rng.shuffle(chunks)
    per_doc, pool = {}, []
    for chunk in chunks:
        doc = chunk["document_id"]
        if per_doc.get(doc, 0) >= args.max_per_doc:
            continue
        per_doc[doc] = per_doc.get(doc, 0) + 1
        pool.append(chunk)

    # 검증에서 버려지는 것이 있으니 목표보다 넉넉히 시도한다.
    attempts = pool[:min(len(pool), int(args.count * 1.8) + 5)]
    docs = sorted({c["document_id"] for c in attempts})
    print(f"# 청크 {len(chunks)}건 중 {len(attempts)}건 시도 → 목표 {args.count}건")
    print(f"# 문서 {len(docs)}개 {docs}  모델 {args.model}\n", flush=True)

    client = OpenAIClient(api_key, args.model)
    qa_pairs, traces = [], []
    with ThreadPoolExecutor(max_workers=args.workers) as pool_exec:
        futures = [pool_exec.submit(build_one, client, c, args.max_ngram_overlap, args.content_limit)
                   for c in attempts]
        for i, future in enumerate(futures, 1):
            qa, trace = future.result()
            traces.append(trace)
            if qa:
                qa_pairs.append(qa)
                print(f"[{i}/{len(attempts)}] 채택 chunk={qa['chunk_id']} 겹침={trace['max_overlap']}자"
                      f"  {qa['question'][:52]}", flush=True)
            else:
                print(f"[{i}/{len(attempts)}] 버림 chunk={trace['chunk_id']}  {trace['reason'][:70]}",
                      flush=True)
            if len(qa_pairs) >= args.count:
                print(f"\n목표 {args.count}건 도달. 남은 시도는 건너뛴다.", flush=True)
                for remaining in futures[i:]:
                    remaining.cancel()
                break

    if not qa_pairs:
        # 채택이 0건이어도 --audit 은 남긴다. 이게 없으면 왜 다 버려졌는지 볼 방법이 없다.
        if args.audit:
            with open(args.audit, "w", encoding="utf-8") as f:
                json.dump(traces, f, ensure_ascii=False, indent=2)
            print(f"\n전체 기록: {args.audit}", file=sys.stderr)
        print("error: 채택된 질의가 없다. --audit 파일에서 reason 을 확인해라.", file=sys.stderr)
        return 1

    qa_pairs.sort(key=lambda q: q["chunk_id"])
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(qa_pairs, f, ensure_ascii=False, indent=2)
        f.write("\n")

    kept_docs = sorted({q["document_id"] for q in qa_pairs})
    print(f"\n채택 {len(qa_pairs)}건 / 시도 {len(traces)}건  문서 {len(kept_docs)}개 {kept_docs}")
    print(f"저장: {args.out}")

    if args.audit:
        with open(args.audit, "w", encoding="utf-8") as f:
            json.dump(traces, f, ensure_ascii=False, indent=2)
        print(f"전체 기록: {args.audit}")
        print("버린 이유를 훑어보고 질문 품질을 눈으로 확인해라. 답안지는 결과의 근거이므로"
              " 자동 생성만 믿고 쓰지 않는 편이 낫다.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n중단됨", file=sys.stderr)
        sys.exit(130)