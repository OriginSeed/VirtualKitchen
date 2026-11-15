import type { ImgHTMLAttributes, MouseEvent } from 'react'
import { useImageLightbox, type LightboxImage } from './useImageLightbox'

const describe = (alt: string, caption?: string) => alt.trim() || caption?.trim() || 'image'

type ZoomableImageProps = Omit<ImgHTMLAttributes<HTMLImageElement>, 'src' | 'alt'> & {
  src: string
  alt: string
  /** Shown under the full-size image; also what the trigger says it opens when `alt` is empty. */
  caption?: string
  /** Class for the wrapping button (the `<img>` keeps `className`). */
  triggerClassName?: string
}

/**
 * An image that opens full size in the {@link ImageLightbox} when clicked (or activated from the keyboard). Use it
 * where the image is not inside another clickable element; there, use {@link ImageZoomButton}.
 */
export default function ZoomableImage({ src, alt, caption, triggerClassName, ...imgProps }: ZoomableImageProps) {
  const lightbox = useImageLightbox()
  return (
    <>
      <button
        type="button"
        className={`image-zoom-trigger${triggerClassName ? ` ${triggerClassName}` : ''}`}
        aria-label={`View full image: ${describe(alt, caption)}`}
        onClick={() => lightbox.open({ src, alt, caption })}
      >
        <img src={src} alt={alt} {...imgProps} />
      </button>
      {lightbox.element}
    </>
  )
}

type ImageZoomButtonProps = {
  src: string
  alt: string
  caption?: string
  /** Extra classes, e.g. React Flow's `nodrag nopan` inside an editor node. */
  className?: string
  /** Opens the image with a lightbox the caller already has (see useImageLightbox) instead of its own. */
  onOpen?: (image: LightboxImage) => void
}

/**
 * A small corner button that opens an image full size, for images inside something already clickable (a card that
 * opens a recipe, an editor node). Place it as a sibling of that element, inside a `position: relative` box —
 * a button can't sit inside another button. Clicks don't reach the parent.
 */
export function ImageZoomButton({ src, alt, caption, className, onOpen }: ImageZoomButtonProps) {
  const lightbox = useImageLightbox()
  const handleClick = (event: MouseEvent) => {
    event.preventDefault()
    event.stopPropagation()
    ;(onOpen ?? lightbox.open)({ src, alt, caption })
  }
  return (
    <>
      <button
        type="button"
        className={`image-zoom-button${className ? ` ${className}` : ''}`}
        aria-label={`View full image: ${describe(alt, caption)}`}
        title="View full image"
        onClick={handleClick}
        onMouseDown={(event) => event.stopPropagation()}
        onPointerDown={(event) => event.stopPropagation()}
        onDoubleClick={(event) => event.stopPropagation()}
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7" />
        </svg>
      </button>
      {lightbox.element}
    </>
  )
}
