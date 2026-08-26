
#!/usr/bin/env python3
"""
rerank on/off 를 같은 질의에 짝지어(paired) 돌려 비교한다.

search_test.py 는 한 번에 한 쪽만 재므로 두 번 돌려 눈으로 비교해야 하고, 그 과정에서
아래 세 가지가 결과를 조용히 망친다. 이 스크립트는 그것들을 막는 데 목적이 있다.

1. 질의 임베딩 캐시(QueryEmbeddingCache, LRU 1000)
   먼저 돌린 쪽이 캐시를 데워 주므로, 나중에 돌린 쪽이 부당하게 빨라 보인다.
   -> 측정 전에 워밍업 패스를 한 번 돌려 양쪽 모두 캐시 적중 상태에서 잰다.

2. Cohere Trial 키의 분당 10회 제한
   서버는 리랭킹 실패를 fail-open 으로 처리한다(SearchChunkRepository 의 rerank()).
   429 가 나도 응답은 200 이고 순서만 RRF 로 돌아간다. 그래서 rerank=true 로 요청해도
   실제로는 재정렬이 안 된 결과가 섞이는데 클라이언트는 알 수 없다.
   -> 슬라이딩 윈도로 호출 간격을 지키고, off 와 순서가 완전히 같은 질의를
      "재정렬 미적용 의심"으로 따로 세어 보고한다.

3. 표본 52개
   Recall/MRR 차이가 우연인지 알 수 없다.
   -> 짝지은 질의별 차이에 부호검정과 부트스트랩 신뢰구간을 붙인다.

사용법:
    python3 scripts/eval/rerank_ab.py \\
        --url https://43-201-101-133.sslip.io/api/v1/search --user-id 1

    # Production 키로 올렸으면 제한을 풀어 훨씬 빨리 돌린다
    python3 scripts/eval/rerank_ab.py --rate-limit 0

    # 결과를 파일로 남겨 나중에 비교
    python3 scripts/eval/rerank_ab.py --json /tmp/ab.json
"""
import argparse
import json
import math
import os
import random
import statistics
import sys
import time
import urllib.error
import urllib.request
from collections import deque

DEFAULT_QA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "qa_set.json")


class RateLimiter:
    """분당 호출 수를 슬라이딩 윈도로 지킨다. limit=0 이면 제한하지 않는다."""

    def __init__(self, limit, window):
        self.limit = limit
        self.window = window
        self.calls = deque()

    def acquire(self):
        if not self.limit:
            return 0.0
        now = time.monotonic()
        while self.calls and now - self.calls[0] > self.window:
            self.calls.popleft()
        waited = 0.0
        if len(self.calls) >= self.limit:
            waited = max(0.0, self.window - (now - self.calls[0]))
            if waited:
                time.sleep(waited)
            self.calls.popleft()
        self.calls.append(time.monotonic())
        return waited


def search(url, user_id, query, top_k, rerank, timeout):
    payload = {"query": query, "topK": top_k}
    if rerank:
        payload["rerank"] = True
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        headers={"X-User-Id": str(user_id), "Content-Type": "application/json"},
    )
    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = json.load(response)
    elapsed_ms = (time.perf_counter() - started) * 1000
    return [item["chunkId"] for item in body["items"]], elapsed_ms


def rank_of(gold_chunk_id, chunk_ids):
    """정답 청크의 1-기반 순위. 결과에 없으면 None."""
    return chunk_ids.index(gold_chunk_id) + 1 if gold_chunk_id in chunk_ids else None


def percentile(values, p):
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, int(round(p * (len(ordered) - 1))))
    return ordered[idx]


def arm_metrics(ranks, latencies, ks):
    """ranks 는 질의별 정답 순위 리스트(못 찾으면 None)."""
    n = len(ranks)
    return {
        "n": n,
        "recall": {k: sum(1 for r in ranks if r is not None and r <= k) / n for k in ks},
        "mrr": sum(0.0 if r is None else 1.0 / r for r in ranks) / n,
        "hits": sum(1 for r in ranks if r is not None),
        "latency_p50": percentile(latencies, 0.5),
        "latency_p95": percentile(latencies, 0.95),
        "latency_mean": statistics.fmean(latencies) if latencies else 0.0,
    }


