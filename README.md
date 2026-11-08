# ALFHA ModelHub

ALFHA ModelHub is a local platform for running, comparing, and evaluating AI
models. It is independent of ALFHA Agent.

## Current scope

- A Java 21 Spring Boot backend connects to Ollama and optional OpenRouter,
  runs generation and comparisons, and saves execution history in PostgreSQL.
- The React web UI supports chat, comparison, and execution history.
  [Windows startup guide](#local-web-ui-on-windows-11)
- Versioned benchmarks cover 10 basic tasks, 24 software engineering tasks,
  and an opt-in Java coding pilot that runs submissions in restricted Docker
  containers. Reports include task results, provider failures, and latency.
  [Benchmark usage and scoring](docs/benchmarks.md)
- Opt-in routing previews and routed generation use curated benchmark reports.
  Cloud dispatch requires a fresh pricing check and sends zero-price limits.
  [Routing design](docs/routing-design.md)
- Milestone 10 covers benchmark coverage and repeatability.
  [Evaluation protocol](docs/benchmark-repeatability.md)
- A two-model workflow generates a proposal and then collects feedback from an
  explicitly selected reviewer. [Proposal and review](#two-model-proposal-and-review)

The published Milestone 10 release is [v0.2.0](https://github.com/ALFHAvn/ALFHA-ModelHub/releases/tag/v0.2.0).
The [v0.3.0 release draft](docs/v0.3.0-release-draft.md) covers the Milestone 11
proposal-and-review workflow and its local evaluation.

Docker Compose and GitHub Actions are included. The validation notes describe
which behaviors have been exercised with live providers. [Validation](docs/validation.md)

## Prerequisites

- JDK 21 (`JAVA_HOME` must point to the JDK directory).
- Apache Maven 3.6.3 or newer (verified locally with 3.9.12).
- Internet access for the initial Maven dependency download.
- PostgreSQL for running the application (tested with PostgreSQL 17).
- Docker running Linux containers for the Testcontainers integration test.

The current application requires a database at startup. Cloud access remains optional.
Spring Boot is pinned to 4.1.2; Java compilation targets release 21.

Check your environment:

```shell
java -version
mvn -version
```

For PowerShell, select an installed JDK for the current session:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

This development workspace also has a local Temurin JDK under the ignored
`.tools/jdk21` directory. To use it in this checkout:

```powershell
$env:JAVA_HOME = (Get-ChildItem '.tools\jdk21' -Directory | Select-Object -First 1).FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

## Build and test

```shell
mvn clean verify
```

To keep Maven's dependency cache inside this checkout (as used during setup):

```shell
mvn "-Dmaven.repo.local=.m2/repository" --batch-mode --no-transfer-progress clean verify
```

`mvn test` runs infrastructure-free unit and HTTP tests. The test-only default
configuration disables history storage and datasource/Flyway auto-configuration.
`mvn verify` also runs `HistoryIT` using a disposable PostgreSQL Testcontainer;
it requires Docker and may download PostgreSQL and the Testcontainers helper image.
Database tests fail rather than silently skip when Docker is unavailable.
AI providers in automated tests are fake providers or loopback HTTP stubs.
JUnit 5 is explicitly selected to meet the project requirements. Because Spring
Framework 7's JUnit extension requires JUnit 6, the test manages the application
context directly rather than using `@SpringBootTest`.
Reports are written to `target/surefire-reports` and `target/failsafe-reports`.

## Run

First configure PostgreSQL as described under Execution history below. Flyway
migrates the configured database on startup; the database and login must already exist.

```shell
mvn spring-boot:run
```

Or run the packaged application after building:

```shell
java -jar target/modelhub-0.3.0.jar
```

If you use the local Maven cache, include `"-Dmaven.repo.local=.m2/repository"`
in the Maven run command as well.

The default port is 8080. Override it with `SERVER_PORT`, for example in PowerShell:

```powershell
$env:SERVER_PORT = '8081'
java -jar target/modelhub-0.3.0.jar
```

## Health API

```shell
curl http://localhost:8080/api/health
```

PowerShell alternative:

```powershell
Invoke-RestMethod http://localhost:8080/api/health
```

HTTP 200, `Content-Type: application/json`:

```json
{"status":"UP","application":"ALFHA ModelHub"}
```

This endpoint reports application liveness. It does not check AI providers or a
database. Default error responses exclude exception details and stack traces.

## Architecture

- `vn.alfha.modelhub.ModelHubApplication`: application entry point and component scan root.
- `controller.HealthController`: HTTP routing.
- `dto.HealthResponse`: immutable response record.
- `src/main/resources/application.properties`: application name, environment-based port, error settings.
- `src/test/java/vn/alfha/modelhub/HealthEndpointTest.java`: real HTTP integration test.

The foundation uses Spring MVC with embedded Tomcat. No service is necessary for
a static health response. Later milestones will introduce `service`, `provider`,
`config`, `exception`, `entity`, and `repository` packages as their behavior is
implemented; controllers will delegate orchestration to services and external
communication to provider adapters.

## Development workflow

Keep `.env` files, credentials, downloaded tools, dependency caches, and build
outputs out of Git. Make local commits after tested milestones. Remote pushes
require explicit approval.

## Ollama generation

Start Ollama separately (`ollama serve` if it is not already running), and install
the default model with `ollama pull qwen3:4b`. The download may be several GB.
The application never downloads models automatically.

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Trusted Ollama server origin |
| `OLLAMA_MODEL` | `qwen3:4b` | Configured local model identifier |
| `OLLAMA_SECONDARY_MODEL` | Empty | Optional second installed Ollama model for explicit selection |
| `OLLAMA_TIMEOUT` | `120s` | Positive connection and complete request timeout |

```powershell
$body = @{ model = 'qwen3:4b'; prompt = 'Explain REST in one sentence.' } | ConvertTo-Json
Invoke-RestMethod http://localhost:8080/api/generate -Method Post -ContentType 'application/json' -Body $body
```

Successful response (duration varies):

```json
{"model":"qwen3:4b","response":"...","durationMs":1234,"status":"SUCCESS","error":null}
```

The controller delegates to `GenerationService`, which validates input, checks the
configured model, measures elapsed time with a monotonic clock, and invokes the
`ModelProvider` contract. `OllamaProvider` uses Java's HTTP client with streaming
disabled and no automatic redirects. It requires a completed response with a string
`response` field. Prompts must contain non-whitespace text and be at most 32000 characters.

Invalid JSON/input and unknown models return HTTP 400 with `{ "code": "...", "message": "..." }`.
Provider failures return the generation envelope with `status: FAILED`, a null
response, elapsed duration, and an `error` object using the same code/message shape.
HTTP codes: timeout 504, connection failure/interruption 503, upstream rate limit
429, other upstream errors/invalid responses 502. Raw upstream error bodies and
exception messages are never returned. There are no retries or prompt logging.

Tests use fake providers and a local stub HTTP server, including success, invalid
input, upstream errors, malformed/incomplete responses, timeouts, and connection
failure. They do not require real models, cloud credentials, or paid services.
Real inference was also verified separately on November 5, 2026: the packaged
application called the installed local `qwen3:4b` through `/api/generate` and
returned `response: Hello`, `status: SUCCESS`, `error: null`, and `durationMs: 6540`.
The temporary application process was stopped afterward. No model was downloaded.

### Opt-in JSON generation

Use `POST /api/generate/json` with a configured, installed Ollama model when
the response must be one JSON object with exact top-level string fields.

```powershell
$body = @{
  model = 'qwen3:4b'
  prompt = 'Return a JSON object with the string field label set to ready.'
  requiredStringFields = @('label')
} | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:8080/api/generate/json -Method Post -ContentType 'application/json' -Body $body
```

Supply 1 to 16 distinct names matching `[A-Za-z_][A-Za-z0-9_]{0,63}`.
ModelHub checks the exact installed identifier in Ollama's model inventory and
rejects entries marked as remote. It sends a schema to Ollama and checks the
returned text independently. Missing or extra fields, non-string values,
duplicate keys, malformed JSON, Markdown fences, and trailing text produce
HTTP 502 with `OUTPUT_FORMAT_ERROR`. The response and execution history retain
the original text, including formatting errors. Provider failures retain their
existing error codes. A success means the JSON matched the requested shape.
The caller still decides whether the values are correct.

On Ollama 0.40.15, one short schema probe each passed for the installed
`qwen3:4b` and Qwen2.5 Coder 3B and 7B Q4_K_M variants. This checks basic
compatibility with the format parameter. [Ollama generation API](https://docs.ollama.com/api/generate)

The new endpoint accepts Ollama models only and uses the inventory reported by
the configured Ollama server. Ollama's own local-only setting can disable its
cloud features at the server. [Ollama cloud configuration](https://docs.ollama.com/faq#how-do-i-disable-ollamas-cloud-features)

OpenRouter advertises schema output for compatible models and providers. The
configured `openrouter/free` identifier selects among free models, so it does
not establish support for a fixed model. Cloud schema generation was not
tested. [Structured outputs](https://openrouter.ai/docs/guides/features/structured-outputs) · [Free model router](https://openrouter.ai/openrouter/free)

Standard generation, workflows, and benchmark grading continue to use their
existing contracts. The JSON endpoint stores the original response and creates
no transformed version. It does not accept arbitrary schemas or raw code.

## Optional OpenRouter provider

OpenRouter is disabled by default. Configure it through environment variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `OPENROUTER_ENABLED` | `false` | Register the cloud model when true |
| `OPENROUTER_MODEL` | empty | Free model ID selected from the current OpenRouter catalog |
| `OPENROUTER_API_KEY` | empty | Secret API key; never put it in source or request JSON |
| `OPENROUTER_TIMEOUT` | `120s` | Positive connection and request timeout |
| `OPENROUTER_ENDPOINT` | `https://openrouter.ai/api/v1/chat/completions` | Trusted endpoint; requires HTTPS except loopback test servers |

Choose a currently available `vendor/model:free` ID, or `openrouter/free`.
The application rejects paid model IDs and supplies no fallback model list.
Availability is not hardcoded or checked at startup. A missing API key produces
HTTP 503 / `CONFIGURATION_ERROR` without a network call. OpenRouter HTTP errors
including authentication failures use sanitized `PROVIDER_ERROR`; rate limits
use `RATE_LIMITED`. Other errors use the same generation envelope and HTTP codes
as Ollama. Redirects are not followed, and credentials are not returned or logged.

After configuring the environment and restarting the app:

```powershell
Invoke-RestMethod http://localhost:8080/api/models
$body = @{ model = $env:OPENROUTER_MODEL; prompt = 'Explain REST in one sentence.' } | ConvertTo-Json
Invoke-RestMethod http://localhost:8080/api/generate -Method Post -ContentType 'application/json' -Body $body
```

`GET /api/models` lists configured identifiers and provider names only. It is a
configuration listing, not a live availability check. Disabled providers are omitted.
`ModelRegistry` resolves the selected ID to a `ModelProvider`; controllers contain
no provider-specific routing. Existing Ollama requests keep the same format.

OpenRouter tests use loopback HTTP stubs and fake keys. They cover authenticated
chat payloads, provider selection, public model listing, paid-model rejection,
missing credentials, rate limits, upstream failures, invalid responses, timeouts,
and connection failures. Separate real-service acceptance checks also passed for
OpenRouter, Ollama comparison, and saved history; see the validation record.

Reference: [OpenRouter chat API](https://openrouter.ai/docs/api/api-reference/chat/create-a-chat-completion)
and [free variants](https://openrouter.ai/docs/guides/routing/model-variants/free).

## Model comparison

`POST /api/compare` accepts exactly two distinct configured model identifiers and
one shared prompt. With the current registry, enable OpenRouter to configure a
second model. Use `GET /api/models` to inspect your configured selections.

```powershell
$body = @{ models = @('qwen3:4b', $env:OPENROUTER_MODEL); prompt = 'Explain REST in one sentence.' } | ConvertTo-Json
Invoke-RestMethod http://localhost:8080/api/compare -Method Post -ContentType 'application/json' -Body $body
```

Example partial failure response (durations vary):

```json
{
  "results": [
    {"model":"qwen3:4b","response":"REST is ...","durationMs":1100,"status":"SUCCESS","error":null},
    {"model":"vendor/model:free","response":null,"durationMs":120000,"status":"FAILED","error":{"code":"TIMEOUT","message":"OpenRouter request timed out"}}
  ]
}
```

`ComparisonService` validates both selections and the prompt before any provider
call. It starts two tasks on Java 21 virtual threads and reuses `GenerationService`
for individual execution, timing, and errors. Each provider retains its configured
timeout. Results are returned in selection order, regardless of completion order.
Provider failures, including unexpected runtime exceptions, become independent
failed results and never discard the other model's result. No winner is selected.

HTTP 200 means the comparison completed, including when one or both executions
failed; inspect each result's `status` and `error`. Invalid requests and unknown
models return HTTP 400 before execution. `/api/generate` retains its individual
HTTP failure codes. Both endpoints use the same prompt length limit.

Concurrency tests use a latch that requires both fake providers to enter before
either can finish. Other tests cover failures/timeouts in either position, both
failures, input rejection before provider calls, result ordering, and real HTTP
requests against local Ollama/OpenRouter stubs. Real two-provider comparison was
subsequently verified separately during release acceptance.

## Two-model proposal and review

`POST /api/workflows/proposal-review` accepts a prompt and two distinct model
IDs from `GET /api/models`. To run both steps locally, set `OLLAMA_MODEL` and
`OLLAMA_SECONDARY_MODEL` to two different models already installed in Ollama.
Set these in `.env` for Docker Compose or in the backend process environment
for a direct launch, then restart the backend. The second local model is
available for explicit selection. Automatic routing keeps its existing
candidate set.

```powershell
$body = @{
  proposerModel = 'qwen2.5-coder:3b-instruct-q4_K_M'
  reviewerModel = 'qwen2.5-coder:7b-instruct-q4_K_M'
  prompt = 'Propose a Java API for an in-memory cache with bounded size.'
} | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:8080/api/workflows/proposal-review -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 300
```

The reviewer receives the original task and proposal. Its response is feedback.
Both steps return a model ID, response, `requestId`, `durationMs`, `status`,
and `error`. HTTP 200 returns the outcomes of a valid request, including step
failures. Invalid inputs return HTTP 400 before either model runs. A failed
review still returns the proposal. A failed or blank proposal, or a combined
review prompt over 32,000 characters, yields a `SKIPPED` review with a reason
and no execution ID. A history write failure retains any generated response
and reports `HISTORY_UNAVAILABLE` for that step.

Each attempted step is saved independently in [execution history](#execution-history)
using its execution ID. The API response groups the two roles for the caller.
The workflow only exchanges text and makes at most two sequential generation
calls. It has no tools for running code or acting on feedback.

Cloud steps require OpenRouter to be configured and `allowCloud: true` in the
request. Only a fixed `:free` model on the official endpoint is eligible. The
workflow applies zero-price limits and disables provider fallback. Selecting a
cloud reviewer sends the original prompt and the proposal to OpenRouter.

[Objective evaluation plan](docs/proposal-review-evaluation.md)

### Opt-in defect ID output

For defect-identification tasks, send the same request body to
`POST /api/workflows/proposal-review/defect-ids`. Select two configured,
installed Ollama models and describe the candidate IDs in the prompt. Both
steps receive an Ollama schema for a JSON object containing only `defectIds`,
an array of unique strings. An empty array is valid. The API checks shape
and uniqueness. Whether the IDs identify real defects depends on the task.

A rejected step retains its original response and reports
`OUTPUT_FORMAT_ERROR`. A rejected proposal skips review, and a rejected
review leaves the proposal available. The structured route uses the same
task prompt and review template as the standard route, with no extraction or
retry.

[Structured-output comparison protocol](docs/proposal-review-evaluation.md#structured-output-comparison-v1)

## Execution history

Provide a dedicated PostgreSQL database and role with schema creation/migration
permissions. For example, an administrator can run the following using `psql`:

```sql
CREATE ROLE modelhub LOGIN;
\password modelhub
CREATE DATABASE modelhub OWNER modelhub;
```

Configure the application's process environment before running it:

| Variable | Default | Purpose |
| --- | --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/modelhub` | JDBC connection URL |
| `DATABASE_USER` | `modelhub` | Database login |
| `DATABASE_PASSWORD` | empty | Set to your database role's password |

Set the password through your environment/secret manager, not source files or
committed `.env` files. The application does not automatically load `.env` files.
Flyway owns the schema through `src/main/resources/db/migration`; no automatic
ORM schema updates are used. The repository uses Spring JDBC parameterized SQL,
an immutable `ExecutionRun` record, and a short transaction for pagination.
No database transaction stays open while an AI provider is running.

Every accepted generation and each comparison execution saves its UUID request ID,
model, prompt, response, provider duration in milliseconds, status, sanitized error,
and UTC completion timestamp. `requestId` is also returned in each generation
result (including comparison results), allowing lookup in history. Invalid inputs
are rejected before execution and are not recorded. Comparison results have
independent IDs and are saved independently. No provider credentials or raw
upstream errors are included in history records.

```powershell
Invoke-RestMethod 'http://localhost:8080/api/runs?page=0&size=20'
```

Response: `content` contains execution records with `requestId`, `model`, `prompt`,
`response`, `durationMs`, `status`, `error`, and `timestamp`. Pagination metadata is
`page`, `size`, `totalElements`, and `totalPages`. Page numbers start at zero;
default size is 20 and the allowed range is 1–100. Invalid parameters return HTTP
400. Pages past the end return empty `content`. Ordering is newest first, with
UUID as a stable tie-breaker. Count and content share a repeatable-read snapshot;
new records between separate page requests may still shift offset pagination.

The application fails startup if the database/migrations are unavailable. For a
database outage after startup, history reads return sanitized HTTP 503 errors.
If saving an execution fails, generation returns HTTP 503 with `HISTORY_UNAVAILABLE`,
`status: FAILED`, and preserves the generated text, request ID, and provider duration.
Comparison still returns both independent results with HTTP 200. Failed saves
are not queued or retried and do not appear in history; the returned persistence
error takes precedence over any provider error for that result.

The API currently has no authentication. History contains the prompts and model
responses submitted by callers; use this MVP in a trusted local environment.

The database integration test exercises real PostgreSQL, automatic migrations,
successful and failed execution storage, partial comparison persistence, pagination,
request-ID correlation, restart durability, and simulated write failure. AI responses
remain stubbed; real cloud calls are not part of this test.

## Local Web UI on Windows 11

Use PowerShell, Docker Desktop in Linux-container mode, Ollama for Windows,
and Node.js 22.12 or newer with npm. Run the following from the repository
root. Check that Ollama is running and choose a model from `ollama list`.
If Ollama is stopped, run `ollama serve` in a separate PowerShell window.

```powershell
ollama list
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
notepad .env
```

In `.env`, set a unique `DATABASE_PASSWORD` for a new database, or use the
existing password for an initialized Compose volume. Set `OLLAMA_MODEL`
to the exact installed model ID and keep `APP_PORT=8080` for the UI proxy.
For local-only inference set `OPENROUTER_ENABLED=false`. Save the file and run:

```powershell
docker compose up -d --build --wait
Invoke-RestMethod http://127.0.0.1:8080/api/health
Set-Location web
npm ci
npm run dev
```

Open [ModelHub](http://127.0.0.1:5173/) in your browser. Choose a model in
**Chat**, enter a prompt, and select **Send prompt**. Responses show execution
status and backend-measured latency. Open **History** to browse saved
executions. **Compare** requires two distinct configured models. If you
enable OpenRouter for the second model, keep its key in the root `.env`
and restart the backend with `docker compose up -d --wait` from the
repository root. Selecting that model sends your prompt to OpenRouter.

The UI listens on `127.0.0.1:5173` and proxies the chat, compare, history,
model, and health API requests to the backend at `127.0.0.1:8080`. Keep
both services on loopback. When using a host backend instead of Compose,
start it with `SERVER_ADDRESS=127.0.0.1` and `SERVER_PORT=8080`.

If the UI says **Backend offline**, check `docker compose ps` and refresh
the connection in the UI. If the backend is online but Ollama is unreachable,
check the configured `OLLAMA_BASE_URL` from Docker as described below.
Stop Vite with Ctrl+C and run `docker compose stop` from the repository root
when finished. The database volume retains your history.

To verify the frontend from the `web` directory:

```powershell
npm test
npm run build
```

## Docker Compose quick start

Docker Desktop must be running Linux containers. You do not need a host JDK,
Maven, or PostgreSQL for this path. Ollama runs separately on the host.

```powershell
Copy-Item .env.example .env
# Edit .env and set DATABASE_PASSWORD to a unique local password.
docker compose up --build --wait
Invoke-RestMethod http://localhost:8080/api/health
Invoke-RestMethod http://localhost:8080/api/models
```

Compose reads `.env` and passes the selected values to the containers. It refuses
startup with an empty database password. PostgreSQL is private to the Compose
network, and the HTTP port is published only on host loopback. The app runs as a
non-root user, waits for the database health check, then applies Flyway migrations.
`APP_PORT` changes the published host port; the container always listens on 8080.

The default Ollama URL is `http://host.docker.internal:11434`, with a host-gateway
mapping for Linux. Verify that Ollama is reachable from Docker. If it binds only
to loopback and your Docker host cannot forward to it, configure Ollama to listen
on a host interface reachable from Docker, restrict access with your host firewall,
and set `OLLAMA_BASE_URL` accordingly. The application does not install Ollama or
download models automatically. For local generation, install `qwen3:4b` separately.

Useful commands:

```shell
docker compose ps
docker compose logs --tail=100 app
docker compose stop
docker compose start
docker compose down
```

`down` removes containers and the network but preserves the named database volume.
Do not add `--volumes` unless you intend to erase that database. A changed database
password in `.env` does not change the role password in an already initialized
volume; update the existing role through PostgreSQL before changing the app login.
For a host-Java setup, use the earlier database instructions and set process
environment variables yourself; `.env` is only read by Compose.

## CI and examples

`.github/workflows/ci.yml` runs on pushes, pull requests, and manual dispatch. It
uses Java 21 on Ubuntu, runs `mvn clean verify` (including real PostgreSQL
Testcontainers tests), builds the Docker image, validates Compose, and uploads
test reports. Official actions are pinned to commit hashes and repository
permissions are read-only. No cloud key is needed; no deployment or image push is
configured. The initial pushed milestone commit passed GitHub CI; see the
validation record for the exact tested commit and run.

`examples/requests.http` contains health, model listing, local/cloud generation,
comparison, history, and invalid-input requests. Replace its cloud placeholder
with your configured ID. The PowerShell examples above work without an editor
HTTP extension.

## Reliability and scope

Provider calls have explicit connection/request timeouts and sanitized errors.
Cloud model selection is restricted to free IDs.
The opt-in Java coding benchmark compiles and runs submissions in restricted
Docker containers. [Coding benchmark setup](docs/benchmarks.md#isolated-java-coding-benchmark)
PostgreSQL connection, socket, and query timeouts bound common database outages.
Expected HTTP errors preserve 400/404/405/415 status codes and the code/message
error shape. The application health endpoint is liveness only; database migration
must succeed at startup, but a later database outage does not change that endpoint.

This MVP is for trusted local use. Authentication, authorization, inbound rate
limits, global concurrency limits, retention/deletion policies, backups, and TLS
termination are outside the implemented scope. Model prompts and answers are
stored as submitted. Never include secrets in prompts. Do not expose the service
publicly without access controls. No model quality ranking is performed.

All six implementation milestones are present. Use `docs/validation.md` to see
what was verified locally and what still needs real-service or GitHub validation.

## Objective benchmarks

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/api/benchmarks/dataset` | Inspect the bundled ten-task dataset and rules |
| POST | `/api/benchmarks` | Execute the dataset against one or two fixed configured models |
| GET | `/api/benchmarks/{id}` | Retrieve a saved report |

```powershell
$body = @{ datasetId = 'basics'; models = @('qwen3:4b') } | ConvertTo-Json
$report = Invoke-RestMethod http://localhost:8080/api/benchmarks -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 1800
$report.summaries | Format-Table
$report | ConvertTo-Json -Depth 30 | Set-Content benchmark-report.json
```

One local model is enough to start. To compare providers, configure a specific
available OpenRouter `vendor/model:free` ID and include it as the second model.
`openrouter/free` is rejected for benchmarks because it can change the selected
model across calls. Do not change your key or enable paid models for this feature.
Automated benchmark tests use simulated AI responses and real PostgreSQL. A
separate [real local baseline](benchmarks/baselines/2026-11-05-qwen3-4b/README.md)
completed all ten executions: nine scoring passes and one literal-string mismatch.
