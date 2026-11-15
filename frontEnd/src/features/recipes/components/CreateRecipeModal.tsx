import { useEffect, useId } from 'react'
import DraftRestoredNote from '../../../shared/drafts/DraftRestoredNote'
import type { CreateRecipeDraft, CreateRecipeMode } from './createRecipeDraft'

/** The recipe file chosen for an import: its name and what it holds, or why it can't be used. */
export type ChosenRecipeFile =
  | { fileName: string; error: string }
  | { fileName: string; error?: undefined; steps: number; processes: number; images: number; audio: number }

const plural = (count: number, noun: string, many = `${noun}s`) => `${count} ${count === 1 ? noun : many}`

type CreateRecipeModalProps = {
  open: boolean
  draft: CreateRecipeDraft
  loading: boolean
  error: string | null
  onChange: (update: Partial<CreateRecipeDraft>) => void
  onCreate: () => void
  onClose: () => void
  /** The fields were refilled from an unfinished earlier attempt; `onClearDraft` starts over. */
  draftRestored: boolean
  onClearDraft: () => void
  /** "Import from file": the file picked so far, and picking one. */
  chosenFile: ChosenRecipeFile | null
  onChooseFile: (file: File) => void
}

const MODES: Array<{ value: CreateRecipeMode; icon: string; title: string; text: string }> = [
  { value: 'ai', icon: '✨', title: 'Describe it for AI', text: 'Tell us the dish; AI drafts the steps for you to review.' },
  { value: 'blank', icon: '🧩', title: 'Start from scratch', text: 'Build the steps yourself in the editor.' },
  { value: 'import', icon: '📥', title: 'Import from file', text: 'Create it from a recipe file someone exported.' },
]

