import React, { useState } from 'react'
import type { Node } from '@xyflow/react'

type SidebarProps = {
  onAddNode: (type: string, sectionId?: string) => void
  onAddSection?: () => void
  sections?: Node[]
  selectedSectionId?: string | null
  onSectionSelect?: (id: string | null) => void
}

const nodeTypes = [
  { icon: '🔵', label: 'Action Step',  type: 'recipeStepNode', sub: 'A cooking action',  color: '#6366f1' },
  { icon: '🟢', label: 'Ingredient',   type: 'recipeStepNode', sub: 'An ingredient',     color: '#16a34a' },
  { icon: '🟡', label: 'Timer',        type: 'recipeStepNode', sub: 'Wait for a duration', color: '#d97706' },
  { icon: '🔀', label: 'Condition',    type: 'conditionNode',  sub: 'Decision / branch', color: '#f59e0b' },
  { icon: '📝', label: 'Note',         type: 'recipeStepNode', sub: 'Add a note',        color: '#64748b' },
  { icon: '🖼️', label: 'Media',       type: 'recipeStepNode', sub: 'Image or video',    color: '#ef4444' },
  { icon: '📦', label: 'Group',        type: 'recipeStepNode', sub: 'Group steps',       color: '#8b5cf6' },
]

const BtnStyle = (color: string): React.CSSProperties => ({
  display: 'flex', alignItems: 'center', gap: 10, padding: '7px 10px',
  background: 'white', border: '1px solid #e2e8f0', borderRadius: 8,
  cursor: 'pointer', textAlign: 'left', width: '100%',
  transition: 'all 0.15s',
})

