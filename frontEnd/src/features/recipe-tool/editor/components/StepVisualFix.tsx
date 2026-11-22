import { useState } from 'react'
import type { RunModelCapability } from '../../../../types/recipeAiWorkflow'
import { errorMessage, friendlyJobError } from '../../../../shared/errors'
import { isTrackedJobActive, stepFixJobKey } from '../../jobs/jobTracker'
import { useJobTracker, useTrackedJob } from '../../jobs/useJobTracker'
import { ModelChoiceDialog } from '../../workflow/components/RunModelPicker'
import ZoomableImage from '../../../../shared/components/image-viewer/ZoomableImage'

const FIX_MODELS: RunModelCapability[] = ['TEXT_TO_TEXT', 'TEXT_TO_IMAGE']

/**
 * A step's visual in the editor: its image and image prompt, and "Fix this image": the cook says what is wrong, the
 * prompt is rewritten from it and the image redrawn with the current one as the reference. The fix runs in the
 * background (followed by the app's job tracker) and can be undone once.
 */
export default function StepVisualFix({ recipeId, processId, stepId, imageUrl, imagePrompt }: {
  recipeId: number
  processId: number
  stepId: string
  imageUrl?: string
  imagePrompt?: string
}) {
  const jobTracker = useJobTracker()
  const tracked = useTrackedJob(stepFixJobKey(recipeId, stepId))
  const running = isTrackedJobActive(tracked)
  const fix = tracked?.job
  const [feedback, setFeedback] = useState('')
  const [choosingModels, setChoosingModels] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const start = async (modelKeys: Parameters<typeof jobTracker.startStepFix>[4]) => {
    setChoosingModels(false)
    setError(null)
    try {
      await jobTracker.startStepFix(recipeId, processId, stepId, feedback.trim(), modelKeys)
      setFeedback('')
    } catch (failure) {
      setError(errorMessage(failure, 'Unable to fix this image right now.'))
    }
  }

  const undo = async () => {
    setError(null)
    try {
      await jobTracker.revertStepFix(recipeId, stepId)
    } catch (failure) {
      setError(errorMessage(failure, 'Unable to undo the fix.'))
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {imageUrl ? (
        <div style={{ width: '100%', aspectRatio: '16 / 10', borderRadius: 10, overflow: 'hidden', border: '1px solid var(--flow-border)', background: '#0f172a' }}>
          <ZoomableImage
            src={imageUrl}
            alt="Step visual"
            caption="Step visual"
            style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }}
          />
        </div>
      ) : (
        <div style={{ fontSize: 11, color: 'var(--flow-text-subtle)' }}>
          No image on this step. Visualize (in the ✨ AI menu) puts one image on the last step of each panel; describe one below to draw it for this step.
        </div>
      )}
      {imagePrompt && (
        <details style={{ fontSize: 11, color: 'var(--flow-text-muted)' }}>
          <summary style={{ cursor: 'pointer' }}>Image prompt</summary>
          <div style={{ marginTop: 4, whiteSpace: 'pre-wrap' }}>{imagePrompt}</div>
        </details>
      )}

      <label className="flow-properties-label" htmlFor={`fix-${stepId}`}>
        {imageUrl ? 'Fix this image' : 'Draw an image for this step'}
      </label>
      <textarea
        id={`fix-${stepId}`}
        className="flow-properties-textarea"
        rows={2}
        value={feedback}
        disabled={running}
        onChange={(event) => setFeedback(event.target.value)}
        placeholder={imageUrl ? 'What is wrong? e.g. "The onions should be golden, and use a cast iron pan"' : 'What should it show?'}
        maxLength={1000}
      />
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        <button
          type="button"
          className="recipe-storyboard-button is-primary"
          disabled={running || !feedback.trim()}
          onClick={() => setChoosingModels(true)}
        >
          {running ? 'Redrawing…' : imageUrl ? '✨ Fix image' : '✨ Draw image'}
        </button>
        {fix?.status === 'COMPLETED' && !fix.reverted && (
          <button type="button" className="recipe-storyboard-button" onClick={() => void undo()} title="Put back the image and prompt the step had before this fix">
            Undo fix
          </button>
        )}
      </div>
      {running && <div role="status" style={{ fontSize: 11, color: 'var(--flow-magic)' }}>Rewriting the prompt and redrawing the image…</div>}
      {fix?.status === 'COMPLETED' && fix.promptFromTemplate && !fix.reverted && (
        <div style={{ fontSize: 10.5, color: 'var(--flow-text-subtle)' }}>Your feedback was added to the old prompt as it was.</div>
      )}
      {fix?.status === 'FAILED' && <div role="alert" style={{ fontSize: 11, color: 'var(--flow-danger)' }}>{friendlyJobError(fix.errorMessage, 'The image could not be fixed.')}</div>}
      {error && <div role="alert" style={{ fontSize: 11, color: 'var(--flow-danger)' }}>{error}</div>}

      {choosingModels && (
        <ModelChoiceDialog
          title={imageUrl ? '✨ Fix this image' : '✨ Draw this step'}
          description="The text model rewrites the image prompt from your feedback; the image model redraws it, using the current image as the reference when it can."
          capabilities={FIX_MODELS}
          confirmLabel={imageUrl ? 'Fix image' : 'Draw image'}
          onCancel={() => setChoosingModels(false)}
          onConfirm={(modelKeys) => void start(modelKeys)}
        />
      )}
    </div>
  )
}
