import type { Node } from '@xyflow/react'

type SidebarProps = {
  onAddNode: (type: string, sectionId?: string) => void
  onAddSection?: () => void
  sections?: Node[]
  selectedSectionId?: string | null
  onSectionSelect?: (id: string | null) => void
}

export default function Sidebar({ onAddNode, onAddSection, sections = [], selectedSectionId }: SidebarProps) {
  return (
    <div style={{
      width: 210, background: 'white', borderRight: '1px solid #e2e8f0',
      display: 'flex', flexDirection: 'column',
      fontFamily: "'DM Sans', system-ui, sans-serif", height: '100vh', overflow: 'auto',
    }}>
      <div style={{ padding: '16px 14px 12px', borderBottom: '1px solid #f1f5f9' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{ width: 30, height: 30, background: 'linear-gradient(135deg, #6366f1, #8b5cf6)', borderRadius: 8, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 15 }}>
            👨‍🍳
          </div>
          <div>
            <div style={{ fontWeight: 700, fontSize: 12, color: '#0f172a' }}>Recipe Builder</div>
            <div style={{ fontSize: 10, color: '#94a3b8' }}>Create delicious recipes</div>
          </div>
        </div>
      </div>

      <div style={{ padding: '10px 10px 0' }}>
        <div style={{ fontSize: 10, fontWeight: 700, color: '#94a3b8', letterSpacing: '0.08em', textTransform: 'uppercase', marginBottom: 8, paddingLeft: 4 }}>Quick Add</div>
        <div style={{ display: 'flex', gap: 6 }}>
          <button onClick={() => onAddNode('recipeStepNode', selectedSectionId ?? undefined)} style={{ flex: 1, padding: '7px 0', borderRadius: 8, border: '1.5px solid #bfdbfe', background: '#eff6ff', color: '#2563eb', fontSize: 11, fontWeight: 700, cursor: 'pointer' }} title="Add a free-floating step node">+ Step</button>
          <button onClick={onAddSection} style={{ flex: 1, padding: '7px 0', borderRadius: 8, border: '1.5px solid #86efac', background: '#f0fdf4', color: '#16a34a', fontSize: 11, fontWeight: 700, cursor: 'pointer' }} title="Add a new section container">+ Section</button>
        </div>
      </div>

      {sections.length > 0 && (
        <div style={{ padding: '10px 10px 0' }}>
          <div style={{ fontSize: 10, fontWeight: 700, color: '#94a3b8', letterSpacing: '0.08em', textTransform: 'uppercase', marginBottom: 6, paddingLeft: 4 }}>Sections</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            {sections.map(section => (
              <div key={section.id} style={{ padding: '6px 8px', borderRadius: 8, background: '#f8fafc', color: '#334155', fontSize: 11, fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {String(section.data?.title ?? 'Untitled')}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}