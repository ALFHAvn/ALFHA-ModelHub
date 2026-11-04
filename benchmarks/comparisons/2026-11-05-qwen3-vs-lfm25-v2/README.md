# Fixed free cloud versus local Ollama: basics v2

Measured November 5, 2026, approximately 03:14–03:21 Asia/Saigon, using commit
`da38d3c8fbc83a11271254e9985b2a5f64091a89`. Three repetitions sent each of the ten
v2 prompts to both providers concurrently. No task was retried, no model was
substituted, and no paid fallback was requested. The dataset and scorer were not
changed in response to these results.

## Combined observations

| Measurement | Local `qwen3:4b` | Cloud `liquid/lfm-2.5-2.6b:free` |
| --- | --- | --- |
| Attempted | 30 | 30 |
| Successful responses | 30 | 17 |
| Provider failures | 0 | 13 (`RATE_LIMITED`) |
| Scoring passes | 30 | 16 |
| Scoring failures | 0 | 1 |
| Passes / successful responses | 30/30 (100%) | 16/17 (94.1%) |
| Passes / all attempts | 30/30 (100%) | 16/30 (53.3%) |
| Mean duration of successful executions | 14533.2 ms | 943.5 ms |
| Distinct tasks with at least one successful response | 10/10 | 10/10 |

Rate-limited attempts have score `NOT_SCORED`, not an invented wrong answer.
They stay in the denominator of `passRateAllAttempts` to show end-to-end completion
of the requested checks. The successful-response mean excludes failures; durations
for failed attempts remain in the raw reports.

| Repetition | Local passes | Cloud passes | Cloud rate limits | Cloud scoring failures |
| --- | --- | --- | --- | --- |
| 1 | 10/10 | 4/10 attempts (4/4 responses) | 6 | 0 |
| 2 | 10/10 | 7/10 attempts (7/7 responses) | 3 | 0 |
| 3 | 10/10 | 5/10 attempts (5/6 responses) | 4 | 1 |

## Failure inspection

All 13 operational failures were sanitized `RATE_LIMITED` responses. The initial
free quota was sufficient for the planned attempts, but this does not prevent
other rate limits. The adapter did not retain upstream rate-limit metadata, so
this record cannot identify whether the limiting policy was per-model, upstream,
or another request-rate restriction. No automatic backoff/retry was used.

The scoring failure was `array-field` in repetition 3. It asked for only a JSON
object with `numbers: [1,2,3]`, but the response included Markdown fences:

````text
```json
{
  "numbers": [1, 2, 3]
}
```
````

The values inside the fence were correct. The predefined `JSON_FIELDS` rule
requires the entire response to be JSON, so this failed format adherence. The
same task passed in repetition 2; repetition 1 was rate-limited. The scorer was
not changed to strip fences after observing this failure.

## Cloud task coverage

Every cloud task was attempted three times, but successful-response coverage was
uneven. A high conditional pass rate should not be read as three complete scored runs.

| Task | Successful responses | Scoring passes | Rate-limited attempts |
| --- | --- | --- | --- |
| arithmetic | 3 | 3 | 0 |
| capital | 1 | 1 | 2 |
| boolean | 1 | 1 | 2 |
| sorting | 3 | 3 | 0 |
| json-object | 1 | 1 | 2 |
| json-nested | 1 | 1 | 2 |
| typed-fields | 1 | 1 | 2 |
| boolean-field | 2 | 2 | 1 |
| array-field | 2 | 1 | 1 |
| null-field | 2 | 2 | 1 |

## Configuration and verification

The fixed cloud ID was selected from the live [OpenRouter model catalog](https://openrouter.ai/api/v1/models).
Its recorded entry listed zero prompt/completion prices. A free suffix is not a
promise of future availability; see [OpenRouter's free-model documentation](https://openrouter.ai/docs/guides/routing/model-variants/free).
The benchmark did not use the variable `openrouter/free` router.

The free-request counter was 2 used / 48 remaining before the run, and 19 used /
31 remaining afterward (daily limit 50). Those observed counters increased by 17,
matching the successful cloud responses, rather than all 30 attempted calls.
This observation does not guarantee how future failures will be counted.
No credits were purchased, and no paid model was requested.

Local Ollama version `0.40.14` and model digest
`359d7dd4bcdab3d86b87d73ac27966f4dbb9f5efdfcc75d34a8764a09474fae7`
matched before and after measurement. The dataset was `basics` v2, SHA-256
`25e3bdbecce5b8e41b3e8acf4f896de3226940ac7bb981def7801a622bafe2c1`, with
the unchanged `objective-v1` scorer. Both providers used their default inference
settings and 120-second request timeout. The exact cloud backend revision was
not pinned or independently verified.

All three reports were retrieved from PostgreSQL. Every one of the 60 execution
UUIDs, responses, and statuses matched execution history. The isolated Compose
stack was stopped and removed, preserving its named database volume
`modelhub-fixed-cloud-comparison_postgres-data`. The runner left `.env` unchanged.

Artifacts:

- `run-1.json`, `run-2.json`, `run-3.json`: complete persisted snapshots and raw results.
- `summary.json`: recomputed combined metrics, per-run summaries, and cloud task coverage.
- `model-catalog-entry.json`: public catalog entry checked during preflight.
- `quota-before.json`, `quota-after.json`: free-request counters without key identifiers.
- `runtime-before.json`, `runtime-after.json`: local runtime/model metadata.
- `verification.json`: tested source commit, report UUIDs, and verification counts.

## Interpretation and next step

This small dataset measures a few exact-answer and structured-output checks. It
does not establish a general model-quality ranking. The deployments use different
hardware and serving infrastructure. The cloud latency average covers a smaller,
uneven task sample and excludes failed attempts; it is not evidence of an
equivalent-hardware speedup or reliable end-to-end throughput.

The most useful next engineering step is rate-limit-aware benchmark scheduling:
retain safe retry timing from the provider, add configurable pacing and explicit
attempt limits, and keep every attempt visible. Any later retry experiment should
be reported separately from this unretried baseline and respect remaining quota.
