# Backend Code Structure

This document explains how the backend of the Virtual Kitchen application is organised: which package owns what, which way the packages may depend on each other, and the conventions that keep it that way. The code is grouped **by feature**, with the usual controller/service/repository/model/dto layers nested inside each feature.

## Tech stack

- Java 17
- Spring Boot 4.0.6 (Web, Security, Data MongoDB, Actuator, Validation); OTP emails via the Resend REST API
- MongoDB (Spring Data MongoDB) as the primary datastore
- Lombok for boilerplate reduction on models/DTOs; entity ↔ DTO mapping is hand-written (`*Mapper` components), not MapStruct
- JWT-based authentication (`JwtService` + `JwtAuthenticationFilter`), Google ID-token sign-in, email OTPs
- Outbound REST clients to Google Gemini, OpenAI, Ollama, DrawThings, a local TTS server (AI) and Supabase (storage)
- Maven (with the Maven Wrapper, `mvnw`/`mvnw.cmd`)

## Package map

```text
backend/src/main/java/com/processVisualisation/virtualKitchen/
├── VirtualKitchenApplication.java   Spring Boot entry point (@EnableScheduling)
├── common/        shared infrastructure, no feature knowledge
├── auth/          users, login (password, Google, OTP), JWT, security config
├── store/         ingredient & equipment catalog, unit conversion, item costs
├── kitchen/       kitchens, inventory, store orders, inventory reservations
├── recipe/        recipes and their process graphs
├── recipeorder/   ordering a recipe to be cooked by a kitchen
└── ai/
    ├── provider/  HTTP adapters to AI/storage providers
    ├── platform/  the AI request pipeline every AI call goes through
    ├── asset/     generated image assets shared by step visuals and catalog images
    ├── chat/      free-form AI chat
    ├── globalasset/  one generated image per catalog ingredient/equipment (admin)
    └── recipe/    AI features for recipes: generation, edit, visualization, narration, workflow
```

## Dependency rules

Each package may depend only on the packages below it; nothing below knows about anything above.

```text
recipeorder ──► recipe ──► store ──► auth ──► common
     │            ▲          ▲
     └──► kitchen ┴──────────┘

ai.recipe / ai.globalasset ──► ai.asset ──► ai.platform ──► ai.provider ──► common
ai.chat ──► ai.platform                 ai.recipe ──► recipe, store      ai.globalasset ──► store
```

When a lower package needs something from a higher one, it inverts the dependency instead of importing it:

- **Events** — `auth` publishes `UserRegisteredEvent`; `kitchen.service.DefaultKitchenProvisioner` listens and creates the user's default kitchen.
- **Ports** — `recipe.service.StepImageLookup` is declared by `recipe` and implemented by `ai.recipe.visualization.VisualizationStepImageLookup`; `ai.provider.ProviderCallReporter` is declared by the providers and implemented by `ai.platform.usage.MeteredProviderCallReporter`.
- **Status on the exception** — feature exceptions extend `common.web.ApiException`, which carries their HTTP status, error title and log outcome, so `common` never imports a feature exception.

## Conventions

- **Feature layers.** A feature uses the sub-packages it needs from `controller/`, `service/`, `repository/`, `model/`, `dto/`, `mapper/`, `exception/`, `config/`. Small AI sub-features keep their services, repositories and documents flat in the feature package, with `controller/`, `dto/` and `model/` sub-packages.
- **Services are classes.** There are no single-implementation `I*Service` interfaces; an interface exists only where there are several implementations (e.g. `NarrationAudioStorage`), an in-memory test double (`InventoryReservationStore`, `RecipeOrderStore`, `AiAnalyticsStore`, `StepNarrationStore`), or a dependency to invert (the ports above).
- **Mappers live with their feature** (`auth.mapper`, `store.mapper`, `kitchen.mapper`, `recipe.mapper`, `recipeorder.mapper`).
- **Errors.** Throw a feature exception extending `ApiException` (status + title + `Outcome`), `NoSuchElementException` (404) or `IllegalArgumentException` (400); `GlobalExceptionHandler` turns them into an `ErrorResponse`. Anything else is a 500 and is logged with its stack trace.
- **Naming.** AI classes use the `Ai` prefix (`AiClient`, `AiChatController`, `AiTimeoutException`); provider brand names keep their spelling (`OpenAIClient`).
- **Mongo collections are named explicitly** in every `@Document`, so classes can be moved or renamed without touching stored data.

## `common/` — shared infrastructure

