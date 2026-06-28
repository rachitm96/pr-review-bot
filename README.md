Presentation: [Presentation_Rachit_Mathur.pptx](https://github.com/user-attachments/files/29443872/Presentation_Rachit_Mathur.pptx)




# 🤖 PR Review Bot

AI-powered GitHub PR reviewer specialised in backend failure patterns.  
Built with **Java 17**, **Spring Boot 3.2**, and **OpenAI GPT-4o**.

---

## What It Does

Listens for GitHub PR events and automatically reviews code for:

| Severity | Pattern |
|---|---|
| 🔴 Critical | N+1 queries, missing idempotency, no transaction boundary, unhandled I/O exceptions, hardcoded secrets |
| 🟠 High | Missing timeouts, no pagination, generic exception catch, blocking calls, logging PII |
| 🟡 Medium | Missing indexes, no circuit breaker, resource leaks, null dereference risk |

Posts structured inline comments back to the PR with:
- What's wrong
- Production impact
- Concrete fix

---

## Architecture

```
GitHub PR Event
      │
      ▼
WebhookController        ← validates HMAC-SHA256 signature, returns 202 immediately
      │ async
      ▼
PullRequestHandler       ← orchestrates the pipeline
      │
      ├─► DiffParser     ← filters noise, classifies files (backend/frontend)
      │
      ├─► ReviewStrategyFactory
      │       ├─ STANDARD  → single OpenAI call (default)
      │       └─ AGENT     → two-pass reasoning (bot.review.agent-mode-enabled=true)
      │
      ├─► OpenAiClient   ← calls GPT-4o with domain-specific system prompt
      │
      ├─► ReviewCommenter ← posts review + individual issue comments to GitHub
      │
      └─► ReviewStore    ← ConcurrentLinkedDeque in-memory audit log
                │
                └─► GET /api/reviews  ← consumed by React dashboard (future)
```

---

## Prerequisites

- Java 17+
- Maven 3.8+
- [ngrok](https://ngrok.com) (for local development)
- GitHub Personal Access Token (repo scope)
- OpenAI API Key (GPT-4o access)

---

## Setup

### 1. Clone and configure

```bash
git clone <your-repo>
cd pr-review-bot
cp src/main/resources/application.yaml src/main/resources/application-local.yaml
```

Edit `application-local.yaml` or set environment variables:

```yaml
github:
  webhook:
    secret: your-webhook-secret     # any random string, you'll set same in GitHub
  token: ghp_xxxxxxxxxxxx           # GitHub PAT with repo scope

openai:
  api:
    key: sk-xxxxxxxxxxxx            # OpenAI API key
    model: gpt-4o                   # or gpt-4-turbo
```

### 2. Start ngrok

```bash
ngrok http 3000
```

Copy the HTTPS URL — e.g. `https://abc123.ngrok.io`

### 3. Configure GitHub Webhook

1. Go to your test repo → **Settings → Webhooks → Add webhook**
2. Payload URL: `https://abc123.ngrok.io/webhook`
3. Content type: `application/json`
4. Secret: same value as `github.webhook.secret`
5. Events: select **Pull requests** only
6. Click **Add webhook**

### 4. Build and run

```bash
# With environment variables
export GITHUB_WEBHOOK_SECRET=your-secret
export GITHUB_TOKEN=ghp_xxxx
export OPENAI_API_KEY=sk-xxxx

mvn spring-boot:run

# Or with the local yaml profile
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Server starts on `http://localhost:3000`

### 5. Verify it's running

```bash
curl http://localhost:3000/health
# → PR Review Bot is running
```

---

## Test the Bot

### Test 1 — Review with issues (should REQUEST_CHANGES)

1. Create a new branch in your test repo
2. Copy `test-pr-samples/BuggyPaymentService.java` into the repo
3. Commit and open a PR
4. Watch the bot comment within ~20-30 seconds

Expected: 3 critical, 3 high, 1 medium issue + REQUEST_CHANGES review

### Test 2 — Clean review (should APPROVE)

1. Create another branch
2. Copy `test-pr-samples/CleanPaymentService.java` into the repo
3. Open a PR
4. Watch the bot approve

### Test 3 — Check the review API

```bash
curl http://localhost:3000/api/reviews | python3 -m json.tool
curl http://localhost:3000/api/stats
```

---

## Agent Mode

Enable two-pass reasoning for more thorough cross-file analysis:

```yaml
# application.yaml
bot:
  review:
    agent-mode-enabled: true
```

**What it does:** Pass 1 broadly enumerates suspicious patterns across all files.
Pass 2 reasons about severity with full cross-file context, catching interactions
that single-pass misses (e.g. no idempotency + caller retries without backoff).

**Trade-off:** ~2x API calls, ~30-60s total vs ~15-30s standard mode.

---

## Frontend Review (Future Scope)

Frontend review is built and ready — activate by removing extensions from the skip list:

```yaml
# application.yaml
bot:
  skip-extensions:
    - .lock
    - .png
    # Remove .html, .css, .jsx, .tsx to enable frontend review
```

`ReviewStrategyFactory` automatically routes frontend-only PRs to `FRONTEND_SYSTEM_PROMPT`,
which checks for: XSS vulnerabilities, memory leaks in useEffect, missing error boundaries,
missing key props, accessibility issues, unnecessary re-renders, and hardcoded strings.

---

## API Reference

| Endpoint | Description |
|---|---|
| `POST /webhook` | GitHub webhook receiver |
| `GET /health` | Health check |
| `GET /api/reviews` | All reviews (newest first) |
| `GET /api/reviews/{id}` | Single review with full issue list |
| `GET /api/reviews/repo/{repo}` | Reviews filtered by repo name |
| `GET /api/stats` | Aggregate dashboard stats |
| `GET /actuator/health` | Spring actuator health |

---

## Running Tests

```bash
mvn test
```

Tests cover:
- `SignatureValidatorTest` — HMAC validation including timing-safe comparison
- `DiffParserTest` — file filtering, classification, context building
- `OpenAiClientTest` — response parsing, rate limit handling, error fallbacks
- `PullRequestHandlerTest` — end-to-end orchestration, skip logic, fail-open

---

## Key Design Decisions

### Why 202 Accepted immediately?
GitHub has a 10-second webhook timeout and retries on failure.
If we held the connection waiting for OpenAI (~20-30s), GitHub would retry
and we'd post duplicate reviews on every PR.

### Why `MessageDigest.isEqual` over `.equals()`?
String equality short-circuits on the first byte mismatch — that timing
difference is measurable. Constant-time comparison prevents timing side-channel attacks.

### Why `ConcurrentLinkedDeque` for the store?
Multiple PRs arrive simultaneously. Lock-free concurrent access with O(1) prepend
for newest-first ordering. No lock contention under concurrent load.

### Why direct RestTemplate over OpenAI SDK?
The Java SDK is in beta. Direct HTTP gives full control, better testability
(mock at the RestTemplate boundary), and explicit understanding of the API contract.

### Why a ReviewStrategyFactory?
Extension point for adding new strategies (agent, security, SQL, combined)
without touching the orchestration layer. The handler calls `reviewFiles()` —
it doesn't know which strategy was selected.

---
