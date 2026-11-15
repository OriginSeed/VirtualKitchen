import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react'
import { ImageZoomButton } from '../../../shared/components/image-viewer/ZoomableImage'
import { useImageLightbox } from '../../../shared/components/image-viewer/useImageLightbox'
import NarrationControls from './narration/NarrationControls'
import type { NarrationPhase } from './narration/narrationPlayer'
import { useNarrationPlayer } from './narration/useNarrationPlayer'
import AnimatedInstruction, { type InstructionRevealMode, type ProgressSource } from './AnimatedInstruction'
import './RecipeVisualizationSlideshow.css'
import '../styles/modals.css'

export type SlideshowStep = {
  id: string
  title: string
  /** The step's own instruction sentence (its action description), shown as the slide's main text. */
  instruction?: string
  /** Supporting details (targets, timing, expected result) shown under the title. */
  description?: string
  imageUrl?: string
  stepNumber?: number
  /** The caption's eyebrow, e.g. "Marinate chicken · Step 2 of 18" — defaults to "Step N of M". */
  label?: string
  /** A check's answers: each jumps to the step (or check) with `targetId`. */
  choices?: { key: string; label: string; detail: string; targetId: string }[]
  /** Never narrated (e.g. a check) — only used for a recipe-wide walkthrough. */
  silent?: boolean
}

/** How the instruction follows the current step's narration phase (see AnimatedInstruction). */
const revealModeFor = (phase: NarrationPhase): InstructionRevealMode => {
  switch (phase) {
    case 'playing':
    case 'paused':
      return 'audio'
    case 'loading':
      // The narration is about to start; the text then follows it from the first word.
      return 'hidden'
    case 'ended':
    case 'generating':
    case 'blocked':
      // Nothing to follow (yet): keep the whole step readable.
      return 'full'
    default:
      // idle / unavailable / failed: no narration to follow, so a short reading reveal.
      return 'timed'
  }
}

type StepStageProps = {
  step: SlideshowStep
  /** The narration script from the backend: exactly what the audio says, so it wins over the step's own text. */
  narrationText?: string | null
  revealMode: InstructionRevealMode
  paused: boolean
  progress: ProgressSource
  narrationDurationSeconds?: number | null
}

/**
 * One step's visual area. The instruction is always shown; the image is an enhancement on top of
 * it: until the image has actually loaded, the text fills the stage, and once it has, the image
 * fades in and the text moves to a caption overlay. Image state never touches narration.
 */
function StepStage({ step, narrationText, revealMode, paused, progress, narrationDurationSeconds }: StepStageProps) {
  const imageUrl = step.imageUrl
  const [image, setImage] = useState<{ url: string; status: 'loaded' | 'error' } | null>(null)
  const imageStatus = !imageUrl ? 'none' : image?.url === imageUrl ? image.status : 'loading'
  const showImage = imageStatus === 'loaded'
  // Until the narration state has loaded (or when a step has none), fall back to the step's own text.
  const text = narrationText?.trim() || step.instruction?.trim() || step.title.trim() || 'Untitled step'
  // Full-size view: click the image (mouse/touch) or its corner button (keyboard). Opens over the walkthrough.
  const lightbox = useImageLightbox()
  const fullImage = imageUrl ? { src: imageUrl, alt: step.title, caption: step.instruction?.trim() || step.title } : null

  return (
    <div className={`recipe-slideshow-body ${showImage ? 'recipe-slideshow-body-image' : 'recipe-slideshow-body-text'}`}>
      {imageUrl && imageStatus !== 'error' && (
        <img
          key={imageUrl}
          className={`recipe-slideshow-image${showImage ? ' is-loaded' : ''}`}
          src={imageUrl}
          alt={step.title}
          style={showImage ? { cursor: 'zoom-in' } : undefined}
          onClick={() => fullImage && lightbox.open(fullImage)}
          onLoad={() => setImage({ url: imageUrl, status: 'loaded' })}
          onError={() => setImage({ url: imageUrl, status: 'error' })}
        />
      )}
      {showImage && fullImage && <ImageZoomButton {...fullImage} onOpen={lightbox.open} />}
      {lightbox.element}
      <AnimatedInstruction
        // A different text (e.g. the narration script arriving) restarts the reveal cleanly.
        key={text}
        text={text}
        mode={revealMode}
        paused={paused}
        progress={progress}
        fallbackDurationSeconds={narrationDurationSeconds}
        variant={showImage ? 'overlay' : 'full'}
      />
      {!showImage && (
        <span className="recipe-slideshow-media-status">
          {imageStatus === 'loading' ? 'Image loading…' : imageStatus === 'error' ? 'Image unavailable' : '🖼️ Image not generated yet'}
        </span>
      )}
    </div>
  )
}

type RecipeVisualizationSlideshowProps = {
  steps: SlideshowStep[]
  recipeId: number
  /**
   * The process being previewed, or null for a recipe-wide walkthrough (Cook mode), whose step ids
   * are recipe-wide ids (see recipeStepId) spanning several processes.
   */
  processId: number | null
  /** Settles once the editor's pending edits are saved; narration waits for it (see useNarrationPlayer). */
  narrationReady?: Promise<boolean> | null
  /** Header title — defaults to "🎬 Recipe Visualization". */
  heading?: string
  onClose: () => void
}

/**
 * Step-by-step walkthrough of a process — or, as Cook mode, of a whole recipe. In play mode each step is narrated aloud and the next
 * step follows when its narration ends (steps without narration stay up for a reading-time dwell).
 * All playback rules live in NarrationPlayer; this component only renders its state.
 */
