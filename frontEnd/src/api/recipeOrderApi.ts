/**
 * Recipe Order API
 * Ordering a recipe from the user's own kitchen: draft -> confirm -> ingredient check/reservation ->
 * simulated payment -> simulated preparation and delivery. Identity comes from the request's JWT,
 * so nothing here takes a userId. Conflicts (a stale status, stock that changed) come back as 409
 * ApiErrors carrying the server's message.
 */

import { apiGet, apiPost, apiPut } from './client'
import { API } from './endpoints'
import type {
  AvailabilityReport,
  DeliveryAddress,
  PaymentMethod,
  RecipeOrder,
  RecipeOrderCreateRequest,
  RecipeOrderStatus,
  RecipeOrderUpdateRequest,
} from '../types/recipeOrder'

export const RecipeOrderApi = {
  /** The caller's recipe orders, newest first. Summaries: `recipe.processes` is null. */
  async list(): Promise<RecipeOrder[]> {
    return apiGet<RecipeOrder[]>(API.recipeOrders.list)
  },

  /** Starts a DRAFT order; the recipe is snapshotted now and the saved default address prefilled. */
  async create(data: RecipeOrderCreateRequest): Promise<RecipeOrder> {
    return apiPost<RecipeOrder>(API.recipeOrders.list, data, { uiAction: 'order.place' })
  },

  /** One order in full, including the recipe's process snapshot. */
  async get(orderId: number): Promise<RecipeOrder> {
    return apiGet<RecipeOrder>(API.recipeOrders.byId(orderId))
  },

  /** Changes servings/address/notes. A new serving count sends the order back to DRAFT. */
  async update(orderId: number, data: RecipeOrderUpdateRequest): Promise<RecipeOrder> {
    return apiPut<RecipeOrder>(API.recipeOrders.byId(orderId), data)
  },

  /** DRAFT -> AWAITING_INGREDIENTS: works out the ingredient requirements for the chosen servings. */
  async confirm(orderId: number, saveAddressAsDefault: boolean): Promise<RecipeOrder> {
    return apiPost<RecipeOrder>(API.recipeOrders.confirm(orderId), { saveAddressAsDefault })
  },

  /** Read-only stock check of every requirement against the kitchen inventory. */
  async availability(orderId: number): Promise<AvailabilityReport> {
    return apiGet<AvailabilityReport>(API.recipeOrders.availability(orderId))
  },

  /** Holds the ingredients for this order (-> AWAITING_PAYMENT). 409 when stock is short. */
  async reserve(orderId: number): Promise<RecipeOrder> {
    return apiPost<RecipeOrder>(API.recipeOrders.reserve(orderId), {})
  },

  /**
   * Runs the simulated payment. Reuse the same `idempotencyKey` when retrying one attempt (e.g.
   * after a timeout) so it is never charged twice; a new attempt gets a new key.
   */
  async pay(orderId: number, method: PaymentMethod, idempotencyKey: string): Promise<RecipeOrder> {
    return apiPost<RecipeOrder>(API.recipeOrders.pay(orderId), { method, idempotencyKey })
  },

  /** Moves the simulated preparation one stage on, from the status/step the client last showed. */
  async advance(orderId: number, expectedStatus: RecipeOrderStatus, expectedStepIndex: number | null): Promise<RecipeOrder> {
    return apiPost<RecipeOrder>(API.recipeOrders.advance(orderId), { expectedStatus, expectedStepIndex })
  },

  /** Allowed until preparation starts; releases any reservation. */
  async cancel(orderId: number): Promise<RecipeOrder> {
    return apiPost<RecipeOrder>(API.recipeOrders.cancel(orderId), {})
  },
}

export const DeliveryAddressApi = {
  /** The saved default delivery address, or null when there is none. */
  async get(): Promise<DeliveryAddress | null> {
    return apiGet<DeliveryAddress | null>(API.deliveryAddress.mine)
  },

  async save(address: DeliveryAddress): Promise<DeliveryAddress | null> {
    return apiPut<DeliveryAddress | null>(API.deliveryAddress.mine, address)
  },
}
