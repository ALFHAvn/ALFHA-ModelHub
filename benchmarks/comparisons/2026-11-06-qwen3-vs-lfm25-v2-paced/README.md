# Live paced comparison on basics v2

Measured November 6, 2026, 00:10–00:13 Asia/Saigon, using commit
`db0a0b0248327642f842824d94e63386365e6e33`. One paired repetition used local
`qwen3:4b` and fixed `liquid/lfm-2.5-2.6b:free` with a minimum cloud start interval
of 3000 ms and at most one retry per task after HTTP 429. The dataset hash,
scorer, model IDs, and local model digest match the earlier comparison.

| Metric | Earlier unpaced runs, 3 repetitions | Paced run, 1 repetition |
| --- | --- | --- |
| Cloud tasks attempted | 30 | 10 |
| First attempts rate-limited | 13/30 (43.3%) | 6/10 (60%) |
| Cloud retries | 0 | 6 |
| Tasks recovered by retry | — | 5 |
| Cloud tasks still rate-limited at completion | 13/30 | 1/10 |
| Cloud tasks skipped | 0 | 0 |
| Cloud scoring passes | 16/30 tasks | 9/10 tasks |
| Cloud scoring passes among responses | 16/17 | 9/9 |
| Local scoring passes | 30/30 | 10/10 |
| Cloud provider attempts, including retries | 30 | 16 |
| Benchmark elapsed time per repetition | 132.7–159.0 s (mean 145.4 s) | 167.4 s |

Four cloud tasks passed on their first attempt. Six received HTTP 429 and
retried. Five retries returned passing responses. The `array-field` retry
received another 429 and its terminal score was `NOT_SCORED`. All seven HTTP
429 responses carried a parsed 1000 ms retry hint. The cloud's mean provider
duration for successful responses was 997 ms and the local mean was 15.71 s.
Those provider durations exclude scheduling waits. The elapsed time uses the
report timestamps.

Retries recovered five tasks at the cost of six extra requests in this run.
The first-attempt rate-limit rate was higher in the paced sample than in the
earlier runs. Different measurement times and a single paced repetition leave
the effect of pacing on rate limits and elapsed time uncertain.

The runner verified zero catalog pricing at preflight and observed 50 free
requests remaining before the run and 41 afterward. All 26 execution IDs,
responses, and statuses matched PostgreSQL history. The Compose stack was
stopped after export. See [the full report](run-1.json), [verification](verification.json),
and [the unpaced comparison](../2026-11-05-qwen3-vs-lfm25-v2/README.md).