def sign_test(diffs):
    """짝지은 차이의 부호검정. 동점은 버린다. 양측 p 값을 정확 이항분포로 낸다."""
    wins = sum(1 for d in diffs if d > 0)
    losses = sum(1 for d in diffs if d < 0)
    n = wins + losses
    if n == 0:
        return {"wins": 0, "losses": 0, "ties": len(diffs), "p_value": 1.0}

    def tail_at_most(x):
        return sum(math.comb(n, i) for i in range(0, x + 1)) / (2 ** n)

    lower = tail_at_most(min(wins, losses))
    p = min(1.0, 2 * lower)
    return {"wins": wins, "losses": losses, "ties": len(diffs) - n, "p_value": p}


def bootstrap_ci(diffs, iterations=10000, seed=42, alpha=0.05):
    """짝지은 차이 평균의 백분위 부트스트랩 신뢰구간."""
    if not diffs:
        return (0.0, 0.0)
    rng = random.Random(seed)
    n = len(diffs)
    means = []
    for _ in range(iterations):
        means.append(statistics.fmean(rng.choices(diffs, k=n)))
    means.sort()
    lo = means[int((alpha / 2) * iterations)]
    hi = means[min(iterations - 1, int((1 - alpha / 2) * iterations))]
    return (lo, hi)


