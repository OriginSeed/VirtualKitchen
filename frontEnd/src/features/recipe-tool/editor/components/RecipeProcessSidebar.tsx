import { useState, type ReactNode } from 'react'
import '../../../../styles/flow-ui.css'
import '../styles/RecipeEditorToolbar.css'
import type { Process } from '../../../../types/process'
import { isPendingProcessId } from '../../model/subprocessReferences'
import { getPreparationFlows } from '../../model/preparationFlows'
import PreparationNotesPopover from './PreparationNotesPopover'
import { useProcessSidebarActions } from '../context/ProcessSidebarActionsContext'

export type ProcessIssueCounts = { blockers: number; warnings: number }

type RecipeProcessSidebarProps = {
  processes: Process[]
  selectedProcessId: number | null
  isOwner: boolean
  onSelect: (processId: number) => void
  onCreateMainProcess: () => void
  creatingMainProcess: boolean
  /** Opens the Preparation, creating it when the recipe has none yet; omitted for readers. */
  onOpenPreparation?: () => void
  creatingPreparation?: boolean
  /** Renames the main process; omitted for readers. (A Preparation flow is named by its last step.) */
  onRenameProcess?: (processId: number, name: string) => void
  /** Readiness issues per process, shown as a dot on its card. */
  issueCounts?: ReadonlyMap<number, ProcessIssueCounts>
  /** The recipe's default preparation guidance (null = the standard text). */
  preparationNotes?: string | null
  onSavePreparationNotes?: (notes: string | null) => Promise<void>
  /** The recipe's readiness summary (RecipeReadinessPanel), shown under the header. */
  readinessPanel?: ReactNode
}

const sectionLabel = 'px-1 text-[0.68rem] font-bold uppercase tracking-wide'

/**
 * "Recipe": the Recipe Editor's two flow editors — Preparation (independent flows, each making one named component,
 * e.g. "Chopped Onions") above the Main Process (the one connected cooking sequence that uses them) — with the
 * Step and Condition add buttons at the top. Selecting a card just updates local state in RecipeEditorView — it never
 * navigates to a different route ("keep the user inside the Recipe Tool").
 */