export default function RecipeVisualizationSlideshow({
  steps,
  recipeId,
  processId,
  narrationReady,
  heading = '🎬 Recipe Visualization',
  onClose,
}: RecipeVisualizationSlideshowProps) {
  const playerSteps = useMemo(
    () => steps.map((step) => ({
      id: step.id,
      text: [step.title, step.instruction, step.description].filter(Boolean).join('. '),
    })),
    [steps],
  )
  const silentStepIds = useMemo(() => steps.filter((step) => step.silent).map((step) => step.id), [steps])
  const { player, state, unavailable, syncWarning } = useNarrationPlayer({
    recipeId,
    processId,
    steps: playerSteps,
    silentStepIds,
    ready: narrationReady,
  })

  const hasSteps = steps.length > 0
  const index = Math.min(state.index, Math.max(0, steps.length - 1))
  const currentStep = hasSteps ? steps[index] : undefined

  // Slide direction follows the index change, whoever caused it (buttons or auto-advance).
  const [view, setView] = useState({ index, direction: 'next' as 'next' | 'prev' })
  if (view.index !== index) {
    setView({ index, direction: index > view.index ? 'next' : 'prev' })
  }

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (target && ['INPUT', 'SELECT', 'TEXTAREA'].includes(target.tagName)) return
      // A focused button already activates on Space; handling it here too would toggle twice.
      if (event.key === ' ' && target?.tagName === 'BUTTON') return
      if (event.key === 'ArrowRight') player.next()
      else if (event.key === 'ArrowLeft') player.previous()
      else if (event.key === ' ') {
        event.preventDefault()
        player.toggle()
      } else if (event.key === 'Escape') onClose()
      else return
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [player, onClose])

  // A real dialog: focus moves in on open (and back to whatever opened it on close), and Tab stays inside.
  const titleId = useId()
  const dialogRef = useRef<HTMLDivElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null
    closeRef.current?.focus()
    return () => opener?.focus?.()
  }, [])
  const trapFocus = (event: ReactKeyboardEvent) => {
    if (event.key !== 'Tab' || !dialogRef.current) return
    const focusable = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(
      'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])',
    )).filter((element) => element.offsetParent !== null)
    if (focusable.length === 0) return
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  const isPlaying = state.autoplay && state.phase !== 'blocked'
  const playLabel = isPlaying ? '⏸ Pause' : state.finished ? '↺ Play again' : '▶ Play'

  return (
    <div className="flow-canvas-export-modal-overlay" onClick={onClose}>
      <div
        ref={dialogRef}
        className="recipe-slideshow-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onClick={(event) => event.stopPropagation()}
        onKeyDown={trapFocus}
      >
        <div className="recipe-slideshow-header">
          <div id={titleId} className="recipe-slideshow-title">{heading}</div>
          <button ref={closeRef} type="button" className="recipe-slideshow-close-btn" onClick={onClose} aria-label="Close">✕</button>
        </div>

        {!hasSteps ? (
          <div className="recipe-slideshow-empty">
            No recipe steps yet. Add steps to the flow to preview them here.
          </div>
        ) : (
          <>
            <div
              key={currentStep?.id ?? index}
              className={`recipe-slideshow-stage recipe-slideshow-stage-${view.direction}`}
            >
              {currentStep && (
                <StepStage
                  step={currentStep}
                  narrationText={state.narrations[currentStep.id]?.text}
                  revealMode={revealModeFor(state.phase)}
                  paused={state.phase === 'paused'}
                  progress={player}
                  narrationDurationSeconds={state.narrations[currentStep.id]?.durationSeconds}
                />
              )}

              <div className="recipe-slideshow-caption">
                <div className="recipe-slideshow-step-label">
                  {currentStep?.label ?? `Step ${currentStep?.stepNumber ?? index + 1} of ${steps.length}`}
                </div>
                {/* With a custom label the title is optional (Cook mode leaves it out when the instruction says it all). */}
                {(currentStep?.title || currentStep?.label == null) && (
                  <div className="recipe-slideshow-step-title">{currentStep?.title || 'Untitled step'}</div>
                )}
                {currentStep?.description && (
                  <div className="recipe-slideshow-step-description">{currentStep.description}</div>
                )}
                {currentStep?.choices && currentStep.choices.length > 0 && (
                  <div className="recipe-slideshow-choices">
                    {currentStep.choices.map((choice) => (
                      <button
                        key={choice.key}
                        type="button"
                        className="recipe-slideshow-choice"
                        onClick={() => {
                          const target = steps.findIndex((step) => step.id === choice.targetId)
                          if (target >= 0) player.goTo(target)
                        }}
                      >
                        <strong>{choice.label}</strong>
                        <span>{choice.detail}</span>
                      </button>
                    ))}
                  </div>
                )}
              </div>
            </div>

            <div className="recipe-slideshow-controls">
              <button
                type="button"
                className="recipe-slideshow-control-btn"
                onClick={() => player.previous()}
                disabled={index === 0}
              >
                ⏮ Previous
              </button>
              <button
                type="button"
                className="recipe-slideshow-control-btn recipe-slideshow-play-btn"
                onClick={() => player.toggle()}
              >
                {playLabel}
              </button>
              <button
                type="button"
                className="recipe-slideshow-control-btn"
                onClick={() => player.next()}
                disabled={index === steps.length - 1}
              >
                Next ⏭
              </button>
            </div>

            <NarrationControls
              player={player}
              state={state}
              stepId={currentStep?.id}
              unavailable={unavailable}
              syncWarning={syncWarning}
            />
          </>
        )}
      </div>
    </div>
  )
}
