# AI Bench

Runs a list of recipes through every listed AI model combination (text→text, text→image, text→speech), as a real account, and reports latency, cost, fallbacks and output quality side by side.

Each run is **(recipe × combo × repeat)** and does what a user would do in the app:

1. Create a fresh recipe. Images are cached per recipe step, so recipes are never reused between combos.
2. **Text→text:** start a process generation job and poll it.
3. Load the result with the frontend's own converter (`recipeProcessGenerationConverter.ts`) and save it through the editor's own save path (`snapshotPersistence.ts`).
4. **Text→image:** run one visualization job per saved process.
5. **Text→speech:** narrate every step.
6. If an admin account is configured, read the server-side numbers for the recipe from AI analytics: queue wait, execution time, tokens and cost.

Models are chosen by setting the user's saved AI preferences before each combo. The preferences are restored afterwards, as are any admin overrides.

## Setup

The bench has no dependencies of its own. It builds with the frontend's toolchain, so run `npm install` in `frontEnd/` first.

```bash
cd tools/ai-bench
npm run build          # -> dist/bench.mjs
npm run typecheck      # optional
```

1. Copy `bench.example.json` to `bench.json` and edit the recipes and combos. List the model keys with `GET /api/v1/ai/models` or in Admin → Models.
2. Put credentials in environment variables, never in the JSON. The config only names the variables. Use `tokenEnv` instead of `emailEnv`/`passwordEnv` to pass a ready JWT.
   - The **user** account runs the recipes. It must be email-verified.
   - The **admin** account is optional. It is needed for:
     - `grantCredits`, so paid models don't silently fall back when credits run out,
     - `allowAdminOverrides`, to temporarily enable non-selectable models such as `gemini-tts`, `ollama-qwen`, `drawthings-sdxl` or `local-tts`,
     - server metrics.

```bash
export BENCH_USER_EMAIL=... BENCH_USER_PASSWORD=...
export BENCH_ADMIN_EMAIL=... BENCH_ADMIN_PASSWORD=...
```

## Run

```bash
node dist/bench.mjs run bench.json --dry-run      # checks accounts, models, overrides; changes nothing
node dist/bench.mjs run bench.json
node dist/bench.mjs run bench.json --only-combo gemini-all --only-recipe pancakes --stages text,image
```

Output goes to `out/<runId>/`:

| File | What |
|---|---|
| `results.jsonl` | One JSON row per run with per-stage status, requested vs. resolved models, fallback, client latency, backend stage timings, warnings/repairs, per-step image/audio results, quality checks and server metrics |
| `summary.csv` | Per combo × stage: p50/p95/max latency, success and fallback rate, server queue/execution time, tokens, cost, average steps/warnings/repairs, image failure rate, audio seconds per step |
| `report.html` | Recipes × combos grid with each run's steps, image thumbnails, audio players and badges |
| `artifacts/` | Raw generation JSON, saved processes, the run's `flow.json`, downloaded images and audio |
| `flows/` | One `<recipe>.<textModel>.flow.json` per flow a group generated, ready to import or reuse in a later run |
| `restore.json` | What the run changed and has to put back |

To re-render the report from the results:

```bash
node dist/bench.mjs report out/<runId>
```

If a run is killed hard, Ctrl+C restores state automatically. If restoring didn't happen:

```bash
node dist/bench.mjs restore out/<runId> --config bench.json
```

## Config reference

| Key | Default | Meaning |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | Backend |
| `recipes[]` | — | `{id, file}` (relative to the config), `{id, text}`, or `{id, flow}`: a saved flow file to build the recipe from, with no text call |
| `combos[]` | — | `{name, text?, image?, speech?, anchor?, reference?, focusedView?, stepsPerImage?, skipLookAlike?, adminConfig?}`, or an inner list of them (a group that shares its flow, see below). A model that is `null` or left out means that stage is not run. `text` may only be left out where a flow is available: in a group after a combo that has one, or when every recipe has a flow file |
| `combos[].anchor` / `reference` / `focusedView` | server's value | Image settings for this combo, `true` or `false`: the scene anchor, drawing each cooking step against the previous step's image, and the Focused Cooking View. Shorthand for the `images` settings `sceneAnchor.enabled`, `referenceChain.enabled` and `focusedView.enabled`. Need an admin account. `reference: true` with `focusedView: false` is rejected |
| `combos[].stepsPerImage` / `skipLookAlike` | server's value | Image cost settings for this combo. `stepsPerImage` (1, 2 or 4): how many Preparation steps are drawn as panels of one image, which is then cut into one image per step (needs the focused view). `skipLookAlike` (`true`/`false`): steps the prompt writer marks as looking like the step before reuse its image. Shorthand for the `images` settings `preparation.stepsPerImage` and `lookAlike.skip`. Need an admin account |
| `combos[].adminConfig` | — | Admin runtime settings to use while this combo runs, by section then key, e.g. `{"images": {"sceneAnchor.enabled": false}}`. Needs an admin account. Checked in the dry run, reverted before the next combo, and restored at the end. Keys are the ones in Admin → Config (`GET /api/v1/admin/config/{section}`) |
| `repeats` | 1 | Runs per recipe × combo. In a group, later repeats copy the flow too |
| `concurrency` | 1 | Parallel runs within a combo. Combos always run one after another because they share the user's preferences. Use 1 for clean latency numbers |
| `grantCredits` | 0 | Credits the admin adds before the run. Not taken back |
| `allowAdminOverrides` | false | Temporarily set `model.<key>.enabled` / `userSelectable` to true for the combos' models |
| `failOnFallback` | false | Exit code 1 if any stage ran on a different model than requested |
| `cleanup` | `keep` | `delete` removes the bench recipes afterwards |
| `downloadAssets` | true | Save images and audio under `artifacts/` |
| `timeouts` | text 600, image 1200, speech 600 | Per-stage polling timeout in seconds |

