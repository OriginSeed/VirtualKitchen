import { useEffect, useRef } from 'react'
import { CANVAS_SHORTCUTS } from '../hooks/useCanvasShortcuts'
import '../../styles/modals.css'

/** The editor's keyboard shortcuts ("?" or the ⌨ button). */
export default function ShortcutHelpDialog({ onClose }: { onClose: () => void }) {
  const closeRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    closeRef.current?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div
      className="recipe-modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <div className="recipe-modal" role="dialog" aria-modal="true" aria-labelledby="shortcut-help-title" style={{ width: 'min(100%, 460px)' }}>
        <div className="recipe-modal-header">
          <div>
            <h2 id="shortcut-help-title" className="text-lg font-bold text-[var(--flow-text)]">Keyboard shortcuts</h2>
            <p className="mt-1 text-sm text-[var(--flow-text-muted)]">They work anywhere on the canvas, except while typing in a field.</p>
          </div>
          <button ref={closeRef} type="button" onClick={onClose} className="recipe-modal-close" aria-label="Close">✕</button>
        </div>
        <div className="recipe-modal-body">
          <dl style={{ display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '8px 16px', margin: 0, fontSize: 13 }}>
            {CANVAS_SHORTCUTS.map((shortcut) => (
              <div key={shortcut.keys} style={{ display: 'contents' }}>
                <dt style={{ fontWeight: 700, color: 'var(--flow-text)', whiteSpace: 'nowrap' }}>{shortcut.keys}</dt>
                <dd style={{ margin: 0, color: 'var(--flow-text-muted)' }}>{shortcut.action}</dd>
              </div>
            ))}
          </dl>
        </div>
      </div>
    </div>
  )
}
