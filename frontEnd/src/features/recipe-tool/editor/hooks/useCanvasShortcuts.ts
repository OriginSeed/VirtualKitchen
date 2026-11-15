import { useEffect } from 'react'

/** Every editor shortcut, for the "?" help popover (kept next to the handler so the two never drift apart). */
export const CANVAS_SHORTCUTS: Array<{ keys: string; action: string }> = [
  { keys: 'Ctrl/⌘ + S', action: 'Save now (changes also save automatically)' },
  { keys: 'Ctrl/⌘ + Z', action: 'Undo' },
  { keys: 'Ctrl/⌘ + Y  or  Ctrl/⌘ + Shift + Z', action: 'Redo' },
  { keys: 'Delete / Backspace', action: 'Delete the selected step, condition or connection' },
  { keys: '?', action: 'Show these shortcuts' },
]

/**
 * Keyboard shortcuts (Ctrl/Cmd+S, Delete/Backspace, Ctrl/Cmd+Z, Ctrl/Cmd+Y or Ctrl/Cmd+Shift+Z, ?) — a global
 * listener so shortcuts work regardless of which element inside the canvas currently has focus, not only while
 * the React Flow pane itself is focused. Skips typing in an input/textarea/select or an editable text (the @
 * description editor) — except Save, which works everywhere so the browser's own "Save page" never opens — and
 * is scoped to specific key combos only, so it never interferes with browser/app navigation shortcuts.
 *
 * This is the *only* keyboard-delete path — React Flow's own built-in one is disabled (`deleteKeyCode={null}`
 * on the canvas) so a single press can't be handled twice, and so edges (whose selection React Flow tracks
 * independently) are covered too.
 */
export function useCanvasShortcuts({ onDelete, onUndo, onRedo, onSave, onShowHelp, isActive }: {
  onDelete: () => void
  onUndo: () => void
  onRedo: () => void
  onSave?: () => void
  onShowHelp?: () => void
  /** False while the editor is hidden (the Recipe Process tab), so keys there never edit an unseen canvas. */
  isActive?: () => boolean
}) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (isActive && !isActive()) return
      const mod = event.ctrlKey || event.metaKey
      const key = event.key.toLowerCase()
      if (mod && key === 's' && onSave) {
        event.preventDefault()
        onSave()
        return
      }
      const target = event.target as HTMLElement | null
      const tag = target?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target?.isContentEditable) return
      // Inside an editor that sits over the canvas (the step popup), keys belong to that editor.
      if (target?.closest?.('[data-canvas-shortcuts="off"]')) return
      if (event.key === 'Delete' || event.key === 'Backspace') onDelete()
      if (mod && key === 'z' && !event.shiftKey) { event.preventDefault(); onUndo() }
      if (mod && (key === 'y' || (key === 'z' && event.shiftKey))) { event.preventDefault(); onRedo() }
      if (event.key === '?' && !mod && onShowHelp) { event.preventDefault(); onShowHelp() }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [onDelete, onUndo, onRedo, onSave, onShowHelp, isActive])
}