## Spending less: groups, flows and null models

Images and narration are cached per recipe, so comparing image settings needs a fresh recipe per setting. It does not need a fresh *flow*: the steps can be generated once and copied.

**Put the combos you want to compare in an inner list.** They form a group, and inside a group:

- The flow is generated once per recipe and text model, by the first combo that names that `text` model. Every other run of the group (other combos, later repeats) builds its recipe by copying it. No text call is made for those.
- A combo without `text` uses the group's flow. Its image prompts are still written by the flow's text model.
- Narration is spoken once per flow and speech model. The other runs point at the same audio.
- If the flow can't be generated, the runs that depend on it fail at once, without any AI call.

A combo outside a list is a group of one and behaves as it always did: every run generates its own flow.

The four image modes on one flow, with one narration:

```json
"combos": [
  [
    { "name": "prompt",           "text": "gemini-flash", "image": "gemini-image", "speech": "gemini-tts",
      "focusedView": true, "anchor": false, "reference": false },
    { "name": "anchor",           "image": "gemini-image", "speech": "gemini-tts",
      "focusedView": true, "anchor": true,  "reference": false },
    { "name": "reference",        "image": "gemini-image", "speech": "gemini-tts",
      "focusedView": true, "anchor": false, "reference": true },
    { "name": "anchor+reference", "image": "gemini-image", "speech": "gemini-tts",
      "focusedView": true, "anchor": true,  "reference": true }
  ]
]
```

The plan printed before a run (and by `--dry-run`) says what this will ask the AI for:

```
group 1 (one flow per recipe, shared by its 4 combos):
  ...
  -> flows: 1 generated, 3 copied · image runs: 4 · narration: 1 narrated, 3 reused
```

Each image run still makes its own text call to write its image prompts, since those depend on the settings being compared.

**Skipping a stage.** Set a model to `null` or leave it out: `"speech": null` makes no narration, `"image": null` no images.

**Flow files.** Every generated flow is saved as `flow.json` next to the run's artifacts, and a group's flows also under `out/<runId>/flows/`. A flow holds the recipe text and its saved processes, without images or audio. Use one in a later run instead of generating again:

```json
"recipes": [ { "id": "tomato-masala", "flow": "out/2026-11-11T10-00-00/flows/tomato-masala.gemini-flash.flow.json" } ]
```

or build it into a recipe in the app, with no AI call:

```bash
node dist/bench.mjs import out/<runId>/flows/tomato-masala.gemini-flash.flow.json --config bench.json --name "Tomato Masala"
```

It prints the new recipe's id. Step ids are kept, so the same step has the same id in every recipe built from one flow.

In `report.html` a copied flow or reused narration shows as a dashed "reused" badge, and `summary.csv` counts them in a `reused` column and leaves them out of latency and cost.

Reference images only reach image models that take them (Gemini; DeepInfra only with `ai.deepinfra.image.edit-endpoint` set). On a prompt-only model, `reference` only changes the order the cooking steps are generated in, and `anchor` only adds an anchor image that is never sent.

## Comparing a setting, e.g. the scene anchor

To test the same models with a setting on and off, give two combos the same models and different `adminConfig`:

```json
"combos": [
  { "name": "gemini-anchor-on",  "text": "gemini-flash", "image": "gemini-image" },
  { "name": "gemini-anchor-off", "text": "gemini-flash", "image": "gemini-image",
    "adminConfig": { "images": { "sceneAnchor.enabled": false } } }
]
```

A combo without `adminConfig` runs with the settings as they were before the run. The setting is shown under the combo name in `report.html` and in the `adminConfig` column of `summary.csv`. With the anchor off, expect one image call fewer per recipe and less consistency between step images.

## Comparing the Focused Cooking View

`recipes/tomato-masala.txt` is a six-step recipe written for this comparison: chop onions, add oil, add onions, add tomatoes, add turmeric and stir, cook until soft. Run it with the focused view off (images as they were before it), on, and on without the reference chain:

```json
"recipes": [ { "id": "tomato-masala", "file": "recipes/tomato-masala.txt" } ],
"combos": [
  { "name": "before",          "text": "gemini-flash", "image": "gemini-image",
    "adminConfig": { "images": { "focusedView.enabled": false } } },
  { "name": "focused",         "text": "gemini-flash", "image": "gemini-image" },
  { "name": "focused-nochain", "text": "gemini-flash", "image": "gemini-image",
    "adminConfig": { "images": { "referenceChain.enabled": false } } }
]
```

Compare the step images of each combo for: the action shown, the ingredients present, how the food changes from step to step, the same pan and stove throughout, the same camera and framing for the cooking steps, and overall quality. The admin AI Evaluation page shows each image with its prompt and scores it on step correctness, state continuity, consistency and realism.

Written as separate combos like this, the text model writes the steps again for every combo, so their wording can differ slightly between them. Put the combos in a group (above) to run all of them on the same steps.

## Notes

- Bench recipes are named `bench <runId> <recipe> <combo> #n`, so they're easy to find in My Recipes. Server metrics join to them by `recipeId`.
- The image row in `summary.csv` includes the text calls the backend makes to write image prompts.
- `npm test` runs the tool's own tests (config, flow files and the spend plan); they need no backend.
- Exit codes: `0` all ok, `1` a run failed (or fell back, with `failOnFallback`), `2` config or model errors.
