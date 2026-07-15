/**
 * Centralized API Endpoints
 * All endpoint URLs are defined here to avoid hardcoding in components
 */

export const API = {
  // Authentication & Users
  auth: {
    users: '/api/v1/users',
    userByEmail: (email: string) => `/api/v1/users/email/${email}`,
  },

  // Kitchens
  kitchen: {
    list: '/api/v1/kitchens',
    byId: (id: number) => `/api/v1/kitchens/${id}`,
    byOwnerId: (ownerId: number) => `/api/v1/kitchens/owner/${ownerId}`,
  },

  // Inventory
  inventory: {
    byKitchenId: (kitchenId: number) => `/api/v1/kitchen-inventory/${kitchenId}`,
  },

  // Shop Items
  shop: {
    ingredients: '/api/v1/ingredients',
    equipment: '/api/v1/equipments',
  },

  // Recipes / Process Templates
  recipes: {
    list: '/api/v1/process-templates',
    byId: (id: number) => `/api/v1/process-templates/${id}`,
    byUserId: (userId: number) => `/api/v1/process-templates/user/${userId}`,
  },

  // Flows
  flows: {
    byId: (id: number | string) => `/api/v1/flows/${id}`,
  },

  // Visualizations
  visualizations: {
    byId: (id: number | string) => `/api/v1/visualizations/${String(id)}`,
  },
}
