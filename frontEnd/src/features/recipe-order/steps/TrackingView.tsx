import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { RecipeOrderApi } from '../../../api'
import { RecipeImage, type RecipeTimelineStep } from '../../recipe-tool'
import CancelOrderButton from '../components/CancelOrderButton'
import DemoNotice from '../components/DemoNotice'
import IngredientUsageList from '../components/IngredientUsageList'
import OrderSummaryCard from '../components/OrderSummaryCard'
import SnapshotModal from '../components/SnapshotModal'
import SuggestionsPanel from '../components/SuggestionsPanel'
import TrackingTimeline from '../components/TrackingTimeline'
import { SHOW_ORDER_MOCKS } from '../orderFeatures'
import { buildOrderPresentation, flattenPresentationSteps } from '../model/orderPresentation'
import {
  canAdvanceStatus,
  deriveTrackingStages,
  nextActionLabel,
  trackingProgressPercent,
} from '../model/trackingModel'
import type { OrderStepProps } from './stepProps'

/** Delay between auto-played stages. */
const AUTO_PLAY_MS = 2500

type TrackingTab = 'timeline' | 'suggestions'

function CurrentStepCard({ step, index, total }: { step: RecipeTimelineStep | undefined; index: number; total: number }) {
  return (
    <div className="ro-current-step">
      {step?.imageUrl && (
        <div className="ro-current-step-media">
          <RecipeImage src={step.imageUrl} alt={`Step ${index + 1}`} fallbackIcon={step.icon} />
        </div>
      )}
      <div className="ro-current-step-text">
        <div className="ro-eyebrow">Step {index + 1} of {total}</div>
        <p className="ro-current-step-instruction">{step?.instructionText || 'Working on the next step of the recipe.'}</p>
        {step?.explanation && <p className="ro-muted">{step.explanation}</p>}
        {step?.readyWhen && <p className="ro-muted">Ready when: {step.readyWhen}</p>}
      </div>
    </div>
  )
}

/**
 * Step 5: tracking the simulated preparation and delivery. Every stage change comes from the
 * backend: "Next step" (or auto-play) asks it to advance from the status/step shown here, and the
 * timeline is re-derived from its answer — this page never moves an order on by itself.
 */
