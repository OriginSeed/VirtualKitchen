/**
 * Recipe API
 * Recipes, their detail (ingredients/nutrition/main process), their Process graphs, and the
 * recipe-process AI generation/visualization jobs.
 */

import { apiGet, apiPost, apiDelete, apiPut } from './client'
import type { UiActionOptions } from './client'
import { API } from './endpoints'
import type { Process, ProcessBatchUpdateItem, ProcessBatchUpdateResult, ProcessCreateRequest, ProcessUpdateRequest } from '../types/process'
import type { RecipeAssetImportRequest, RecipeAssetImportResponse } from '../types/recipe'
import type { NutritionInfo, RecipeActiveJobsResponse, RecipeProcessGenerationJobResponse, RecipeProcessGenerationRequest, RecipeProcessVisualizationJobResponse, RecipeDetail, RecipeIngredient } from '../types/recipe'
import type { UnitType } from '../types/kitchen'

export type RecipeVisibility = 'PUBLIC' | 'PRIVATE'

export interface Recipe {
  id: number
  name: string
  description?: string
  createdAt?: string
  /** Last change to the recipe or any of its processes. Absent on recipes saved before dates were recorded. */
  updatedAt?: string
  createdBy?: number
  visibility?: RecipeVisibility
  /** The public recipe this one was copied from ("Add to My Recipes"); null when it wasn't copied. */
  copiedFromRecipeId?: number | null
  /** The recipe's latest generated step visual, resolved by the backend. Only the recipe list endpoints fill it; null when there is none. */
  thumbnailUrl?: string | null
}

export interface RecipeCreateRequest {
  name: string
  description?: string
}

export interface RecipeUpdateRequest {
  name: string
  description?: string
}

export const RecipeApi = {
  /**
   * Get all recipes owned by a user (My Recipes)
   */
  async getRecipesByUserId(userId: number): Promise<Recipe[]> {
    return apiGet<Recipe[]>(API.recipes.byUserId(userId))
  },

  /**
   * Get public recipes owned by other users (shown in Discover)
   */
  async getGlobalRecipes(userId: number): Promise<Recipe[]> {
    return apiGet<Recipe[]>(API.recipes.global(userId))
  },

  /**
   * Create a new recipe
   */
  async createRecipe(data: RecipeCreateRequest): Promise<Recipe> {
    return apiPost<Recipe>(API.recipes.list, data)
  },

  /**
   * Update a recipe's name/description. Only the owner may update.
   */
  async updateRecipe(recipeId: number, data: RecipeUpdateRequest): Promise<Recipe> {
    return apiPut<Recipe>(API.recipes.byId(recipeId), data)
  },

  /**
   * Publish/unpublish a recipe. Only the owner may change visibility.
   */
  async updateVisibility(recipeId: number, visibility: RecipeVisibility): Promise<Recipe> {
    return apiPut<Recipe>(API.recipes.visibility(recipeId), undefined, { params: { visibility } })
  },

  /**
   * Set the recipe's default preparation guidance; null or blank goes back to the app's default. Only the owner may change it.
   */
  async updatePreparationNotes(recipeId: number, preparationNotes: string | null): Promise<RecipeDetail> {
    return apiPut<RecipeDetail>(API.recipes.preparationNotes(recipeId), { preparationNotes })
  },

  /**
   * Copy a public recipe into the current user's My Recipes as an independent recipe.
   */
  async copyRecipe(recipeId: number): Promise<Recipe> {
    return apiPost<Recipe>(API.recipes.copy(recipeId))
  },

  /**
   * Delete a recipe. Only the owner may delete.
   */
  async deleteRecipe(recipeId: number): Promise<void> {
    return apiDelete<void>(API.recipes.byId(recipeId))
  },
}

/**
 * A recipe's ingredients/nutrition/main-process, backed by `/api/v1/recipes/{recipeId}/**`
 * ({@link RecipeApi} above serves the recipe list/create/publish/copy endpoints). As everywhere,
 * the authenticated user is resolved by the backend from the request's JWT.
 */
