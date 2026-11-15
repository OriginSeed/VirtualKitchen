import '../../../../styles/flow-ui.css'
import '../styles/RecipeEditorToolbar.css'
import type { ProcessType } from '../../../../types/process'
import type { RecipeProcessBreadcrumbEntry } from '../../../../types/recipe'
import RecipeProcessBreadcrumb from './RecipeProcessBreadcrumb'
import AiActionsMenu, { type RecipeAiActions } from './AiActionsMenu'
import AddNodeMenu from './AddNodeMenu'

/** One entry of the node selector: a STEP ("Step 2 · Chop") or a CONDITION. */
export type NodeSelectorOption = { id: string; label: string; kind: 'STEP' | 'CONDITION' }

type RecipeProcessTopBarProps = {
  /** The recipe's name, shown as the breadcrumb's root — omitted where it isn't known (standalone process route). */
  recipeName?: string
  name: string
  processType: ProcessType
  breadcrumbAncestors: RecipeProcessBreadcrumbEntry[]
  onNavigateToList: () => void
  onNavigateToAncestor: (index: number) => void
  onBack?: () => void
  /** "Save everything now" — flushes the pending autosave. */
  onSave: () => void
  /** A save is in flight (the save status itself is shown in the Recipe Tool's header). */
  isSaving?: boolean
  /** Opens the keyboard shortcuts help. */
  onShowShortcuts?: () => void
  onUndo?: () => void
  onRedo?: () => void
  canUndo?: boolean
  canRedo?: boolean
  onDeleteSelected?: () => void
  zoomPercent?: number
  onZoomIn?: () => void
  onZoomOut?: () => void
  onFitView?: () => void
  /** Re-lays the whole graph out (positions only, one undo step); disabled without nodes. */
  onAutoArrange?: () => void
  canAutoArrange?: boolean
  /** Opens the slideshow player (visuals + narration) of this process only (Cook mode plays the whole recipe). */
  onPreview?: () => void
  onExport?: () => void
  /** Starts the AI visualization job for this process's own steps (see RecipeProcessCanvas's generateVisuals). */
  onGenerateVisuals?: () => void
  isGeneratingVisuals?: boolean
  /** Progress while generating, e.g. "Generating… 2/5". */
  visualsProgressLabel?: string
  generateVisualsStatus?: { type: 'success' | 'error' | 'info'; text: string } | null
  /** Recipe-level AI actions (AI Recipe Creation, Edit with AI) — omitted where the editor has none (standalone route). */
  aiActions?: RecipeAiActions
  /**
   * Adding nodes normally lives in the process sidebar's "+ Add" menu; these are passed only where
   * there's no sidebar to show it (the standalone process route), and then appear here instead.
   */
  onAddStep?: () => void
  onAddCondition?: () => void
  /** The current process's nodes for the node selector: STEPs in canvas order, then CONDITIONs. */
  nodeOptions?: NodeSelectorOption[]
  /** The selected node's id (null when nothing, or an edge, is selected). */
  currentNodeId?: string | null
  /** Selects a node on the canvas and opens its properties — kept separate from process navigation (the breadcrumb), which moves between processes. */
  onSelectNode?: (nodeId: string) => void
}

const STATUS_COLORS = { success: 'var(--flow-success)', info: 'var(--flow-magic)', error: 'var(--flow-danger)' } as const

/**
 * The Process Editor's top bar, grouped by what the user is doing:
 * - left — context only: Back, then Recipe / Process / Node (the node selector);
 * - right — canvas controls (Auto Arrange, Fit, Zoom, Undo/Redo), then output: ✨ AI, Preview process,
 *   Export, and finally shortcuts help and Save as the one high-emphasis action (the save status itself is in
 *   the Recipe Tool's header, visible from both tabs).
 * Secondary labels collapse to icons as the bar narrows (container queries on the bar's own width,
 * since the editor shares the window with panels), keeping tooltips and accessible names.
 */
