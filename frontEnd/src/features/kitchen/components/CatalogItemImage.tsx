import { useState } from 'react'
import ZoomableImage from '../../../shared/components/image-viewer/ZoomableImage'
import './CatalogItemImage.css'

type CatalogItemImageProps = {
  src?: string | null
  alt: string
  /** Emoji shown when the item has no generated image yet, or it fails to load. */
  fallbackIcon: string
  /**
   * Clicking opens the image full size (default). Turn off inside something already clickable (use ImageZoomButton
   * beside it instead).
   */
  zoomable?: boolean
}

/** A shop/inventory card's ingredient or equipment image, falling back to its emoji. */
export default function CatalogItemImage({ src, alt, fallbackIcon, zoomable = true }: CatalogItemImageProps) {
  // Remembers which src failed (not just "failed"), so a new src gets a fresh attempt.
  const [failedSrc, setFailedSrc] = useState<string | null>(null)

  if (!src || failedSrc === src) {
    return <span aria-hidden="true">{fallbackIcon}</span>
  }

  if (zoomable) {
    return (
      <ZoomableImage
        className="catalog-item-image"
        src={src}
        alt={alt}
        caption={alt || undefined}
        loading="lazy"
        onError={() => setFailedSrc(src)}
      />
    )
  }

  return (
    <img
      className="catalog-item-image"
      src={src}
      alt={alt}
      loading="lazy"
      onError={() => setFailedSrc(src)}
    />
  )
}
