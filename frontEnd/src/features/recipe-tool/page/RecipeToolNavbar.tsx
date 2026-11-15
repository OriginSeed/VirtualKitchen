import { useState } from 'react'
import { NavLink } from 'react-router-dom'
import '../../../styles/flow-ui.css'
import './RecipeToolNavbar.css'
import type { RecipeDetail } from '../../../types/recipe'
import { recipeToolPath, type RecipeToolView } from '../recipeToolRoutes'
import RecipeSummarySection from './RecipeSummarySection'
import AddToMyRecipesButton from './AddToMyRecipesButton'
import type { AddToMyRecipes } from './useAddToMyRecipes'
import ActionMenu, { type ActionMenuItem } from '../../../shared/components/ActionMenu'
import { useReadinessGate, useRecipeReadiness } from '../session/useRecipeReadiness'
import { useRecipeSession } from '../session/sessionContext'
import { useResolveConflict } from '../session/useResolveConflict'
import RecipeSaveStatus from '../editor/components/RecipeSaveStatus'
import type { ReadinessIssue } from '../model/recipeReadiness'
import { readinessChip } from './readinessChip'
import { useCookMode } from './useCookMode'
import { downloadTextFile, exportRecipeFile } from '../session/recipeFile'
import { useNotifications } from '../../../shared/components/notifications/notificationContext'
import { errorMessage } from '../../../shared/errors'

const TABS: { id: RecipeToolView; label: string; icon: string }[] = [
  { id: 'editor', label: 'Recipe Editor', icon: '🧩' },
  { id: 'process', label: 'Recipe Process', icon: '📖' },
]

type RecipeToolNavbarProps = {
  recipe: RecipeDetail
  /** Someone else's recipe: no editor tab, just a "Read only" marker. */
  readOnly?: boolean
  /** Readers of a public recipe: "Add to My Recipes" / "Open your copy". */
  addToMyRecipes?: AddToMyRecipes | null
  onBack: () => void
  /**
   * Owners only: "Order". The app shows its ordering dialog; `ensureReady` (save, then refuse while the recipe has
   * blockers) runs before a (demo) order actually starts.
   */
  onOrderRecipe?: (ensureReady: () => Promise<boolean>) => void
  /** Opens a readiness issue in the editor. */
  onShowIssue?: (issue: ReadinessIssue) => void
  /** The order is being created. */
  ordering?: boolean
  /** Owners only: publish / unpublish; `blockers` lets the confirmation warn about an unfinished recipe. */
  onToggleVisibility?: (blockers: number) => void
  /** Public recipes: copy a link others can open. */
  onCopyLink?: () => void
  /** Owners only: the ⋯ menu's other recipe actions. */
  onEditDetails?: () => void
  onDeleteRecipe?: () => void
}

/** The readiness badge: green / amber / red, opening the first issue in the editor. */
function ReadinessBadge({ onShowIssue }: { onShowIssue?: (issue: ReadinessIssue) => void }) {
  const readiness = useRecipeReadiness()
  if (!readiness) return null
  const chip = readinessChip({ blockers: readiness.blockers.length, warnings: readiness.warnings.length })
  const firstIssue = readiness.blockers[0] ?? readiness.warnings[0]
  const clickable = Boolean(onShowIssue && firstIssue)
  return (
    <button
      type="button"
      className={`rt-readiness is-${chip.tone}`}
      onClick={clickable ? () => onShowIssue?.(firstIssue) : undefined}
      disabled={!clickable}
      title={chip.title}
      aria-label={chip.title}
    >
      <span aria-hidden>{chip.tone === 'ready' ? '✓' : chip.tone === 'blocked' ? '✖' : '⚠'}</span>
      <span className="rt-readiness-label">{chip.label}</span>
    </button>
  )
}

/** The save status, from either tab (owners only: readers never save). */
function SaveStatus() {
  const session = useRecipeSession()
  const resolveConflict = useResolveConflict()
  if (!session?.canEdit || session.loading) return null
  return (
    <span className={`rt-save-status${session.saveState.status === 'conflict' ? ' is-conflict' : ''}`}>
      <RecipeSaveStatus saveState={session.saveState} onResolveConflict={(choice) => void resolveConflict(choice)} />
    </span>
  )
}

/**
 * The Recipe Tool's header, the one place for the recipe as a whole on both tabs:
 * - left — Back, the recipe (details on click), its readiness and save status;
 * - centre — the Editor / Process tabs (owners) or a Read-only marker, and Cook mode;
 * - right — Publish / Copy link, Order (owners) or Add to My Recipes (readers), and the ⋯ menu.
 * Labels collapse to icons on narrow screens (Tailwind breakpoints), keeping tooltips and accessible names.
 */
