import { useState, type ReactNode } from 'react'
import ImageLightbox from './ImageLightbox'

export type LightboxImage = { src: string; alt: string; caption?: string }

/**
 * Opens images full size from code that manages its own trigger: `open(...)` shows the {@link ImageLightbox};
 * render `element` somewhere (it portals to the page body).
 */
export function useImageLightbox(): { open: (image: LightboxImage) => void; element: ReactNode } {
  const [image, setImage] = useState<LightboxImage | null>(null)
  const element = image ? (
    <ImageLightbox src={image.src} alt={image.alt || image.caption || ''} caption={image.caption} onClose={() => setImage(null)} />
  ) : null
  return { open: setImage, element }
}
