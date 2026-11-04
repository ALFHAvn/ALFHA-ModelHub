# MVP acceptance and validation

Validation date: November 5, 2026. This records actual checks, with limitations
kept separate from implementation status.

| Requirement | Evidence |
| --- | --- |
| Java backend builds | Java 21 / Maven `clean verify` executed locally |
| Common provider interface | Ollama and OpenRouter implement `ModelProvider` |
| Configurable models | `/api/models`, registry tests, environment configuration |
| Local Ollama generation | Real installed `qwen3:4b` returned `Hello` in Milestone 2 |
| Optional cloud provider | Stub tests plus real `openrouter/free` generation succeeded during release acceptance |
| Concurrent comparison | Latch test requires both providers to start before either completes |
| Partial failures | Both result positions, timeouts, both-failed case, and HTTP partial failure tested |
| PostgreSQL history | Real PostgreSQL 17 Testcontainer: migration, success/failure saves, comparison, pagination, restart durability |
| Database failures | Isolated integration test simulates unavailable table and checks safe failure with preserved output |
| Input/errors | Validation, unknown models, malformed JSON, upstream failures, rate limits, timeouts, 404/405/415 tested |
| Docker delivery | Multi-stage image and Compose configuration provided; local smoke result recorded below |
| Automated CI | [Java CI passed](https://github.com/ALFHAvn/ALFHA-ModelHub/actions/runs/41893585994) for commit `69eaffa186f7e5f8284576f7aa783def09d936df` |
| No paid subscription required | Local inference works; cloud disabled by default and restricted to free identifiers, without paid fallback |

Automated tests use fake providers and loopback HTTP servers, not real cloud APIs.
The database integration test uses an actual disposable PostgreSQL container and
does not skip silently when Docker is unavailable. It does not touch a user's
existing database. Reports are in `target/surefire-reports` and
`target/failsafe-reports` after a full build.

Real OpenRouter generation, Ollama–OpenRouter comparison, and matching saved
history all passed during release acceptance. A configured free cloud ID is not
a guarantee of future availability. Credentials stayed in the ignored `.env`.

## Local delivery smoke check

Completed locally on November 5, 2026:

- Java 21 Maven `clean verify`: 21 unit/HTTP tests and 1 real PostgreSQL
  integration test passed, with no failures, errors, or skips.
- Docker image built successfully; its build also ran the 21 unit/HTTP tests.
- `docker compose config --quiet` passed.
- Isolated Compose project `modelhub-m6-check` started on loopback port 18082;
  PostgreSQL and the application both reached healthy status.
- Health and configured-model listing returned expected JSON.
- Containerized app called real host Ollama `qwen3:4b`, returning `Hello`,
  `SUCCESS`, and duration 9096 ms. History returned the matching UUID
  `07474f4e-171d-4bfc-ac8c-4ad8375c59ce` and saved prompt/answer.
- `id` in the app container confirmed non-root UID 100 (`modelhub`).
- Both containers were removed and recreated using the named volume; the same
  history record was still present afterward.
- Test containers/network were stopped and removed. The isolated
  `modelhub-m6-check_postgres-data` volume and built images were preserved.

The Milestone 6 local checks made no cloud calls or deployments. The completed
milestones were subsequently pushed at the user's request, and Java CI passed
on GitHub. Real cloud checks subsequently passed as recorded below.

## Real-service release acceptance

On November 5, 2026 (Asia/Saigon), `scripts/verify-cloud.ps1` passed with:

| Execution | Model | Duration | Result |
| --- | --- | --- | --- |
| Standalone cloud generation | `openrouter/free` | 4617 ms | SUCCESS |
| Comparison local execution | `qwen3:4b` | 38942 ms | SUCCESS |
| Comparison cloud execution | `openrouter/free` | 744 ms | SUCCESS |

All three UUIDs and response texts matched records retrieved from PostgreSQL
history. The stack was stopped afterward, preserving its isolated database volume.
The local evidence file `target/cloud-validation.json` contains execution IDs,
model IDs, statuses, and timings, without credentials or response text.
The tested application source matches commit `bd7595e`; the acceptance script
had the model-array handling correction included in this release commit.

The initial attempt stopped before any provider request because the script wrapped
PowerShell's REST array response incorrectly. This was reproduced locally and
fixed by assigning the response before enumerating it. The successful run above
used the corrected script. No paid model or automatic fallback was requested.

## Completing real cloud acceptance

Create the ignored `.env` from `.env.example`, then set `DATABASE_PASSWORD`,
`OPENROUTER_API_KEY`, and `OPENROUTER_MODEL` (a current `:free` model or
`openrouter/free`). Do not paste credentials into chat. Keep Ollama running with
the configured local model installed. Run from PowerShell:

```powershell
./scripts/verify-cloud.ps1
```

This starts a separate local Compose project on loopback port 18083, enables
OpenRouter for that process, and makes two cloud requests: one standalone and one
alongside Ollama in a comparison. It verifies that all three execution IDs and
responses match saved history. Free-model rate limits can cause a failed check;
the script does not retry or fall back to paid models.

On success, `target/cloud-validation.json` records the tested commit, execution
IDs, model IDs, statuses, and durations without keys or response text. The stack
is stopped afterward and its database volume is preserved. No tag or push is
performed. Create `v0.1.0` only after real-service checks and CI pass for the
release commit.

## Milestone 7 implementation checks

The benchmark feature adds a versioned ten-task dataset, deterministic objective
scoring rules, per-model summaries, and persistent JSON reports. A full Java 21
`mvn clean verify` passed with 28 unit/HTTP tests and 2 PostgreSQL integration
tests during development. V1-to-V2 migration, preservation of existing history,
partial model failure, execution-ID correlation, and report retrieval after
restart were verified against actual PostgreSQL containers. AI responses for
these checks were simulated; no real ten-task benchmark scores are claimed.
See `docs/benchmarks.md` for scoring semantics, limits, and run/export examples.

## First real local benchmark baseline

On November 5, 2026, the `basics` v1 benchmark ran against actual local
`qwen3:4b` with OpenRouter explicitly disabled. All ten provider calls succeeded;
nine passed their scoring rule. The failure returned `label: ""` where the rule
expected the literal string `"empty"`; the potentially ambiguous prompt is noted
in the analysis without modifying the original dataset. Average provider duration
was 14880.7 ms. The saved report was retrieved from PostgreSQL and all ten execution
IDs, responses, and statuses matched history. The test stack was stopped afterward.

Full evidence: `benchmarks/baselines/2026-11-05-qwen3-4b/`. This is a single-run
local baseline, not a statistically validated model ranking or general accuracy score.

## Dataset v2 and repeated local measurements

Dataset v2 changes only the ambiguous literal-string prompt; expected values and
the scorer stay unchanged. Explicit `datasetVersion` and dataset query `version`
select v2, while missing/null request versions and the unparameterized dataset
endpoint preserve v1. Tests verify v1's original hash and reject unknown versions
before provider calls. The complete build passed 32 tests, including real
PostgreSQL v2 report storage and retrieval after restart.

Three actual local `qwen3:4b` v2 runs passed 10/10 each, with zero provider failures
and all 30 attempts matched to saved history. Average durations per run were
13394.5, 16816.5, and 16512.8 ms; combined average was 15574.6 ms. The model digest
and Ollama version matched before and after measurement. No cloud calls or task
retries were made. The stack was stopped after verification.

Evidence and limitations: `benchmarks/baselines/2026-11-05-qwen3-4b-v2/`.
These different-version samples do not establish a model improvement or general
accuracy estimate; the original v1 reports are preserved unchanged.

## Fixed free cloud comparison

On November 5, 2026, three paired `basics` v2 repetitions ran against actual local
`qwen3:4b` and fixed `liquid/lfm-2.5-2.6b:free`, selected from the live zero-priced
catalog. The new PowerShell runner passed a syntax check and completed this
real-service workflow; application source and scoring behavior were unchanged.

Local results: 30/30 execution successes and scoring passes. Cloud results:
17 successful responses, 16 scoring passes, one JSON-fence scoring failure, and
13 rate-limited attempts recorded as `NOT_SCORED`. All 60 execution IDs, responses,
and statuses matched persisted history. All ten cloud tasks had at least one
successful response, but repetition coverage was uneven. No retries, paid fallback,
model substitution, or dataset/scorer changes were made during measurement.

The key's observed free-request allowance went from 48 remaining to 31 remaining.
The test stack was stopped, the local model digest remained unchanged, and `.env`
was not modified. Raw reports, public model-catalog metadata, quota counters,
combined metrics, and interpretation are in
`benchmarks/comparisons/2026-11-05-qwen3-vs-lfm25-v2/`.
