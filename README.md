# Simple Chatbot

A minimal web chatbot: a Spring Boot (Java 25) backend that proxies chat
requests to an OpenAI-compatible `/chat/completions` endpoint, and a plain
HTML/CSS/JS frontend that talks to it asynchronously (no full page reloads).

```
Browser  --fetch()-->  POST /api/chat  --RestClient-->  OpenAI-compatible API
   ^                         |
   |____ JSON { reply } _____|
```

## Project layout

```
src/main/java/com/example/chatbot/
  controller/   ChatController (POST /api/chat), HealthController (GET /api/health)
  service/      OpenAiService - builds the upstream request, maps upstream errors
  config/       OpenAiProperties (env-var backed config), RestClientConfig
  dto/          Request/response records for our API and for the OpenAI API
  exception/    UpstreamException + a @RestControllerAdvice that turns every
                failure into a uniform { error, message, timestamp } JSON body
src/main/resources/static/   index.html, app.js, style.css (served by Spring)
```

## Environment variables

| Variable               | Required | Default                        | Description                                             |
|-------------------------|:--------:|---------------------------------|-----------------------------------------------------------|
| `OPENAI_API_KEY`        | Yes*     | *(empty)*                       | API key sent as `Authorization: Bearer ...`               |
| `OPENAI_BASE_URL`       | No       | `https://api.openai.com/v1`     | Base URL of the chat completions API (no trailing path)   |
| `OPENAI_MODEL`          | No       | `gpt-4o-mini`                   | Model name passed in the request body                     |
| `OPENAI_CONNECT_TIMEOUT`| No       | `10s`                           | Connect timeout, Spring duration syntax (e.g. `10s`)      |
| `OPENAI_READ_TIMEOUT`   | No       | `60s`                           | Read timeout for the upstream call                        |
| `PORT`                  | No       | `8080`                          | Port the server listens on                                 |

\* If `OPENAI_API_KEY` is unset, the server still starts (so you can look at
the UI / hit `/api/health`), but `/api/chat` returns a `503` with a clear
message instead of crashing the app.

No secrets are hardcoded or committed anywhere in this repo. `.env` is
git-ignored; `.env.example` shows the shape.

## Running locally (no Docker)

Requires JDK 25.

```bash
export OPENAI_API_KEY=sk-...
export OPENAI_BASE_URL=https://api.openai.com/v1   # optional
export OPENAI_MODEL=gpt-4o-mini                     # optional

./mvnw spring-boot:run
```

Then open http://localhost:8080.

Run tests:

```bash
./mvnw test
```

## Running with Docker

Build and run directly:

```bash
docker build -t chatbot:local .
docker run --rm -p 8080:8080 \
  -e OPENAI_API_KEY=sk-... \
  -e OPENAI_BASE_URL=https://api.openai.com/v1 \
  -e OPENAI_MODEL=gpt-4o-mini \
  chatbot:local
```

Or with docker-compose (reads `.env`):

```bash
cp .env.example .env    # fill in OPENAI_API_KEY
docker compose up --build
```

Either way, open http://localhost:8080.

### Using a pre-built image from GitHub Container Registry

CI publishes images to `ghcr.io/<owner>/<repo>` on every push to `main`
(tagged `latest` and with the short commit SHA) and on version tags like
`v1.0.0`.

```bash
docker run --rm -p 8080:8080 \
  -e OPENAI_API_KEY=sk-... \
  ghcr.io/<owner>/<repo>:latest
```

## Pointing at a different OpenAI-compatible endpoint (e.g. a local model)

The backend only assumes the upstream implements the standard
`POST {OPENAI_BASE_URL}/chat/completions` shape (OpenAI's format). To point
it at something else — [LM Studio](https://lmstudio.ai), Ollama's OpenAI
compatibility layer, vLLM, Azure OpenAI's OpenAI-compatible route, etc. —
just change the environment variables, no code changes needed:

```bash
# Example: LM Studio running locally
export OPENAI_BASE_URL=http://localhost:1234/v1
export OPENAI_API_KEY=not-needed        # LM Studio ignores the key but the header is still sent
export OPENAI_MODEL=<model-name-as-loaded-in-lm-studio>
```

If you're running the app in Docker and the model server is on your host
machine, use `http://host.docker.internal:1234/v1` as the base URL instead
of `localhost` (or run both via `docker compose` on the same network).

## API

### `POST /api/chat`

Request:
```json
{
  "message": "What's the capital of France?",
  "history": [
    { "role": "user", "content": "Hi" },
    { "role": "assistant", "content": "Hello! How can I help?" }
  ]
}
```
`history` is optional; the frontend sends back everything it has rendered
so far and the backend stays stateless (no session/DB).

Success response (`200`):
```json
{ "reply": "Paris.", "model": "gpt-4o-mini" }
```

Error response shape (used for every failure — validation, timeouts, rate
limiting, bad key, unexpected errors):
```json
{ "error": "upstream_error", "message": "The upstream API is rate-limiting requests. Please try again shortly.", "timestamp": "2026-07-30T10:00:00Z" }
```
Status codes: `400` invalid request/bad upstream request, `429` upstream
rate limit, `502` upstream error or malformed response, `503` misconfigured
(no API key), `504` upstream timeout, `500` anything unexpected.

### `GET /api/health`
Trivial liveness check used by the Docker `HEALTHCHECK`.

## Assumptions & trade-offs

- **Stateless backend, client-owned history.** No database/session store —
  the frontend replays the conversation on every request. Simple and fine
  for a demo; a real app would move history server-side (and probably add
  auth) once conversations need to survive a page refresh or be shared
  across devices.
- **Single container.** Spring Boot serves the static frontend directly
  from `src/main/resources/static`, so there's one JAR, one Dockerfile, one
  process. A docker-compose file is included anyway as a convenience for
  local dev with `.env`, but it's not required to run the app.
- **Java 25 / Spring Boot 3.5.0.** Matches the "Java 25" requirement in the
  brief. If a given Spring Boot patch version hasn't yet certified against a
  very recently released JDK, bump `spring-boot-starter-parent` to the
  latest 3.5.x line — no other code changes are needed.
- **No streaming.** Responses are returned as a single JSON blob rather than
  streamed token-by-token (SSE). Kept the API/UI simpler; the loading state
  ("Thinking...") covers the wait. Streaming would be the natural next
  iteration.
- **Error messages are sanitized.** The backend never forwards the raw
  upstream response body to the browser (it could leak implementation
  details); it maps known cases (401, 429, timeout, empty body) to specific
  messages and falls back to a generic one otherwise.
- **CI publishes to GitHub Container Registry**, not Docker Hub, since it
  needs no extra secrets — `GITHUB_TOKEN` is enough. Swap the login step if
  you'd rather push to Docker Hub or another registry.
- **No real API key required to submit.** The integration code is real and
  functional; testing it against a real key (or a local LM Studio server)
  is left to whoever runs it, per the brief.

## Manual testing without an API key

Run the app, open the UI, and send a message: with `OPENAI_API_KEY` unset
you should see a friendly `503` error rendered in the red banner, not a
crash or a blank screen. Setting `OPENAI_BASE_URL`/`OPENAI_API_KEY` to a
local LM Studio instance (or a real OpenAI key) lets you exercise the full
happy path.
