# First local Ollama baseline

Measured November 5, 2026, 02:46–02:48 Asia/Saigon. This is one attempt per task
on the ten-task `basics` v1 dataset, scored using `objective-v1`. No cloud calls
were made. Source commit: `02ff79bc84ea8cc7648ed1f91d8984ac9172f8ed`.

| Measurement | Result |
| --- | --- |
| Model | `qwen3:4b`, 4.0B, Q4_K_M |
| Ollama version | `0.40.14` |
| Completed executions | 10/10 |
| Provider failures | 0 |
| Objective checks passed | 9/10 (90%) |
| Average successful provider duration | 14880.7 ms |
| Total provider duration | 148807 ms |
| Batch elapsed time | approximately 149.5 seconds |
| Saved history matches | 10/10 request IDs, responses, and statuses |

| Task | Score | Provider duration (ms) |
| --- | --- | --- |
| arithmetic | PASS | 11480 |
| capital | PASS | 2538 |
| boolean | PASS | 5235 |
| sorting | PASS | 10700 |
| json-object | PASS | 29542 |
| json-nested | PASS | 18023 |
| typed-fields | PASS | 19164 |
| boolean-field | PASS | 21088 |
| array-field | PASS | 10881 |
| null-field | FAIL | 20156 |

## Failed task inspection

The task asked for `value` set to JSON null and `label` set to "the string empty".
The required fields were:

```json
{"value":null,"label":"empty"}
```

The model returned:

```json
{"value":null,"label":""}
```

The response was valid JSON and handled null correctly. It failed the literal
label expectation. The wording can be interpreted as asking for an empty string,
so this is a candidate prompt-clarity issue rather than evidence of a general
null-handling weakness. The prompt and scorer were not changed after seeing the
result. A future dataset version can explicitly say `the literal JSON string
"empty" (not an empty string)` and should be measured as a separate baseline.

## Evidence and limits

- `report.json`: full report retrieved from PostgreSQL, including dataset snapshot,
  hash, expected values, raw responses, individual scores, and execution UUIDs.
- `runtime.json`: actual Ollama version and model digest, read through the
  container's configured Ollama endpoint.
- `verification.json`: tested source commit, UTC verification timestamp, history
  correlation count, and cloud-disabled flag.

Report UUID: `3df0f2b5-ac19-4512-b636-49bf43be3bef`.
Dataset SHA-256: `6de7890d47a885fc17f703d0b1a07970395f66640906a783ffd2498de467917e`.

The isolated Compose stack was stopped and removed after export. Its named volume
`modelhub-baseline_postgres-data` was preserved. The 9/10 result describes only
these ten checks in this one run; it is not a general model accuracy estimate or
a comparison against another model. Sampling uses provider defaults. Latency can
depend on loading, hardware, prompt/output length, and competing work; this run
did not separately measure those factors. No task was retried to improve scores.

Recommended follow-up: clarify the ambiguous prompt in a new dataset version,
then collect repeated local runs before comparing a fixed free cloud model.