export default function CreateRecipeModal({
  open,
  draft,
  loading,
  error,
  onChange,
  onCreate,
  onClose,
  draftRestored,
  onClearDraft,
  chosenFile,
  onChooseFile,
}: CreateRecipeModalProps) {
  const id = useId()
  // Escape closes the dialog wherever focus is (it may have left the dialog after a click).
  useEffect(() => {
    if (!open) return undefined
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !loading) onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, loading, onClose])
  if (!open) {
    return null
  }

  const { title, description, mode, recipeText } = draft
  const hasContent = Boolean(title.trim() || description.trim() || recipeText.trim())

  return (
    <div
      className="recipe-modal-backdrop"
      onMouseDown={e => {
        if (e.target === e.currentTarget && !loading) {
          onClose()
        }
      }}
    >
      <div
        className="recipe-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby={`${id}-title`}
      >
        <div className="recipe-modal-header">
          <div>
            <div className="mb-1 text-[0.7rem] font-bold uppercase tracking-[0.14em] text-[var(--flow-accent)]">
              New recipe
            </div>

            <h2
              id={`${id}-title`}
              className="text-xl font-bold text-[var(--flow-text)]"
            >
              Create a recipe
            </h2>

            <p className="mt-1 text-sm text-[var(--flow-text-muted)]">
              Give it a name, then choose how to build its cooking steps.
            </p>
          </div>

          <button
            type="button"
            onClick={onClose}
            disabled={loading}
            className="recipe-modal-close"
            aria-label="Close"
          >
            ✕
          </button>
        </div>

        <form
          className="recipe-modal-body"
          onSubmit={e => {
            e.preventDefault()
            onCreate()
          }}
        >
          {draftRestored && hasContent && (
            <div className="mb-3"><DraftRestoredNote onDiscard={onClearDraft} /></div>
          )}
          <label className="block">
            <div className="mb-1.5 text-xs font-bold text-[var(--flow-text)]">
              Recipe name
            </div>

            <input
              autoFocus
              value={title}
              onChange={e => onChange({ title: e.target.value })}
              placeholder="e.g. Spaghetti Aglio e Olio"
              className="flow-editor-input"
              maxLength={120}
            />
          </label>

          <label className="mt-4 block">
            <div className="mb-1.5 text-xs font-bold text-[var(--flow-text)]">
              Short description
              <span className="ml-1 font-normal text-[var(--flow-text-subtle)]">
                optional
              </span>
            </div>

            <input
              value={description}
              onChange={e => onChange({ description: e.target.value })}
              placeholder="A one-line summary shown on the recipe card"
              className="flow-editor-input"
              maxLength={1000}
            />
          </label>

          <fieldset className="create-recipe-modes mt-4">
            <legend className="mb-1.5 text-xs font-bold text-[var(--flow-text)]">How do you want to start?</legend>
            {MODES.map((option) => (
              <label key={option.value} className={`create-recipe-mode${mode === option.value ? ' is-selected' : ''}`}>
                <input
                  type="radio"
                  name={`${id}-mode`}
                  value={option.value}
                  checked={mode === option.value}
                  onChange={() => onChange({ mode: option.value })}
                />
                <span className="create-recipe-mode-icon" aria-hidden="true">{option.icon}</span>
                <span>
                  <strong>{option.title}</strong>
                  <small>{option.text}</small>
                </span>
              </label>
            ))}
          </fieldset>

          {mode === 'ai' && (
            <label className="mt-4 block">
              <div className="mb-1.5 text-xs font-bold text-[var(--flow-text)]">
                Describe the dish
              </div>

              <textarea
                value={recipeText}
                onChange={e => onChange({ recipeText: e.target.value })}
                placeholder="e.g. Classic garlic pasta for 2: spaghetti, garlic, olive oil, chilli flakes, parsley. Toast the garlic gently, toss with the pasta and some pasta water."
                rows={4}
                className="flow-editor-input resize-none"
              />
              <span className="mt-1 block text-[0.72rem] text-[var(--flow-text-muted)]">
                Ingredients, amounts and how you cook it all help. You’ll see the cost and approve the draft before anything is applied.
              </span>
            </label>
          )}

          {mode === 'import' && (
            <div className="mt-4">
              <div className="mb-1.5 text-xs font-bold text-[var(--flow-text)]">Recipe file</div>
              <input
                type="file"
                accept=".json,application/json"
                aria-label="Recipe file"
                className="flow-editor-input"
                disabled={loading}
                onChange={e => {
                  const file = e.target.files?.[0]
                  if (file) onChooseFile(file)
                }}
              />
              {chosenFile?.error && (
                <div className="recipe-form-error" role="alert">
                  This file can’t be imported: {chosenFile.error}
                </div>
              )}
              {chosenFile && 'steps' in chosenFile && (
                <span className="mt-1 block text-[0.72rem] text-[var(--flow-text-muted)]" role="status">
                  {chosenFile.fileName}: {plural(chosenFile.steps, 'step')} in {plural(chosenFile.processes, 'process', 'processes')}
                  , {plural(chosenFile.images, 'image')} and {plural(chosenFile.audio, 'narration')}.
                  The steps are created as they are, with no AI. Images and narration are copied into your own recipe.
                </span>
              )}
              {!chosenFile && (
                <span className="mt-1 block text-[0.72rem] text-[var(--flow-text-muted)]">
                  Choose a <code>.recipe.json</code> file exported from the recipe editor (Export, or ⋯ → Export recipe).
                </span>
              )}
            </div>
          )}

          {error && (
            <div className="recipe-form-error" role="alert">
              {error}
            </div>
          )}

          {/* Lets Enter in a single-line field submit the form. */}
          <button type="submit" hidden aria-hidden="true" tabIndex={-1} />
        </form>

        <div className="recipe-modal-footer">
          <button
            type="button"
            onClick={onClose}
            disabled={loading}
            className="recipe-secondary-button"
          >
            Cancel
          </button>

          <button
            type="button"
            onClick={onCreate}
            disabled={loading}
            className="recipe-primary-button"
          >
            {loading ? (mode === 'import' ? 'Importing...' : 'Creating...') : mode === 'ai' ? 'Create & open AI' : mode === 'import' ? 'Import & open editor' : 'Create & open editor'}
            {!loading && <span aria-hidden="true">→</span>}
          </button>
        </div>
      </div>
    </div>
  )
}
