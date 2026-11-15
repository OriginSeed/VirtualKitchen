import './AppFooter.css'

// Privacy, terms and contact links come back once those pages exist; until then the footer links nowhere.
export default function AppFooter() {
  return (
    <footer className="app-footer">
      <div className="app-footer-content">
        <p>&copy; {new Date().getFullYear()} Virtual Kitchen. All rights reserved.</p>
      </div>
    </footer>
  )
}
