import type { ReactNode } from 'react'
import { BrowserRouter, Navigate, Route, Routes, useLocation, useSearchParams } from 'react-router-dom'
import AiAnalyticsPage from '../features/admin/ai-analytics/AiAnalyticsPage'
import AiEvaluationPage from '../features/admin/ai-evaluation/AiEvaluationPage'
import ConfigLayout from '../features/admin/config/ConfigLayout'
import ConfigSectionPage from '../features/admin/config/ConfigSectionPage'
import AddModelPage from '../features/admin/config/models/AddModelPage'
import AdminLayout from '../features/admin/layout/AdminLayout'
import { ADMIN_SECTIONS } from '../features/admin/layout/adminSections'
import Auth from '../features/auth/Auth'
import InventoryView, { type InventoryFilter } from '../features/kitchen/inventory/InventoryView'
import KitchenLayout from '../features/kitchen/layout/KitchenLayout'
import KitchenWorkspaceLayout from '../features/kitchen/layout/KitchenWorkspaceLayout'
import OrderHistoryView from '../features/kitchen/orders/OrderHistoryView'
import { AiJobIndicator } from '../features/recipe-tool'
import AccountSettingsPage from '../features/settings/AccountSettingsPage'
import IngredientCatalogGate from '../shared/catalog/IngredientCatalogGate'
import type { Kitchen } from '../types/kitchen'
import type { User } from '../types/User'
import AppSplash from './AppSplash'
import NotFoundPage from './NotFoundPage'
import HomeRoute from './routes/HomeRoute'
import OverviewRoute from './routes/OverviewRoute'
import RecipeOrderRoute from './routes/RecipeOrderRoute'
import { DiscoverRoute, LegacyProcessRedirect, MyRecipesRoute, RecipeToolRoute } from './routes/RecipeRoutes'
import ShopRoute from './routes/ShopRoute'
import { authPathFor, RETURN_PARAM, returnPathOr } from './session/returnPath'
import { SignedInContext, useSignedIn, type SignedInSession } from './session/signedIn'
import { useAppSession } from './session/useAppSession'

/**
 * The kitchen area: only for a signed-in user, inside the kitchen layout. A signed-out visitor goes to sign-in and
 * comes back to the page they asked for afterwards.
 */
function SignedInArea({ session, onLogout }: { session: SignedInSession | null; onLogout: () => void }) {
  const location = useLocation()
  if (!session) return <Navigate to={authPathFor(location.pathname + location.search + location.hash)} replace />
  return (
    <SignedInContext.Provider value={session}>
      <KitchenLayout user={session.user} onLogout={onLogout} navbarExtras={<AiJobIndicator />} />
    </SignedInContext.Provider>
  )
}

/** Admin-only pages; the backend enforces the ADMIN role on every call too. */
function AdminOnly({ children }: { children: ReactNode }) {
  const { user } = useSignedIn()
  return user.userType === 'ADMIN' ? children : <Navigate to="/kitchen" replace />
}

function InventoryRoute({ filter }: { filter: InventoryFilter }) {
  const { kitchen } = useSignedIn()
  return <InventoryView kitchenId={kitchen.id} filter={filter} />
}

function SettingsRoute() {
  const { user } = useSignedIn()
  return <AccountSettingsPage account={{ name: user.name, email: user.email }} />
}

function OrdersRoute() {
  const { user } = useSignedIn()
  return <OrderHistoryView userId={user.id} />
}

/** The standalone sign-in page; once signed in, it forwards to the page the user was sent here from. */
function AuthRoute({ signedIn, notice, onLoginSuccess }: {
  signedIn: boolean
  notice: string | null
  onLoginSuccess: (user: User, kitchen: Kitchen) => void
}) {
  const [params] = useSearchParams()
  const from = params.get(RETURN_PARAM)
  if (signedIn) return <Navigate to={returnPathOr(from)} replace />
  const mode = params.get('mode')
  return (
    <Auth
      onLoginSuccess={onLoginSuccess}
      // Someone sent here from a page (or an expired session) already has an account.
      initialMode={mode === 'signup' || mode === 'login' ? mode : from || notice ? 'login' : undefined}
      notice={notice ?? (from ? 'Log in to continue.' : undefined)}
    />
  )
}

/**
 * The route table.
 *
 *   /                  landing page (sign-in dialog)        /auth   sign-in page (returns to ?from=)
 *   /kitchen           My Kitchen: Overview, My Recipes, Ingredients, Equipment, Shop, Orders (side nav)
 *   /kitchen/discover  public recipes
 *   /kitchen/recipes/:id/tool/:view   Recipe Editor / Recipe Process (full screen)
 *   /kitchen/recipe-orders/:id/:view  demo recipe order (full screen)
 *   /kitchen/settings, /kitchen/admin/*
 */
function App() {
  const { session, restoring, notice, login, logout } = useAppSession()

  if (restoring) {
    return <AppSplash />
  }

  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<HomeRoute signedIn={session != null} onLoginSuccess={login} />} />
        <Route path="/auth" element={<AuthRoute signedIn={session != null} notice={notice} onLoginSuccess={login} />} />

        <Route path="/kitchen" element={<SignedInArea session={session} onLogout={logout} />}>
          {/* My Kitchen's own pages, around its side navigation. */}
          <Route element={<KitchenWorkspaceLayout />}>
            <Route index element={<OverviewRoute />} />
            <Route path="recipes" element={<MyRecipesRoute />} />
            <Route path="ingredients" element={<InventoryRoute filter="ingredients" />} />
            <Route path="equipment" element={<InventoryRoute filter="equipment" />} />
            <Route path="shop" element={<ShopRoute />} />
            <Route path="orders" element={<OrdersRoute />} />
          </Route>
          {/* The pantry used to be one page. */}
          <Route path="inventory" element={<Navigate to="/kitchen/ingredients" replace />} />

          <Route path="discover" element={<DiscoverRoute />} />
          <Route path="settings" element={<SettingsRoute />} />

          {/* Old links to a recipe (this path used to open a separate editor) land in the Recipe Tool. */}
          <Route path="recipes/:recipeId" element={<Navigate to="tool" replace />} />
          <Route path="recipes/:recipeId/tool/:view?" element={<IngredientCatalogGate><RecipeToolRoute /></IngredientCatalogGate>} />
          {/* The standalone process pages were folded into the Recipe Editor. */}
          <Route path="recipes/:recipeId/processes" element={<LegacyProcessRedirect />} />
          <Route path="recipes/:recipeId/process/:processId" element={<LegacyProcessRedirect />} />

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

          <Route path="*" element={<NotFoundPage signedIn />} />
        </Route>

        <Route path="*" element={<NotFoundPage signedIn={false} />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App
