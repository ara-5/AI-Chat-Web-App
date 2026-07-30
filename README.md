# AI Chat Web App

A web-based chatbot built with **Java 25 + Spring Boot 3.5** on the backend and plain **HTML/CSS/JS** on the frontend. The backend proxies chat requests to any OpenAI-compatible `/chat/completions` endpoint and streams the response token-by-token to the browser.

```
Browser ──fetch (POST)──► /api/chat/stream ──RestClient──► OpenAI-compatible API
   ▲                             │  (SSE stream)                    │
   └──── SSE token events ───────┘◄─────── text/event-stream ───────┘
```

---

## Features

- **Real-time streaming** — responses appear token-by-token via Server-Sent Events (SSE); no waiting for the full reply
- **Conversation history** — the client sends the full conversation context with each request; the backend stays stateless
- **Markdown rendering** — assistant replies are rendered as formatted markdown (code blocks, lists, bold, etc.) via `marked.js` + `DOMPurify`
- **Graceful error handling** — specific messages for invalid API key (401), rate limits (429), timeouts, missing configuration, and unexpected errors
- **Input validation** — all request fields are validated server-side; message capped at 8 000 characters
- **Non-streaming fallback** — `POST /api/chat` (JSON) is kept alongside the streaming endpoint for tooling and testing

---

## Project layout

```
src/main/java/com/example/chatbot/
  controller/   ChatController   POST /api/chat (JSON) + POST /api/chat/stream (SSE)
                HealthController GET /api/health (Docker healthcheck / liveness)
  service/      OpenAiService    builds upstream requests, maps all error paths,
                                 handles both blocking and streaming calls
  config/       OpenAiProperties env-var-backed @ConfigurationProperties record
                RestClientConfig builds the RestClient with configurable timeouts
  dto/          ChatRequest / ChatResponse          — our public API shapes
                Message                             — shared message record
                openai/OpenAiChatRequest            — upstream request body (stream flag)
                openai/OpenAiChatResponse           — non-streaming upstream response
                openai/OpenAiStreamChunk            — per-event SSE chunk from upstream
                openai/OpenAiErrorResponse          — upstream error body
                ErrorResponse                       — uniform error shape for all failures
  exception/    UpstreamException          — carries HTTP status + sanitised message
                GlobalExceptionHandler     — @RestControllerAdvice → uniform JSON errors

src/main/resources/
  application.yml          all config with env-var placeholders, no defaults for secrets
  static/
    index.html             shell — imports marked.js + DOMPurify (both CDN, pinned versions)
    style.css              dark theme, animated typing indicator, markdown content styles
    app.js                 SSE stream consumer, markdown render, history management

Dockerfile                 self-contained multi-stage build (local docker build .)
Dockerfile.ci              runtime-only image — used by CI with the pre-built JAR
docker-compose.yml         convenience wrapper reading from .env
.env.example               documents required env vars; .env is gitignored
.github/workflows/ci.yml   build → test → Docker image → push to ghcr.io
```

---

## Environment variables

| Variable | Required | Default | Description |
|---|:---:|---|---|
| `OPENAI_API_KEY` | Yes* | *(empty)* | Sent as `Authorization: Bearer …` to the upstream API |
| `OPENAI_BASE_URL` | No | `https://api.openai.com/v1` | Base URL of the completions API — no trailing path |
| `OPENAI_MODEL` | No | `gpt-4o-mini` | Model name passed in the request body |
| `OPENAI_CONNECT_TIMEOUT` | No | `10s` | Connection timeout (Spring duration syntax, e.g. `10s`) |
| `OPENAI_READ_TIMEOUT` | No | `60s` | Read timeout for the upstream streaming call |
| `PORT` | No | `8080` | Port the embedded Tomcat server listens on |

\* If `OPENAI_API_KEY` is unset the server still starts. `GET /api/health` returns `200 ok`. Any call to `/api/chat` or `/api/chat/stream` returns a `503` with a clear message rather than crashing.

> **Security**: No secrets are hardcoded or committed anywhere. `.env` is gitignored. `.env.example` contains only placeholder values.

---

## Running locally (no Docker)

Requires **JDK 25**.

