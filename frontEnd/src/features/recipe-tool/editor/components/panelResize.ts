import type { PointerEvent as ReactPointerEvent } from 'react'

/**
 * Starts dragging a side panel's resize handle: follows the pointer (mouse, pen or finger) until it's released,
 * reporting the new width clamped to [minWidth, maxWidth()]. `side` is where the panel sits — dragging towards
 * the canvas grows it. `maxWidth` is read on every move, so it can leave room for the other panel and the canvas.
 */
export function startPanelResize(event: ReactPointerEvent, { side, startWidth, minWidth, maxWidth, onResize }: {
  side: 'left' | 'right'
  startWidth: number
  minWidth: number
  maxWidth: () => number
  onResize: (width: number) => void
}) {
  if (event.pointerType === 'mouse' && event.button !== 0) return
  const handle = event.currentTarget as HTMLElement
  const pointerId = event.pointerId
  const startX = event.clientX
  // Keeps receiving the drag even when the pointer leaves the thin handle.
  handle.setPointerCapture?.(pointerId)

  const onMove = (moveEvent: PointerEvent) => {
    if (moveEvent.pointerId !== pointerId) return
    const delta = side === 'left' ? moveEvent.clientX - startX : startX - moveEvent.clientX
    const effectiveMax = maxWidth()
    let nextWidth = startWidth + delta
    if (nextWidth < minWidth) nextWidth = minWidth
    if (nextWidth > effectiveMax) nextWidth = Math.max(minWidth, effectiveMax)
    onResize(nextWidth)
  }
  const onUp = (upEvent: PointerEvent) => {
    if (upEvent.pointerId !== pointerId) return
    handle.releasePointerCapture?.(pointerId)
    handle.removeEventListener('pointermove', onMove)
    handle.removeEventListener('pointerup', onUp)
    handle.removeEventListener('pointercancel', onUp)
  }
  handle.addEventListener('pointermove', onMove)
  handle.addEventListener('pointerup', onUp)
  handle.addEventListener('pointercancel', onUp)
  event.preventDefault()
}
