import { useState, type ReactNode } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import AiAnalyticsPage from '../features/admin/ai-analytics/AiAnalyticsPage'
import AiEvaluationPage from '../features/admin/ai-evaluation/AiEvaluationPage'
import ConfigLayout from '../features/admin/config/ConfigLayout'
import ConfigSectionPage from '../features/admin/config/ConfigSectionPage'
import AddModelPage from '../features/admin/config/models/AddModelPage'
import AdminLayout from '../features/admin/layout/AdminLayout'
import { ADMIN_SECTIONS } from '../features/admin/layout/adminSections'
import Auth from '../features/auth/Auth'
import InventoryView from '../features/kitchen/inventory/InventoryView'
import KitchenLayout from '../features/kitchen/layout/KitchenLayout'
import OrderHistoryView from '../features/kitchen/orders/OrderHistoryView'
import AccountSettingsPage from '../features/settings/AccountSettingsPage'
import IngredientCatalogGate from '../shared/catalog/IngredientCatalogGate'
import HomeRoute from './routes/HomeRoute'
import RecipeOrderRoute from './routes/RecipeOrderRoute'
import { RecipeProcessEditorRoute, RecipeProcessListRoute, RecipesRoute, RecipeToolRoute } from './routes/RecipeRoutes'
import ShopRoute from './routes/ShopRoute'
import { SignedInContext, useSignedIn, type SignedInSession } from './session/signedIn'
import { useAppSession } from './session/useAppSession'

/** The kitchen area: only for a signed-in user (else back to the landing page), inside the kitchen layout. */
function SignedInArea({ session, onLogout }: { session: SignedInSession | null; onLogout: () => void }) {
  if (!session) return <Navigate to="/" replace />
  return (
    <SignedInContext.Provider value={session}>
      <KitchenLayout user={session.user} kitchen={session.kitchen} onLogout={onLogout} />
    </SignedInContext.Provider>
  )
}

/** Admin-only pages; the backend enforces the ADMIN role on every call too. */
function AdminOnly({ children }: { children: ReactNode }) {
  const { user } = useSignedIn()
  return user.userType === 'ADMIN' ? children : <Navigate to="/kitchen/inventory" replace />
}

type InventoryFilter = 'ingredients' | 'equipment'

function InventoryRoute({ filter, onFilterChange }: { filter: InventoryFilter; onFilterChange: (filter: InventoryFilter) => void }) {
  const { kitchen } = useSignedIn()
  return <InventoryView kitchenId={kitchen.id} filter={filter} onFilterChange={onFilterChange} />
}

function OrdersRoute() {
  const { user } = useSignedIn()
  return <OrderHistoryView userId={user.id} />
}

function App() {
  const { session, restoring, login, logout } = useAppSession()
  // Kept here so the inventory tab remembers its filter while the user visits other kitchen pages.
  const [inventoryFilter, setInventoryFilter] = useState<InventoryFilter>('ingredients')

  if (restoring) {
    return null
  }

  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<HomeRoute signedIn={session != null} onLoginSuccess={login} />} />
        <Route path="/auth" element={session ? <Navigate to="/kitchen/inventory" replace /> : <Auth onLoginSuccess={login} />} />

        <Route path="/kitchen" element={<SignedInArea session={session} onLogout={logout} />}>
          <Route index element={<Navigate to="inventory" replace />} />
          <Route path="inventory" element={<InventoryRoute filter={inventoryFilter} onFilterChange={setInventoryFilter} />} />
          <Route path="shop" element={<ShopRoute />} />
          <Route path="orders" element={<OrdersRoute />} />
          <Route path="settings" element={<AccountSettingsPage />} />

          <Route path="recipes" element={<RecipesRoute />} />
          {/* Old links to a recipe (this path used to open a separate editor) land in the Recipe Tool. */}
          <Route path="recipes/:recipeId" element={<Navigate to="tool" replace />} />
          <Route path="recipes/:recipeId/tool/:view?" element={<IngredientCatalogGate><RecipeToolRoute /></IngredientCatalogGate>} />
          <Route path="recipes/:recipeId/processes" element={<IngredientCatalogGate><RecipeProcessListRoute /></IngredientCatalogGate>} />
          <Route path="recipes/:recipeId/process/:processId" element={<IngredientCatalogGate><RecipeProcessEditorRoute /></IngredientCatalogGate>} />

          {/* Recipe orders: confirm -> ingredients -> payment -> receipt -> track (see features/recipe-order). */}
          <Route path="recipe-orders/:orderId/:view?" element={<IngredientCatalogGate><RecipeOrderRoute /></IngredientCatalogGate>} />

          {/* Admin area: one guard and the admin sub-navbar around every admin feature. */}
          <Route path="admin" element={<AdminOnly><AdminLayout /></AdminOnly>}>
            <Route index element={<Navigate to={ADMIN_SECTIONS[0].path} replace />} />
            <Route path="ai-analytics/:tab?" element={<AiAnalyticsPage />} />
            <Route path="ai-evaluation/sets/:setKey" element={<AiEvaluationPage />} />
            <Route path="ai-evaluation/:tab?" element={<AiEvaluationPage />} />
            {/* Runtime config: a side nav of every section around the selected one. */}
            <Route path="config" element={<ConfigLayout />}>
              <Route index element={<Navigate to="models" replace />} />
              <Route path="models/new" element={<AddModelPage />} />
              <Route path="models/:modelKey/edit" element={<AddModelPage />} />
              <Route path=":sectionId" element={<ConfigSectionPage />} />
            </Route>
          </Route>
        </Route>

        <Route path="*" element={<Navigate to={session ? '/kitchen/inventory' : '/auth'} replace />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App