export const RecipeDetailApi = {
  /**
   * Get a recipe's detail view (ingredients, nutrition, main process id, plus base fields).
   */
  async getRecipeDetail(recipeId: number): Promise<RecipeDetail> {
    return apiGet<RecipeDetail>(API.recipeDetail.byId(recipeId))
  },

  /**
   * Replace a recipe's ingredient list. Requires ownership.
   */
  async updateIngredients(recipeId: number, ingredients: RecipeIngredient[]): Promise<RecipeDetail> {
    return apiPut<RecipeDetail>(API.recipeDetail.ingredients(recipeId), ingredients)
  },

  /**
   * Replace a recipe's nutrition info. Requires ownership.
   */
  async updateNutrition(recipeId: number, nutrition: NutritionInfo): Promise<RecipeDetail> {
    return apiPut<RecipeDetail>(API.recipeDetail.nutrition(recipeId), nutrition)
  },

  /**
   * Copies the images and narration audio a recipe file links to into this recipe, as its own files. Each
   * file is copied or reported alone; the call only fails as a whole when the recipe isn't the caller's.
   */
  async importAssets(recipeId: number, request: RecipeAssetImportRequest): Promise<RecipeAssetImportResponse> {
    return apiPost<RecipeAssetImportResponse>(API.recipeDetail.importAssets(recipeId), request)
  },

  /**
   * Get a recipe's MAIN process.
   */
  async getMainProcess(recipeId: number): Promise<Process> {
    return apiGet<Process>(API.recipeDetail.mainProcess(recipeId))
  },

  /**
   * Ensure a recipe has a MAIN process, creating one if it doesn't already have one
   * (idempotent — safe to call repeatedly). Requires ownership. Calls for the same recipe made while one is
   * still in flight share its request, so a page mounting twice (StrictMode, a quick remount) asks only once.
   */
  createMainProcess(recipeId: number): Promise<Process> {
    const pending = pendingMainProcess.get(recipeId)
    if (pending) return pending
    const request = apiPost<Process>(API.recipeDetail.mainProcess(recipeId), undefined)
      .finally(() => pendingMainProcess.delete(recipeId))
    pendingMainProcess.set(recipeId, request)
    return request
  },
}

/** In-flight "ensure a MAIN process" requests, by recipe id (see RecipeDetailApi.createMainProcess). */
const pendingMainProcess = new Map<number, Promise<Process>>()

/**
 * Process (MAIN/SUBPROCESS) graph CRUD + recipe-scoped copy-on-insert reuse, backed
 * by `/api/v1/recipes/{recipeId}/processes/**`. Every process is scoped to a recipe id.
 */
export const ProcessApi = {
  /**
   * List every process (MAIN and any SUBPROCESS documents) belonging to a recipe.
   */
  async listByRecipe(recipeId: number): Promise<Process[]> {
    return apiGet<Process[]>(API.processes.list(recipeId))
  },

  /**
   * Create a new process under a recipe. Pass `type: 'SUBPROCESS'` to create a subprocess;
   * `type: 'MAIN'` is normally created via {@link RecipeDetailApi.createMainProcess} instead,
   * which also assigns it as the recipe's main process. Requires ownership.
   */
  async create(recipeId: number, data: ProcessCreateRequest): Promise<Process> {
    return apiPost<Process>(API.processes.list(recipeId), data)
  },

  /**
   * Get a single process by id, scoped to a recipe.
   */
  async get(recipeId: number, processId: number): Promise<Process> {
    return apiGet<Process>(API.processes.byId(recipeId, processId))
  },

  /**
   * Update a process's name/description and replace its node/edge graph and viewport.
   * Requires ownership.
   */
  async update(recipeId: number, processId: number, data: ProcessUpdateRequest): Promise<Process> {
    return apiPut<Process>(API.processes.byId(recipeId, processId), data)
  },

  /**
   * Recipe-level save: replace name/description/nodes/edges/viewport for every listed process in
   * one call — the Recipe Tool's single "Save" action for its unified editing session (MAIN plus
   * every loaded SUBPROCESS), instead of one independent save per process. The backend validates
   * every item before persisting any of them, so one invalid process rejects the whole batch.
   * `baseRevision` is the recipe process revision the snapshot was based on: if the recipe was saved
   * elsewhere since, the save is rejected with 409. Resolves with the recipe's new revision.
   * Requires ownership. `options.uiAction` tags the request with the click that caused it.
   */
  async updateAll(
    recipeId: number,
    items: ProcessBatchUpdateItem[],
    baseRevision?: number,
    options?: UiActionOptions,
  ): Promise<ProcessBatchUpdateResult> {
    return apiPut<ProcessBatchUpdateResult>(API.processes.list(recipeId), { processes: items, baseRevision }, options)
  },

  /**
   * Delete a process. Rejected by the backend while it is the recipe's referenced MAIN
   * process, or while another process in the recipe still references it. Requires ownership.
   */
  async delete(recipeId: number, processId: number): Promise<void> {
    return apiDelete<void>(API.processes.byId(recipeId, processId))
  },

  /**
   * Copy-on-insert reuse: deep-clone a SUBPROCESS (recursively, including nested subprocess
   * references) into a new, independent process within the same recipe. Requires ownership.
   * The MAIN process cannot be copied.
   */
  async copy(recipeId: number, processId: number): Promise<Process> {
    return apiPost<Process>(API.processes.copy(recipeId, processId), undefined)
  },
}

