/**
 * Inventory API
 * Handles all inventory-related API calls
 */

import { apiGet } from './client'
import { API } from './endpoints'

export interface InventoryItem {
  id: number
  kitchenId: number
  inventoryId: number
  itemName?: string
  itemType?: string
  quantity?: number
  unit?: string
  lastUpdated?: string
}

export interface ShopItem {
  id: number
  name: string
  itemType: string
  description?: string
  basePrice?: number
}

export const InventoryApi = {
  /**
   * Get inventory items by kitchen ID
   */
  async getInventoryByKitchenId(kitchenId: number): Promise<InventoryItem[]> {
    return apiGet<InventoryItem[]>(API.inventory.byKitchenId(kitchenId))
  },
}

export const ShopApi = {
  /**
   * Get all ingredients from the shop
   */
  async getIngredients(): Promise<ShopItem[]> {
    return apiGet<ShopItem[]>(API.shop.ingredients)
  },

  /**
   * Get all equipment from the shop
   */
  async getEquipment(): Promise<ShopItem[]> {
    return apiGet<ShopItem[]>(API.shop.equipment)
  },
}
