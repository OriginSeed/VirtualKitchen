import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import type { Recipe } from '../../api'
import { createRecipeFromFile, describeMissingAssets, EditRecipeDetailsModal, parseRecipeFile, summarizeRecipeFile, type RecipeFile } from '../recipe-tool'
import { useNotifications } from '../../shared/components/notifications/notificationContext'
import ListEnd from '../../shared/components/ListEnd'
import { EmptyState, ErrorState, PageHeader, SkeletonGrid } from '../../shared/components/page'
import { draftKeys } from '../../shared/drafts/draftStore'
import { useDraft } from '../../shared/drafts/useDraft'
import { errorMessage } from '../../shared/errors'
import { useIncrementalReveal } from '../../shared/hooks/useIncrementalReveal'
import '../../styles/flow-ui.css'
import ConfirmVisibilityModal from './components/ConfirmVisibilityModal'
import CreateRecipeModal, { type ChosenRecipeFile } from './components/CreateRecipeModal'
import { EMPTY_CREATE_RECIPE_DRAFT, type CreateRecipeDraft, type CreateRecipeMode } from './components/createRecipeDraft'
import RecipeCard from './components/RecipeCard'
import RecipeToolbar from './components/RecipeToolbar'
import { searchRecipes, sortRecipes, type RecipeSort } from './recipeListing'
import { useRecipeLibrary, type RecipeScope } from './useRecipeLibrary'

export type CreatedRecipe = {
  recipeId: number
  title: string
  mode: CreateRecipeMode
  /** The dish described for AI (empty for a blank start). */
  recipeText: string
}

type RecipeLibraryPageProps = {
  userId: number
  /** My Recipes (the user's own, in My Kitchen) or Discover (other people's public recipes). */
  scope: RecipeScope
  /** Opens a recipe in read mode. The app routes it to the Recipe Tool's Recipe Process. */
  onOpenRecipe: (recipeId: number) => void
  /** Opens one of the user's recipes in the Recipe Editor. */
  onEditRecipe?: (recipeId: number) => void
  /** Called right after creating a recipe; the app opens the editor (with AI Recipe Creation for an AI start). */
  onCreateRecipe?: (created: CreatedRecipe) => void
  /** "Order" on one of the user's recipes; the app shows the ordering dialog. */
  onOrderRecipe?: (recipe: Recipe) => void
  /** Changes each time something asks to create a recipe (the navbar's "+ New recipe"); the dialog opens then. */
  createRequestKey?: string | null
}

const COPY: Record<RecipeScope, { eyebrow: string; title: string; subtitle: string; search: string }> = {
  mine: {
    eyebrow: 'My Kitchen',
    title: 'My Recipes',
    subtitle: 'Recipes you created or added from Discover. Open one to read it, or edit its cooking flow.',
    search: 'Search your recipes...',
  },
  discover: {
    eyebrow: 'Discover',
    title: 'Discover recipes',
    subtitle: 'Recipes other cooks have published. Read any of them, cook along, or add a copy to make it your own.',
    search: 'Search public recipes...',
  },
}

