import { useEffect, useState } from 'react'
import Auth from '../features/auth/Auth'
import KitchenPage from '../features/kitchen/KitchenPage'
import FlowEditor from '../features/flow-editor/FlowEditor'
import type { User } from '../types/User'
import { AuthApi, KitchenApi } from '../api'
import '../App.css'

interface Kitchen {
  id: number
  name: string
  ownerId: number
}

type AppView = 'login' | 'kitchen' | 'editor'
const SESSION_EMAIL_KEY = 'virtual-kitchen.session.email'

function App() {
  const [appView, setAppView] = useState<AppView>('login')
  const [currentUser, setCurrentUser] = useState<User | null>(null)
  const [currentKitchen, setCurrentKitchen] = useState<Kitchen | null>(null)
  const [isRestoringSession, setIsRestoringSession] = useState(true)

  useEffect(() => {
    const restoreSession = async () => {
      const storedEmail = localStorage.getItem(SESSION_EMAIL_KEY)?.trim()

      if (!storedEmail) {
        setIsRestoringSession(false)
        return
      }

      try {
        const user = await AuthApi.getUserByEmail(storedEmail)
        const kitchensData = await KitchenApi.getKitchenByOwnerId(user.id)
        const kitchens = Array.isArray(kitchensData) ? kitchensData : [kitchensData]
        const kitchen = kitchens[0]

        if (!kitchen) {
          throw new Error('No kitchen found for this user')
        }

        setCurrentUser(user)
        setCurrentKitchen(kitchen)
        setAppView('kitchen')
      } catch (error) {
        console.error('Session restore failed:', error)
        localStorage.removeItem(SESSION_EMAIL_KEY)
        setCurrentUser(null)
        setCurrentKitchen(null)
        setAppView('login')
      } finally {
        setIsRestoringSession(false)
      }
    }

    void restoreSession()
  }, [])

  const handleLoginSuccess = (user: User, kitchen: Kitchen) => {
    localStorage.setItem(SESSION_EMAIL_KEY, user.email)
    setCurrentUser(user)
    setCurrentKitchen(kitchen)
    setAppView('kitchen')
  }

  const handleLogout = () => {
    localStorage.removeItem(SESSION_EMAIL_KEY)
    setCurrentUser(null)
    setCurrentKitchen(null)
    setAppView('login')
  }

  const goToEditor = () => {
    setAppView('editor')
  }

  const backToKitchen = () => {
    setAppView('kitchen')
  }

  if (isRestoringSession) {
    return null
  }

  return (
    <>
      {appView === 'login' ? (
        <Auth onLoginSuccess={handleLoginSuccess} />
      ) : appView === 'kitchen' && currentUser && currentKitchen ? (
        <KitchenPage user={currentUser} kitchen={currentKitchen} onLogout={handleLogout} onCreateRecipe={goToEditor} />
      ) : appView === 'editor' ? (
        <div>
          <button onClick={backToKitchen} style={{ padding: '10px', margin: '10px' }}>
            ← Back to Kitchen
          </button>
          <FlowEditor />
        </div>
      ) : null}
    </>
  )
}

export default App
