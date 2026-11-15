# Logging

Logging is designed so that a single search answers four questions about any user action: **who** did it, **what** happened step by step (including the background jobs it started), **what it used** (AI, database, cache) and **how it ended**.

Configuration lives in [`backend/src/main/resources/logback-spring.xml`](../backend/src/main/resources/logback-spring.xml).

## Files (`logs/`)

| File | Contents | Kept |
|---|---|---|
| `application.log` | INFO and above: per-action summaries, business events, job start/end lines, AI request summaries, warnings and errors. **Read this one.** | 14 days |
| `application.json.log` | The same lines as JSON (Elastic Common Schema). Every context key below is its own field. | 7 days |
| `application-error.log` | ERROR only. Unexpected bugs include their stack trace. | 30 days |
| `debug.log` | Everything, plus DEBUG detail (cache checks, provider calls) and third-party noise. | 3 days |
| `access.log` | One line per HTTP request (method, path, status, duration, action). Polling ends up here. | 7 days |

## Line format

```
2026-11-20 10:01:02.360 INFO  [http-nio-8080-exec-4] [requestId=ab12cd34ef56ab78 userId=4 area=recipe action=recipe.update jobId=- recipeId=63 uiAction=editor.save] c.p.v.c.logging.RequestIdFilter - event=action_completed method=PUT path=/api/v1/process-templates/63 status=200 outcome=success durationMs=48 aiRequests=0 inputTokens=0 outputTokens=0 costUsd=0.000000 costInr=0.0000 dbReads=6 dbWrites=2 cacheHits=0 artifactsReused=0
```

The bracketed block is the context, which is filled in automatically:

| Key | Set by | Meaning |
|---|---|---|
| `requestId` | `RequestIdFilter` | One HTTP request. It is returned in the `X-Request-ID` header, and the UI shows it in error messages as `Ref: xxxxxxxx`. |
| `userId` | `JwtAuthenticationFilter` | The signed-in user. `-` means anonymous. |
| `area` | `ActionContextInterceptor` | Feature area: `recipe`, `order`, `kitchen`, `store`, `auth`, `admin`, `narration`, `visualization`, `generation`, `workflow`, `ai`, `asset`, or `system` for scheduled jobs. |
| `action` | `ActionContextInterceptor` | The user action, such as `recipe.update`, `narration.ensureAll` or `scheduled.stale_job_sweep`. |
| `jobId` | `OperationLog` and scheduled jobs | The background operation the line belongs to. |
| `parentJobId` | `OperationLog` | The job that started this one. Shown only when set. |
| `workflowId` | `RecipeAiWorkflowService` | The AI workflow the line belongs to. Shown only when set. |
| `recipeId` (also `processId`, `orderId`, `stepId` in the JSON file) | `ActionContextInterceptor`, from the URL path | Shown only when set. |
| `uiAction` | The `X-UI-Action` request header | The click that caused the request, such as `editor.save`. Shown only when set. |

Background jobs inherit the whole context from the request that started them, because `ThreadPoolTaskPool` copies it to the worker thread. That means `action=` also matches every line of the jobs an action started.

### How actions get their names
- **Default:** `ActionContextInterceptor` builds the name from the controller and method (`KitchenController.update` becomes `kitchen.update`). It derives the area from the package.
- **Override:** add `@LogAction` to the controller class (`@LogAction("recipe")` makes the names `recipe.<method>`) or to a single method (`@LogAction("recipe.publish")`).
- **Enforced:** `ActionContextInterceptorTest` checks that every endpoint resolves to a well-formed name and that no two endpoints share one.

## Writing a log line

- Start every message with `event=<object>_<verb in the past tense>` (`recipe_updated`, `order_placed`, `auth_login_failed`), then add `key=value` fields. `LoggingHygieneTest` enforces this.
- Write one line per state change in the service that makes it. Don't repeat duration or outcome; `action_completed` already records them.
- For background jobs, use `OperationLog`:
  ```java
  OperationLog op = OperationLog.start(log, "x_job", id, "recipeId=..");
  try {
      ...
      op.completed("..");
  } catch (Exception e) {
      op.failed(e, "..");
  } finally {
      op.close();
  }
  ```
  It writes the `_started` and `_completed`/`_failed` lines, together with everything the job used.
- To log a failure at the boundary that finally handles it, use `FailureLogger.logFailure`. It chooses the level for you.
- To set context keys in code that isn't a request (for example scheduled work), use `LogScope.with(key, value, ...)`. It restores the previous values when closed.
- To count resource use, call `UsageLedger.increment(Counter.CACHE_HITS)` or another counter. MongoDB reads and writes are counted automatically.

### Levels
| Level | Use for |
|---|---|
| ERROR | Something is broken and someone should look. Include the stack trace only for bugs. |
| WARN | Finished, but something went wrong: rejected by a business rule, a fallback was used, it was slow, or part of it failed. |
| INFO | An action finished or something changed state. |
| DEBUG | The steps in between. These go to `debug.log` only. |

### Never log
Email addresses, names, passwords, OTPs, tokens, recipe text, prompts or AI responses. Log ids, counts and sizes instead. (AI payloads have a separate logger, `virtualKitchen.ai.payload`, which is off by default.)

## Finding things

```bash
grep "action=recipe.update" logs/application.log      # every time a user did this, and what it caused
grep "requestId=<Ref from the UI>" logs/debug.log     # everything one request did
grep "jobId=<id>" logs/application.log                # the story of one background job
grep "workflowId=<id>" logs/application.log           # every job in one AI workflow
grep "userId=4 " logs/application.log                 # everything one user did
grep "outcome=failed\|_failed" logs/application.log   # what went wrong
```

To search the JSON file (requires `jq`):

```bash
jq -c 'select(.action=="recipe.update")' logs/application.json.log
```

## Costs
- AI cost uses the model's token prices. Models priced per image or per audio second use that rate instead.
- When no price is known, the line shows `costUsd=unpriced` (unknown, not free).
- When only some requests in a total could be priced, the cost is shown as a lower bound, for example `costUsd=>=0.001000`.