- **config** — `MongoConfig`, `JacksonConfig`, `OpenApiConfig`.
- **concurrent** — a framework-agnostic bounded task pool: `Task`/`NamedTask`, `TaskPool`/`ThreadPoolTaskPool`, `TaskResult`/`BatchResult`, `TaskPoolFactory`.
- **logging** — `RequestIdFilter` (request id + access log), `MdcKeys`, `FailureLogger` (logs a failure once, at a level chosen from its `ApiException.Outcome`).
- **persistence** — `DBSequence` + `SequenceGeneratorService` (atomic `Long` id sequences).
- **web** — `ApiResponse<T>` (success envelope), `ErrorResponse`, `ApiException`, `GlobalExceptionHandler`.

## `auth/`

- **controller** — `AuthController` (`/api/v1/auth`: signup, login, Google, email/login OTPs, password reset, `/me`), `UserController` (`/api/v1/users`), `DeliveryAddressController`.
- **security** — `SecurityConfig` (filter chain, CORS, BCrypt, path rules), `JwtAuthenticationFilter`, `JwtService`, `CurrentUser` (the authenticated user id / admin check for controllers).
- **service** — `AuthService` (all login flows, admin promotion via `AdminAllowlist`, publishes `UserRegisteredEvent`), `UserService`, `OtpService`, `EmailService` (sends through `ResendEmailClient`), `GoogleTokenVerifierService`.
- **event** — `UserRegisteredEvent`. **exception** — `AuthException`, `UserNotFoundException`. **mapper** — `UserMapper`.
- **model / repository / dto** — `User` (+ embedded `DeliveryAddress`), `Otp`, their enums and repositories, auth DTOs.

## `store/`

The catalog of things that exist: ingredients and equipment.

- **controller / service** — `IngredientController`/`IngredientService`, `EquipmentController`/`EquipmentService`, `ItemCostController`/`ItemCostService`.
- **catalog** — `StepCatalogResource` (reads `stepCatalogs.data.json`, shared with the frontend), `IngredientCatalogSeeder` (startup runner seeding the ingredient catalog), `IngredientCatalogChangedEvent`.
- **units** — `UnitType`, `UnitConversionService` (mass/volume/count conversion using ingredient density), `UnitConversionException`.
- **model / repository / mapper / dto** — `Ingredient`, `Equipment`, `ItemCost`, `ItemType`.

## `kitchen/`

What a user has: kitchens and their stock.

- **controller / service** — `KitchenController`/`KitchenService`, `InventoryController`/`InventoryService` (unit-converting stock upserts), `KitchenInventoryController`/`KitchenInventoryService`, `OrderController`/`OrderService` (store orders, synced into inventory), `DefaultKitchenProvisioner`.
- **reservation** — `InventoryReservationStore` / `MongoInventoryReservationStore`: atomic, idempotent reserve/release/consume of stock, used by recipe orders.
- **model / repository / mapper / dto** — `Kitchen`, `Inventory` (polymorphic `itemType` + `itemId` reference into the store catalog), `KitchenInventory`, `Order`/`OrderItem`.

## `recipe/`

Recipes and the process graphs they are made of.

- **model** — `RecipeTemplate` (the recipe: ingredients, nutrition, visibility, `mainProcessId`, `processRevision`; collection `process_template`) and `Process` (a React-Flow graph of STEP/CONDITION `ProcessNode`s and `ProcessEdge`s, MAIN or SUBPROCESS; collection `processes`).
- **controller** — `RecipeTemplateController` (`/api/v1/process-templates`), `RecipeDetailController` (`/api/v1/recipes/{id}`), `ProcessController` (`/api/v1/recipes/{id}/processes`).
- **service** — `RecipeTemplateService`, `ProcessService` (recipe-scoped CRUD, revision-checked batch save, copy), `ProcessGraphCloner` (deep graph copy for the copy features), `ProcessStepOrder`, `RecipeThumbnailService` + `StepImageLookup`.
- **validation** — `ProcessValidator`, `ProcessValidationResult`, `ProcessValidationException` (what every save must satisfy), and `RecipeReadinessEvaluator` (whole-recipe Preparation + Main structure; gates ordering and AI workflow approval, never saving — see docs/recipe-vocabulary-v2.md §I).
- **migration** — `ProcessIngredientIdMigration` (startup runner back-filling catalog ingredient ids into process nodes).
- **exception / mapper / repository / dto** — `RecipeAccessDeniedException`, `RecipeRevisionConflictException`, `ProcessMapper`, `RecipeTemplateMapper`, graph and recipe DTOs.

## `recipeorder/`

Ordering a recipe to be cooked by a kitchen.