export default function RecipeProcessTopBar({
  recipeName,
  name,
  processType,
  breadcrumbAncestors,
  onNavigateToList,
  onNavigateToAncestor,
  onBack,
  onSave,
  isSaving = false,
  onShowShortcuts,
  onUndo,
  onRedo,
  canUndo = false,
  canRedo = false,
  onDeleteSelected,
  zoomPercent,
  onZoomIn,
  onZoomOut,
  onFitView,
  onAutoArrange,
  canAutoArrange = false,
  onPreview,
  onExport,
  onGenerateVisuals,
  isGeneratingVisuals = false,
  visualsProgressLabel,
  generateVisualsStatus,
  aiActions,
  onAddStep,
  onAddCondition,
  nodeOptions = [],
  currentNodeId,
  onSelectNode,
}: RecipeProcessTopBarProps) {
  const steps = nodeOptions.filter((option) => option.kind === 'STEP')
  const conditions = nodeOptions.filter((option) => option.kind === 'CONDITION')
  const currentNode = nodeOptions.find((option) => option.id === currentNodeId) ?? null

  const nodeSelector = onSelectNode && (
    <span className="recipe-node-selector">
      <select
        value={currentNode?.id ?? ''}
        onChange={(event) => {
          if (event.target.value) onSelectNode(event.target.value)
        }}
        disabled={nodeOptions.length === 0}
        aria-label="Selected node"
        title={currentNode ? `Selected node: ${currentNode.label} — choose another node to select it` : 'Choose a node to select it on the canvas'}
        className={currentNode ? 'has-value' : undefined}
      >
        {/* Placeholder only — once a node is selected, the select shows that node's label instead. */}
        <option value="" disabled>{nodeOptions.length === 0 ? 'No nodes yet' : 'Node: Select'}</option>
        {steps.length > 0 && (
          <optgroup label="Steps">
            {steps.map((option) => <option key={option.id} value={option.id}>{option.label}</option>)}
          </optgroup>
        )}
        {conditions.length > 0 && (
          <optgroup label="Conditions">
            {conditions.map((option) => <option key={option.id} value={option.id}>{option.label}</option>)}
          </optgroup>
        )}
      </select>
      <span aria-hidden className="recipe-topbar-caret">▾</span>
    </span>
  )

  return (
    <div className="recipe-topbar @container">
      <div className="recipe-topbar-inner">
        {/* Left: where am I? */}
        <div className="recipe-topbar-context">
          {onBack && (
            <button type="button" onClick={onBack} className="recipe-topbar-button" title="Back to the parent process">
              ← <span className="hidden @3xl:inline">Back</span>
            </button>
          )}
          <RecipeProcessBreadcrumb
            recipeName={recipeName}
            ancestors={breadcrumbAncestors}
            currentName={name || 'Untitled Process'}
            processType={processType}
            onNavigateToList={onNavigateToList}
            onNavigateToAncestor={onNavigateToAncestor}
            trailing={nodeSelector}
          />
        </div>

        {/* Right: canvas controls, then AI / output, then save. */}
        <div className="recipe-topbar-actions">
          <div className="recipe-topbar-group" role="toolbar" aria-label="Canvas controls">
            {(onAddStep || onAddCondition) && (
              <AddNodeMenu
                onAddStep={onAddStep}
                onAddCondition={onAddCondition}
                placement="bottom-start"
                buttonClassName="recipe-topbar-button"
                label={<>+ <span className="hidden @4xl:inline">Add</span></>}
              />
            )}

            {onAutoArrange && (
              <button
                type="button"
                onClick={onAutoArrange}
                disabled={!canAutoArrange}
                className="recipe-topbar-button"
                aria-label="Auto Arrange"
                title="Auto Arrange: lay the flow out in rows (Undo restores your layout)"
              >
                ⇅ <span className="hidden @6xl:inline">Auto Arrange</span>
              </button>
            )}

            {onFitView && (
              <button type="button" onClick={onFitView} className="recipe-topbar-button" aria-label="Fit to view" title="Fit: show the whole flow">
                ⛶ <span className="hidden @7xl:inline">Fit</span>
              </button>
            )}

            {zoomPercent != null && onZoomIn && onZoomOut && (
              // Zoom is also on the canvas's own controls (bottom left), so it can step aside on narrow bars.
              <div className="recipe-topbar-zoom hidden @4xl:flex" role="group" aria-label="Zoom">
                <button type="button" onClick={onZoomOut} aria-label="Zoom out" title="Zoom out">−</button>
                <span aria-live="polite">{zoomPercent}%</span>
                <button type="button" onClick={onZoomIn} aria-label="Zoom in" title="Zoom in">+</button>
              </div>
            )}

            {onUndo && onRedo && (
              <>
                <button type="button" onClick={onUndo} disabled={!canUndo} className="recipe-topbar-button" aria-label="Undo" title="Undo (Ctrl+Z)">↩</button>
                <button type="button" onClick={onRedo} disabled={!canRedo} className="recipe-topbar-button" aria-label="Redo" title="Redo (Ctrl+Y or Ctrl+Shift+Z)">↪</button>
              </>
            )}

            {onDeleteSelected && (
              <button type="button" onClick={onDeleteSelected} className="recipe-topbar-button is-danger" aria-label="Delete selected" title="Delete the selected node or connection (Delete)">
                🗑
              </button>
            )}
          </div>

          <span className="recipe-topbar-divider" aria-hidden />

          <div className="recipe-topbar-group">
            {generateVisualsStatus && (
              <span
                className="recipe-topbar-status hidden @6xl:inline"
                role="status"
                title={generateVisualsStatus.text}
                style={{ color: STATUS_COLORS[generateVisualsStatus.type] }}
              >
                {generateVisualsStatus.text}
              </span>
            )}
            <AiActionsMenu
              recipeActions={aiActions}
              onGenerateVisuals={onGenerateVisuals}
              isGeneratingVisuals={isGeneratingVisuals}
              visualsProgressLabel={visualsProgressLabel}
              generateVisualsStatus={generateVisualsStatus}
            />

            {onPreview && (
              <button type="button" onClick={onPreview} className="recipe-topbar-button" aria-label="Preview this process" title="Preview this process as a slideshow with its visuals and narration (Cook mode, in the header, plays the whole recipe)">
                ▶ <span className="hidden @3xl:inline">Preview process</span>
              </button>
            )}

            {onExport && (
              <button type="button" onClick={onExport} className="recipe-topbar-button" aria-label="Export recipe" title="Export the whole recipe as a file that can be imported from New recipe">
                ⤓ <span className="hidden @6xl:inline">Export</span>
              </button>
            )}
          </div>

          <span className="recipe-topbar-divider" aria-hidden />

          <div className="recipe-topbar-save">
            {onShowShortcuts && (
              <button type="button" onClick={onShowShortcuts} className="recipe-topbar-button" aria-label="Keyboard shortcuts" title="Keyboard shortcuts (?)">
                ⌨
              </button>
            )}
            <button
              type="button"
              onClick={onSave}
              disabled={isSaving}
              title="Changes are saved automatically — this saves everything right now (Ctrl+S)"
              className="recipe-topbar-button is-primary"
            >
              {isSaving ? 'Saving…' : 'Save'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