/**
 * AI-driven recipe process generation (semantic MAIN + subprocesses from free-form recipe text) —
 * async job only. The result is kept only on the job (never saved as a process) by the backend; the
 * frontend loads it into the current Recipe working session (see
 * features/recipe-tool/editor/adapters/recipeProcessGenerationConverter.ts and
 * RecipeSessionContext) and the user Saves explicitly.
 */
export const RecipeProcessGenerationApi = {
  async startJob(recipeId: number, data: RecipeProcessGenerationRequest): Promise<RecipeProcessGenerationJobResponse> {
    return apiPost<RecipeProcessGenerationJobResponse>(API.recipeProcessGeneration.startJob(recipeId), data)
  },

  async getJobStatus(recipeId: number, jobId: string, signal?: AbortSignal): Promise<RecipeProcessGenerationJobResponse> {
    return apiGet<RecipeProcessGenerationJobResponse>(API.recipeProcessGeneration.jobStatus(recipeId, jobId), { signal })
  },

  /** Records that a completed job's result was loaded into the session, so it isn't offered back again. */
  async markApplied(recipeId: number, jobId: string): Promise<void> {
    return apiPost<void>(API.recipeProcessGeneration.markApplied(recipeId, jobId), undefined)
  },
}

/** The caller's background AI jobs for a recipe — see features/recipe-tool/jobs/JobTrackerContext.tsx. */
export const RecipeJobsApi = {
  async getActive(recipeId: number): Promise<RecipeActiveJobsResponse> {
    return apiGet<RecipeActiveJobsResponse>(API.recipeJobs.active(recipeId))
  },
}

/**
 * Async, per-step recipe process visualization (one generated image per STEP of the given
 * MAIN/SUBPROCESS). Scoped to one
 * process at a time: visualizing a process only generates images for its own STEP nodes.
 */
export const RecipeProcessVisualizationApi = {
  /**
   * `modelKeys`: the models picked for this run (text for the image prompts, image for the pictures).
   * `overwriteExisting`: regenerate and replace images the steps already have (default: keep them, draw only missing).
   */
  async startJob(
    recipeId: number,
    processId: number,
    modelKeys?: RecipeProcessGenerationRequest['modelKeys'],
    overwriteExisting = false,
  ): Promise<RecipeProcessVisualizationJobResponse> {
    return apiPost<RecipeProcessVisualizationJobResponse>(API.recipeProcessVisualization.startJob(recipeId, processId), {
      ...(modelKeys && Object.keys(modelKeys).length > 0 ? { modelKeys } : {}),
      ...(overwriteExisting ? { overwriteExisting: true } : {}),
    })
  },

  async getJobStatus(recipeId: number, processId: number, jobId: string, signal?: AbortSignal): Promise<RecipeProcessVisualizationJobResponse> {
    return apiGet<RecipeProcessVisualizationJobResponse>(API.recipeProcessVisualization.jobStatus(recipeId, processId, jobId), { signal })
  },
}

/**
 * A global ingredient catalog entry (store/dto/IngredientResponseDTO). This DB collection is THE
 * ingredient catalog: recipe steps reference an entry by `String(id)` (see
 * shared/catalog/ingredientCatalog.ts), and the shop sells the same entries. Distinct
 * from the looser `ShopItem` type in inventoryApi.ts, which is shared with equipment — this one is
 * typed to exactly what the backend returns for an ingredient.
 */
export interface GlobalIngredient {
  id: number
  name: string
  description?: string | null
  /** The shop/inventory unit. */
  defaultUnit: UnitType | null
  imageUrl?: string | null
  /** The entry's id in the original static catalog (e.g. "onion"); legacy step data may still use it. */
  catalogSlug?: string | null
  category?: string | null
  icon?: string | null
  aliases?: string[] | null
  /** Recipe unit ids (catalog/unitCatalog.ts), default first. */
  recipeUnits?: string[] | null
  preparationStyleSets?: string[] | null
  densityGPerMl?: number | null
  unitWeightsG?: Record<string, number> | null
  createdAt?: string
  updatedAt?: string
}

/**
 * The global ingredient catalog (`/api/v1/ingredients`, the same endpoint `ShopApi.getIngredients`
 * already calls), typed accurately for the Recipe Tool's ingredient catalog store. Recipes never
 * copy an ingredient's own data; they only reference it by id.
 */
export const IngredientCatalogApi = {
  async list(): Promise<GlobalIngredient[]> {
    return apiGet<GlobalIngredient[]>(API.shop.ingredients)
  },
}
