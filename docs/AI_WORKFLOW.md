# AI Platform – How an AI Request Is Executed

> Audience: a developer working on the AI layer.
> Scope: what happens **from the moment a caller asks for an AI result until the model's REST API answers**.
> The caller (recipe generation, visuals, narration, …) is treated as a black box that just wants a result.
> Package root: `backend/src/main/java/com/processVisualisation/virtualKitchen/ai/` (paths below are relative to it).

---

## 1. Concepts

| Concept | One-line meaning | Where |
|---|---|---|
| **Capability** | Kind of AI call: `TEXT_TO_TEXT`, `TEXT_TO_IMAGE`, `TEXT_TO_SPEECH`. | `platform/registry/AiCapability` |
| **Model definition** | A config entry: key, capability, tier, provider bean, provider model id, credit cost, prices, fallback. | `platform/registry/ModelDefinition`, `AiModelRegistry` |
| **Tier** | `PAID` (spends user credits) or `OPEN_SOURCE` (free / self-hosted). | `ModelTier` |
| **Model selection** | Picks the model for a request and reserves credits; switches to the fallback model if credits are short. | `platform/routing/ModelSelectionService` |
| **Credits** | Reserve before the call → consume on success / release on failure. Atomic Mongo updates. | `platform/credit/CreditService` |
| **Request queue** | The single entry point. Admission control, job record, retries, credit settlement. | `platform/queue/AiRequestQueueService` |
| **AiWork** | Lambda the caller passes in: "given the chosen model, do the call". | `platform/queue/AiWork` |
| **Client resolver** | Turns a model definition into the right provider client bean, wrapped with logging/metering. | `platform/dispatch/AiClientResolver` |
| **Provider client** | Makes the actual REST call to one vendor and maps its JSON/errors to our types. | `provider/*` |
| **Artifact store** | Saves paid output (image/audio bytes) before it is used, so a later failure never forces a second paid call. | `platform/artifact/*` |
| **Usage & analytics** | Tokens, cost (USD/INR), duration and status of every request and every provider call. | `platform/usage/*`, `platform/analytics/*` |

**Golden rule:** callers never call a provider client directly. They go through `AiRequestQueueService`, otherwise
credits, fallback, retries, artifacts and analytics are all skipped.

---

## 2. Models and the REST APIs we call

### 2.1 Registered models (`application.properties`, `ai.models[n]`)

| Key | Capability | Tier / credits | Provider model id | Bean | Fallback |
|---|---|---|---|---|---|
| `gemini-flash` (default text) | TEXT | PAID / 1 | `gemini-3.1-flash-lite` | `geminiAiClient` | `ollama-qwen` |
| `deepinfra-deepseek-v4-flash` | TEXT | PAID / 1 | `deepseek-ai/DeepSeek-V4-Flash` | `deepInfraAiClient` | `ollama-qwen` |
| `openai-gpt4o-mini` (disabled) | TEXT | PAID / 2 | `gpt-4o-mini` | `openAiAiClient` | `ollama-qwen` |
| `ollama-qwen` | TEXT | OPEN_SOURCE / 0 | `qwen2.5:7b` | `ollamaAiClient` | – |
| `gemini-image` (default image) | IMAGE | PAID / 5 | `gemini-2.5-flash-image` | `geminiImageClient` | `drawthings-sdxl` |
| `deepinfra-flux2-klein` | IMAGE | PAID / 2 | `black-forest-labs/FLUX-2-klein-4b` | `deepInfraImageClient` | `drawthings-sdxl` |
| `drawthings-sdxl` | IMAGE | OPEN_SOURCE / 0 | `sdxl` | `drawThingsImageClient` | – |
| `gemini-tts` (default speech) | SPEECH | PAID / 1 | `gemini-2.5-flash-preview-tts` | `geminiTtsProvider` | `local-tts` |
| `local-tts` | SPEECH | OPEN_SOURCE / 0 | `kokoro` | `localTtsProvider` | – |

Defaults: `ai.default-model.<CAPABILITY>` (env `AI_TEXT_DEFAULT_MODEL`, `AI_IMAGE_DEFAULT_MODEL`, `AI_TTS_DEFAULT_MODEL`).
DeepInfra entries are enabled only with `DEEPINFRA_ENABLED` / `DEEPINFRA_IMAGE_ENABLED=true`.

### 2.2 REST calls per provider

All calls are `POST`, JSON in / JSON out, non-streaming.

