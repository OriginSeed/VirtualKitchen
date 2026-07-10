import type { Node } from '@xyflow/react'
import './Sidebar.css'

type SidebarProps = {
  onAddNode: (type: string, sectionId?: string) => void
  onAddSection?: () => void
  sections?: Node[]
  selectedSectionId?: string | null
  onSectionSelect?: (id: string | null) => void
}

export default function Sidebar({ onAddNode, onAddSection, sections = [], selectedSectionId, onSectionSelect }: SidebarProps) {
  return (
    <div className="flow-sidebar">
      {/* Header */}
      <div className="flow-sidebar-header">
        <div className="flow-sidebar-header-content">
          <div className="flow-sidebar-header-icon">👨‍🍳</div>
          <div className="flow-sidebar-header-text">
            <div className="flow-sidebar-header-title">Recipe Builder</div>
            <div className="flow-sidebar-header-subtitle">Create delicious recipes</div>
          </div>
        </div>
      </div>

      {/* Quick Add Section */}
      <div className="flow-sidebar-section">
        <div className="flow-sidebar-section-label">Quick Add</div>
        <div className="flow-sidebar-quick-add">
          <button 
            onClick={() => onAddNode('recipeStepNode', selectedSectionId ?? undefined)} 
            className="flow-sidebar-button flow-sidebar-button-step"
            title="Add a free-floating step node"
          >
            + Step
          </button>
          <button 
            onClick={onAddSection} 
            className="flow-sidebar-button flow-sidebar-button-section"
            title="Add a new section container"
          >
            + Section
          </button>
        </div>
      </div>

      {/* Sections List */}
      {sections.length > 0 && (
        <div className="flow-sidebar-sections-list">
          <div className="flow-sidebar-sections-label">Sections</div>
          <div className="flow-sidebar-sections-items">
            {sections.map(section => (
              <div 
                key={section.id} 
                className={"flow-sidebar-section-item" + (String(section.id) === String(selectedSectionId) ? ' selected' : '')}
                title={String(section.data?.title ?? 'Untitled')}
                onClick={() => {
                  if (selectedSectionId === String(section.id)) {
                    onSectionSelect?.(null)
                  } else {
                    onSectionSelect?.(String(section.id))
                  }
                }}
              >
                {String(section.data?.title ?? 'Untitled')}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}