export default function TrackingView({ order, onOrderChange, onError }: OrderStepProps) {
  const presentation = useMemo(() => buildOrderPresentation(order.recipe?.processes), [order.recipe?.processes])
  const steps = useMemo(() => flattenPresentationSteps(presentation), [presentation])
  const stages = deriveTrackingStages(order.status, order.currentStepIndex, order.totalSteps)
  const cooking = stages.find((stage) => stage.id === 'cooking')?.stepProgress

  const [tab, setTab] = useState<TrackingTab>('timeline')
  const [advancing, setAdvancing] = useState(false)
  const [autoPlay, setAutoPlay] = useState(false)
  const [snapshotOpen, setSnapshotOpen] = useState(false)
  // Purely illustrative: counts down locally, nothing is requested.
  const [snapshotsLeft, setSnapshotsLeft] = useState(order.demo?.snapshotRequests ?? 0)
  const inFlight = useRef(false)

  const canAdvance = order.allowedActions.includes('ADVANCE') && canAdvanceStatus(order.status)
  const actionLabel = nextActionLabel(order.status, order.currentStepIndex, order.totalSteps)
  const completed = order.status === 'COMPLETED'

  const advance = useCallback(async () => {
    // One request at a time, whether from a click or auto-play.
    if (inFlight.current) return
    inFlight.current = true
    setAdvancing(true)
    try {
      onOrderChange(await RecipeOrderApi.advance(order.id, order.status, order.currentStepIndex))
    } catch (error) {
      setAutoPlay(false)
      onError(error)
    } finally {
      inFlight.current = false
      setAdvancing(false)
    }
  }, [order.id, order.status, order.currentStepIndex, onOrderChange, onError, setAutoPlay])

  // Auto-play: the next stage is requested only once the previous request has finished (each
  // response re-runs this effect), and stops when there's nothing left to advance, on an error
  // (advance turns it off), or when the page unmounts (the cleanup clears the pending timer).
  const autoPlaying = autoPlay && canAdvance
  useEffect(() => {
    if (!autoPlaying || advancing) return
    const timer = window.setTimeout(() => void advance(), AUTO_PLAY_MS)
    return () => window.clearTimeout(timer)
  }, [autoPlaying, advancing, advance])

  const currentStep = cooking ? steps[cooking.index] : undefined
  const snapshotImage = currentStep?.imageUrl ?? steps.find((step) => step.imageUrl)?.imageUrl

  return (
    <div className="ro-stack">
      <OrderSummaryCard order={order}>
        <div className="ro-progress" aria-label="Progress">
          <div className="ro-progress-bar" style={{ width: `${trackingProgressPercent(stages)}%` }} />
        </div>
      </OrderSummaryCard>

      <DemoNotice variant="strong" icon="🧑‍🍳">
        Simulated preparation — no real chef/kitchen is connected in this prototype.
      </DemoNotice>

      {/* The Suggestions tab (chef chat) is a mock-up; without it there is only the timeline, so no tabs. */}
      {SHOW_ORDER_MOCKS && <div className="ro-tabs" role="tablist" aria-label="Tracking">
        {(['timeline', 'suggestions'] as const).map((id) => (
          <button
            key={id}
            type="button"
            role="tab"
            id={`ro-tab-${id}`}
            aria-selected={tab === id}
            aria-controls={`ro-panel-${id}`}
            className={`ro-tab${tab === id ? ' ro-tab-active' : ''}`}
            onClick={() => setTab(id)}
          >
            {id === 'timeline' ? 'Timeline' : 'Suggestions'}
          </button>
        ))}
      </div>}

      {tab === 'timeline' || !SHOW_ORDER_MOCKS ? (
        <div id="ro-panel-timeline" role="tabpanel" aria-labelledby="ro-tab-timeline" className="ro-stack">
          {completed ? (
            <section className="ro-card ro-complete">
              <div className="ro-complete-icon" aria-hidden>🎉</div>
              <h3 className="ro-card-title">Delivered — enjoy your meal!</h3>
              <p className="ro-muted">The simulated preparation and delivery are complete. These ingredients were used from your inventory:</p>
              <IngredientUsageList order={order} />
              <div className="ro-actions">
                <Link to="/kitchen/orders" className="ro-btn ro-btn-primary">Back to Orders</Link>
              </div>
            </section>
          ) : (
            <section className="ro-card ro-controls" aria-label="Simulation controls">
              <div className="ro-controls-text">
                <strong>Simulation controls</strong>
                <span className="ro-muted">Move the simulated kitchen on one stage at a time, or let it play.</span>
              </div>
              <div className="ro-actions">
                {SHOW_ORDER_MOCKS && (
                  <button
                    type="button"
                    className="ro-btn ro-btn-ghost"
                    onClick={() => {
                      setSnapshotsLeft((value) => Math.max(0, value - 1))
                      setSnapshotOpen(true)
                    }}
                    disabled={snapshotsLeft <= 0}
                  >
                    📷 Request Snapshot · {snapshotsLeft} remaining
                  </button>
                )}
                {order.status !== 'CONFIRMED' && canAdvance && (
                  <button
                    type="button"
                    className={`ro-btn ro-btn-ghost${autoPlaying ? ' ro-btn-on' : ''}`}
                    aria-pressed={autoPlaying}
                    onClick={() => setAutoPlay((value) => !value)}
                  >
                    {autoPlaying ? '⏸ Pause auto-play' : '▶ Auto-play'}
                  </button>
                )}
                {actionLabel && (
                  <button type="button" className="ro-btn ro-btn-primary" onClick={() => void advance()} disabled={!canAdvance || advancing}>
                    {advancing ? 'Updating…' : actionLabel}
                  </button>
                )}
              </div>
            </section>
          )}

          <section className="ro-card">
            <TrackingTimeline
              stages={stages}
              timestamps={order.timestamps}
              cookingDetail={cooking ? <CurrentStepCard step={currentStep} index={cooking.index} total={cooking.total} /> : null}
            />
          </section>

          {order.allowedActions.includes('CANCEL') && (
            <div className="ro-actions">
              <CancelOrderButton order={order} onOrderChange={onOrderChange} onError={onError} />
            </div>
          )}
        </div>
      ) : (
        <div id="ro-panel-suggestions" role="tabpanel" aria-labelledby="ro-tab-suggestions" className="ro-card">
          <SuggestionsPanel />
        </div>
      )}

      {SHOW_ORDER_MOCKS && <SnapshotModal open={snapshotOpen} onClose={() => setSnapshotOpen(false)} imageUrl={snapshotImage} />}
    </div>
  )
}