| Client | Endpoint | Auth | Request body (main fields) | Response read from |
|---|---|---|---|---|
| `GeminiClient` (text) | `{gemini}/v1beta/models/{model}:generateContent` | header `x-goog-api-key` | `contents[].parts[].text`, `systemInstruction` **or** `cachedContent`, `generationConfig{temperature, maxOutputTokens, responseMimeType, responseJsonSchema}` | `candidates[0].content.parts[].text`, `usageMetadata.*TokenCount` |
| `GeminiPromptCacheService` | `{gemini}/v1beta/cachedContents` | `x-goog-api-key` | model + system prompt, TTL | `name` (reused as `cachedContent`) |
| `GeminiImageClient` | same `:generateContent` with image model | `x-goog-api-key` | prompt text + reference images as `inlineData` parts | `candidates[].content.parts[].inlineData{mimeType, data(base64)}` |
| `GeminiTtsProvider` | same `:generateContent` with TTS model | `x-goog-api-key` | `contents[].parts[].text`, `generationConfig{responseModalities:[AUDIO], speechConfig{voiceConfig.prebuiltVoiceConfig.voiceName, languageCode}}` | `inlineData.data` (base64 PCM → WAV) |
| `DeepInfraClient` (text) | `{deepinfra}/v1/openai/chat/completions` | `Authorization: Bearer` | OpenAI format: `model`, `messages[]`, `temperature`, `max_tokens`, `response_format{json_schema / json_object}`, `reasoning_effort` | `choices[0].message.content`, `usage`, `usage.prompt_tokens_details.cached_tokens` |
| `DeepInfraImageClient` | `{deepinfra}/v1/openai/images/generations` | `Authorization: Bearer` | `prompt`, `model`, `size` ("1024x1024"), `n`, `response_format: b64_json` | `data[0].b64_json` |
| `OpenAIClient` (disabled) | OpenAI chat completions | `Authorization: Bearer` | same as DeepInfra text | same as DeepInfra text |
| `OllamaClient` | `{ollama}/api/chat` (local) | none | `model`, `messages[]`, `stream:false`, `options{temperature, num_predict}`, `format` (schema or `json`), `think:false` | `message.content`, token counts, `total_duration` |
| `DrawThingsImageClient` | `{drawthings}/sdapi/v1/txt2img` (local, A1111 API) | none | `prompt`, `width`, `height`, `steps` | `images[0]` (base64) |
| `LocalTtsProvider` | `{local-tts}/v1/audio/speech` (local, OpenAI-compatible) | optional Bearer | `model`, `input`, `voice`, `response_format`, `language` | raw audio bytes |

Not a model, but called after image generation (from an artifact consumer):
`SupabaseImageStorageClient` → `POST /storage/v1/object/{bucket}/{path}` (Bearer service key); public URL is stored.

### 2.3 HTTP error mapping (all clients)

| HTTP / failure | Mapped to | Retried? |
|---|---|---|
| 401 / 403 | `AiAuthenticationException` | No |
| 429 / 503 (DeepInfra, Gemini TTS) | `AiRateLimitedException` (+ `Retry-After`) | Yes, rate-limit backoff |
| 429 (Gemini text/image) and other 4xx/5xx | `AiCommunicationException` | Yes, once |
| Timeout / connection refused | `AiTimeoutException` | Yes, once |
| Empty / unparseable body, no content | `AiInvalidResponseException` | No |

Gemini-specific: if a call using `cachedContent` gets 400/403/404, the cache is evicted and the call is re-sent once
with the system prompt inline.

---

## 3. Dispatch: from model definition to REST call

### 3.1 Resolving the client (`AiClientResolver`)
Spring injects every provider bean into maps keyed by bean name. `ModelDefinition.providerBean` (from config) is
the lookup key:

```
ai.models[0].provider-bean=geminiAiClient   ──►  textClients.get("geminiAiClient")  ──►  GeminiClient
```

| Method | Returns | Wrapped with |
|---|---|---|
| `resolveTextClient(model)` | `AiClient` | `MeteredAiClient(LoggingAiClient(client))` |
| `resolveImageClient(model)` | `ImageGenerationClient` | `LoggingImageGenerationClient` |
| `resolveTtsProvider(model)` | `TtsProvider` | `LoggingTtsProvider` |

- **Logging wrapper** – one `ai_call_completed` / `ai_call_failed` line per HTTP call (duration, tokens, sizes).
  Prompts/responses go only to a separate payload logger that is off by default.
- **Metered wrapper** – adds the response's token usage to the current job's `AiUsage`. The job's usage is bound to
  the thread by `AiUsageMeter`, so no usage object is passed through method signatures.