def main():
    parser = argparse.ArgumentParser(
        description="rerank on/off 짝지은 A/B 평가",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--url", default=os.environ.get("SEARCH_API_URL", "http://localhost:8080/api/v1/search"))
    parser.add_argument("--user-id", default=os.environ.get("EVAL_USER_ID", "1"))
    parser.add_argument("--qa", default=DEFAULT_QA, help="질의-정답 쌍 JSON")
    parser.add_argument("--topk", type=int, default=10)
    parser.add_argument("--timeout", type=float, default=90.0, help="요청 타임아웃(초)")
    parser.add_argument("--rate-limit", type=int, default=9,
                        help="rerank 요청의 분당 최대 호출 수. Cohere Trial 키는 10 이므로 9 로 둔다. 0 이면 제한 없음")
    parser.add_argument("--rate-window", type=float, default=62.0, help="위 제한을 적용할 창(초)")
    parser.add_argument("--no-warmup", action="store_true",
                        help="워밍업 패스를 건너뛴다. 지연시간 비교가 캐시 때문에 한쪽으로 기운다")
    parser.add_argument("--json", dest="json_out", help="결과를 이 경로에 JSON 으로 저장")
    args = parser.parse_args()

    with open(args.qa, encoding="utf-8") as f:
        qa_pairs = json.load(f)
    total = len(qa_pairs)
    ks = sorted({k for k in (1, 3, 5, 10) if k <= args.topk} | {args.topk})
    limiter = RateLimiter(args.rate_limit, args.rate_window)

    print(f"# {args.url}  질의 {total}건  topK={args.topk}")
    print(f"# rerank 요청 제한: {args.rate_limit or '없음'}/{args.rate_window:.0f}s\n")

    if not args.no_warmup:
        # 양쪽 arm 이 모두 임베딩 캐시 적중 상태에서 측정되도록 한 바퀴 버리는 패스를 돈다.
        # rerank 를 끄고 돌리므로 Cohere 호출은 쓰지 않는다.
        print("워밍업(임베딩 캐시 채우기)...", end="", flush=True)
        for qa in qa_pairs:
            try:
                search(args.url, args.user_id, qa["question"], args.topk, False, args.timeout)
            except (urllib.error.URLError, TimeoutError) as e:
                print(f"\n  경고: 워밍업 요청 실패({e}). 계속한다.", flush=True)
        print(" 완료\n")

    off_ranks, on_ranks = [], []
    off_lat, on_lat = [], []
    identical, rows = 0, []

    for i, qa in enumerate(qa_pairs, 1):
        question, gold = qa["question"], qa["chunk_id"]
        off_ids, off_ms = search(args.url, args.user_id, question, args.topk, False, args.timeout)
        limiter.acquire()
        on_ids, on_ms = search(args.url, args.user_id, question, args.topk, True, args.timeout)

        r_off, r_on = rank_of(gold, off_ids), rank_of(gold, on_ids)
        off_ranks.append(r_off); on_ranks.append(r_on)
        off_lat.append(off_ms); on_lat.append(on_ms)

        same = off_ids == on_ids
        if same:
            identical += 1

        if r_off is None and r_on is None:
            verdict = "둘 다 MISS"
        elif r_off is None:
            verdict = f"MISS → {r_on}위  개선"
        elif r_on is None:
            verdict = f"{r_off}위 → MISS  악화"
        elif r_on < r_off:
            verdict = f"{r_off}위 → {r_on}위  개선"
        elif r_on > r_off:
            verdict = f"{r_off}위 → {r_on}위  악화"
        else:
            verdict = f"{r_off}위 동일"

        rows.append({"index": i, "question": question, "gold_chunk_id": gold,
                     "rank_off": r_off, "rank_on": r_on,
                     "ms_off": round(off_ms, 1), "ms_on": round(on_ms, 1),
                     "identical_order": same})
        flag = " [순서동일:재정렬 미적용 의심]" if same else ""
        print(f"[{i}/{total}] {verdict:<22} off {off_ms:6.0f}ms / on {on_ms:6.0f}ms{flag}", flush=True)

    off = arm_metrics(off_ranks, off_lat, ks)
    on = arm_metrics(on_ranks, on_lat, ks)
    rr = lambda r: 0.0 if r is None else 1.0 / r
    rr_diffs = [rr(b) - rr(a) for a, b in zip(off_ranks, on_ranks)]

    print(f"\n{'':<12}{'off':>10}{'on':>10}{'차이':>10}")
    for k in ks:
        print(f"{'Recall@'+str(k):<12}{off['recall'][k]:>10.3f}{on['recall'][k]:>10.3f}"
              f"{on['recall'][k]-off['recall'][k]:>+10.3f}")
    print(f"{'MRR':<12}{off['mrr']:>10.3f}{on['mrr']:>10.3f}{on['mrr']-off['mrr']:>+10.3f}")
    print(f"{'p50(ms)':<12}{off['latency_p50']:>10.0f}{on['latency_p50']:>10.0f}"
          f"{on['latency_p50']-off['latency_p50']:>+10.0f}")
    print(f"{'p95(ms)':<12}{off['latency_p95']:>10.0f}{on['latency_p95']:>10.0f}"
          f"{on['latency_p95']-off['latency_p95']:>+10.0f}")

    test = sign_test(rr_diffs)
    lo, hi = bootstrap_ci(rr_diffs)
    print(f"\n부호검정(질의별 역순위 기준): 개선 {test['wins']} / 악화 {test['losses']} / 동일 {test['ties']}")
    print(f"  양측 p = {test['p_value']:.4f}  {'유의(p<0.05)' if test['p_value'] < 0.05 else '유의하지 않음'}")
    print(f"ΔMRR 부트스트랩 95% CI = [{lo:+.3f}, {hi:+.3f}]"
          f"  {'0 을 포함하지 않음' if lo > 0 or hi < 0 else '0 을 포함 — 방향을 단정할 수 없음'}")

    if identical:
        print(f"\n경고: {identical}/{total} 건이 off 와 순서가 완전히 같다.")
        print("  Cohere 429 로 fail-open 됐을 가능성이 높다. --rate-limit 을 낮추거나 Production 키로 올린 뒤 다시 재라.")
        print("  서버에서 확인: docker logs aidocs-it-server | grep '리랭킹 실패'")

    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as f:
            json.dump({"config": vars(args), "off": off, "on": on,
                       "sign_test": test, "delta_mrr_ci": [lo, hi],
                       "identical_order_count": identical, "per_query": rows},
                      f, ensure_ascii=False, indent=2)
        print(f"\n저장: {args.json_out}")

    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n중단됨", file=sys.stderr)
        sys.exit(130)