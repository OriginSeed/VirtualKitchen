/**
 * Recipe & Flow API
 * Handles all recipe and flow-related API calls
 */

import { apiGet, apiPost, apiDelete, apiPut } from './client'
import { API } from './endpoints'
import type { FlowData } from '../types/recipeFlow'

export interface Recipe {
  id: number
  name: string
  description?: string
  createdAt?: string
}

export interface RecipeCreateRequest {
  name: string
  description?: string
  createdBy: number
}

export interface VisualizationRequest {
  nodes: FlowData['nodes']
  edges: FlowData['edges']
}

export interface VisualizationClip {
  clipId?: string
  [key: string]: unknown
}

export interface VisualizationFinalClip {
  clipId?: string
  [key: string]: unknown
}

export interface VisualizationResponse {
  clips?: VisualizationClip[]
  finalClip?: VisualizationFinalClip
}

export const RecipeApi = {
  /**
   * Get all recipes for a user
   */
  async getRecipesByUserId(userId: number): Promise<Recipe[]> {
    return apiGet<Recipe[]>(API.recipes.byUserId(userId))
  },

  /**
   * Create a new recipe
   */
  async createRecipe(data: RecipeCreateRequest): Promise<Recipe> {
    return apiPost<Recipe>(API.recipes.list, data)
  },

  /**
   * Delete a recipe
   */
  async deleteRecipe(recipeId: number): Promise<void> {
    return apiDelete<void>(API.recipes.byId(recipeId))
  },
}

export const FlowApi = {
  /**
   * Load flow data for a recipe
   */
  async getFlowByRecipeId(recipeId: number | string): Promise<FlowData> {
    return apiGet<FlowData>(API.flows.byId(recipeId))
  },

  /**
   * Save flow data for a recipe
   */
  async saveFlow(
    flowId: number | string,
    data: FlowData
  ): Promise<FlowData> {
    return apiPut<FlowData>(API.flows.byId(flowId), data)
  },
}

export const VisualizationApi = {
  /**
   * Generate visualization for a flow
   */
  async generateVisualization(
    recipeId: number | string,
    data: VisualizationRequest
  ): Promise<VisualizationResponse> {
    return apiPost<VisualizationResponse>(
      API.visualizations.byId(recipeId),
      data
    )
  },
}
