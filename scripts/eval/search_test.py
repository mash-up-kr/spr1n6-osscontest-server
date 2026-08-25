#!/usr/bin/env python3
"""
qa_set.json의 질의를 실제 Server의 POST /api/v1/search에 태워 Recall@10/MRR을 잰다.

사용법:
    ./gradlew bootRun --args='--spring.profiles.active=local --server.port=18081' &
    python3 scripts/eval/search_test.py            # rerank 없이
    SEARCH_RERANK=true python3 scripts/eval/search_test.py   # rerank:true로 요청
"""
import json
import os
import time
import urllib.request

QA_SET_PATH = os.path.join(os.path.dirname(__file__), "qa_set.json")
API_URL = os.environ.get("SEARCH_API_URL", "http://localhost:18081/api/v1/search")
EVAL_USER_ID = os.environ.get("EVAL_USER_ID", "1")
RERANK = os.environ.get("SEARCH_RERANK", "").lower() in ("1", "true", "yes")


def search(query: str, top_k: int = 10):
    payload = {"query": query, "topK": top_k}
    if RERANK:
        payload["rerank"] = True
    req = urllib.request.Request(
        API_URL,
        data=json.dumps(payload).encode(),
        headers={"X-User-Id": EVAL_USER_ID, "Content-Type": "application/json"},
    )
    start = time.perf_counter()
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = json.load(resp)
    elapsed_ms = (time.perf_counter() - start) * 1000
    ids = [item["chunkId"] for item in body["items"]]
    return ids, elapsed_ms


def percentile(values, p):
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, int(round(p * (len(ordered) - 1))))
    return ordered[idx]


def main():
    with open(QA_SET_PATH, encoding="utf-8") as f:
        qa_pairs = json.load(f)

    hits = 0
    reciprocal_ranks = []
    latencies = []
    print(f"# 질의 {len(qa_pairs)}건 평가 (topK=10, rerank={RERANK}, {API_URL})\n")
    for i, qa in enumerate(qa_pairs, 1):
        ids, ms = search(qa["question"])
        latencies.append(ms)
        rank = ids.index(qa["chunk_id"]) + 1 if qa["chunk_id"] in ids else None
        if rank:
            hits += 1
            reciprocal_ranks.append(1.0 / rank)
            print(f"[{i}/{len(qa_pairs)}] HIT  rank={rank:<3} {ms:7.1f}ms chunk_id={qa['chunk_id']:<6} {qa['question']}")
        else:
            reciprocal_ranks.append(0.0)
            print(f"[{i}/{len(qa_pairs)}] MISS         {ms:7.1f}ms chunk_id={qa['chunk_id']:<6} {qa['question']}")

    recall = hits / len(qa_pairs)
    mrr = sum(reciprocal_ranks) / len(reciprocal_ranks)
    print(f"\nRecall@10 = {recall:.2f} ({hits}/{len(qa_pairs)})")
    print(f"MRR        = {mrr:.3f}")
    print(f"End-to-End(ms): p50={percentile(latencies,0.5):.1f} p95={percentile(latencies,0.95):.1f} max={max(latencies):.1f}")


if __name__ == "__main__":
    main()
