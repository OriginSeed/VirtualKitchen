import type { RecipeAiTaskType } from '../../../../types/recipeAiWorkflow'
import { useAiCredit } from '../../workflow/components/AiCreditBadge'
import { useJobsFinishedSignal } from '../../jobs/useJobTracker'
import ToolbarMenu, { MenuDivider, MenuHeading, MenuItem } from './ToolbarMenu'

/**
 * The recipe-level AI actions the Recipe Editor (RecipeEditorView) owns — AI Recipe Creation and
 * "Edit with AI" — handed down to the canvas's top bar so every AI action lives in one menu.
 */
export type RecipeAiActions = {
  /** Opens AI Recipe Creation; `tasks` pre-ticks what AI should create (it shows its progress instead while one runs). */
  onOpenAiCreation: (tasks?: RecipeAiTaskType[]) => void
  /** Opens "Edit with AI" for the open process — omitted while it has nothing to edit. */
  onOpenAiEdit?: () => void
  /** AI Recipe Creation's state while it needs showing (running, waiting for approval, needs attention). */
  status?: { badge: string; title: string } | null
  /** An AI generation/edit job running for this recipe. */
  generationProgress?: { percent: number; stageLabel: string } | null
  /** The recipe storyboard: opens its dialog; `progress` (e.g. "2/4") while one is being generated. */
  storyboard?: { onOpen: () => void; progress: string | null }
}

type AiActionsMenuProps = {
  recipeActions?: RecipeAiActions
  /** Starts the AI visualization job for the open process's own steps. */
  onGenerateVisuals?: () => void
  isGeneratingVisuals?: boolean
  /** e.g. "Generating… 2/5". */
  visualsProgressLabel?: string
  generateVisualsStatus?: { type: 'success' | 'error' | 'info'; text: string } | null
}

const STATUS_COLORS = { success: 'var(--flow-success)', info: 'var(--flow-magic)', error: 'var(--flow-danger)' } as const

/**
 * "✨ AI": every AI operation of the Recipe Editor in one menu, with the user's AI credit balance —
 * credits sit here because only these actions spend them. The button itself reflects what AI is
 * doing right now (AI Recipe Creation's badge, or a running generation/visuals job).
 */
export default function AiActionsMenu({
  recipeActions,
  onGenerateVisuals,
  isGeneratingVisuals = false,
  visualsProgressLabel,
  generateVisualsStatus,
}: AiActionsMenuProps) {
  // Refetch the credit balance whenever a background AI job finishes (it charged or refunded credits).
  const jobsFinishedSignal = useJobsFinishedSignal()
  const credits = useAiCredit(jobsFinishedSignal)
  if (!recipeActions && !onGenerateVisuals) return null

  const { status, generationProgress, storyboard } = recipeActions ?? {}
  const busyLabel = status?.badge
    ?? (generationProgress ? `✨ ${generationProgress.percent}%` : null)
    ?? (isGeneratingVisuals ? '✨ Visuals…' : null)
    ?? (storyboard?.progress ? '✨ Storyboard…' : null)
  const busy = busyLabel != null && busyLabel !== '✨ AI'
  const buttonTitle = status?.title
    ? `AI Recipe Creation — ${status.title}`
    : generationProgress
      ? `Generating with AI — ${generationProgress.stageLabel}`
      : isGeneratingVisuals
        ? (generateVisualsStatus?.text ?? 'Generating step visuals')
        : (storyboard?.progress ? 'Generating the recipe storyboard' : 'AI actions')

  return (
    <ToolbarMenu
      ariaLabel="AI actions"
      title={buttonTitle}
      placement="bottom-end"
      width={286}
      buttonClassName={`recipe-topbar-button is-ai${busy ? ' is-busy' : ''}`}
      label={
        <>
          <span>{busy ? busyLabel : '✨ AI'}</span>
          {credits && (
            <span className={`recipe-topbar-ai-credits${credits.isLow ? ' is-low' : ''}`} title={credits.description}>
              ⚡{credits.credit.availableBalance}
            </span>
          )}
          <span aria-hidden className="recipe-topbar-caret">▾</span>
        </>
      }
    >
      <>
        {status && recipeActions && (
          <>
            <MenuItem
              icon="⏳"
              label="AI Recipe Creation"
              description={status.title}
              onSelect={() => recipeActions.onOpenAiCreation()}
            />
            <MenuDivider />
          </>
        )}
        <MenuHeading>Create with AI</MenuHeading>
        {recipeActions && (
          <MenuItem
            icon="🧭"
            label="Generate Recipe Process"
            description="Turn your recipe text into steps"
            onSelect={() => recipeActions.onOpenAiCreation(['PROCESS'])}
          />
        )}
        {recipeActions?.onOpenAiEdit && (
          <MenuItem
            icon="✏️"
            label="Edit Process with AI"
            description="Describe a change to this process"
            trailing={generationProgress ? `${generationProgress.percent}%` : undefined}
            onSelect={recipeActions.onOpenAiEdit}
          />
        )}
        {onGenerateVisuals && (
          <MenuItem
            icon="🖼️"
            label="Generate Step Visuals"
            description="An AI image for every step of this process"
            trailing={isGeneratingVisuals ? (visualsProgressLabel ?? 'Generating…') : undefined}
            disabled={isGeneratingVisuals}
            title={isGeneratingVisuals ? 'Visuals are being generated for this process' : undefined}
            onSelect={onGenerateVisuals}
          />
        )}
        {storyboard && (
          <MenuItem
            icon="🎞️"
            label="Recipe Storyboard"
            description="The whole recipe as one image of panels"
            trailing={storyboard.progress ?? undefined}
            onSelect={storyboard.onOpen}
          />
        )}
        {recipeActions && (
          <>
            <MenuItem
              icon="🔊"
              label="Generate Narration"
              description="Voice narration for the steps"
              onSelect={() => recipeActions.onOpenAiCreation(['NARRATION'])}
            />
            <MenuItem
              icon="✨"
              label="Complete Recipe Experience"
              description="Process, visuals and narration"
              onSelect={() => recipeActions.onOpenAiCreation(['PROCESS', 'VISUALS', 'NARRATION'])}
            />
          </>
        )}
        {generateVisualsStatus && (
          <div className="recipe-toolbar-menu-status" role="status" style={{ color: STATUS_COLORS[generateVisualsStatus.type] }}>
            {generateVisualsStatus.text}
          </div>
        )}
        {credits && (
          <>
            <MenuDivider />
            <div className={`recipe-toolbar-menu-footer${credits.isLow ? ' is-low' : ''}`} title={credits.description}>
              <span>AI credits</span>
              <strong>⚡ {credits.credit.availableBalance} / {credits.credit.monthlyAllocation}</strong>
            </div>
          </>
        )}
      </>
    </ToolbarMenu>
  )
}
