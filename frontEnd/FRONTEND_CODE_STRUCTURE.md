# Frontend Code Structure

How the Virtual Kitchen frontend is organised: what each folder owns, which way folders may import each other,
and the conventions that keep it that way.

## Tech stack

- React 19 + TypeScript, built with Vite 8
- React Router 7 (`BrowserRouter`, nested routes)
- `@xyflow/react` (React Flow) for the recipe process editor
- Tailwind 4 for layout/spacing, plain CSS files for component styling, `--flow-*` CSS variables for light/dark themes
- `node --test` unit tests in `tests/` (pure modules only; runtime imports there carry a `.ts` extension)

## Folder map

```text
src/
├── main.tsx          mounts the providers (theme, notifications, confirm, AI job tracker) and <App />
├── app/              the app shell
│   ├── App.tsx            the route table + signed-in / admin guards (see "Navigation" below)
│   ├── AppSplash.tsx      shown while a stored session is restored;  NotFoundPage.tsx: unknown addresses
│   ├── routes/            route components that read URL params and wire features together
│   └── session/           useAppSession (restore / login / logout / 401 notice), the SignedIn context,
│                          returnPath (where to land after sign-in; only /kitchen paths are accepted)
├── api/              HTTP client (auth header, 401 → sign-out, envelope unwrapping) + one module per backend area
├── types/            domain types shared across features (kitchen, recipe, process, recipe order, …)
├── shared/           feature-independent building blocks
│   ├── catalog/           step & ingredient catalogs (stepCatalogs.data.json is also read by the backend build)
│   ├── components/        confirm dialog, notifications, payment gateway, ActionMenu, ListEnd, SearchableSelect, …
│   │   └── page/              PageHeader, EmptyState, ErrorState, SkeletonGrid + page.css (vk-btn, vk-chip, …)
│   ├── format/            money, date, number formatting
│   ├── hooks/             useApiData (load/reload/mutate), useIncrementalReveal
│   ├── cart/              cartStorage (fires CART_CHANGED_EVENT) and useCartCount (the navbar badge)
│   ├── drafts/  theme/
│   ├── clipboard.ts       copyText(text)
│   └── errors.ts          errorMessage(error, fallback)
├── styles/           global.css (Tailwind), theme-tokens.css, flow-ui.css (shared flow-* component classes)
└── features/
    ├── home/              landing page
    ├── auth/              login / signup / OTP / password reset
    ├── kitchen/           layout/ (navbar, KitchenWorkspaceLayout side nav, kitchenSections), overview/ (My Kitchen
    │                      dashboard), inventory/ (the read-only pantry: Ingredients / Equipment), shop/, orders/
    ├── recipes/           RecipeLibraryPage (scope "mine" = My Recipes, "discover" = Discover), create dialog
    ├── recipe-order/      entry/ (the "Order" dialog: coming soon + demo), the demo order wizard:
    │                      confirm → ingredients → payment → receipt → track   (public API: index.ts)
    ├── recipe-tool/       the recipe tool                                                       (public API: index.ts)
    ├── settings/          account settings (AI models, narration voice, credits)
    └── admin/             admin layout, AI analytics, AI evaluation, runtime config
```

### Inside `features/recipe-tool`