```bash
# Clone
git clone https://github.com/ara-5/AI-Chat-Web-App.git
cd AI-Chat-Web-App

# Set config (at minimum the API key)
export OPENAI_API_KEY=sk-...
export OPENAI_BASE_URL=https://api.openai.com/v1   # optional
export OPENAI_MODEL=gpt-4o-mini                     # optional

# Run
./mvnw spring-boot:run
```

Open **http://localhost:8080**.

Run tests:

```bash
./mvnw test
```

---

## Running with Docker

### Option A — self-contained build from source

```bash
docker build -t chatbot:local .
docker run --rm -p 8080:8080 \
  -e OPENAI_API_KEY=sk-... \
  -e OPENAI_BASE_URL=https://api.openai.com/v1 \
  -e OPENAI_MODEL=gpt-4o-mini \
  chatbot:local
```

Open **http://localhost:8080**.

### Option B — docker-compose (reads `.env`)

```bash
cp .env.example .env   # fill in OPENAI_API_KEY
docker compose up --build
```

### Option C — pre-built image from GitHub Container Registry

CI publishes images to `ghcr.io/ara-5/ai-chat-web-app` on every push to `main`
(tagged `latest` + short commit SHA) and on version tags (`v1.0.0` etc.).

```bash
docker run --rm -p 8080:8080 \
  -e OPENAI_API_KEY=sk-... \
  ghcr.io/ara-5/ai-chat-web-app:latest
```

---

## Pointing at a different OpenAI-compatible endpoint

The backend only assumes the upstream implements `POST {OPENAI_BASE_URL}/chat/completions`
with OpenAI's request/response shape. Change the environment variables — no code changes needed.

```bash
# LM Studio (local model server)
export OPENAI_BASE_URL=http://localhost:1234/v1
export OPENAI_API_KEY=not-needed        # LM Studio ignores the key; header is still sent
export OPENAI_MODEL=<model-name-as-loaded-in-lm-studio>

# Ollama (OpenAI-compatible mode)
export OPENAI_BASE_URL=http://localhost:11434/v1
export OPENAI_API_KEY=ollama
export OPENAI_MODEL=llama3.2

# Azure OpenAI
export OPENAI_BASE_URL=https://<resource>.openai.azure.com/openai/deployments/<deployment>
export OPENAI_API_KEY=<azure-api-key>
export OPENAI_MODEL=<deployment-name>
```

> **Docker + local model**: if the model server is on the host machine, use
> `http://host.docker.internal:1234/v1` instead of `localhost` (or put both services
> on the same `docker compose` network).

---

## API reference

### `POST /api/chat` — non-streaming

Request:
```json
{
  "message": "What is the capital of France?",
  "history": [
    { "role": "user",      "content": "Hello" },
    { "role": "assistant", "content": "Hi! How can I help?" }
  ]
}
```
`history` is optional. `message` is required, max 8 000 characters. `role` must be one of `system`, `user`, `assistant`.

Success `200`:
```json
{ "reply": "Paris.", "model": "gpt-4o-mini" }
```

### `POST /api/chat/stream` — SSE streaming

Same request body as above. Returns `Content-Type: text/event-stream`.

Event types:

| Event name | Data | Meaning |
|---|---|---|
| `token` | one content delta (string) | append to the in-progress message |
| `done` | *(empty)* | stream complete; finalise the message |
| `error` | human-readable error string | upstream or config failure |

### Error response shape (all failures)

```json
{
  "error":     "upstream_error",
  "message":   "The upstream API is rate-limiting requests. Please try again shortly.",
  "timestamp": "2026-07-30T10:00:00Z"
}
```

HTTP status codes used: `400` invalid request, `429` upstream rate limit, `500` unexpected, `502` upstream error, `503` not configured, `504` upstream timeout.

### `GET /api/health`

```json
{ "status": "ok" }
```

Used by the Docker `HEALTHCHECK` and any load balancer liveness probe.

---

## CI/CD

The GitHub Actions workflow (`.github/workflows/ci.yml`) has two sequential jobs:

**`build-and-test`** (runs on every push and PR)
- Checks out the code
- Installs JDK 25 (Temurin) via `actions/setup-java` with Maven cache
- Runs `./mvnw clean verify` (compiles + all tests)
- Uploads `chatbot.jar` as a workflow artifact