export default function Sidebar({ onAddNode, onAddSection, sections = [], selectedSectionId, onSectionSelect }: SidebarProps) {
  const [nodesOpen,  setNodesOpen]  = useState(true)
  const [layoutOpen, setLayoutOpen] = useState(true)

  return (
    <div style={{
      width: 210, background: 'white', borderRight: '1px solid #e2e8f0',
      display: 'flex', flexDirection: 'column',
      fontFamily: "'DM Sans', system-ui, sans-serif", height: '100vh', overflow: 'auto',
    }}>
      {/* Brand */}
      <div style={{ padding: '16px 14px 12px', borderBottom: '1px solid #f1f5f9' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{ width: 30, height: 30, background: 'linear-gradient(135deg, #6366f1, #8b5cf6)',
            borderRadius: 8, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 15 }}>
            👨‍🍳
          </div>
          <div>
            <div style={{ fontWeight: 700, fontSize: 12, color: '#0f172a' }}>Recipe Builder</div>
            <div style={{ fontSize: 10, color: '#94a3b8' }}>Create delicious recipes</div>
          </div>
        </div>
      </div>

      {/* Quick-add row */}
      <div style={{ padding: '10px 10px 0' }}>
        <div style={{ fontSize: 10, fontWeight: 700, color: '#94a3b8', letterSpacing: '0.08em',
          textTransform: 'uppercase', marginBottom: 8, paddingLeft: 4 }}>Quick Add</div>
        <div style={{ display: 'flex', gap: 6 }}>
          <button onClick={() => onAddNode('recipeStepNode', selectedSectionId ?? undefined)}
            style={{ flex: 1, padding: '7px 0', borderRadius: 8, border: '1.5px solid #bfdbfe',
              background: '#eff6ff', color: '#2563eb', fontSize: 11, fontWeight: 700, cursor: 'pointer' }}
            title="Add a free-floating step node">
            + Step
          </button>
          <button onClick={onAddSection}
            style={{ flex: 1, padding: '7px 0', borderRadius: 8, border: '1.5px solid #86efac',
              background: '#f0fdf4', color: '#16a34a', fontSize: 11, fontWeight: 700, cursor: 'pointer' }}
            title="Add a new section container">
            + Section
          </button>
        </div>
      </div>

      {/* Section selector */}
      {sections.length > 0 && (
        <div style={{ padding: '10px 10px 0' }}>
          <div style={{ fontSize: 10, fontWeight: 700, color: '#94a3b8', letterSpacing: '0.08em',
            textTransform: 'uppercase', marginBottom: 6, paddingLeft: 4 }}>Target Section</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <button onClick={() => onSectionSelect?.(null)}
              style={{ 
                padding: '6px 10px', borderRadius: 6, border: selectedSectionId === null ? '1px solid #cbd5e1' : '1px solid #e2e8f0',
                background: selectedSectionId === null ? '#f1f5f9' : 'white', 
                color: '#64748b', fontSize: 11, fontWeight: 500, cursor: 'pointer',
                textAlign: 'left'
              }}>
              📍 No section (free)
            </button>
            {sections.map(section => (
              <button key={section.id} onClick={() => onSectionSelect?.(section.id)}
                style={{ 
                  padding: '6px 10px', borderRadius: 6, border: selectedSectionId === section.id ? '1px solid #6366f1' : '1px solid #e2e8f0',
                  background: selectedSectionId === section.id ? '#eff6ff' : 'white', 
                  color: selectedSectionId === section.id ? '#2563eb' : '#64748b',
                  fontSize: 11, fontWeight: 500, cursor: 'pointer',
                  textAlign: 'left', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'
                }}>
                📋 {(section.data?.title as string) || 'Untitled'}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Node types */}
      <div style={{ padding: '12px 0 0' }}>
        <button onClick={() => setNodesOpen(o => !o)} style={{
          width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '0 14px 8px', background: 'none', border: 'none', cursor: 'pointer',
        }}>
          <span style={{ fontSize: 10, fontWeight: 700, color: '#94a3b8', letterSpacing: '0.08em', textTransform: 'uppercase' }}>
            Add Nodes
          </span>
          <span style={{ fontSize: 10, color: '#cbd5e1' }}>{nodesOpen ? '▲' : '▼'}</span>
        </button>

        {nodesOpen && (
          <div style={{ padding: '0 8px 8px' }}>
            <p style={{ fontSize: 10, color: '#94a3b8', marginBottom: 6, paddingLeft: 4 }}>Drag and drop to canvas</p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
              {nodeTypes.map(nt => (
                <button key={nt.label} onClick={() => onAddNode(nt.type, selectedSectionId ?? undefined)}
                  style={BtnStyle(nt.color)}
                  onMouseEnter={e => { (e.currentTarget as HTMLElement).style.background = '#f8fafc'; (e.currentTarget as HTMLElement).style.borderColor = '#cbd5e1' }}
                  onMouseLeave={e => { (e.currentTarget as HTMLElement).style.background = 'white'; (e.currentTarget as HTMLElement).style.borderColor = '#e2e8f0' }}>
                  <div style={{ width: 26, height: 26, borderRadius: 6, background: `${nt.color}15`,
                    border: `1px solid ${nt.color}30`, display: 'flex', alignItems: 'center',
                    justifyContent: 'center', fontSize: 13, flexShrink: 0 }}>
                    {nt.icon}
                  </div>
                  <div>
                    <div style={{ fontSize: 11, fontWeight: 600, color: '#1e293b' }}>{nt.label}</div>
                    <div style={{ fontSize: 10, color: '#94a3b8' }}>{nt.sub}</div>
                  </div>
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Layout */}
      <div style={{ borderTop: '1px solid #f1f5f9', paddingTop: 12 }}>
        <button onClick={() => setLayoutOpen(o => !o)} style={{
          width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '0 14px 8px', background: 'none', border: 'none', cursor: 'pointer',
        }}>
          <span style={{ fontSize: 10, fontWeight: 700, color: '#94a3b8', letterSpacing: '0.08em', textTransform: 'uppercase' }}>
            Layout
          </span>
          <span style={{ fontSize: 10, color: '#cbd5e1' }}>{layoutOpen ? '▲' : '▼'}</span>
        </button>

        {layoutOpen && (
          <div style={{ padding: '0 8px 8px', display: 'flex', flexDirection: 'column', gap: 3 }}>
            {[{ icon: '⚡', label: 'Auto Arrange', sub: 'Arrange nodes' }, { icon: '🔍', label: 'Zoom to Fit', sub: 'Fit canvas' }].map(item => (
              <button key={item.label} style={BtnStyle('#6b7280')}
                onMouseEnter={e => ((e.currentTarget as HTMLElement).style.background = '#f8fafc')}
                onMouseLeave={e => ((e.currentTarget as HTMLElement).style.background = 'white')}>
                <div style={{ width: 26, height: 26, borderRadius: 6, background: '#f1f5f9',
                  display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 13, flexShrink: 0 }}>
                  {item.icon}
                </div>
                <div>
                  <div style={{ fontSize: 11, fontWeight: 600, color: '#1e293b' }}>{item.label}</div>
                  <div style={{ fontSize: 10, color: '#94a3b8' }}>{item.sub}</div>
                </div>
              </button>
            ))}
          </div>
        )}
      </div>

      {/* Mini preview */}
      <div style={{ padding: '10px 8px', marginTop: 'auto', borderTop: '1px solid #f1f5f9' }}>
        <div style={{ background: '#f8fafc', borderRadius: 8, border: '1px solid #e2e8f0',
          height: 80, display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: 10, color: '#94a3b8' }}>Canvas Preview</div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 6, padding: '0 2px' }}>
          <button style={{ fontSize: 14, background: 'none', border: 'none', cursor: 'pointer', color: '#64748b' }}>−</button>
          <span style={{ fontSize: 11, color: '#64748b', fontWeight: 600 }}>100%</span>
          <button style={{ fontSize: 14, background: 'none', border: 'none', cursor: 'pointer', color: '#64748b' }}>+</button>
        </div>
      </div>
    </div>
  )
}