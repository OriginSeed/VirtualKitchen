import { useState } from 'react'
import FlowCanvas from './components/canvas/FlowCanvas'
import RecipeHomePage from './RecipeHomePage'

export default function FlowEditor() {
  const [activeRecipe, setActiveRecipe] = useState<{ id: number; title: string } | null>(null)

  if (!activeRecipe) {
    return <RecipeHomePage onCreateRecipe={(id, title) => setActiveRecipe({ id, title })} />
  }

  return (
    <div className="w-screen h-screen">
      <FlowCanvas recipe={activeRecipe} onBack={() => setActiveRecipe(null)} />
    </div>
  )
}