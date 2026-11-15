import './page.css'

/** Placeholder cards while a grid of items loads. */
export default function SkeletonGrid({ count = 6, label = 'Loading…' }: { count?: number; label?: string }) {
  return (
    <div className="vk-skeleton-grid" role="status" aria-live="polite">
      <span className="vk-sr-only">{label}</span>
      {Array.from({ length: count }, (_, index) => (
        <div key={index} className="vk-skeleton-card" aria-hidden="true">
          <div className="vk-skeleton-block" />
          <div className="vk-skeleton-body">
            <div className="vk-skeleton-line is-short" />
            <div className="vk-skeleton-line is-medium" />
            <div className="vk-skeleton-line" />
          </div>
        </div>
      ))}
    </div>
  )
}
