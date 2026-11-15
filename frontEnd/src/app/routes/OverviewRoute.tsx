import { useNavigate } from 'react-router-dom'
import OverviewPage from '../../features/kitchen/overview/OverviewPage'
import { recipeToolPath } from '../../features/recipe-tool'
import { useSignedIn } from '../session/signedIn'

/** My Kitchen's front page; a recipe opens in its Recipe Process. */
export default function OverviewRoute() {
  const { user, kitchen } = useSignedIn()
  const navigate = useNavigate()
  return (
    <OverviewPage
      userId={user.id}
      userName={user.name}
      kitchenId={kitchen.id}
      onOpenRecipe={(recipeId) => navigate(recipeToolPath(recipeId, 'process'))}
    />
  )
}
