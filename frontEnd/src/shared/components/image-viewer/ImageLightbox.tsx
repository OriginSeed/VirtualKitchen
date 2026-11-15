import { useEffect, useId, useRef, type SyntheticEvent } from 'react'
import { createPortal } from 'react-dom'
import './ImageLightbox.css'

export type ImageLightboxProps = {
  src: string
  /** Describes the image; also names the dialog when there is no caption. */
  alt: string
  /** Shown under the image (e.g. the step title). */
  caption?: string
  onClose: () => void
}

const FOCUSABLE = 'button:not([disabled]), [href], [tabindex]:not([tabindex="-1"])'

/** Synthetic events bubble through a portal to its React parents (a card button, an editor node...): keep them here. */
const stop = (event: SyntheticEvent) => event.stopPropagation()

/**
 * Full-size view of one image, over everything else. A modal dialog: focus moves to the close button, Tab stays
 * inside, and focus returns to what opened it. Esc, the close button and a click on the backdrop close it. While it
 * is open no key reaches the page underneath, so an open slideshow or the editor's shortcuts don't react as well.
 */
export default function ImageLightbox({ src, alt, caption, onClose }: ImageLightboxProps) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  const captionId = useId()
  // Read through a ref so a new onClose from the parent doesn't re-run the focus effect below.
  const onCloseRef = useRef(onClose)
  useEffect(() => {
    onCloseRef.current = onClose
  })

  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    closeRef.current?.focus()
    const { overflow } = document.body.style
    document.body.style.overflow = 'hidden'

    const onKeyDown = (event: KeyboardEvent) => {
      // Capture phase on window: runs before any other key handler, and none of them sees the key.
      event.stopPropagation()
      if (event.key === 'Escape') {
        event.preventDefault()
        onCloseRef.current()
        return
      }
      if (event.key !== 'Tab' || !dialogRef.current) return
      const focusable = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(FOCUSABLE))
      if (focusable.length === 0) return
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      const inside = dialogRef.current.contains(document.activeElement)
      if (event.shiftKey && (document.activeElement === first || !inside)) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && (document.activeElement === last || !inside)) {
        event.preventDefault()
        first.focus()
      }
    }

    window.addEventListener('keydown', onKeyDown, true)
    return () => {
      window.removeEventListener('keydown', onKeyDown, true)
      document.body.style.overflow = overflow
      opener?.focus()
    }
  }, [])

  return createPortal(
    <div
      className="image-lightbox-backdrop"
      onMouseDown={(event) => {
        event.stopPropagation()
        if (event.target === event.currentTarget) onClose()
      }}
      onClick={stop}
      onPointerDown={stop}
      onDoubleClick={stop}
      onKeyDown={stop}
      onWheel={stop}
    >
      <div
        ref={dialogRef}
        className="image-lightbox"
        role="dialog"
        aria-modal="true"
        aria-label={caption ? undefined : alt || 'Image'}
        aria-labelledby={caption ? captionId : undefined}
      >
        <button ref={closeRef} type="button" className="image-lightbox-close" onClick={onClose} aria-label="Close image">
          ✕
        </button>
        <figure className="image-lightbox-figure">
          <img className="image-lightbox-image" src={src} alt={alt} />
          {caption && <figcaption id={captionId} className="image-lightbox-caption">{caption}</figcaption>}
        </figure>
      </div>
    </div>,
    document.body,
  )
}