export default function RecipeToolNavbar({
  recipe, readOnly = false, addToMyRecipes, onBack, onOrderRecipe, ordering = false, onShowIssue,
  onToggleVisibility, onCopyLink, onEditDetails, onDeleteRecipe,
}: RecipeToolNavbarProps) {
  const [showDetails, setShowDetails] = useState(false)
  const readinessGate = useReadinessGate(onShowIssue)
  const readiness = useRecipeReadiness()
  const cookMode = useCookMode(recipe.id)
  const isPublic = recipe.visibility === 'PUBLIC'
  const session = useRecipeSession()
  const { notifySuccess, notifyError } = useNotifications()

  // Owners only: the recipe as a file anyone can import from "New recipe". Saved first, so the file holds
  // what is on screen.
  const exportRecipe = async () => {
    if (!session) return
    try {
      await session.saveNow()
      const file = await exportRecipeFile(recipe.id, session.getProcesses())
      downloadTextFile(file.json, file.fileName)
      notifySuccess(`Recipe exported as ${file.fileName}. Share it, or import it from New recipe.`)
    } catch (error) {
      notifyError(errorMessage(error, 'Unable to export this recipe right now'))
    }
  }

  const menuItems: ActionMenuItem[] = [
    ...(onEditDetails ? [{ label: '✏️ Edit name & description', onSelect: onEditDetails }] : []),
    ...(session?.canEdit && !session.loading ? [{ label: '⤓ Export recipe', onSelect: () => void exportRecipe() }] : []),
    ...(onToggleVisibility && isPublic
      ? [{ label: '🔒 Make private', onSelect: () => onToggleVisibility(readiness?.blockers.length ?? 0) }]
      : []),
    ...(onDeleteRecipe ? [{ label: '🗑️ Delete recipe', onSelect: onDeleteRecipe, danger: true }] : []),
  ]

  return (
    <div className="recipe-tool-navbar rt-navbar">
      <div className="rt-navbar-left">
        <button type="button" onClick={onBack} className="rt-button" title="Back to your recipes">
          ← <span className="hidden md:inline">Back</span>
        </button>

        <button
          type="button"
          onClick={() => setShowDetails((value) => !value)}
          className={`rt-recipe-chip${showDetails ? ' is-open' : ''}`}
          title="View full recipe details"
          aria-expanded={showDetails}
        >
          <span aria-hidden className="rt-recipe-icon">🍳</span>
          <span className="hidden min-w-0 flex-col items-start sm:flex">
            <span className="rt-recipe-name">{recipe.name}</span>
            {recipe.description && <span className="rt-recipe-description hidden xl:inline">{recipe.description}</span>}
          </span>
          <span className={`rt-visibility hidden sm:inline-flex${isPublic ? ' is-public' : ''}`}>
            {isPublic ? 'Public' : 'Private'}
          </span>
          <span aria-hidden className="rt-chevron">▾</span>
        </button>

        {!readOnly && (
          <span className="rt-status">
            <ReadinessBadge onShowIssue={onShowIssue} />
            <span className="hidden min-w-0 xl:inline-flex"><SaveStatus /></span>
          </span>
        )}
      </div>

      <div className="rt-navbar-center">
        {readOnly ? (
          <span className="rt-readonly" title="This is someone else's recipe — add it to your kitchen to edit it">
            <span aria-hidden>👀</span><span className="hidden sm:inline"> Read only</span>
          </span>
        ) : (
          <div className="rt-tabs" role="tablist" aria-label="Recipe views">
            {TABS.map((tab) => (
              <NavLink
                key={tab.id}
                to={recipeToolPath(recipe.id, tab.id)}
                replace
                role="tab"
                className={({ isActive }) => `rt-tab${isActive ? ' is-active' : ''}`}
                aria-label={tab.label}
              >
                <span aria-hidden>{tab.icon}</span><span className="hidden sm:inline"> {tab.label}</span>
              </NavLink>
            ))}
          </div>
        )}
        {cookMode.available && (
          <button
            type="button"
            onClick={() => void cookMode.open()}
            className="rt-button"
            title="Cook mode: follow the whole recipe one step at a time, with narration"
            aria-label="Cook mode"
          >
            <span aria-hidden>▶</span><span className="hidden md:inline"> Cook mode</span>
          </button>
        )}
      </div>

      <div className="rt-navbar-right">
        {onToggleVisibility && !isPublic && (
          <button
            type="button"
            onClick={() => onToggleVisibility(readiness?.blockers.length ?? 0)}
            className="rt-button"
            title="Publish: let anyone find this recipe in Discover and add a copy to their kitchen"
            aria-label="Publish recipe"
          >
            <span aria-hidden>🌐</span><span className="hidden lg:inline"> Publish</span>
          </button>
        )}
        {isPublic && onCopyLink && (
          <button type="button" onClick={onCopyLink} className="rt-button" title="Copy a link to this public recipe" aria-label="Copy link">
            <span aria-hidden>🔗</span><span className="hidden lg:inline"> Copy link</span>
          </button>
        )}

        {addToMyRecipes && (
          <AddToMyRecipesButton action={addToMyRecipes} compact className="rt-button is-primary" />
        )}

        {onOrderRecipe && (
          <button
            type="button"
            onClick={() => onOrderRecipe(() => readinessGate('ordering'))}
            disabled={ordering}
            className="rt-button is-primary"
            aria-label={ordering ? 'Starting recipe order' : 'Order'}
            title="Order this recipe — chef-prepared delivery is coming soon; a demo is available"
          >
            <span aria-hidden>🧾</span>
            <span className="hidden md:inline"> {ordering ? 'Starting…' : 'Order'}</span>
          </button>
        )}

        {menuItems.length > 0 && <ActionMenu items={menuItems} label={`More actions for ${recipe.name}`} placement="down" />}
      </div>

      {showDetails && (
        <div className="rt-details-popover">
          <RecipeSummarySection recipe={recipe} />
        </div>
      )}

      {cookMode.element}
    </div>
  )
}