export default function RecipeProcessSidebar({
  processes,
  selectedProcessId,
  isOwner,
  onSelect,
  onCreateMainProcess,
  creatingMainProcess,
  onOpenPreparation,
  creatingPreparation = false,
  onRenameProcess,
  issueCounts,
  preparationNotes = null,
  onSavePreparationNotes,
  readinessPanel,
}: RecipeProcessSidebarProps) {
  // Adding a step/condition and collapsing come from the canvas hosting this sidebar (none without one).
  const { onAddStep, onAddCondition, onCollapse } = useProcessSidebarActions() ?? {}
  // The main process being renamed and its draft name.
  const [renaming, setRenaming] = useState<{ processId: number; name: string } | null>(null)

  const mainProcess = processes.find((process) => process.type === 'MAIN') ?? null
  // Normally one; a reader may still see a recipe saved before the single Preparation (the owner's editor merges them).
  const preparations = processes.filter((process) => process.type === 'SUBPROCESS')

  const commitRename = () => {
    if (!renaming || !onRenameProcess) return
    const next = renaming.name.trim()
    const current = processes.find((process) => process.id === renaming.processId)
    if (current && next && next !== current.name) onRenameProcess(renaming.processId, next)
    setRenaming(null)
  }

  const renderCard = (process: Process) => {
    const selected = process.id === selectedProcessId
    const stepCount = process.nodes.filter((node) => node.kind === 'STEP').length
    const isMain = process.type === 'MAIN'
    const flowCount = isMain ? 0 : getPreparationFlows(process).filter((flow) => flow.nodes.some((node) => node.kind === 'STEP')).length
    const renamable = isMain && onRenameProcess != null
    const counts = issueCounts?.get(process.id)

    if (renaming?.processId === process.id) {
      return (
        <div key={process.id} className="recipe-sidebar-process" style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <input
            className="flow-properties-input"
            value={renaming.name}
            autoFocus
            aria-label="Main process name"
            onChange={(event) => setRenaming({ processId: process.id, name: event.target.value })}
            onKeyDown={(event) => {
              if (event.key === 'Enter') commitRename()
              if (event.key === 'Escape') setRenaming(null)
            }}
            onBlur={commitRename}
          />
          <span style={{ fontSize: 10.5, color: 'var(--flow-text-subtle)', padding: '0 4px' }}>Enter to save · Esc to cancel</span>
        </div>
      )
    }

    return (
      <div key={process.id} className="recipe-sidebar-process">
        <button
          type="button"
          onClick={() => onSelect(process.id)}
          aria-current={selected ? 'true' : undefined}
          style={{
            width: '100%',
            textAlign: 'left',
            padding: renamable ? '10px 38px 10px 12px' : '10px 12px',
            borderRadius: 10,
            border: `1px solid ${selected ? 'var(--flow-accent)' : 'var(--flow-border)'}`,
            background: selected ? 'var(--flow-accent-soft)' : 'var(--flow-surface)',
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            gap: 8,
          }}
        >
          <span aria-hidden style={{ fontSize: 14, flexShrink: 0 }}>{isMain ? '🍳' : '🔪'}</span>
          <div style={{ minWidth: 0, flex: 1 }}>
            <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--flow-text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {process.name.trim() || (isMain ? 'Main Process' : 'Preparation')}
            </div>
            <div style={{ fontSize: 10.5, color: 'var(--flow-text-muted)' }}>
              {isMain
                ? `Main process · ${stepCount} step${stepCount === 1 ? '' : 's'}`
                : `${flowCount} flow${flowCount === 1 ? '' : 's'} · ${stepCount} step${stepCount === 1 ? '' : 's'}`}
            </div>
          </div>
          {counts && (counts.blockers > 0 || counts.warnings > 0) && (
            <span
              title={[
                counts.blockers > 0 ? `${counts.blockers} to fix before cooking or ordering` : '',
                counts.warnings > 0 ? `${counts.warnings} warning${counts.warnings === 1 ? '' : 's'}` : '',
              ].filter(Boolean).join(' · ')}
              aria-label={`${counts.blockers} blocking, ${counts.warnings} warnings`}
              style={{
                flexShrink: 0, width: 8, height: 8, borderRadius: 999,
                background: counts.blockers > 0 ? 'var(--flow-warning)' : 'var(--flow-warning-border)',
              }}
            />
          )}
          {isPendingProcessId(process.id) && (
            <span
              title="Generated — not saved yet"
              style={{ flexShrink: 0, fontSize: 9.5, fontWeight: 700, padding: '2px 6px', borderRadius: 999, background: 'var(--flow-magic-soft)', border: '1px solid var(--flow-magic-border)', color: 'var(--flow-magic)' }}
            >
              NEW
            </span>
          )}
        </button>
        {renamable && (
          <div className="recipe-sidebar-process-actions">
            <button
              type="button"
              className="recipe-sidebar-process-rename"
              onClick={() => setRenaming({ processId: process.id, name: process.name })}
              aria-label={`Rename ${process.name}`}
              title="Rename"
            >
              ✎
            </button>
          </div>
        )}
      </div>
    )
  }

  return (
    <div className="flex h-full flex-col">
      <div className="flex flex-shrink-0 items-center justify-between gap-2 border-b border-[var(--flow-border)] py-2.5 pr-2 pl-3.5">
        <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--flow-text)' }}>Recipe</div>
        {onCollapse && (
          <button
            type="button"
            onClick={onCollapse}
            aria-label="Collapse sidebar"
            title="Collapse sidebar"
            className="recipe-topbar-button"
            style={{ height: 28, padding: '0 8px' }}
          >
            «
          </button>
        )}
      </div>

      {readinessPanel}

      {(onAddStep || onAddCondition) && (
        <div className="flex flex-shrink-0 gap-2 border-b border-[var(--flow-border)] p-2.5">
          {onAddStep && (
            <button type="button" className="recipe-sidebar-add" onClick={onAddStep} title="A cooking action in this process">
              <span aria-hidden>▭</span> Step
            </button>
          )}
          {onAddCondition && (
            <button type="button" className="recipe-sidebar-add" onClick={onAddCondition} title="A yes / no check that branches the flow">
              <span aria-hidden>◇</span> Condition
            </button>
          )}
        </div>
      )}

      <div className="flex flex-1 flex-col gap-2 overflow-y-auto p-2.5">
        <div className={`${sectionLabel} flex items-center gap-1`} style={{ color: 'var(--flow-text-subtle)' }}>
          Preparation
          <PreparationNotesPopover notes={preparationNotes} onSave={isOwner ? onSavePreparationNotes : undefined} />
        </div>
        {preparations.length > 0 ? (
          preparations.map(renderCard)
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, padding: '10px 12px', borderRadius: 10, border: '1px dashed var(--flow-border)' }}>
            <span style={{ fontSize: 11.5, color: 'var(--flow-text-muted)' }}>
              No preparation yet: prep work that makes components, like chopped onions or a marinade.
            </span>
            {onOpenPreparation && (
              <button
                type="button"
                onClick={onOpenPreparation}
                disabled={creatingPreparation}
                style={{ alignSelf: 'flex-start', padding: '5px 10px', borderRadius: 7, border: '1px solid var(--flow-accent)', background: 'var(--flow-accent)', color: 'white', fontSize: 11, fontWeight: 700, cursor: creatingPreparation ? 'wait' : 'pointer' }}
              >
                {creatingPreparation ? 'Adding…' : 'Add Preparation'}
              </button>
            )}
          </div>
        )}

        <div className={`${sectionLabel} mt-2`} style={{ color: 'var(--flow-text-subtle)' }}>Main Process</div>
        {mainProcess ? (
          renderCard(mainProcess)
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, padding: '10px 12px', borderRadius: 10, border: '1px dashed var(--flow-border)' }}>
            <span style={{ fontSize: 11.5, color: 'var(--flow-text-muted)' }}>No main process yet.</span>
            {isOwner && (
              <button
                type="button"
                onClick={onCreateMainProcess}
                disabled={creatingMainProcess}
                style={{ alignSelf: 'flex-start', padding: '5px 10px', borderRadius: 7, border: '1px solid var(--flow-accent)', background: 'var(--flow-accent)', color: 'white', fontSize: 11, fontWeight: 700, cursor: creatingMainProcess ? 'wait' : 'pointer' }}
              >
                {creatingMainProcess ? 'Creating…' : 'Create Main Process'}
              </button>
            )}
          </div>
        )}
      </div>

    </div>
  )
}
