import { useState } from 'react'
import ZoomableImage from '../../../../shared/components/image-viewer/ZoomableImage'

type RecipeImageProps = {
  src?: string
  alt: string
  /** Emoji shown on the decorative fallback when there's no image or it fails to load. */
  fallbackIcon: string
  /**
   * Clicking the image opens it full size (default). Turn off where the image sits inside something already
   * clickable (a link or card button) — use ImageZoomButton beside that element instead.
   */
  zoomable?: boolean
  /** Shown under the full-size image. */
  caption?: string
}

/** A cover-fit image that falls back to a decorative tile instead of ever showing a broken image. */
export default function RecipeImage({ src, alt, fallbackIcon, zoomable = true, caption }: RecipeImageProps) {
  // Remembers which src failed (not just "failed"), so a new src gets a fresh attempt.
  const [failedSrc, setFailedSrc] = useState<string | null>(null)

  if (!src || failedSrc === src) {
    return (
      <div className="rp-image-fallback" role="img" aria-label={alt}>
        <span aria-hidden>{fallbackIcon}</span>
      </div>
    )
  }

  if (zoomable) {
    return (
      <ZoomableImage
        className="rp-image"
        src={src}
        alt={alt}
        caption={caption}
        loading="lazy"
        onError={() => setFailedSrc(src)}
      />
    )
  }

  return <img className="rp-image" src={src} alt={alt} loading="lazy" onError={() => setFailedSrc(src)} />
}