### 3.2 Provider contracts (`provider/`)

```java
AiResponse     AiClient.chat(AiRequest)                                     // text
GeneratedImage ImageGenerationClient.generate(prompt, references[, settings]) // image
GeneratedAudio TtsProvider.synthesize(TtsRequest)                           // speech
```

`AiRequest` (provider-neutral): `systemPrompt`, `userPrompt`, `model`, `temperature`, `maxTokens`,
`responseFormat` (`json_object`), `responseSchema` (+ name), `cacheSystemPrompt`, `operation`.
`AiResponse`: `content`, `model`, `promptTokens`, `completionTokens`, `cachedTokens`, `thoughtsTokens`,
`finishReason`, `rawResponse`.
`GeneratedImage` / `GeneratedAudio`: bytes + mime type + `ProviderUsage`.

Each client: validate config → build vendor JSON → POST via its own `RestClient` → parse → map errors to
`AiClientException` subtypes. RestClients (base URL, timeouts) are built in `provider/config/AiRestClientConfig`;
settings come from `GeminiProperties`, `DeepInfraProperties`, `OllamaProperties`, `OpenAIProperties`,
`DrawThingsProperties`, `LocalTtsProperties`.

---

# Technical flow

> Sections 4–5 walk through what happens inside the AI package for one request, step by step.

## 4. The big picture

```
 Caller (any feature service)
   │  executeBounded(...) / executeInline(...)  + AiWork lambda
   ▼
 AiRequestQueueService ──► AiArtifactService.findReusable()   (stored result? return it, no cost)
   │                   ──► admission control (pool depth)
   │                   ──► AiRequestJob saved (ai_request_jobs)
   │                   ──► ModelSelectionService.select() ──► AiModelRegistry + CreditService.reserve()
   │                   ──► run AiWork with retries
   │                          └─ AiClientResolver.resolve*(model)
   │                                └─ MeteredAiClient → LoggingAiClient → <Provider>Client ──HTTP──► Model API
   │                   ──► CreditService.consume() / release()
   │                   ──► AiArtifactService.stage()   (persist paid bytes)
   ▼
 AiRequestOutcome<R>  { value, selection (model, tier, fallback), jobId, artifact }
```

---

## 5. Step-by-step request lifecycle

### 5.1 Entry: what the caller sends

```java
AiRequestOutcome<R> outcome = queueService.executeInline(      // or executeBounded
        userId,
        AiCapability.TEXT_TO_IMAGE,
        null,                 // preferredModelKey – null = configured default
        null,                 // idempotencyKey   – null = random
        "visualization-image",// correlationType  – what the call is for (analytics, artifact key)
        correlationId,        // e.g. "recipe-42::step-7"
        artifactSpec,         // optional – opt in to artifact reuse/staging
        selection -> clientResolver.resolveImageClient(selection.model())
                                   .generate(prompt));          // the AiWork
```

Two execution modes, same pipeline:

| Mode | Runs work on | Timeout | Used for |
|---|---|---|---|
| `executeBounded` | `ai-text` pool (4 threads) | `ai.request.timeout-ms.<CAPABILITY>` | Text calls |
| `executeInline` | Caller's thread (already a worker of `visualization` / `narration` pool) | Provider HTTP timeout | Images and speech |

Inline exists so a worker in a pool never submits to and waits on its own pool (deadlock risk).

### 5.2 Artifact reuse check – *before* any spending
`findReusable()` looks up the `artifactKey` in `ai_artifacts`. A `PENDING` artifact for that key means we already paid
for this exact input → it is decoded and returned. No credits, no job row, no provider call.
It runs before admission control, so retries are served even when the queue is full.

### 5.3 Admission control
Each pool (`ai-text`, `visualization`, `narration`) has a max in-flight depth (`app.queue.*`, `AiQueueProperties`).
Above it → `AiQueueFullException` (fail fast instead of queueing forever).

### 5.4 Job record
An `AiRequestJob` is saved in `ai_request_jobs` with status `QUEUED`.
Unique index on `(userId, idempotencyKey)` → a duplicate submit becomes HTTP 409.
Status flow: `QUEUED → PROCESSING → COMPLETED | FAILED`.

### 5.5 Model selection + credit reservation (`ModelSelectionService.select`)

```
candidate = preferredModelKey (if enabled)  else  ai.default-model.<CAPABILITY>
if candidate.tier == OPEN_SOURCE          → use it, no credits
else if CreditService.reserve(cost) ok    → use it, keep the reservation
else if registry has fallback for it      → use fallback (usedFallback=true, reason INSUFFICIENT_CREDITS)
else                                      → NoAvailableModelException
```
Fallback is **only** for missing credits, not for provider errors.
The resolved model, tier, fallback flag and credit transaction are written onto the job (`PROCESSING`).

