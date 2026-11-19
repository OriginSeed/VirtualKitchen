# Backend configuration

`src/main/resources/application.properties` is committed and holds **no secrets**. Every secret and
environment-specific value is read from an environment variable through a `${VAR}` placeholder.

- **Production:** set real environment variables on the server or in the container, or use your platform's secret
  manager.
- **Local development:** create `backend/.env` in properties format (`KEY=value`, one per line). It is
  git-ignored and loaded automatically (`spring.config.import` in `application.properties`). Real environment
  variables override it.

If a required variable is missing, the app fails at startup (for example `Could not resolve placeholder
'MONGODB_URI'`). A `JWT_SECRET` shorter than 32 characters also fails startup.

## Required

| Variable | Used for |
|---|---|
| `MONGODB_URI` | MongoDB connection string, including the database name |
| `JWT_SECRET` | JWT signing key, 32+ random characters (`openssl rand -hex 32`) |
| `GOOGLE_CLIENT_ID` | Google sign-in; must match the frontend's `VITE_GOOGLE_CLIENT_ID` |
| `MAIL_FROM`, `MAIL_USERNAME`, `MAIL_PASSWORD` | SMTP account for OTP, verification and reset emails |
| `SUPABASE_URL`, `SUPABASE_SERVICE_KEY` | Image and audio storage |

## AI providers (blank = provider unavailable)

| Variable | Used for |
|---|---|
| `GEMINI_API_KEY` | Gemini text, image and speech |
| `OPENAI_API_KEY` | OpenAI text, image and speech (`OPENAI_ENABLED=false` hides these models) |
| `DEEPINFRA_API_KEY` | DeepInfra text, image and speech |

## Production settings

| Variable | Default | Meaning |
|---|---|---|
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | Comma-separated frontend origin(s). Set the real domain, e.g. `https://app.example.com`. `*` is refused at startup. |
| `SWAGGER_ENABLED` | `false` | Swagger UI (`/ui`) and `/v3/api-docs`. Local development only; leave unset in production. |
| `RATE_LIMIT_ENABLED` | `true` | Per-client request rate limits (see below). |
| `RATE_LIMIT_TRUST_FORWARDED` | `false` | Set `true` only behind a reverse proxy or load balancer that sets `X-Forwarded-For`; otherwise every client looks like the proxy. |
| `MAIL_HOST`, `MAIL_PORT` | `smtp.gmail.com`, `587` | SMTP server. |
| `JWT_EXPIRATION_MS` | `86400000` (24 h) | Access token lifetime. |
| `VK_ADMIN_EMAILS` | empty | Emails promoted to ADMIN at sign-in. |
| `VK_ADMIN_SECRET_EDITING` | `false` | Admin panel "Keys" section; also needs `VK_CONFIG_ENC_KEY`. |
| `VK_CONFIG_ENC_KEY` | empty | Passphrase that encrypts provider keys stored from the admin panel. |

Other tuning variables (thread pools, model defaults, narration storage, ...) are listed next to their
properties in `application.properties`.

## Rate limits

Token buckets per client, in memory (`app.ratelimit.*` in `application.properties`):

| Tier | Default | Applies to |
|---|---|---|
| Auth | 10/min per address | `POST /api/v1/auth/**` (sign-in, sign-up, OTP, password reset) |
| AI start | 10/min per user | POSTs that start paid AI work (generation, visualization, storyboard, step fix, narration batch, AI workflows, chat) |
| General | 300/min per user | Everything else, including job polling |

Over the limit, the API answers 429 with `Retry-After` and error code `RATE_LIMITED`. The counters live in one JVM,
so with several backend instances each limits on its own.

## Request limits

- **Request body:** at most 5 MB (`app.http.max-body-bytes`). A larger body gets 413 and code `PAYLOAD_TOO_LARGE`.
- **Text and list fields:** limited by `@Size` on the request DTOs. The limits are in
  `common/web/InputLimits.java`, and the frontend mirrors them in `frontEnd/src/shared/inputLimits.ts`.

## Error responses

Every failed request returns:

```json
{"timestamp": "...", "status": 429, "error": "Too Many Requests", "message": "<safe to show users>",
 "code": "RATE_LIMITED", "requestId": "<quote to support>"}
```

The values of `code` are listed in `common/web/ErrorCode.java`. Stack traces, exception messages and AI providers'
own error text are logged and never sent to clients.
