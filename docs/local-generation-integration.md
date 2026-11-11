# Local generation integration contract v1

This contract describes ModelHub's local HTTP interface for trusted native clients,
including a possible future ALFHA Agent adapter. The two applications remain
independently installed, configured, and operated. The contract revision is
independent of the ModelHub release and routing policy versions. Paths use the
existing `/api` convention. Clients should tolerate added JSON fields and error
codes, and treat a missing endpoint as an unsupported capability. This revision
requires a ModelHub build containing the explicit local endpoint added in
Milestone 13. The published v0.4.0 build already supports local routed generation.

## Setup and discovery

Use `http://127.0.0.1:8080` by default. The Windows launcher uses Docker Compose
and publishes only `127.0.0.1:${APP_PORT:-8080}`. The packaged image listens on
its container interfaces so Docker can reach it. Use the supplied loopback
port mapping for host access. Direct Spring Boot startup defaults to loopback,
with an optional `SERVER_ADDRESS` override. Keep OpenRouter disabled
(`OPENROUTER_ENABLED=false`) for an integration-only deployment.

Configure the trusted Ollama origin and exact installed tag with
`OLLAMA_BASE_URL` and `OLLAMA_MODEL`. Compose defaults to
`http://host.docker.internal:11434`. Direct startup defaults to
`http://localhost:11434`. An optional second installed tag can be configured
with `OLLAMA_SECONDARY_MODEL`. Ollama must be reachable from the backend.
ModelHub does not install models.

| Method and path | Client use |
| --- | --- |
| `GET /api/health` | Check HTTP reachability. Expect `{"status":"UP","application":"ALFHA ModelHub"}`. |
| `GET /api/models` | List configured entries like `{"model":"qwen3:4b","provider":"ollama"}`. Filter for `ollama`. The registry does not probe Ollama availability. |
| `POST /api/generate/local` | Generate with one explicit configured, installed Ollama model. |
| `POST /api/routing/generate-local` | Select a model and generate locally in one operation. Enable both `MODELHUB_ROUTING_PREVIEW_ENABLED` and `MODELHUB_ROUTING_GENERATION_ENABLED`. |
| `GET /api/routing/preview?profile=basic` | Optional operator diagnostic. It uses preview policy, which may include cloud candidates or check pricing when configured to do so. Use the generation response as the record of the actual decision. |

Set `Content-Type: application/json` for POST requests. A prompt must be
nonblank and at most 32,000 Java UTF-16 code units. Model identifiers are exact
and case-sensitive. The client supplies neither a provider URL nor credentials.

## Explicit generation

POST `/api/generate/local` with this body.

```json
{"model":"qwen3:4b","prompt":"Explain what a primary key does in one sentence."}
```

A successful response has HTTP 200 and this shape.

```json
{
  "requestId": "bb4f28c9-86d3-40b2-984d-e0f19355e67c",
  "model": "qwen3:4b",
  "response": "A primary key uniquely identifies each row in a table.",
  "durationMs": 841,
  "status": "SUCCESS",
  "error": null
}
```

The local endpoint requires the registered Ollama provider, checks
`GET /api/tags` for exactly one matching installed tag without
`remote_host` or `remote_model` values, then calls Ollama's
`POST /api/generate` with streaming disabled. The existing
`POST /api/generate` remains available to general ModelHub clients and
follows its existing provider and availability rules.

## Local routed generation

POST `/api/routing/generate-local` with this body.

```json
{"profile":"basic","prompt":"Explain what a primary key does in one sentence."}
```

Example HTTP 200 response with a subset of the decision fields shown.

```json
{
  "decision": {
    "profile": "basic",
    "policyVersion": "basic-local-dispatch-v2",
    "outcome": "FALLBACK_LOCAL",
    "selectedModel": "qwen3:4b",
    "reason": "CUTOFF_REQUIRED",
    "reportIds": [],
    "exclusions": []
  },
  "generation": {
    "requestId": "8097e70b-48dc-42be-bc62-c03998d81c3d",
    "model": "qwen3:4b",
    "response": "A primary key uniquely identifies each row in a table.",
    "durationMs": 841,
    "status": "SUCCESS",
    "error": null
  }
}
```