### 5.6 Execute with retries (`runWithRetries`)
The `AiWork` lambda runs with the `ModelSelectionOutcome`. Inside it, the caller resolves a client and builds
the provider request (section 3).

| Error from provider client | Retry policy |
|---|---|
| `AiTimeoutException`, `AiCommunicationException` | Retry `ai.request.max-retries` times (default 1), `retry-backoff-ms` (500 ms) |
| `AiRateLimitedException` (429 / 503) | Separate budget; waits `Retry-After` or exponential backoff, capped, +≤20% jitter |
| `AiAuthenticationException`, `AiInvalidResponseException`, other | No retry |

All provider errors extend `provider/exception/AiClientException`; each client maps HTTP status to one of them.

### 5.7 Settle credits and log
- Success → `CreditService.consume()`, job `COMPLETED`.
- Final failure → `CreditService.release()` (credits returned), job `FAILED`, exception rethrown to the caller.
- One log line per request: `event=ai_request_completed|ai_request_failed` with `aiRequestId`, model, attempts,
  tokens, `costUsd`, `costInr`. Cost = tokens × `ai.models[n].*-usd-per-million`.

Credit ledger (`AiCreditTransaction`): `RESERVE`, `CONSUME`, `RELEASE`, `MONTHLY_GRANT`, …
Balance lives in `UserAiCredit`; monthly reset by `CreditCycleResetJob`.

### 5.8 Stage the artifact
If an `artifactSpec` was passed, the result is encoded by its codec (`GeneratedImageCodec`, `GeneratedAudioCodec`)
and saved as a `PENDING` `AiArtifact` **before** returning. The caller then does the dependent work (upload to storage)
via an `AiArtifactConsumer` and calls `markConsumed()`.

```
PENDING ──consumer succeeds──► CONSUMED (payload deleted)
   └──── consumer keeps failing ──► ABANDONED (after max-recovery-attempts)
```
`AiArtifactRecoveryJob` re-runs the consumer for orphaned `PENDING` artifacts (e.g. JVM died mid-upload).
It never calls a model or touches credits.

Artifact key (`AiArtifactKeyBuilder`): `CAPABILITY::correlationType::correlationId::sha256(inputs)` – scope first,
content hash last, so two different steps can never share a payload.

---

## 6. Threads and data

| Pool | Threads | Role |
|---|---|---|
| `ai-text` | 4 | Runs `executeBounded` work |
| `visualization` / `narration` | 4 / 2 | Workers that call `executeInline` for image / speech |

Config: `platform/config/TaskPoolConfig` (`app.taskpool.<name>.n-threads`).

| Mongo collection | Written by | Contains |
|---|---|---|
| `ai_request_jobs` | `AiRequestQueueService` | One row per real provider request: model, fallback, attempts, credits, tokens, cost |
| `ai_artifacts` | `AiArtifactService` | Paid payloads until consumed |
| `ai_request_analytics` | `AiAnalyticsRecorder` | Per-request + per-attempt analytics (admin dashboards) |
| `ai_responses` | `AiResponseService` | Raw text outputs |
| `UserAiCredit`, `AiCreditTransaction` | `CreditService` | Balance and ledger |

`StaleJobReconciler` fails `PROCESSING` jobs left behind by a restart and releases their reserved credits.

---

## 7. Extending

| Task | Steps |
|---|---|
| Switch a default model | Set `AI_*_DEFAULT_MODEL` to a key from 2.1. No code change. |
| Add a model on an existing provider | Add an `ai.models[n]` entry (same `provider-bean`, new `provider-model-id`, cost, prices, fallback). |
| Add a new provider | 1) implement `AiClient` / `ImageGenerationClient` / `TtsProvider` as `@Component("myBean")`; 2) add `*Properties` + a `RestClient` bean in `AiRestClientConfig`; 3) map HTTP errors to `AiClientException` subtypes (needed for retries); 4) return token usage so cost is tracked; 5) add an `ai.models[n]` entry with `provider-bean=myBean`. |
| Add a new AI call | Call `AiRequestQueueService` with an `AiWork` lambda; pass an `AiArtifactSpec` if the output is expensive and used by a later step that can fail. |
| Debug a call | Find `event=ai_request_failed` → take `aiRequestId` → look at `ai_request_jobs` / admin AI analytics; enable the payload logger to see prompts. |
