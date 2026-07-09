import { useState } from 'react'
import LoginPage from '../features/auth/LoginPage'
import KitchenPage from '../features/kitchen/KitchenPage'
import FlowEditor from '../features/flow-editor/FlowEditor'
import type { User } from '../types/User'
import '../App.css'

interface Kitchen {
  id: number
  name: string
  ownerId: number
}

type AppView = 'login' | 'kitchen' | 'editor'

function App() {
  const [appView, setAppView] = useState<AppView>('login')
  const [currentUser, setCurrentUser] = useState<User | null>(null)
  const [currentKitchen, setCurrentKitchen] = useState<Kitchen | null>(null)

  const handleLoginSuccess = (user: User, kitchen: Kitchen) => {
    setCurrentUser(user)
    setCurrentKitchen(kitchen)
    setAppView('kitchen')
  }

  const handleLogout = () => {
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

  return (
    <>
      {appView === 'login' ? (
        <LoginPage onLoginSuccess={handleLoginSuccess} />
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
