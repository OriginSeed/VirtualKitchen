/**
 * Authentication & User API
 * Handles all user-related API calls
 */

import { apiGet, apiPost } from './client'
import { API } from './endpoints'
import type { User } from '../types/User'

export interface UserCreateRequest {
  name: string
  email: string
  passwordHash: string
}

export interface KitchenCreateRequest {
  name: string
  ownerId: number
}

export interface Kitchen {
  id: number
  name: string
  ownerId: number
}

export const AuthApi = {
  /**
   * Create a new user
   */
  async createUser(data: UserCreateRequest): Promise<User> {
    return apiPost<User>(API.auth.users, data)
  },

  /**
   * Get user by email
   */
  async getUserByEmail(email: string): Promise<User> {
    return apiGet<User>(API.auth.userByEmail(email))
  },
}

export const KitchenApi = {
  /**
   * Create a new kitchen
   */
  async createKitchen(data: KitchenCreateRequest): Promise<Kitchen> {
    return apiPost<Kitchen>(API.kitchen.list, data)
  },

  /**
   * Get kitchen by owner ID
   */
  async getKitchenByOwnerId(ownerId: number): Promise<Kitchen | Kitchen[]> {
    return apiGet<Kitchen | Kitchen[]>(API.kitchen.byOwnerId(ownerId))
  },
}
