import React, { useState } from 'react'

type SidebarProps = {
  onAddStep: () => void
  onAddSection?: () => void
}

const nodeTypes = [
  { icon: '🔵', label: 'Action Step', sub: 'A cooking action', color: '#6366f1' },
  { icon: '🟢', label: 'Ingredient', sub: 'An ingredient', color: '#16a34a' },
  { icon: '🟡', label: 'Timer', sub: 'Wait for a duration', color: '#d97706' },
  { icon: '🔷', label: 'Condition', sub: 'Decision point', color: '#0ea5e9' },
  { icon: '📝', label: 'Note', sub: 'Add a note', color: '#64748b' },
  { icon: '🖼️', label: 'Media', sub: 'Image or video', color: '#ef4444' },
  { icon: '📦', label: 'Group', sub: 'Group steps', color: '#8b5cf6' },
]

export default function Sidebar({ onAddStep, onAddSection }: SidebarProps) {
  const [nodesOpen, setNodesOpen] = useState(true)
  const [layoutOpen, setLayoutOpen] = useState(true)

  return (
    <div
      style={{
        width: 220,
        background: 'white',
        borderRight: '1px solid #e2e8f0',
        display: 'flex',
        flexDirection: 'column',
        fontFamily: "'DM Sans', system-ui, sans-serif",
        height: '100vh',
        overflow: 'auto',
      }}
    >
      {/* Logo/Brand */}
      <div
        style={{
          padding: '18px 16px 14px',
          borderBottom: '1px solid #f1f5f9',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div
            style={{
              width: 32,
              height: 32,
              background: 'linear-gradient(135deg, #6366f1, #8b5cf6)',
              borderRadius: 8,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 16,
            }}
          >
            👨‍🍳
          </div>
          <div>
            <div style={{ fontWeight: 700, fontSize: 13, color: '#0f172a' }}>Recipe Builder</div>
            <div style={{ fontSize: 10, color: '#94a3b8' }}>Create delicious recipes</div>
          </div>
        </div>
      </div>

      {/* ADD NODES */}
      <div style={{ padding: '14px 0 0' }}>
        <button
          onClick={() => setNodesOpen(o => !o)}
          style={{
            width: '100%',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '0 16px 8px',
            background: 'none',
            border: 'none',
            cursor: 'pointer',
          }}
        >
          <span style={{ fontSize: 10, fontWeight: 700, color: '#94a3b8', letterSpacing: '0.08em', textTransform: 'uppercase' }}>
            Add Nodes
          </span>
          <span style={{ fontSize: 10, color: '#cbd5e1' }}>{nodesOpen ? '▲' : '▼'}</span>
        </button>

        {nodesOpen && (
          <div style={{ padding: '0 10px 10px' }}>
            <p style={{ fontSize: 10, color: '#94a3b8', marginBottom: 8, paddingLeft: 6 }}>
              Drag and drop to canvas
            </p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {nodeTypes.map((nt) => (
                <button
                  key={nt.label}
                  onClick={onAddStep}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    padding: '8px 10px',
                    background: 'white',
                    border: '1px solid #e2e8f0',
                    borderRadius: 8,
                    cursor: 'pointer',
                    textAlign: 'left',
                    transition: 'all 0.15s',
                  }}
                  onMouseEnter={e => {
                    (e.currentTarget as HTMLElement).style.background = '#f8fafc'
                    ;(e.currentTarget as HTMLElement).style.borderColor = '#cbd5e1'
                  }}
                  onMouseLeave={e => {
                    (e.currentTarget as HTMLElement).style.background = 'white'
                    ;(e.currentTarget as HTMLElement).style.borderColor = '#e2e8f0'
                  }}
                >
                  <div
                    style={{
                      width: 28,
                      height: 28,
                      borderRadius: 6,
                      background: `${nt.color}15`,
                      border: `1px solid ${nt.color}30`,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      fontSize: 14,
                      flexShrink: 0,
                    }}
                  >
                    {nt.icon}
                  </div>
                  <div>
                    <div style={{ fontSize: 12, fontWeight: 600, color: '#1e293b' }}>{nt.label}</div>
                    <div style={{ fontSize: 10, color: '#94a3b8' }}>{nt.sub}</div>
                  </div>
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* LAYOUT */}
      <div style={{ borderTop: '1px solid #f1f5f9', paddingTop: 14 }}>
        <button
          onClick={() => setLayoutOpen(o => !o)}
          style={{
            width: '100%',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '0 16px 8px',
            background: 'none',
            border: 'none',
            cursor: 'pointer',
          }}
        >
          <span style={{ fontSize: 10, fontWeight: 700, color: '#94a3b8', letterSpacing: '0.08em', textTransform: 'uppercase' }}>
            Layout
          </span>
          <span style={{ fontSize: 10, color: '#cbd5e1' }}>{layoutOpen ? '▲' : '▼'}</span>
        </button>

        {layoutOpen && (
          <div style={{ padding: '0 10px 10px', display: 'flex', flexDirection: 'column', gap: 4 }}>
            {[
              { icon: '⚡', label: 'Auto Arrange', sub: 'Arrange nodes' },
              { icon: '🔍', label: 'Zoom to Fit', sub: 'Fit canvas' },
            ].map((item) => (
              <button
                key={item.label}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  padding: '8px 10px',
                  background: 'white',
                  border: '1px solid #e2e8f0',
                  borderRadius: 8,
                  cursor: 'pointer',
                  textAlign: 'left',
                }}
                onMouseEnter={e => ((e.currentTarget as HTMLElement).style.background = '#f8fafc')}
                onMouseLeave={e => ((e.currentTarget as HTMLElement).style.background = 'white')}
              >
                <div
                  style={{
                    width: 28,
                    height: 28,
                    borderRadius: 6,
                    background: '#f1f5f9',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontSize: 14,
                    flexShrink: 0,
                  }}
                >
                  {item.icon}
                </div>
                <div>
                  <div style={{ fontSize: 12, fontWeight: 600, color: '#1e293b' }}>{item.label}</div>
                  <div style={{ fontSize: 10, color: '#94a3b8' }}>{item.sub}</div>
                </div>
              </button>
            ))}
          </div>
        )}
      </div>

      {/* Mini preview area placeholder */}
      <div style={{ padding: '14px 10px', marginTop: 'auto', borderTop: '1px solid #f1f5f9' }}>
        <div
          style={{
            background: '#f8fafc',
            borderRadius: 8,
            border: '1px solid #e2e8f0',
            height: 100,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: 10,
            color: '#94a3b8',
          }}
        >
          Canvas Preview
        </div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 8, padding: '0 2px' }}>
          <button style={{ fontSize: 14, background: 'none', border: 'none', cursor: 'pointer', color: '#64748b' }}>−</button>
          <span style={{ fontSize: 11, color: '#64748b', fontWeight: 600 }}>100%</span>
          <button style={{ fontSize: 14, background: 'none', border: 'none', cursor: 'pointer', color: '#64748b' }}>+</button>
        </div>
      </div>
    </div>
  )
}