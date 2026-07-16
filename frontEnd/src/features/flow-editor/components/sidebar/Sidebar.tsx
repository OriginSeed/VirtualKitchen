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
    <div className="flow-sidebar flex min-w-[10rem] max-w-[26rem] flex-col overflow-y-auto border-r border-[var(--flow-border)] bg-white">
      <div className="flex-shrink-0 border-b border-[var(--flow-border)] px-3.5 py-4">
        <div className="flex items-center gap-2.5">
          <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-[linear-gradient(135deg,var(--flow-accent),#8b5cf6)] text-base text-white">👨‍🍳</div>
          <div className="flex flex-col">
            <div className="text-[0.75rem] font-semibold text-[var(--flow-text)]">Recipe Builder</div>
            <div className="text-[0.65rem] text-[var(--flow-text-muted)]">Create delicious recipes</div>
          </div>
        </div>
      </div>

      <div className="flex-shrink-0 px-2.5 pt-2.5">
        <div className="flow-editor-section-heading mb-2 px-1">Quick Add</div>
        <div className="flex gap-1.5">
          <button
            onClick={() => onAddNode('recipeStepNode', selectedSectionId ?? undefined)}
            className="flow-editor-action-button flex-1 border-sky-200 bg-sky-50 px-2 py-2 text-[0.7rem] font-semibold text-sky-700"
            title="Add a free-floating step node"
          >
            + Step
          </button>
          <button
            onClick={onAddSection}
            className="flow-editor-action-button flex-1 border-emerald-200 bg-emerald-50 px-2 py-2 text-[0.7rem] font-semibold text-emerald-700"
            title="Add a new section container"
          >
            + Section
          </button>
        </div>
      </div>

      {sections.length > 0 && (
        <div className="flex flex-1 flex-col overflow-y-auto px-2.5 pb-2.5 pt-2">
          <div className="flow-editor-section-heading mb-1.5 px-1">Sections</div>
          <div className="flex flex-col gap-1">
            {sections.map(section => (
              <div
                key={section.id}
                className={`cursor-pointer overflow-hidden text-ellipsis whitespace-nowrap rounded-lg px-2.5 py-2 text-[0.7rem] font-semibold transition-all ${String(section.id) === String(selectedSectionId) ? 'border border-indigo-200 bg-indigo-50 text-slate-900' : 'bg-slate-50 text-slate-700 hover:bg-slate-100 hover:text-slate-900'}`}
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