- **service** — `RecipeOrderService` (the order lifecycle DRAFT → AWAITING_INGREDIENTS → RESERVING → AWAITING_PAYMENT → CONFIRMED → … → COMPLETED), `RecipeOrderLifecycle` (allowed transitions), `RecipeRequirementCalculator` (scaled ingredient needs from the process graph), `IngredientAvailabilityService` (checks needs against kitchen inventory), `ProcessSnapshots` (the order's frozen copy of the recipe's graphs).
- **repository** — `RecipeOrderStore` / `MongoRecipeOrderStore` (version-checked saves).
- **controller / mapper / config / exception / model / dto** — `RecipeOrderController` (`/api/v1/recipe-orders`), `RecipeOrderMapper`, `RecipeOrderProperties`, `RecipeOrderException`, `RecipeOrder`, `RecipeOrderStatus`.

## `ai/`

### `ai.provider` — provider adapters

- `AiClient` (text; `GeminiClient`, `OpenAIClient`, `OllamaClient`), `ImageGenerationClient` (`GeminiImageClient`, `DrawThingsImageClient`), `tts.TtsProvider` (`GeminiTtsProvider`, `LocalTtsProvider`), `ImageStorageClient` (`SupabaseImageStorageClient`), `ProviderUsage`, `ProviderCallReporter`.
- **config** — `AiRestClientConfig` (one named `RestClient` per provider) and one `*Properties` class per provider. **dto** — `AiRequest`/`AiResponse`. **exception** — `AiClientException` and its subtypes.

### `ai.platform` — the AI request pipeline

| Package | Role |
|---|---|
| `registry` | Configured model catalog (`ai.models[*]`), `AiModelController` |
| `routing` | `ModelSelectionService`: preferred → default → fallback model, reserves credits |
| `credit` | Per-user credit balance and ledger, daily reset, `AiCreditController` |
| `queue` | `AiRequestQueueService`: artifact reuse, admission control, job record, retries, credit settlement |
| `dispatch` | `AiClientResolver`: model → provider bean, wrapped in logging/metering decorators |
| `usage` | Per-request token/cost metering (`AiUsageMeter`, `AiCostLedger`) |
| `artifact` | Durable store of paid image/audio results: reuse instead of paying twice, recovery of unfinished follow-up work |
| `analytics` | One cost/usage record per AI request, priced from versioned rate cards; admin query API |
| `response` | `ai_responses` log of AI text outputs, `AiJsonResponseSupport` |
| `config`, `logging` | `TaskPoolConfig` (the AI worker pools), `OperationLog` |

A text request flows: feature service → `AiRequestQueueService.executeBounded` → reuse check → admission → `ModelSelectionService` (+ credit reservation) → `ai-text` pool → `AiClientResolver` → `Metered(Logging(provider client))` → provider HTTP call → credits consumed, analytics completed, artifact staged. Images and speech use `executeInline` on the feature's own worker pool.

### AI features

- `ai.asset` — `VisualizationAsset` (collection `visualization_asset`) and its repository and enums, shared by step visuals and global assets.
- `ai.chat` — `AiChatController` (`/api/ai/chat`), `AiChatService`.
- `ai.globalasset` — admin-triggered image generation for catalog ingredients/equipment.
- `ai.recipe.generation` — recipe text → preparation flows + the MAIN process (prompt builder, output schema, normalizer that repairs cosmetic slips, structural validator, async job); the frontend stores the flows in the recipe's one Preparation process.
- `ai.recipe.edit` — AI edits of an existing process (builds on the generation schema and validator).
- Step descriptions may carry @ annotations (`recipe.model.StepAnnotations`): readers that show or speak text use its plain-text form, and copies/migrations remap the ids inside them.
- `ai.recipe.visualization` — per-step image generation for a process (async job, artifact consumer, `VisualizationStepImageLookup`).
- `ai.recipe.narration` — per-step text-to-speech with fingerprint-based reuse; audio on the filesystem or Supabase.
- `ai.recipe.workflow` — "AI Recipe Creation": orchestrates generation → approval → visuals → narration, plus the active-jobs view.
- `ai.recipe.vocabulary` — the step vocabulary (actions, units, styles, ingredient index) the prompts and validators share.
- `ai.recipe` — `RecipeProcessAiException`, `StaleJobReconciler` (fails jobs interrupted by a restart).

## Typical request flow

```text
Client -> Controller -> Service -> Repository -> MongoDB
```

Controllers resolve the caller with `CurrentUser`, services enforce ownership/visibility and map entities to DTOs with their feature's mapper, and errors surface through `GlobalExceptionHandler`. Slow AI work runs as an async job on a `TaskPool`; the controller returns a job DTO the client polls.
