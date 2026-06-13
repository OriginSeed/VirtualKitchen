import { useState } from 'react'
import FlowEditor from '../features/flow-editor/FlowEditor'
import '../App.css'

function App() {
  const [count, setCount] = useState(0)

  return (
    <>
      <div>
        <FlowEditor />
      </div> 
    </>
  )
}

export default App
