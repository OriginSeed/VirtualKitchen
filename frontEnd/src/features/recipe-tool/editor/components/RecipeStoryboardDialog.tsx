import { useEffect, useId, useRef, useState } from 'react'
import { RecipeStoryboardApi } from '../../../../api'
import type { RunModelCapability } from '../../../../types/recipeAiWorkflow'
import type { RecipeStoryboardResponse, StoryboardMode } from '../../../../types/recipe'
import { errorMessage } from '../../../../shared/errors'
import { useApiData } from '../../../../shared/hooks/useApiData'
import { isTrackedJobActive, storyboardJobKey } from '../../jobs/jobTracker'
import { useJobTracker, useTrackedJob } from '../../jobs/useJobTracker'
import { storyboardFailureText, storyboardProgress, storyboardStepSummary } from '../../model/storyboardView'
import RecipeStoryboardResult from '../../view/components/RecipeStoryboardResult'
import { ModelChoiceDialog } from '../../workflow/components/RunModelPicker'
import type { RunModels } from '../../workflow/model/workflowSelection'
import '../../styles/modals.css'

const STORYBOARD_MODELS: RunModelCapability[] = ['TEXT_TO_TEXT', 'TEXT_TO_IMAGE']

/** What the model dialog was opened for. */
type StartKind = 'generate' | 'retry' | 'fill' | 'replace'

const START_COPY: Record<StartKind, { title: string; confirm: string; description: string; mode: StoryboardMode }> = {
  generate: {
    title: '🎞️ Visualize the recipe', confirm: 'Visualize', mode: 'FILL',
    description: 'The text model plans the panels and writes the image prompt; the image model draws one image of panels. Steps that already have their own image keep it.',
  },
  retry: {
    title: '🎞️ Retry Visualize', confirm: 'Retry', mode: 'FILL',
    description: 'Carries on from where it stopped: work that already succeeded is not paid for again.',
  },
  fill: {
    title: '🎞️ Update visuals', confirm: 'Update', mode: 'FILL',
    description: 'If the recipe is unchanged, the panels are put back on any step that lost its image, with no AI cost. If it changed, a new image is drawn and steps with their own image keep it.',
  },
  replace: {
    title: '🎞️ Redo visuals', confirm: 'Redo everything', mode: 'REPLACE',
    description: 'Plans the panels again, writes a new image prompt, draws a new image and replaces every step image it covers, including ones you fixed.',
  },
}

/**
 * The recipe's storyboard: the whole recipe (Preparation and main process) told as panels in ONE AI image. Starts a
 * generation (after asking for the models), shows its progress, and shows the finished storyboard: the combined
 * image, the panels cut from it with what each one covers, and the prompt it was drawn from. The job is followed by
 * the app-level JobTracker, so it keeps running when this dialog is closed.
 */
