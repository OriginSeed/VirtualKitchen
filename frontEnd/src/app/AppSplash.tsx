import chefLogo from '../assets/kitchen/blackShadowChef.png'
import './AppSplash.css'

/** Shown while a stored session is being restored, instead of a blank page. */
export default function AppSplash() {
  return (
    <div className="app-splash" role="status" aria-live="polite">
      <img className="app-splash-logo" src={chefLogo} alt="" />
      <span className="app-splash-name">Virtual Kitchen</span>
      <span className="app-splash-text">Opening your kitchen…</span>
    </div>
  )
}
