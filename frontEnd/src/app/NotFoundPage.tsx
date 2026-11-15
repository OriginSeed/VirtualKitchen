import { Link } from 'react-router-dom'
import { EmptyState } from '../shared/components/page'

/** An address the app doesn't know: say so instead of silently sending the user somewhere else. */
export default function NotFoundPage({ signedIn }: { signedIn: boolean }) {
  const page = (
    <div className="vk-page" style={{ maxWidth: 640, paddingTop: signedIn ? 24 : 0 }}>
      <EmptyState
        icon="🧭"
        title="Page not found"
        text="This page doesn’t exist or has moved. Check the address, or head back to somewhere familiar."
        actions={
          <>
            {signedIn && <Link className="vk-btn vk-btn-primary" to="/kitchen">Go to My Kitchen</Link>}
            <Link className={`vk-btn ${signedIn ? 'vk-btn-secondary' : 'vk-btn-primary'}`} to="/">Home page</Link>
          </>
        }
      />
    </div>
  )
  if (signedIn) return page
  // Outside the kitchen layout there is no page chrome, so centre it on the app background.
  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', padding: 16, background: 'var(--flow-bg)' }}>
      {page}
    </div>
  )
}