`EVIDENCE_SELECTED` means the selection used qualifying evidence.
`FALLBACK_LOCAL` selects the configured primary when the evidence is missing,
insufficient, expired, or otherwise unusable. Read `decision.reason` and
`decision.exclusions` for details. A fallback decision can still have a failed
generation if Ollama or the selected model is unavailable. If a decision has no
route, the API returns HTTP 503 with `generation: null`. The profile is
currently `basic`. Routing policy and evidence configuration are documented
in [Routing design](routing-design.md). Treat the policy version and decision
reason as data, since later policy versions may add outcomes or reasons.

## Errors, deadlines, and fallback

| HTTP status | Expected handling |
| --- | --- |
| 400 | A top-level error such as `INVALID_INPUT` or `UNKNOWN_MODEL`. Correct the request or refresh configured models. |
| 404 | The endpoint is unavailable in the running build or its feature flags are off. Check configuration or use a supported local mode. |
| 429 | `RATE_LIMITED`. Honor `retryAfterMs` if present and apply a bounded retry policy. |
| 502 | Provider error or invalid response. Messages omit raw upstream diagnostics. |
| 503 | Connection failure, an unavailable installed model, or unavailable history, among other cases. Read the error code and check whether the model may already have responded. |
| 504 | The Ollama request timed out. Treat completion as uncertain. |

Pre-dispatch validation errors use `{"code":"...","message":"..."}` with no
execution ID. A failed execution uses the same generation envelope as success
with `status: "FAILED"` and an `error` object. The routed envelope can
preserve the decision when generation fails. For example, an installed-model
check may fail with `generation.error.code: "CONFIGURATION_ERROR"`.
`requestId` identifies the generation attempt and its saved history record
when persistence succeeds. A history failure can return generated text with
`HISTORY_UNAVAILABLE`. A client should check status before using that text.
`durationMs` measures the generation attempt, including inventory lookup,
and excludes routing evaluation, persistence, and the HTTP round trip.

Use a short connection and health-check deadline, and a separate overall
generation deadline. `OLLAMA_TIMEOUT` defaults to `120s` and applies
individually to the inventory and inference requests. A caller wanting to
allow both full deadlines should budget more than twice the configured
Ollama timeout and allow extra time for history and, when routing, evidence
queries. There is no end-to-end server deadline or client idempotency key in
this contract. A client timeout or disconnect can leave ModelHub working and
recording the original request.

A future native adapter can check health at startup, pick an explicitly
configured model or opt into routing, and read the actual model from the
returned generation envelope. If ModelHub cannot be reached before dispatch,
it can continue via its own trusted local Ollama client. Keep that fallback
visible to the caller. For failures after dispatch, especially timeouts and
history errors, let the caller decide whether to incur a second generation.
A direct Ollama fallback has no ModelHub decision or ModelHub execution ID.

## Security and operating limits

- The API has no authentication or per-client authorization. Localhost limits
  network exposure and still allows other local processes to reach it. Use a
  trusted single-user host. This contract does not support public exposure,
  port forwarding, shared hosts, or cross-origin browser access. Require a
  separate authenticated design before expanding that boundary.
- ModelHub receives prompt text and normally stores prompts, responses, and
  execution metadata in its own PostgreSQL history. Submit only data suitable
  for that storage. The client needs only HTTP access. Keep ModelHub database
  credentials and the Docker socket out of the client.
- These endpoints call generation services. Treat all returned model text as
  untrusted data, even when it resembles tool calls or commands. They do not
  run benchmarks, execute code, accept file paths or mounts as structured
  inputs, or operate containers. Agent file, tool, and Docker permissions
  stay entirely on the Agent side. Keep ModelHub free of Agent workspace
  mounts and container-management credentials.
- Both local generation paths reject cloud providers and Ollama inventory
  entries marked as remote. They trust the configured Ollama server and its
  inventory. The check cannot attest where a modified server runs inference
  or prevent a tag from changing between lookup and generation. Use a trusted
  local Ollama daemon with local models.

## Milestone 13 verification

- `mvn --batch-mode --no-transfer-progress clean verify` passed 62 unit and
  HTTP tests and two disposable PostgreSQL integration tests. Model calls
  used test fixtures.
- From `web`, `npm test` passed 21 tests and `npm run build` passed.
- `docker build -t alfha-modelhub:milestone13 .` and
  `docker compose config --quiet` passed. The image build ran the unit and
  HTTP tests on Java 21.
- `scripts/test-modelhub-launcher.ps1` passed against a disposable Compose
  project. It verified the packaged endpoint's 503 response with Ollama
  unreachable, loopback-only publishing, the absence of host mounts, and
  that the launcher left an unrelated fixture container running.