```text
recipe-tool/
├── index.ts          everything other features and the app may use
├── recipeToolRoutes.ts
├── page/             RecipeToolPage (editor ⇄ view tabs), its header (readiness, save status, Cook mode, Publish /
│                     Copy link, Order), useCookMode, summary and edit-details dialog
├── model/            the step/condition data model and graph logic, shared by every folder below
│                     (incl. preparationFlows / mergePreparations: the one Preparation and its flows,
│                     recipeReadiness: whole-recipe Preparation + Main checks, stepAnnotations: @ annotations
│                     in step descriptions, stepTitle: derived step titles)
├── session/          RecipeSessionProvider: the one in-memory editing session (autosave, undo/redo,
│                     crash recovery in useRecoveryMirror, generated images in useVisualsApplier,
│                     live readiness + the gate for ordering/cook mode in useRecipeReadiness)
├── jobs/             JobTracker: app-wide polling of AI generation, visuals and workflow jobs; AiJobIndicator (navbar)
├── editor/           the React Flow editor: components/, hooks/, adapters/ (process ⇄ canvas, AI edits),
│                     model/ (layout, slides, deletion wording), nodes/, context/, styles/;
│                     components/annotations/: the @ description editor, its suggestions and amount popover
├── view/             the read-only "Recipe Process" view (recipeView.ts builds it from the graphs;
│                     processExplanation.ts: Preparation flow cards, main-process continuity)
├── walkthrough/      step slideshow + narration player (used by the editor's Preview and the view's cook mode)
├── workflow/         AI Recipe Creation (generate → approve → visuals → narration); seedAiRecipeCreation
└── styles/           modals.css, RecipePropertiesPanel.css
```

## Navigation

```text
/                         landing page (sign-in dialog)          /auth?from=…  sign-in page, returns to `from`
/kitchen                  My Kitchen (side nav, a tab strip on narrow screens — kitchen/layout/kitchenSections.ts):
    (index)  Overview · recipes  My Recipes · ingredients / equipment  Pantry · shop · orders
/kitchen/discover         public recipes
/kitchen/recipes/:id/tool/editor|process   Recipe Editor / Recipe Process (full screen, outside the side nav)
/kitchen/recipe-orders/:id/:view           demo recipe order (full screen)
/kitchen/settings         account settings          /kitchen/admin/*   admin (ADMIN only)
```

The navbar has the areas (My Kitchen, Discover, Admin for admins), then "+ New recipe" (`/kitchen/recipes?create=1`),
the background-AI indicator, the cart (`/kitchen/shop?cart=open`), theme and the account menu. Old addresses
(`/kitchen/inventory`, `?tab=global`, the removed standalone process pages) redirect.

Breakpoints used across pages: 480px (phones), 768px (tablets: kitchen padding, editor note), 900px (side nav →
tab strip), 1000px (landing menu).

## Dependency rules

Imports only go downward; there are no cycles between folders.

```text
app ──► features ──► shared / api / types
kitchen, recipes ──► recipe-order ──► recipe-tool          (other features: no feature imports)
app ──► recipe-tool for the navbar's AiJobIndicator, passed into KitchenLayout as `navbarExtras`
recipe-tool: page ──► editor / view / workflow ──► session / jobs / walkthrough ──► model
```

- Code outside `recipe-tool` or `recipe-order` imports only from that feature's `index.ts`.
- `recipe-tool` never imports another feature: actions it needs from elsewhere (e.g. "Order recipe") are passed in
  as props by the app's route components.
- `shared` never imports a feature; `api` only imports `types`.

## Conventions

- **Data loading:** `useApiData(key, load)` for fetch-on-mount/reload (it handles cancellation, stale responses
  and derived `loading`); don't hand-write `loading`/`error` state with a fetch inside `useEffect`.
- **Errors:** show `errorMessage(error, 'fallback')`; `ApiError` carries the backend's message and status
  (`httpStatusOf`). A 401 on a signed-in request signs the user out (`api/authToken.ts`).
- **Contexts:** keep the context object and its `useX` hook in a `.ts` file and the provider component in a
  `.tsx` file (React Fast Refresh needs component-only files).
- **Types:** shared domain types go in `types/`; a type used by one feature stays in that feature.
- **Styles:** Tailwind for layout; a component's own CSS file for its look; `--flow-*` tokens for colours.

## Request flow

```text
Route (app/routes) → feature page → hook (useApiData / feature hook) → api/<area>.ts → api/client → backend
```

Slow AI work runs as backend jobs; `recipe-tool/jobs/JobTracker` polls them app-wide so progress survives
navigation and reloads.
