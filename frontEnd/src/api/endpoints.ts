/**
 * Centralized API Endpoints
 * All endpoint URLs are defined here to avoid hardcoding in components
 */

export const API = {
  // Authentication & Users
  auth: {
    signup: '/api/v1/auth/signup',
    login: '/api/v1/auth/login',
    google: '/api/v1/auth/google',
    me: '/api/v1/auth/me',

    sendEmailOtp: '/api/v1/auth/email/send-otp',
    verifyEmailOtp: '/api/v1/auth/email/verify-otp',

    sendLoginOtp: '/api/v1/auth/login/otp/send',
    verifyLoginOtp: '/api/v1/auth/login/otp/verify',

    forgotPassword: '/api/v1/auth/password/forgot',
    verifyPasswordResetOtp: '/api/v1/auth/password/verify-otp',
    resetPassword: '/api/v1/auth/password/reset',
  },

  // Kitchens
  kitchen: {
    list: '/api/v1/kitchens',
    byId: (id: number) => `/api/v1/kitchens/${id}`,
    byOwnerId: (ownerId: number) => `/api/v1/kitchens/owner/${ownerId}`,
  },

  // Inventory
  inventory: {
    byKitchenId: (kitchenId: number) => `/api/v1/inventory/kitchen/${kitchenId}`,
  },

  // Shop Items
  shop: {
    ingredients: '/api/v1/ingredients',
    equipment: '/api/v1/equipments',
  },

  // Orders
  orders: {
    list: '/api/v1/orders',
    byUserId: (userId: number) => `/api/v1/orders/user/${userId}`,
  },

  // Recipe orders: order one of your recipes to be prepared and delivered (simulated).
  recipeOrders: {
    list: '/api/v1/recipe-orders',
    byId: (orderId: number) => `/api/v1/recipe-orders/${orderId}`,
    confirm: (orderId: number) => `/api/v1/recipe-orders/${orderId}/confirm`,
    availability: (orderId: number) => `/api/v1/recipe-orders/${orderId}/availability`,
    reserve: (orderId: number) => `/api/v1/recipe-orders/${orderId}/reserve`,
    pay: (orderId: number) => `/api/v1/recipe-orders/${orderId}/pay`,
    advance: (orderId: number) => `/api/v1/recipe-orders/${orderId}/advance`,
    cancel: (orderId: number) => `/api/v1/recipe-orders/${orderId}/cancel`,
  },

  // The signed-in user's saved default delivery address.
  deliveryAddress: {
    mine: '/api/v1/users/me/delivery-address',
  },

  // Recipes / Process Templates
  recipes: {
    list: '/api/v1/process-templates',
    byId: (id: number) => `/api/v1/process-templates/${id}`,
    byUserId: (userId: number) => `/api/v1/process-templates/user/${userId}`,
    global: (userId: number) => `/api/v1/process-templates/global/${userId}`,
    visibility: (id: number) => `/api/v1/process-templates/${id}/visibility`,
    preparationNotes: (id: number) => `/api/v1/process-templates/${id}/preparation-notes`,
    copy: (id: number) => `/api/v1/process-templates/${id}/copy`,
  },

  // Recipe detail (ingredients/nutrition/main process); `recipes` above serves the recipe list.
  recipeDetail: {
    byId: (recipeId: number) => `/api/v1/recipes/${recipeId}`,
    ingredients: (recipeId: number) => `/api/v1/recipes/${recipeId}/ingredients`,
    nutrition: (recipeId: number) => `/api/v1/recipes/${recipeId}/nutrition`,
    mainProcess: (recipeId: number) => `/api/v1/recipes/${recipeId}/main-process`,
  },

  // Process (MAIN/SUBPROCESS) graph CRUD + copy, scoped under a recipe.
  processes: {
    list: (recipeId: number) => `/api/v1/recipes/${recipeId}/processes`,
    byId: (recipeId: number, processId: number) => `/api/v1/recipes/${recipeId}/processes/${processId}`,
    copy: (recipeId: number, processId: number) => `/api/v1/recipes/${recipeId}/processes/${processId}/copy`,
  },

  // AI-driven recipe process generation (semantic MAIN + subprocesses from recipe text) — async job only.
  recipeProcessGeneration: {
    startJob: (recipeId: number) => `/api/v1/recipes/${recipeId}/processes/generate/jobs`,
    jobStatus: (recipeId: number, jobId: string) => `/api/v1/recipes/${recipeId}/processes/generate/jobs/${jobId}`,
    markApplied: (recipeId: number, jobId: string) => `/api/v1/recipes/${recipeId}/processes/generate/jobs/${jobId}/applied`,
  },

  // AI Recipe Creation: one workflow over process generation, visuals and narration, gated by approval.
  recipeAiWorkflows: {
    list: (recipeId: number) => `/api/v1/recipes/${recipeId}/ai-workflows`,
    estimate: (recipeId: number) => `/api/v1/recipes/${recipeId}/ai-workflows/estimate`,
    byId: (recipeId: number, workflowId: string) => `/api/v1/recipes/${recipeId}/ai-workflows/${workflowId}`,
    approvalEstimate: (recipeId: number, workflowId: string) => `/api/v1/recipes/${recipeId}/ai-workflows/${workflowId}/estimate`,
    approve: (recipeId: number, workflowId: string) => `/api/v1/recipes/${recipeId}/ai-workflows/${workflowId}/approve`,
    retryTask: (recipeId: number, workflowId: string, task: string) =>
      `/api/v1/recipes/${recipeId}/ai-workflows/${workflowId}/tasks/${task}/retry`,
    discard: (recipeId: number, workflowId: string) => `/api/v1/recipes/${recipeId}/ai-workflows/${workflowId}/discard`,
    dismiss: (recipeId: number, workflowId: string) => `/api/v1/recipes/${recipeId}/ai-workflows/${workflowId}/dismiss`,
  },

  // The caller's running background jobs for a recipe — re-attached to after a reload/navigation.
  recipeJobs: {
    active: (recipeId: number) => `/api/v1/recipes/${recipeId}/jobs/active`,
  },

  // Recipe process visualization (one image per STEP of a MAIN/SUBPROCESS) — async job only.
  recipeProcessVisualization: {
    startJob: (recipeId: number, processId: number) =>
      `/api/v1/recipes/${recipeId}/processes/${processId}/visualization/jobs`,
    jobStatus: (recipeId: number, processId: number, jobId: string) =>
      `/api/v1/recipes/${recipeId}/processes/${processId}/visualization/jobs/${jobId}`,
  },

  // Spoken step narration for the slideshow — generated lazily, cached server-side by step text.
  stepNarration: {
    list: (recipeId: number, processId: number) =>
      `/api/v1/recipes/${recipeId}/processes/${processId}/narrations`,
    step: (recipeId: number, processId: number, stepId: string) =>
      `/api/v1/recipes/${recipeId}/processes/${processId}/steps/${encodeURIComponent(stepId)}/narration`,
  },

  // AI model management / credits
  ai: {
    models: '/api/v1/ai/models',
    selectableModels: '/api/v1/ai/models/selectable',
    myPreferences: '/api/v1/users/me/ai-preferences',
    myCredits: '/api/v1/users/me/ai-credits',
    myCreditHistory: '/api/v1/users/me/ai-credits/history',
  },
}
