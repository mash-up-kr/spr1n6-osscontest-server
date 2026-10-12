[한국어](SEARCH.md) | **English**

## 1. Design

Designed as a hybrid search that combines vector search (pgvector + HNSW) with keyword search (Postgres full-text search).

## 2. Building the evaluation pool

We used a synthetic QA approach, generating one question per chunk with an LLM instead of manual labeling. To avoid the pitfalls of a naive prompt (questions that simply reuse the source wording, which keyword search then always matches; low-quality questions), we built a paper-backed 3-stage pipeline (summarize → generate a question that avoids reusing proper nouns from the source → verify answerability). This produced a final set of 52 query-answer pairs.

Paper: Bai, F., Harrigian, K., Stremmel, J., Hassanzadeh, H., Saeedi, A., & Dredze, M. (2024). *Give me Some Hard Questions: Synthetic Data Generation for Clinical QA*. Machine Learning for Health (ML4H) Findings.

## 3. Evaluation

- **ANN accuracy**: measured how much HNSW's approximate search overlaps with exact search (Recall@K)
- **Search relevance**: measured Recall@10 / MRR / latency against the evaluation pool
- **Index usage verification**: used `EXPLAIN (ANALYZE)` to confirm the HNSW/GIN indexes were actually being used

During measurement we found a real-world defect: keyword search often returned zero matches for natural-language questions. We traced the cause to the existing approach (`plainto_tsquery` + `ts_rank_cd`), which supports neither Korean morphological analysis nor IDF (term rarity weighting).

## 4. Improvements based on the evaluation results

### Search accuracy improvements (BM25, reranking)

- **Morphological analysis + BM25**: tokenized with a Korean morphological analyzer (Nori) and rebuilt the pipeline to compute BM25 ranking (including IDF) directly in the application layer, combining it with vector search via RRF.
- **Reranking**: re-sorts the top RRF candidates with a dedicated ranking model (Cohere Rerank API). The initial evaluation concluded it lowered accuracy, so it was defaulted to off — but re-measurement showed that conclusion was wrong, and the default was reverted to true. See section 5 for the full story.

### Performance improvements (caching, indexing)

- **Query embedding caching**: skips the embedding API call for repeated identical queries — confirmed roughly an 8x reduction, from about 3.9s to 0.4–0.5s, for repeated queries.
- **HNSW index**: on the real corpus (28,926 chunks), compared ANN (index used, 1–30ms) against exact search (no index, full comparison, 290–730ms) and confirmed it is roughly 25–40x faster.
- **GIN index**: confirmed via `EXPLAIN` that the keyword candidate-retrieval query actually uses the full-text search index (around 6ms on a match).

## 5. Results

Search relevance improved substantially against the same evaluation pool.

|  | Previous (ts_rank_cd) | BM25+HNSW (rerank off) | + Reranking (rerank on) |
| --- | --- | --- | --- |
| Recall@10 | 0.31 | 0.67 | 0.73–0.79 |
| MRR | 0.191 | 0.382 | 0.56–0.64 |

### Re-verifying reranking

The initial evaluation concluded that turning reranking on lowered accuracy, so the default was set to off. Re-measuring later showed that conclusion was wrong, and the default was reverted to true.

**Cause**: at the time of the initial measurement, the Cohere API key was a Trial key (limited to 10 requests/minute). Reranking is designed fail-open — if it fails, the request isn't failed outright, it silently falls back to the RRF order (`SearchChunkRepository.rerank()`) — and 429 responses also take this path. So even with `rerank=true` on the request, a large share of responses had actually never gone through reranking, and those were mixed into the "after reranking" numbers. Server logs (`docker logs | grep 리랭킹 실패`) showed that 23% of requests in the original measurement had fallen through this path.

**Re-measurement**: we rate-limited the calls (5–9 per minute) to reduce the fail-open share, and re-measured using a paired design that ran the same query with rerank on and off side by side (`scripts/eval/rerank_ab.py`). Reranking won on both the existing 52-query pool and a newly built 21-query set drawn from documents the original pool didn't use (`scripts/eval/build_qa_set.py`, `scripts/eval/dump_chunks.sh`).

| | Existing 52 (off) | Existing 52 (on) | New 21 (off) | New 21 (on) |
|---|---|---|---|---|
| Recall@10 | 0.67 | 0.79 | 0.571 | 0.714 |
| MRR | 0.382 | 0.637 | 0.204 | 0.502 |

On the new 21-query set, a sign test (per-query reciprocal-rank difference) gave 12 improved / 1 worse / 8 tied, p=0.0034. The bootstrap 95% CI for ΔMRR was `[+0.135, +0.463]`, which excludes 0. Latency showed almost no difference from off in this re-measurement (p50 +47ms) — the "latency increases" finding from the original measurement may also have been skewed by 429 retry waits mixed into the numbers.

## 6. MCP

Search and document lookup are also exposed as an MCP (Model Context Protocol) server, so LLM clients can call them directly over a standard protocol. Implemented with Spring AI MCP (Streamable HTTP). Once connected, the LLM automatically translates a natural-language request (e.g. "find documents about the budget") into a `search_documents` call.

See [Connecting an MCP server](../README.en.md#connecting-an-mcp-server) for the list of available tools and client setup.

## 7. Limitations and future work

- **Cohere key is still Trial**: at 10 requests/minute, if traffic grows while the default stays on, a large share of reranking calls will again silently fall back to fail-open — the same problem hit in section 5's re-verification could recur, this time in production rather than in evaluation. This needs either a production key or a request queue/rate limiter sized for actual traffic.
- **The re-verification sample is still small**: the newly built 21 queries were drawn from a single document. Even combined with the existing 52, they represent only a fraction of the full corpus (100+ documents).
- **Search option values weren't swept systematically**: `ef_search` was swept for ANN accuracy alone (40→84.4%, 200→95.8%), but the end-to-end Recall@10/latency trade-off wasn't compared across values to pick an optimum — a conservative value of 100 was kept instead. Other hyperparameters such as RRF_K, the minimum-should-match ratio, and the common-word exclusion ratio were also set by convention/intuition rather than validated by sweeping against our data.
- **HNSW tuning covered only the query-time parameter (`ef_search`)**: index build parameters (`m`, `ef_construction`) were left at pgvector's defaults and not swept separately. The schema denormalizes tenant_id to make `hnsw.iterative_scan` usable, but it hasn't actually been enabled and verified.
- **Evaluation pool size is a limitation**: with only 52 queries, metrics like MRR in particular have high variance, so the sample is still too small to state improvements with full confidence.
