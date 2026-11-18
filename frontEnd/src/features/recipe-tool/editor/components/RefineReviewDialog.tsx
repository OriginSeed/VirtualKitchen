import { useEffect, useId, useRef } from 'react'
import type { RecipeProcessGenerationResult } from '../../../../types/recipe'
import '../../styles/modals.css'

/** Steps (not checks) in a refined result, and how many preparation flows it has. */
const countResult = (result: RecipeProcessGenerationResult) => {
  const steps = (process: { steps?: { nodeType: string }[] | null }) => (process.steps ?? []).filter((step) => step.nodeType !== 'CONDITION').length
  const flows = result.subprocesses ?? []
  return { steps: steps(result.mainProcess) + flows.reduce((sum, flow) => sum + steps(flow), 0), flows: flows.length }
}

/**
 * Shows what AI refinement would change in the cook's flow before anything is replaced: the corrections it made
 * and the size of the refined flow. Applying replaces the flow in one undoable step; keeping leaves it as it is.
 */
export default function RefineReviewDialog({ result, currentStepCount, onApply, onKeep }: {
  result: RecipeProcessGenerationResult
  currentStepCount: number
  onApply: () => void
  onKeep: () => void
}) {
  const titleId = useId()
  const applyRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    applyRef.current?.focus()
  }, [])
  const changes = result.changes ?? []
  const refined = countResult(result)

  return (
    <div className="flow-canvas-export-modal-overlay">
      <div className="flow-canvas-export-modal recipe-storyboard-dialog" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <div className="recipe-storyboard-header">
          <div>
            <div id={titleId} className="recipe-storyboard-title">✨ Review the refined flow</div>
            <div className="recipe-storyboard-subtitle">
              AI checked your flow before the next AI step. Nothing changes until you apply it, and Undo brings your version back.
            </div>
          </div>
        </div>
        <div className="recipe-storyboard-body">
          <div className="recipe-storyboard-hint">
            Your flow: {currentStepCount} step{currentStepCount === 1 ? '' : 's'} · Refined: {refined.steps} step{refined.steps === 1 ? '' : 's'}
            {refined.flows > 0 ? ` (${refined.flows} preparation flow${refined.flows === 1 ? '' : 's'} + main process)` : ''}
          </div>
          {changes.length > 0 ? (
            <ul style={{ margin: '8px 0 0', paddingLeft: 18, fontSize: 12.5, lineHeight: 1.5 }}>
              {changes.map((change, index) => <li key={index}>{change}</li>)}
            </ul>
          ) : (
            <p style={{ fontSize: 12.5 }}>Nothing needed fixing beyond adding step summaries and titles.</p>
          )}
          {(result.warnings ?? []).length > 0 && (
            <div className="recipe-builder-status recipe-builder-status-error" role="status" style={{ marginTop: 8 }}>
              {(result.warnings ?? []).join(' · ')}
            </div>
          )}
        </div>
        <div className="recipe-storyboard-footer">
          <button type="button" className="recipe-storyboard-button" onClick={onKeep}>Keep my flow</button>
          <button ref={applyRef} type="button" className="recipe-storyboard-button is-primary" onClick={onApply}>Apply refined flow</button>
        </div>
      </div>
    </div>
  )
}
