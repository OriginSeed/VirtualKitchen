import type { Recipe } from '../../../api'

type ConfirmVisibilityModalProps = {
  recipe: Recipe | null
  loading: boolean
  onConfirm: () => void
  onClose: () => void
}

export default function ConfirmVisibilityModal({
  recipe,
  loading,
  onConfirm,
  onClose,
}: ConfirmVisibilityModalProps) {
  if (!recipe) {
    return null
  }

  const makingPublic = recipe.visibility !== 'PUBLIC'

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
        aria-labelledby="confirm-visibility-title"
      >
        <div className="recipe-modal-header">
          <div>
            <div className="mb-1 text-[0.7rem] font-bold uppercase tracking-[0.14em] text-[var(--flow-accent)]">
              {makingPublic ? 'Make public' : 'Make private'}
            </div>

            <h2
              id="confirm-visibility-title"
              className="text-xl font-bold text-[var(--flow-text)]"
            >
              {makingPublic ? 'Make this recipe public?' : 'Make this recipe private?'}
            </h2>
          </div>

          <button
            onClick={onClose}
            disabled={loading}
            className="recipe-modal-close"
            aria-label="Close"
          >
            ✕
          </button>
        </div>

        <div className="recipe-modal-body">
          <p className="text-sm text-[var(--flow-text-muted)]">
            {makingPublic
              ? 'Anyone browsing Discover will be able to view this recipe and add a copy of it to their own kitchen. You can make it private again at any time.'
              : 'This recipe will be removed from Discover and other users will no longer be able to find or add it. Copies already added to other kitchens will not be affected.'}
          </p>
        </div>

        <div className="recipe-modal-footer">
          <button
            onClick={onClose}
            disabled={loading}
            className="recipe-secondary-button"
          >
            Cancel
          </button>

          <button
            onClick={onConfirm}
            disabled={loading}
            className="recipe-primary-button"
          >
            {loading ? 'Updating...' : `Confirm`}
            {!loading && <span aria-hidden="true">→</span>}
          </button>
        </div>
      </div>
    </div>
  )
}
