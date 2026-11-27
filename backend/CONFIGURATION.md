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
| `MAIL_FROM` | Sender of OTP, verification and reset emails, e.g. `Virtual Kitchen <no-reply@your-domain>`. Its domain must be verified in Resend. |
| `RESEND_API_KEY` | Resend API key used to send those emails (see [Email (Resend)](#email-resend)). The app starts without it, but every email then fails with 502. |
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
| `RESEND_BASE_URL`, `RESEND_TIMEOUT_MS` | `https://api.resend.com`, `10000` | Resend API endpoint and per-send timeout. |
| `JWT_EXPIRATION_MS` | `86400000` (24 h) | Access token lifetime. |
| `VK_ADMIN_EMAILS` | empty | Emails promoted to ADMIN at sign-in. |
| `VK_ADMIN_SECRET_EDITING` | `false` | Admin panel "Keys" section; also needs `VK_CONFIG_ENC_KEY`. |
| `VK_CONFIG_ENC_KEY` | empty | Passphrase that encrypts provider keys stored from the admin panel. |

Other tuning variables (thread pools, model defaults, narration storage, ...) are listed next to their
properties in `application.properties`.

## Email (Resend)

OTP emails (sign-up verification, sign-in codes, password reset) are sent through the
[Resend](https://resend.com) REST API (`POST /emails`) by `auth/service/ResendEmailClient`. There is no SMTP
configuration any more; the old `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_HOST` and `MAIL_PORT` variables are unused.

### Set up Resend

1. Create an account at resend.com.
2. **Domains → Add domain**: add the domain you send from (e.g. `mail.your-domain.com`) and create the DNS records
   it shows (SPF / DKIM, optionally DMARC) at your DNS provider. Wait until the domain shows **Verified**.
   Until then only Resend's test sender `onboarding@resend.dev` works, and it only delivers to your own account's
   address.
3. **API Keys → Create API key**: permission **Sending access**, restricted to that domain. Copy the key (it starts
   with `re_`); it is shown only once.
4. Set `MAIL_FROM` to an address on the verified domain, optionally with a display name:
   `Virtual Kitchen <no-reply@mail.your-domain.com>`.

### Local development

Add `RESEND_API_KEY=` and `MAIL_FROM=` with your values to `backend/.env` (see `env.template`). Never commit the key.

### Google Cloud Run (Secret Manager)

Keep the key in Secret Manager and expose it to the service as an environment variable; `MAIL_FROM` is not secret.
Replace `PROJECT_ID`, `REGION`, `SERVICE` and the service account with your own values.

```sh
# 1. Store the key (paste it on stdin so it never lands in shell history)
gcloud secrets create resend-api-key --replication-policy=automatic --project=PROJECT_ID
gcloud secrets versions add resend-api-key --data-file=- --project=PROJECT_ID

# 2. Let the service's runtime service account read it
gcloud secrets add-iam-policy-binding resend-api-key --project=PROJECT_ID \
  --member=serviceAccount:RUNTIME_SA@PROJECT_ID.iam.gserviceaccount.com \
  --role=roles/secretmanager.secretAccessor

# 3. Wire it into the service and drop the old SMTP variables (the MAIL_* ones that exist)
gcloud run services update SERVICE --region=REGION --project=PROJECT_ID \
  --set-secrets=RESEND_API_KEY=resend-api-key:latest \
  --update-env-vars="MAIL_FROM=Virtual Kitchen <no-reply@mail.your-domain.com>" \
  --remove-env-vars=MAIL_USERNAME,MAIL_HOST,MAIL_PORT
```

If `MAIL_PASSWORD` was mounted from a secret, remove it with `--remove-secrets=MAIL_PASSWORD` instead. To rotate
the key, add a new secret version and deploy a new revision (`:latest` is resolved when a revision starts).

### Test delivery

1. Start the backend and use **Forgot password** (or sign up) with an address you can read.
2. The backend logs `event=email_sent provider=resend purpose=... recipientDomain=... messageId=...`.
3. In the Resend dashboard, **Emails** lists the message and its delivery status.

On failure the client gets `502` "Unable to send verification email. Please try again later." and the backend logs
`event=email_send_failed provider=resend reason=<NOT_CONFIGURED|REJECTED|PROVIDER_ERROR|NETWORK> status=...
providerError=...`. `REJECTED` with status 401/403 usually means a wrong key or an unverified sender domain;
`NOT_CONFIGURED` means `RESEND_API_KEY` or `MAIL_FROM` is empty. API keys, codes, recipient addresses and email
bodies are never logged. The admin panel's **Keys** section can replace the key at runtime (stored encrypted).

## Rate limits

Token buckets per client, in memory (`app.ratelimit.*` in `application.properties`):

| Tier | Default | Applies to |
|---|---|---|
| Auth | 20/min per address (`RATE_LIMIT_AUTH_PER_MINUTE`) | `POST /api/v1/auth/**` (sign-in, sign-up, OTP, password reset). Users behind one office network or proxy share an address; behind a reverse proxy set `RATE_LIMIT_TRUST_FORWARDED=true`. |
| AI start | 10/min per user | POSTs that start paid AI work (generation, visualization, storyboard, step fix, narration batch, AI workflows, chat) |
| General | 300/min per user | Everything else, including job polling |

Over the limit, the API answers 429 with `Retry-After` and error code `RATE_LIMITED`. The counters live in one JVM,
so with several backend instances each limits on its own.

## AI job capacity

Background AI work runs on in-memory thread pools. The sizes below suit about 10–50 concurrent users. Tune them to
your AI providers' rate limits: more threads means more simultaneous provider calls.

| Variable | Default | Meaning |
|---|---|---|
| `VISUALIZATION_THREADS` | 8 | Images drawn at once |
| `VISUALIZATION_ORCHESTRATOR_THREADS` | 6 | Whole visualization / storyboard jobs running at once |
| `GENERATION_ORCHESTRATOR_THREADS` | 4 | Recipe generation jobs running at once |
| `AI_TEXT_THREADS` | 4 | Text AI calls at once |
| `STEP_FIX_THREADS` | 2 | Step-image fixes at once (their own pool, so they never wait behind whole-recipe jobs) |
| `NARRATION_THREADS` | 4 | Narration audio generated at once |
| `AI_JOBS_MAX_ACTIVE_PER_USER` | 10 | AI jobs one user may have queued or running |
| `AI_JOBS_MAX_QUEUED` | 60 | AI jobs waiting system-wide; beyond it new jobs get 429 `AI_BUSY` |

A job still queued after `app.jobs.queued-stale-after-ms` (60 min), or a running job not updated for
`app.jobs.stale-after-ms` (15 min), is marked failed. A failed job never starts or writes again, so no AI work is
paid for twice.

## Database connections

MongoDB client settings (`app.mongo.*`, see `MongoClientTuningConfig`): a 60 s socket timeout so a stuck connection
can't hold a request forever, a 10 s wait for a free connection, 5 warm connections, and at most 100. These override
the same options in `MONGODB_URI`.

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
