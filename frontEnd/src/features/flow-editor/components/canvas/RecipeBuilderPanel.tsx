import { useState } from 'react'

type RecipeBuilderPanelProps = {
  collapsed: boolean
  isGenerating: boolean
  onToggleCollapsed: () => void
  onGenerate: (recipeText: string) => Promise<void>
}

const PLACEHOLDER = `Describe your recipe here...

Example:

Take two cups of rice.
Wash twice.
Add four cups of water.
Cook for twenty minutes.`

export default function RecipeBuilderPanel({
  collapsed,
  isGenerating,
  onToggleCollapsed,
  onGenerate,
}: RecipeBuilderPanelProps) {
  const [recipeText, setRecipeText] = useState('')
  const [lastGeneratedText, setLastGeneratedText] = useState('')
  const [feedback, setFeedback] = useState<string | null>(null)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  const runGeneration = async (value: string) => {
    const normalizedValue = value.trim()

    if (!normalizedValue) {
      setFeedback(null)
      setErrorMessage('Enter a recipe before generating a flow.')
      return
    }

    try {
      setErrorMessage(null)
      setFeedback(null)
      await onGenerate(normalizedValue)
      setLastGeneratedText(normalizedValue)
      setFeedback('Workflow generated. You can now edit nodes, reconnect steps, and save as usual.')
    } catch (error) {
      setFeedback(null)
      setErrorMessage(error instanceof Error ? error.message : 'Unable to generate the workflow right now.')
    }
  }

  const handleClear = () => {
    setRecipeText('')
    setFeedback(null)
    setErrorMessage(null)
  }

  if (collapsed) {
    return (
      <div className="recipe-builder-panel recipe-builder-panel-collapsed">
        <button
          type="button"
          className="recipe-builder-collapse-tab"
          onClick={onToggleCollapsed}
          aria-label="Expand AI recipe builder"
          title="Expand AI Recipe Builder"
        >
          <span className="recipe-builder-collapse-tab-label">AI Builder</span>
          <span className="recipe-builder-collapse-tab-icon">›</span>
        </button>
      </div>
    )
  }

  return (
    <aside className="recipe-builder-panel" aria-label="AI recipe builder">
      <div className="recipe-builder-panel-inner">
        <div className="recipe-builder-header">
          <div>
            <div className="recipe-builder-kicker">AI Recipe Builder</div>
            <h2 className="recipe-builder-title">Turn natural language into a flow</h2>
            <p className="recipe-builder-description">
              Describe the recipe once, generate the first workflow, then refine it directly on the canvas.
            </p>
          </div>
          <button
            type="button"
            className="recipe-builder-icon-button"
            onClick={onToggleCollapsed}
            aria-label="Collapse AI recipe builder"
            title="Collapse panel"
          >
            ‹
          </button>
        </div>

        <div className="recipe-builder-surface">
          <div className="recipe-builder-surface-label">Recipe brief</div>
          <textarea
            value={recipeText}
            onChange={(event) => setRecipeText(event.target.value)}
            className="recipe-builder-textarea"
            placeholder={PLACEHOLDER}
            rows={16}
          />
          <div className="recipe-builder-helper-row">
            <span className="recipe-builder-helper-pill">Natural language</span>
            <span className="recipe-builder-helper-pill">Long-form steps supported</span>
            <span className="recipe-builder-helper-pill">Editable after generation</span>
          </div>
        </div>

        {(feedback || errorMessage) && (
          <div className={errorMessage ? 'recipe-builder-status recipe-builder-status-error' : 'recipe-builder-status'} role={errorMessage ? 'alert' : 'status'}>
            {errorMessage ?? feedback}
          </div>
        )}

        <div className="recipe-builder-actions">
          <button
            type="button"
            className="recipe-builder-primary-action"
            onClick={() => void runGeneration(recipeText)}
            disabled={isGenerating}
          >
            {isGenerating ? (
              <>
                <span className="recipe-builder-spinner" aria-hidden="true" />
                Generating flow...
              </>
            ) : (
              'Generate Flow'
            )}
          </button>

          <button
            type="button"
            className="recipe-builder-secondary-action"
            onClick={handleClear}
            disabled={isGenerating && !recipeText}
          >
            Clear
          </button>

          {lastGeneratedText && (
            <button
              type="button"
              className="recipe-builder-secondary-action"
              onClick={() => void runGeneration(recipeText || lastGeneratedText)}
              disabled={isGenerating}
            >
              Regenerate
            </button>
          )}
        </div>
      </div>
    </aside>
  )
}
