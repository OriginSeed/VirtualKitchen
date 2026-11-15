import { RecipeImage } from '../../recipe-tool'
import '../recipeOrder.css'

type OrderThumbProps = {
  src?: string | null
  alt: string
  /** Emoji for the fallback tile (no image, or it failed to load). */
  icon?: string | null
  size?: 'sm' | 'md' | 'lg'
  /** Opens full size on click (default); off when the thumbnail sits inside a link or button. */
  zoomable?: boolean
}

/** A recipe's thumbnail in a fixed rounded frame, falling back to its icon. */
export default function OrderThumb({ src, alt, icon, size = 'md', zoomable = true }: OrderThumbProps) {
  return (
    <div className={`ro-thumb ro-thumb-${size}`}>
      <RecipeImage src={src ?? undefined} alt={alt} fallbackIcon={icon || '🍳'} zoomable={zoomable} />
    </div>
  )
}
