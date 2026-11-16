import type { RecipeStoryboardResponse } from '../../../../types/recipe'
import ZoomableImage from '../../../../shared/components/image-viewer/ZoomableImage'
import { storyboardLayoutText, storyboardPanelMeta, storyboardPanelTitle } from '../../model/storyboardView'
import '../styles/recipe-storyboard.css'

/**
 * A finished storyboard: the combined image, then every panel (cut from it) with what it covers, and for the owner
 * the prompt it was drawn from. Shown in the editor's storyboard dialog and on the Recipe Process page.
 */
export default function RecipeStoryboardResult({ storyboard }: { storyboard: RecipeStoryboardResponse }) {
  const title = storyboard.title?.trim() || 'Recipe'
  return (
    <div className="recipe-storyboard-result">
      {storyboard.outdated && (
        <div className="recipe-storyboard-note" role="status">
          The recipe changed after this storyboard was made. Generate it again to bring it up to date.
        </div>
      )}
      {storyboard.warnings.map((warning) => (
        <div key={warning} className="recipe-storyboard-note" role="status">{warning}</div>
      ))}

      {storyboard.imageUrl && (
        <figure className="recipe-storyboard-image">
          <ZoomableImage
            src={storyboard.imageUrl}
            alt={`Storyboard of ${title}`}
            caption={`${title} — ${storyboardLayoutText(storyboard)}`}
            loading="lazy"
          />
          <figcaption>{storyboardLayoutText(storyboard)}, read left to right, top to bottom. Click to enlarge.</figcaption>
        </figure>
      )}

      <ol className="recipe-storyboard-panels">
        {storyboard.panels.map((panel) => (
          <li key={panel.panelId} className="recipe-storyboard-panel">
            {panel.imageUrl ? (
              <ZoomableImage
                src={panel.imageUrl}
                alt={storyboardPanelTitle(panel)}
                caption={storyboardPanelTitle(panel)}
                loading="lazy"
              />
            ) : (
              <div className="recipe-storyboard-panel-placeholder" aria-hidden>{panel.sequence}</div>
            )}
            <div className="recipe-storyboard-panel-text">
              <div className="recipe-storyboard-panel-title">{storyboardPanelTitle(panel)}</div>
              <div className="recipe-storyboard-panel-meta">{storyboardPanelMeta(panel)}</div>
              {panel.expectedState && <div className="recipe-storyboard-panel-state">{panel.expectedState}</div>}
            </div>
          </li>
        ))}
      </ol>

      {storyboard.masterPrompt && (
        <details className="recipe-storyboard-prompt">
          <summary>Image prompt</summary>
          <pre>{storyboard.masterPrompt}</pre>
        </details>
      )}
    </div>
  )
}
