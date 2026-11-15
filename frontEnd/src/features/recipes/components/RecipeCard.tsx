import { useState } from 'react'
import type { Recipe } from '../../../api'
import recipeIcon from '../../../assets/kitchen/recipeIcon.png'
import ActionMenu, { type ActionMenuItem } from '../../../shared/components/ActionMenu'
import { ImageZoomButton } from '../../../shared/components/image-viewer/ZoomableImage'
import { formatDate } from '../../../shared/format/date'
import type { RecipeScope } from '../useRecipeLibrary'

type RecipeCardProps = {
  recipe: Recipe
  variant: RecipeScope
  highlighted?: boolean
  /** Opens the recipe to read (its Recipe Process). */
  onOpen: () => void
  // My Recipes: everything else lives in the card's ⋯ menu.
  onEditInEditor?: () => void
  onEditDetails?: () => void
  onDelete?: () => void
  onToggleVisibility?: () => void
  onCopyLink?: () => void
  onOrder?: () => void
  // Discover
  onAddToMyRecipes?: () => void
  /** Discover: the user's own copy of this recipe, if they already added it. */
  ownCopyId?: number | null
  /** Opens the user's own copy (shown instead of "Add" once they have one). */
  onOpenOwnCopy?: () => void
  /** An "Add" for this card is in progress. */
  adding?: boolean
}

export default function RecipeCard({
  recipe,
  variant,
  highlighted,
  onOpen,
  onEditInEditor,
  onEditDetails,
  onDelete,
  onToggleVisibility,
  onCopyLink,
  onOrder,
  onAddToMyRecipes,
  ownCopyId,
  onOpenOwnCopy,
  adding = false,
}: RecipeCardProps) {
  // Falls back to the recipe icon when there is no thumbnail or it fails to load.
  const [failedThumbnail, setFailedThumbnail] = useState<string | null>(null)
  const showThumbnail = Boolean(recipe.thumbnailUrl) && recipe.thumbnailUrl !== failedThumbnail
  const isPublic = recipe.visibility === 'PUBLIC'

  const menuItems: ActionMenuItem[] = variant === 'mine'
    ? [
        ...(onEditInEditor ? [{ label: '🧩 Edit in editor', onSelect: onEditInEditor }] : []),
        ...(onToggleVisibility ? [{ label: isPublic ? '🔒 Make private' : '🌐 Make public', onSelect: onToggleVisibility }] : []),
        ...(isPublic && onCopyLink ? [{ label: '🔗 Copy link', onSelect: onCopyLink }] : []),
        ...(onOrder ? [{ label: '🧾 Order', onSelect: onOrder }] : []),
        ...(onEditDetails ? [{ label: '✏️ Edit name & description', onSelect: onEditDetails }] : []),
        ...(onDelete ? [{ label: '🗑️ Delete recipe', onSelect: onDelete, danger: true }] : []),
      ]
    : []

  return (
    <article className={`recipe-card group${highlighted ? ' recipe-card-highlighted' : ''}`}>
      {/* The whole card opens the recipe in read mode. */}
      <button
        type="button"
        onClick={onOpen}
        className="recipe-card-content"
        aria-label={`Open ${recipe.name}`}
      >
        <div className="recipe-card-image">
          {showThumbnail ? (
            <img
              src={recipe.thumbnailUrl!}
              alt=""
              className="recipe-card-thumbnail"
              loading="lazy"
              onError={() => setFailedThumbnail(recipe.thumbnailUrl ?? null)}
            />
          ) : (
            <div className="recipe-card-image-placeholder">
              <img src={recipeIcon} alt="" className="recipe-card-logo" />
            </div>
          )}
        </div>

        <div className="recipe-card-body">
          <h3 className="line-clamp-1 text-base font-bold text-[var(--flow-text)]">
            {recipe.name}
          </h3>

          {recipe.description?.trim() && (
            <p className="recipe-card-description">
              {recipe.description}
            </p>
          )}

          {(recipe.createdAt || recipe.copiedFromRecipeId != null) && (
            <span className="recipe-card-meta">
              {recipe.createdAt && formatDate(recipe.createdAt)}
              {variant === 'mine' && recipe.copiedFromRecipeId != null && (
                <>{recipe.createdAt ? ' · ' : ''}Added from Discover</>
              )}
            </span>
          )}
        </div>
      </button>

      {/* Overlays on the image corners — siblings of the open-button, so they never open the recipe. */}
      {showThumbnail && (
        <div className="recipe-card-zoom-anchor">
          <ImageZoomButton src={recipe.thumbnailUrl!} alt={recipe.name} caption={recipe.name} />
        </div>
      )}
      {variant === 'mine' && (
        <span
          className={`recipe-card-visibility${isPublic ? ' is-public' : ''}`}
          title={isPublic ? 'Public: anyone can find it in Discover' : 'Private: only you can see it'}
        >
          <span aria-hidden="true">{isPublic ? '🌐' : '🔒'}</span>
          {isPublic ? 'Public' : 'Private'}
        </span>
      )}

      {menuItems.length > 0 && (
        <ActionMenu
          items={menuItems}
          label={`More actions for ${recipe.name}`}
          className="recipe-card-actions"
        />
      )}

      {variant === 'discover' && (
        <div className="recipe-card-footer">
          {ownCopyId != null ? (
            <button
              type="button"
              onClick={onOpenOwnCopy}
              className="recipe-card-order-button"
              title="You already added this recipe — open your copy"
            >
              ✓ In My Recipes · Open
            </button>
          ) : (
            <button
              type="button"
              onClick={onAddToMyRecipes}
              disabled={adding}
              className="recipe-card-order-button"
              title="Add your own private copy, which you can edit and order"
            >
              {adding ? 'Adding…' : '+ Add to My Recipes'}
            </button>
          )}
        </div>
      )}
    </article>
  )
}
