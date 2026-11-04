# ALFHA ModelHub

Java backend for a future multi-model AI orchestration and evaluation platform.
This repository is independent of ALFHA Agent.

## Current scope: Milestone 3

Implemented: Spring Boot application, JSON health endpoint, Java 21 Maven build,
Ollama and optional OpenRouter providers, model listing, generation endpoint, and automated tests. Comparison, PostgreSQL,
Docker Compose, and CI belong to later milestones and are not implemented yet.

## Prerequisites

- JDK 21 (`JAVA_HOME` must point to the JDK directory).
- Apache Maven 3.6.3 or newer (verified locally with 3.9.12).
- Internet access for the initial Maven dependency download.

No database, Ollama instance, cloud account, or API key is needed for Milestone 1.
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

The JUnit 5 test starts the real Spring application on a random port, sends an
HTTP request, verifies HTTP 200, JSON content type, and the exact response fields,
then closes the application. It uses no mocks or external services.
JUnit 5 is explicitly selected to meet the project requirements. Because Spring
Framework 7's JUnit extension requires JUnit 6, the test manages the application
context directly rather than using `@SpringBootTest`.
Reports are written to `target/surefire-reports`.

## Run

```shell
mvn spring-boot:run
```

Or run the packaged application after building:

```shell
java -jar target/modelhub-0.0.1-SNAPSHOT.jar
```

If you use the local Maven cache, include `"-Dmaven.repo.local=.m2/repository"`
in the Maven run command as well.

The default port is 8080. Override it with `SERVER_PORT`, for example in PowerShell:

```powershell
$env:SERVER_PORT = '8081'
java -jar target/modelhub-0.0.1-SNAPSHOT.jar
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
and connection failures. No real OpenRouter call has been made, so real cloud
integration remains unverified. Local Ollama was verified in Milestone 2 as above.

Reference: [OpenRouter chat API](https://openrouter.ai/docs/api/api-reference/chat/create-a-chat-completion)
and [free variants](https://openrouter.ai/docs/guides/routing/model-variants/free).

Next: Milestone 4 — concurrent model comparison with independent results and
partial failure handling, without automatic ranking.
