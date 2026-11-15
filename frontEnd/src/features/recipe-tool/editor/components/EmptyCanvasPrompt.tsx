import '../styles/EmptyCanvasPrompt.css'

/**
 * Shown over an empty canvas, so a first-time user knows how to begin: add a step, add a check (condition),
 * or let AI draft the whole flow.
 */
export default function EmptyCanvasPrompt({ isPreparation, onAddStep, onAddCondition, onGenerateWithAi }: {
  isPreparation: boolean
  onAddStep: () => void
  onAddCondition: () => void
  /** Omitted where AI can't help (the Preparation, which AI Recipe Creation fills together with Main). */
  onGenerateWithAi?: () => void
}) {
  return (
    <div className="empty-canvas-prompt" role="region" aria-label="Start building this process">
      <div className="empty-canvas-card">
        <div className="empty-canvas-icon" aria-hidden="true">{isPreparation ? '🥣' : '🍳'}</div>
        <h3>{isPreparation ? 'No preparation steps yet' : 'Start your recipe’s cooking steps'}</h3>
        <p>
          {isPreparation
            ? 'Prepare components (a sauce, a dough, chopped vegetables) here; the main process uses what they produce.'
            : 'Each step is one action — chop, boil, mix. Add a condition when the cook has to check something, like “Is the pasta al dente?”.'}
        </p>
        <div className="empty-canvas-actions">
          {onGenerateWithAi && (
            <button type="button" className="empty-canvas-button is-primary" onClick={onGenerateWithAi}>
              ✨ Generate with AI
            </button>
          )}
          <button type="button" className={`empty-canvas-button${onGenerateWithAi ? '' : ' is-primary'}`} onClick={onAddStep}>
            ▭ Add first step
          </button>
          <button type="button" className="empty-canvas-button" onClick={onAddCondition}>
            ◇ Add a condition
          </button>
        </div>
        <p className="empty-canvas-hint">Connect steps by dragging from one node’s edge to the next. Press <kbd>?</kbd> for shortcuts.</p>
      </div>
    </div>
  )
}