export default function RecipeStoryboardDialog({ recipeId, onClose }: { recipeId: number; onClose: () => void }) {
  const jobTracker = useJobTracker()
  const tracked = useTrackedJob(storyboardJobKey(recipeId))
  const running = isTrackedJobActive(tracked)
  const trackedJob = tracked?.job

  // The stored storyboard is read again once a generation finishes (it then is the latest one).
  const finishedJobId = trackedJob && trackedJob.status === 'COMPLETED' ? trackedJob.jobId : ''
  const latest = useApiData(`recipe-storyboard:${recipeId}:${finishedJobId}`, () => RecipeStoryboardApi.getLatest(recipeId))

  const [startKind, setStartKind] = useState<StartKind | null>(null)
  const [starting, setStarting] = useState(false)
  const [startError, setStartError] = useState<string | null>(null)
  const titleId = useId()
  const closeRef = useRef<HTMLButtonElement>(null)
  const onCloseRef = useRef(onClose)
  const choosingModelsRef = useRef(false)
  useEffect(() => {
    onCloseRef.current = onClose
    choosingModelsRef.current = startKind != null
  })

  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    closeRef.current?.focus()
    const onKeyDown = (event: KeyboardEvent) => {
      // While the model dialog is open on top, Esc belongs to it.
      if (event.key !== 'Escape' || choosingModelsRef.current) return
      event.stopPropagation()
      event.preventDefault()
      onCloseRef.current()
    }
    window.addEventListener('keydown', onKeyDown, true)
    return () => {
      window.removeEventListener('keydown', onKeyDown, true)
      opener?.focus()
    }
  }, [])

  const start = async (kind: StartKind, modelKeys: RunModels) => {
    setStartKind(null)
    setStartError(null)
    setStarting(true)
    try {
      await jobTracker.startStoryboard(recipeId, { modelKeys, mode: START_COPY[kind].mode })
    } catch (error) {
      setStartError(errorMessage(error, 'Unable to visualize this recipe right now.'))
    } finally {
      setStarting(false)
    }
  }

  const failed = !running && trackedJob?.status === 'FAILED' ? trackedJob : null
  // A generation that just finished is shown straight away; the stored copy (which knows whether the recipe changed
  // since) replaces it once it is loaded.
  const justFinished = !running && trackedJob?.status === 'COMPLETED' ? trackedJob : null
  const storyboard: RecipeStoryboardResponse | null = latest.data && (!justFinished || latest.data.jobId === justFinished.jobId)
    ? latest.data
    : justFinished ?? latest.data ?? null
  const busy = running || starting
  const progress = running && trackedJob ? storyboardProgress(trackedJob) : null
  const loading = !latest.data && !latest.error && latest.loading && !trackedJob

  return (
    <div
      className="flow-canvas-export-modal-overlay"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <div
        className="flow-canvas-export-modal recipe-storyboard-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="recipe-storyboard-header">
          <div>
            <div id={titleId} className="recipe-storyboard-title">🎞️ Recipe visuals</div>
            <div className="recipe-storyboard-subtitle">
              The whole recipe drawn as one AI image of panels, one per cooking action. Each panel is also put on the last step it shows; fix any step image from that step.
            </div>
          </div>
          <button ref={closeRef} type="button" className="recipe-storyboard-close" onClick={onClose} aria-label="Close">✕</button>
        </div>

        <div className="recipe-storyboard-body">
          {startError && <div className="recipe-builder-status recipe-builder-status-error" role="alert">{startError}</div>}

          {progress && (
            <div className="recipe-storyboard-progress" role="status" aria-live="polite">
              <div className="recipe-storyboard-progress-label">
                {progress.label}
                {tracked?.connectionLost ? ' (connection lost, retrying…)' : ''}
              </div>
              <div className="recipe-builder-progress">
                <div className="recipe-builder-progress-track">
                  <div className="recipe-builder-progress-fill" style={{ width: `${progress.percent}%` }} />
                </div>
                <span className="recipe-builder-progress-label">{progress.stageNumber}/{progress.stageCount}</span>
              </div>
              <div className="recipe-storyboard-hint">This takes a minute or two. You can close this and keep working.</div>
            </div>
          )}

          {tracked?.pollError && (
            <div className="recipe-builder-status recipe-builder-status-error" role="alert">
              Lost track of Visualize: {tracked.pollError}
            </div>
          )}

          {failed && (
            <div className="recipe-builder-status recipe-builder-status-error" role="alert">
              {storyboardFailureText(failed)}
              <div className="recipe-storyboard-actions">
                <button type="button" className="recipe-storyboard-button is-primary" disabled={busy} onClick={() => setStartKind('retry')}>
                  Retry
                </button>
              </div>
            </div>
          )}

          {loading && <div className="recipe-storyboard-hint">Loading the storyboard…</div>}
          {latest.error && !storyboard && (
            <div className="recipe-builder-status recipe-builder-status-error" role="alert">{latest.error}</div>
          )}

          {!storyboard && !loading && !running && !failed && !latest.error && (
            <div className="recipe-storyboard-empty">
              <p>This recipe has no visuals yet.</p>
              <p className="recipe-storyboard-hint">
                AI reads the whole recipe, groups its steps into panels, and draws them as one image.
              </p>
              <button type="button" className="recipe-storyboard-button is-primary" disabled={busy} onClick={() => setStartKind('generate')}>
                Visualize
              </button>
            </div>
          )}

          {storyboard && <RecipeStoryboardResult storyboard={storyboard} showPanels showPrompt />}
        </div>

        {storyboard && (
          <div className="recipe-storyboard-footer">
            <span className="recipe-storyboard-hint">{storyboardStepSummary(storyboard)}</span>
            <div className="recipe-storyboard-actions">
              <button type="button" className="recipe-storyboard-button" disabled={busy} onClick={() => setStartKind('fill')}
                title="Put the panels back on steps that lost their image, or draw a new image if the recipe changed">
                {running ? 'Working…' : 'Update'}
              </button>
              <button type="button" className="recipe-storyboard-button" disabled={busy} onClick={() => setStartKind('replace')}
                title="Plan, prompt and draw again, replacing every step image it covers">
                Redo everything
              </button>
            </div>
          </div>
        )}
      </div>

      {startKind && (
        <ModelChoiceDialog
          title={START_COPY[startKind].title}
          description={START_COPY[startKind].description}
          capabilities={STORYBOARD_MODELS}
          confirmLabel={START_COPY[startKind].confirm}
          onConfirm={(modelKeys) => void start(startKind, modelKeys)}
          onCancel={() => setStartKind(null)}
        />
      )}
    </div>
  )
}
