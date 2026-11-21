import { useState } from 'react'
import { Link } from 'react-router-dom'

type ScreenOrientationWithLock = ScreenOrientation & { lock?: (orientation: 'landscape') => Promise<void> }

/** Full screen plus a landscape lock: Android browsers allow it; iPhones can't lock the orientation from a page. */
function canLockLandscape() {
  if (typeof document === 'undefined' || typeof screen === 'undefined') return false
  const orientation = screen.orientation as ScreenOrientationWithLock | undefined
  return Boolean(document.fullscreenEnabled && 'requestFullscreen' in document.documentElement && orientation?.lock)
}

/**
 * The Recipe Editor on a phone held upright: the canvas, its panels and the step editor need the width of a
 * sideways phone, so this asks to rotate (the editor appears as soon as the phone turns) and offers the reading
 * view instead. Cook mode stays one tap away in the bar above.
 */
export default function RotateToEditPrompt({ processPath }: { processPath: string }) {
  const [lockFailed, setLockFailed] = useState(false)
  const showLock = canLockLandscape() && !lockFailed

  const editInLandscape = async () => {
    try {
      await document.documentElement.requestFullscreen()
      await (screen.orientation as ScreenOrientationWithLock).lock?.('landscape')
    } catch {
      // Not allowed here (e.g. a desktop browser in a narrow window): rotating by hand still works.
      setLockFailed(true)
      if (document.fullscreenElement) void document.exitFullscreen().catch(() => undefined)
    }
  }

  return (
    <div className="rt-rotate-prompt" role="note">
      <div className="rt-rotate-icon" aria-hidden>📱</div>
      <h2>Turn your phone sideways to edit</h2>
      <p>
        The recipe editor needs a wider screen. Rotate your phone and the editor opens here. To read the recipe or
        cook along, use the Recipe Process or ▶ Cook mode.
      </p>
      <div className="rt-rotate-actions">
        {showLock && (
          <button type="button" className="vk-btn vk-btn-primary" onClick={() => void editInLandscape()}>
            ⤢ Edit in full screen
          </button>
        )}
        <Link className={`vk-btn ${showLock ? 'vk-btn-secondary' : 'vk-btn-primary'}`} to={processPath} replace>
          📖 Open the Recipe Process
        </Link>
      </div>
    </div>
  )
}