/** A list of recipes — the user's own or the public ones — with search, sort and each card's actions. */
export default function RecipeLibraryPage({
  userId, scope, onOpenRecipe, onEditRecipe, onCreateRecipe, onOrderRecipe, createRequestKey = null,
}: RecipeLibraryPageProps) {
  const library = useRecipeLibrary(userId, scope)
  const copy = COPY[scope]

  // The create-recipe form keeps what was typed until the recipe is created, so closing the dialog
  // or leaving the page doesn't lose it.
  const createDraft = useDraft<CreateRecipeDraft>(draftKeys.newRecipe(), EMPTY_CREATE_RECIPE_DRAFT)
  const draft = { ...EMPTY_CREATE_RECIPE_DRAFT, ...createDraft.value }
  // "+ New recipe" from anywhere lands here with a request to open the dialog.
  const [seenCreateRequest, setSeenCreateRequest] = useState(createRequestKey)
  const [createModalOpen, setCreateModalOpen] = useState(createRequestKey != null && scope === 'mine')
  const [creating, setCreating] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)
  // "Import from file": the parsed file is kept here, not in the saved draft (it can be large, and a file
  // has to be picked again after a reload anyway).
  const [recipeFile, setRecipeFile] = useState<{ chosen: ChosenRecipeFile; doc: RecipeFile | null } | null>(null)
  const { notifySuccess, notifyInfo } = useNotifications()
  if (createRequestKey !== seenCreateRequest) {
    setSeenCreateRequest(createRequestKey)
    if (createRequestKey != null && scope === 'mine') {
      setCreateError(null)
      setCreateModalOpen(true)
    }
  }

  const [search, setSearch] = useState('')
  const [sort, setSort] = useState<RecipeSort>('newest')
  const [editingRecipe, setEditingRecipe] = useState<Recipe | null>(null)
  const [visibilityRecipe, setVisibilityRecipe] = useState<Recipe | null>(null)
  const [visibilityLoading, setVisibilityLoading] = useState(false)
  const [addingRecipeId, setAddingRecipeId] = useState<number | null>(null)

  const openCreateModal = () => {
    setCreateError(null)
    setCreateModalOpen(true)
  }

  const source = scope === 'mine' ? library.recipes : library.globalRecipes
  const listLoading = scope === 'mine' ? library.recipesLoading : library.globalRecipesLoading
  const listError = scope === 'mine' ? library.recipesError : library.globalRecipesError
  const reloadList = scope === 'mine' ? library.reloadRecipes : library.reloadGlobalRecipes

  const filteredRecipes = useMemo(() => sortRecipes(searchRecipes(source, search), sort), [source, search, sort])

  // Public recipes the user already added, mapped to their own copy, so a card offers "Open" instead of a duplicate.
  const ownCopyIdBySource = useMemo(() => {
    const map = new Map<number, number>()
    for (const recipe of library.recipes) {
      if (recipe.copiedFromRecipeId != null && !map.has(recipe.copiedFromRecipeId)) map.set(recipe.copiedFromRecipeId, recipe.id)
    }
    return map
  }, [library.recipes])

  // Long lists render a page at a time, revealing more as the user scrolls.
  const {
    visible: visibleRecipes,
    hasMore: hasMoreRecipes,
    sentinelRef: recipesSentinelRef,
    showMore: showMoreRecipes,
  } = useIncrementalReveal(filteredRecipes, `${scope}|${sort}|${search.trim().toLowerCase()}`)

  const closeCreateModal = () => {
    if (creating) return
    setCreateModalOpen(false)
    setCreateError(null)
  }

  /** Reads the picked file in the browser and checks it is a recipe file; its name fills an empty title. */
  const chooseRecipeFile = async (file: File) => {
    setCreateError(null)
    try {
      const doc = parseRecipeFile(await file.text(), file.name)
      setRecipeFile({ chosen: { fileName: file.name, ...summarizeRecipeFile(doc) }, doc })
      createDraft.setValue((current) => ({
        ...EMPTY_CREATE_RECIPE_DRAFT, ...current,
        title: current?.title?.trim() ? current.title : (doc.name ?? ''),
        description: current?.description?.trim() ? current.description : (doc.description ?? ''),
      }))
    } catch (error) {
      // The parser's message starts with the file's name; the dialog already shows it.
      const reason = errorMessage(error, 'it could not be read').replace(`${file.name}: `, '')
      setRecipeFile({ chosen: { fileName: file.name, error: reason }, doc: null })
    }
  }

  const handleImport = async (doc: RecipeFile) => {
    try {
      setCreating(true)
      setCreateError(null)
      const title = draft.title.trim()
      const imported = await createRecipeFromFile(doc, title, draft.description.trim())
      setCreateModalOpen(false)
      setRecipeFile(null)
      createDraft.clear()
      const missing = describeMissingAssets(imported)
      if (missing) notifyInfo(`Recipe imported. ${missing}`)
      else notifySuccess('Recipe imported.')
      onCreateRecipe?.({ recipeId: imported.recipeId, title, mode: 'import', recipeText: '' })
    } catch (error) {
      setCreateError(errorMessage(error, 'Unable to import this recipe'))
    } finally {
      setCreating(false)
    }
  }

  const handleCreate = async () => {
    if (!draft.title.trim()) {
      setCreateError('Please enter a recipe name')
      return
    }
    if (draft.mode === 'import') {
      if (!recipeFile?.doc) {
        setCreateError(recipeFile ? 'Choose a recipe file that can be imported.' : 'Choose a recipe file to import.')
        return
      }
      await handleImport(recipeFile.doc)
      return
    }
    if (draft.mode === 'ai' && !draft.recipeText.trim()) {
      setCreateError('Describe the dish for the AI, or choose “Start from scratch”.')
      return
    }
    try {
      setCreating(true)
      setCreateError(null)
      const title = draft.title.trim()
      const recipeId = await library.createRecipe(title, draft.description.trim())
      setCreateModalOpen(false)
      createDraft.clear()
      onCreateRecipe?.({ recipeId, title, mode: draft.mode, recipeText: draft.mode === 'ai' ? draft.recipeText.trim() : '' })
    } catch (error) {
      setCreateError(errorMessage(error, 'Unable to create recipe'))
    } finally {
      setCreating(false)
    }
  }

  const confirmToggleVisibility = async () => {
    if (!visibilityRecipe) return
    setVisibilityLoading(true)
    const done = await library.toggleVisibility(visibilityRecipe)
    setVisibilityLoading(false)
    if (done) setVisibilityRecipe(null)
  }

  const handleAddToMyRecipes = async (recipe: Recipe) => {
    setAddingRecipeId(recipe.id)
    await library.addToMyRecipes(recipe)
    setAddingRecipeId(null)
  }

  const searching = Boolean(search.trim())

  return (
    <div className="vk-page flow-editor-shell">
      <PageHeader
        eyebrow={copy.eyebrow}
        title={copy.title}
        subtitle={copy.subtitle}
        actions={scope === 'mine' ? (
          <>
            <Link className="vk-btn vk-btn-secondary" to="/kitchen/discover">🌍 Discover</Link>
            <button type="button" onClick={openCreateModal} className="vk-btn vk-btn-primary">
              <span aria-hidden="true">+</span> New recipe
            </button>
          </>
        ) : (
          <Link className="vk-btn vk-btn-secondary" to="/kitchen/recipes">📖 My Recipes</Link>
        )}
      />

      <RecipeToolbar
        search={search}
        onSearchChange={setSearch}
        sort={sort}
        onSortChange={setSort}
        recipeCount={filteredRecipes.length}
        placeholder={copy.search}
      />

      {listLoading && source.length === 0 ? (
        <SkeletonGrid count={6} label="Loading recipes…" />
      ) : listError ? (
        <ErrorState
          title={scope === 'mine' ? 'Couldn’t load your recipes' : 'Couldn’t load public recipes'}
          message={listError}
          onRetry={reloadList}
        />
      ) : filteredRecipes.length === 0 ? (
        searching ? (
          <EmptyState
            icon="🔍"
            title="No recipes found"
            text="Try a different name or ingredient."
            actions={<button type="button" className="vk-btn vk-btn-secondary" onClick={() => setSearch('')}>Clear search</button>}
          />
        ) : scope === 'discover' ? (
          <EmptyState
            icon="🌍"
            title="No public recipes yet"
            text="When cooks publish their recipes, they show up here. Make one of yours public to be the first."
            actions={<Link className="vk-btn vk-btn-primary" to="/kitchen/recipes">Go to My Recipes</Link>}
          />
        ) : (
          <EmptyState
            icon="🍳"
            title="No recipes yet"
            text="Create your first recipe — describe a dish and let AI draft the steps — or add one from Discover."
            actions={
              <>
                <button type="button" onClick={openCreateModal} className="vk-btn vk-btn-primary">Create your first recipe →</button>
                <Link className="vk-btn vk-btn-secondary" to="/kitchen/discover">Browse Discover</Link>
              </>
            }
          />
        )
      ) : (
        <>
          <div className="recipe-grid">
            {visibleRecipes.map((recipe) => (
              <RecipeCard
                key={recipe.id}
                recipe={recipe}
                variant={scope}
                onOpen={() => onOpenRecipe(recipe.id)}
                onEditInEditor={onEditRecipe ? () => onEditRecipe(recipe.id) : undefined}
                onEditDetails={() => setEditingRecipe(recipe)}
                onDelete={() => void library.deleteRecipe(recipe)}
                onToggleVisibility={() => setVisibilityRecipe(recipe)}
                onCopyLink={() => void library.copyLink(recipe)}
                onOrder={onOrderRecipe ? () => onOrderRecipe(recipe) : undefined}
                onAddToMyRecipes={() => void handleAddToMyRecipes(recipe)}
                adding={addingRecipeId === recipe.id}
                ownCopyId={scope === 'discover' ? ownCopyIdBySource.get(recipe.id) : undefined}
                onOpenOwnCopy={() => {
                  const copyId = ownCopyIdBySource.get(recipe.id)
                  if (copyId != null) onOpenRecipe(copyId)
                }}
              />
            ))}
          </div>
          <ListEnd
            hasMore={hasMoreRecipes}
            shown={visibleRecipes.length}
            total={filteredRecipes.length}
            noun="recipes"
            sentinelRef={recipesSentinelRef}
            onShowMore={showMoreRecipes}
          />
        </>
      )}

      <CreateRecipeModal
        open={createModalOpen}
        draft={draft}
        loading={creating}
        error={createError}
        onChange={(update) => createDraft.setValue((current) => ({ ...EMPTY_CREATE_RECIPE_DRAFT, ...current, ...update }))}
        onCreate={() => void handleCreate()}
        onClose={closeCreateModal}
        draftRestored={createDraft.restored}
        onClearDraft={createDraft.clear}
        chosenFile={recipeFile?.chosen ?? null}
        onChooseFile={(file) => void chooseRecipeFile(file)}
      />

      <EditRecipeDetailsModal
        recipe={editingRecipe}
        onSave={async (details) => {
          if (!editingRecipe) return
          await library.saveDetails(editingRecipe, details)
          setEditingRecipe(null)
        }}
        onClose={() => setEditingRecipe(null)}
      />

      <ConfirmVisibilityModal
        recipe={visibilityRecipe}
        loading={visibilityLoading}
        onConfirm={() => void confirmToggleVisibility()}
        onClose={() => {
          if (!visibilityLoading) setVisibilityRecipe(null)
        }}
      />
    </div>
  )
}
