import { useSyncExternalStore } from 'react'
import { PLAYBACK_RATES, type NarrationPlayer, type NarrationPlayerState } from './narrationPlayer'
import './NarrationControls.css'
import { friendlyJobError } from '../../../../shared/errors'

const formatTime = (seconds: number) => {
  const whole = Math.max(0, Math.floor(seconds))
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`
}

/**
 * Position and seek bar for the current step's narration. Reads the progress stream directly, so
 * only this bar re-renders while audio plays (at most ten times a second, by its rounding).
 */
function NarrationSeekBar({ player, seekable }: { player: NarrationPlayer; seekable: boolean }) {
  const snapshot = useSyncExternalStore(player.subscribeProgress, () => {
    const { currentTime, duration } = player.getProgress()
    return `${Math.round(currentTime * 10)}|${Number.isFinite(duration) && duration > 0 ? Math.round(duration * 10) : 0}`
  })
  const [position, length] = snapshot.split('|').map((part) => Number(part) / 10)
  const enabled = seekable && length > 0

  return (
    <div className="narration-seek-row">
      <input
        type="range"
        className="narration-seek"
        min={0}
        max={length || 1}
        step={0.1}
        value={Math.min(position, length || 1)}
        onChange={(event) => player.seek(Number(event.target.value))}
        disabled={!enabled}
        aria-label="Narration position"
      />
      <span className="narration-seek-time">
        {formatTime(position)} / {length > 0 ? formatTime(length) : '–:––'}
      </span>
    </div>
  )
}

type NarrationControlsProps = {
  player: NarrationPlayer
  state: NarrationPlayerState
  stepId: string | undefined
  unavailable: boolean
  syncWarning: boolean
}

/** Status text for the current step's narration; empty when there is nothing worth saying. */
const describe = (state: NarrationPlayerState, stepId: string | undefined, unavailable: boolean) => {
  if (unavailable) return { tone: 'muted', text: 'Save the recipe to enable narration' }
  if (!state.narrationEnabled) return { tone: 'muted', text: 'Narration off' }
  const known = stepId ? state.narrations[stepId] : undefined
  switch (state.phase) {
    case 'loading':
      return { tone: 'info', text: 'Loading narration…' }
    case 'generating':
      return { tone: 'info', text: 'Generating narration…' }
    case 'playing':
      return { tone: 'active', text: state.muted ? 'Narrating (muted)' : 'Narrating' }
    case 'paused':
      return { tone: 'muted', text: 'Narration paused' }
    case 'ended':
      return { tone: 'muted', text: 'Narration finished' }
    case 'blocked':
      return { tone: 'warn', text: 'Your browser blocked audio. Press Play to start narration' }
    case 'unavailable':
      return { tone: 'muted', text: 'No narration for this step' }
    case 'failed':
      return { tone: 'error', text: `Narration unavailable${state.failureReason ? `: ${friendlyJobError(state.failureReason)}` : ''}` }
    default:
      if (known?.status === 'READY') return { tone: 'muted', text: 'Narration ready' }
      if (known?.status === 'GENERATING') return { tone: 'info', text: 'Generating narration…' }
      if (known?.status === 'STALE') return { tone: 'muted', text: 'Step changed since its narration was made' }
      if (known && !known.narratable) return { tone: 'muted', text: 'No narration for this step' }
      return { tone: 'muted', text: '' }
  }
}

export default function NarrationControls({ player, state, stepId, unavailable, syncWarning }: NarrationControlsProps) {
  const status = describe(state, stepId, unavailable)
  const audioControlsDisabled = unavailable || !state.narrationEnabled

  return (
    <div className="narration-controls">
      {!audioControlsDisabled && (
        <NarrationSeekBar player={player} seekable={state.phase === 'playing' || state.phase === 'paused'} />
      )}
      <div className="narration-controls-row">
        <button
          type="button"
          className="narration-control-btn"
          onClick={() => player.replay()}
          disabled={audioControlsDisabled || !stepId}
          title="Replay this step's narration"
        >
          🔁 Replay
        </button>
        <button
          type="button"
          className="narration-control-btn"
          onClick={() => player.setMuted(!state.muted)}
          disabled={audioControlsDisabled}
          aria-pressed={state.muted}
          title={state.muted ? 'Unmute narration' : 'Mute narration'}
        >
          {state.muted ? '🔇 Muted' : '🔊 Sound'}
        </button>
        <label className="narration-speed">
          <span>Speed</span>
          <select
            value={state.playbackRate}
            onChange={(event) => player.setPlaybackRate(Number(event.target.value))}
            disabled={audioControlsDisabled}
          >
            {PLAYBACK_RATES.map((rate) => (
              <option key={rate} value={rate}>{`${Number.isInteger(rate) ? rate.toFixed(1) : rate}×`}</option>
            ))}
          </select>
        </label>
        <label className="narration-toggle" title="Read each step aloud">
          <input
            type="checkbox"
            checked={state.narrationEnabled}
            onChange={(event) => player.setNarrationEnabled(event.target.checked)}
            disabled={unavailable}
          />
          <span>Narration</span>
        </label>
      </div>

      {(status.text || syncWarning) && (
        <div className="narration-status-row" role="status" aria-live="polite">
          {status.text && <span className={`narration-status narration-status-${status.tone}`}>{status.text}</span>}
          {state.phase === 'failed' && (
            <button type="button" className="narration-link-btn" onClick={() => player.retry()}>
              Retry
            </button>
          )}
          {syncWarning && (
            <span className="narration-status narration-status-warn">
              Latest edits aren't saved yet, so narration follows the last saved version
            </span>
          )}
        </div>
      )}
    </div>
  )
}
