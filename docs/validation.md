# MVP acceptance and validation

Validation date: November 5, 2026. This records actual checks, with limitations
kept separate from implementation status.

| Requirement | Evidence |
| --- | --- |
| Java backend builds | Java 21 / Maven `clean verify` executed locally |
| Common provider interface | Ollama and OpenRouter implement `ModelProvider` |
| Configurable models | `/api/models`, registry tests, environment configuration |
| Local Ollama generation | Real installed `qwen3:4b` returned `Hello` in Milestone 2 |
| Optional cloud provider | Adapter and routing verified with local HTTP stubs; no real OpenRouter call |
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

Real OpenRouter generation and a real two-provider comparison remain unverified.
A configured free cloud ID is not a guarantee of live availability. To finish
those manual acceptance checks, configure your own key and a current free model,
then use the requests in `examples/requests.http`. Never commit the key.

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
on GitHub. Real cloud checks remain pending.

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