**`docker`** (runs on push to `main` or version tags only, after `build-and-test`)
- Downloads the pre-built `chatbot.jar` artifact (avoids re-running Maven inside Docker)
- Logs in to GitHub Container Registry using `GITHUB_TOKEN` (no extra secrets needed)
- Extracts image tags: `latest`, short commit SHA, and semver tag if present
- Builds and pushes `Dockerfile.ci` (runtime-only — just `eclipse-temurin:25-jre` + the JAR)

**Why two Dockerfiles?**

| | `Dockerfile` | `Dockerfile.ci` |
|---|---|---|
| Use case | Local `docker build .` | CI pipeline |
| Builds Maven from source | Yes (multi-stage, JDK 25) | No — uses pre-built JAR |
| Image size | Larger (build layer discarded) | Minimal (JRE only) |
| Network requirements | Maven Central during build | None |

The Maven wrapper downloads Maven itself (~10 MB) from Maven Central inside the Docker layer. This is unreliable in CI Docker networking, so the CI job avoids it entirely by reusing the JAR already built and tested by the `build-and-test` job.

---

## Security

| Area | What's done |
|---|---|
| **No secrets in source** | `.env` is gitignored; `.env.example` has placeholders only; API key read exclusively from env vars via `@ConfigurationProperties` |
| **Input validation** | `@NotBlank`, `@Size(max=8000)` on `message`; `@Pattern(regexp="system\|user\|assistant")` on `role`; all validated via `jakarta.validation` before any upstream call |
| **Error sanitisation** | Backend never forwards the raw upstream response body to the browser — only maps known cases to specific messages; falls back to a generic string |
| **XSS prevention** | Markdown is rendered with `marked.js` then sanitised with `DOMPurify` before `innerHTML` injection; user messages always use `textContent` |
| **Non-root container** | Dockerfile creates a system user (`appuser`) and runs the JVM as that user |
| **No logging of secrets** | The API key is never logged; only sanitised error messages appear in logs |

---

## Testing without a real API key

**Option 1 — observe the 503 path:**
Start the app with `OPENAI_API_KEY` unset. Open the UI and send a message — you'll see the red error banner with a clear `503 Service Unavailable` message instead of a crash.

**Option 2 — LM Studio (free, local):**
1. Download [LM Studio](https://lmstudio.ai) and load any model
2. Start the local server (default: `http://localhost:1234`)
3. Run with:
```bash
export OPENAI_BASE_URL=http://localhost:1234/v1
export OPENAI_API_KEY=not-needed
export OPENAI_MODEL=<your-model>
./mvnw spring-boot:run
```

**Option 3 — run the unit tests (no key needed):**
```bash
./mvnw test
```
Tests use `okhttp3.mockwebserver.MockWebServer` to simulate the upstream API — no network access, no API key required.

---

## Assumptions & trade-offs

- **Stateless backend, client-owned history.** No database or session store — the frontend replays the conversation on every request. Simple and horizontally scalable for a demo; a production app would move history server-side and add authentication once conversations need to survive page refreshes or be shared across devices.

- **SSE streaming as the primary path.** The UI uses `POST /api/chat/stream` for all messages; the non-streaming `POST /api/chat` is preserved for backward compatibility and test coverage. Streaming makes long responses feel faster without any additional infrastructure.

- **Single container.** Spring Boot serves the static frontend directly from `src/main/resources/static/`, so there is one JAR, one process, one container. `docker-compose.yml` is included as a local-dev convenience but is not required.

- **Java 25 / Spring Boot 3.5.0.** Matches the challenge brief. Spring Boot 3.5 is the latest 3.x line at time of writing; if a newer patch is released, bumping `spring-boot-starter-parent` requires no other code changes.

- **No streaming to the upstream in the same HTTP request.** The frontend opens a new SSE connection per message. This is the idiomatic pattern for `fetch`-based SSE (the native `EventSource` API only supports GET).

- **CDN-hosted client libraries.** `marked.js` and `DOMPurify` are loaded from jsDelivr with pinned version numbers. In a production setup these would be bundled and served locally. The pinning ensures reproducible builds without a build step.

- **CI publishes to GitHub Container Registry** rather than Docker Hub because it requires no extra secrets — `GITHUB_TOKEN` is sufficient and is automatically available to all Actions workflows.

- **No real API key required to submit.** The integration code is real and functional; testing it against a real endpoint (or a local LM Studio instance) is left to whoever clones and runs it, per the brief.
