import { useState } from 'react'
import type { ReadinessIssue, RecipeReadiness } from '../../model/recipeReadiness'

type RecipeReadinessPanelProps = {
  readiness: RecipeReadiness
  /** Opens the issue's process and selects its step. */
  onNavigate: (issue: ReadinessIssue) => void
  /** Changes whenever the panel should open by itself (e.g. after a blocked operation's "Show me"). */
  openToken?: number
}

/**
 * The editor's live readiness summary: "Ready to cook", or how many issues block cooking/ordering (✖) and how many
 * are only warnings (⚠). Expands into the list of issues; each opens its process and selects its step. Never
 * interrupts editing — blocked operations explain themselves when they're started (see useReadinessGate).
 */
export default function RecipeReadinessPanel({ readiness, onNavigate, openToken }: RecipeReadinessPanelProps) {
  const [expanded, setExpanded] = useState(false)
  const [handledOpenToken, setHandledOpenToken] = useState(openToken)
  if (openToken !== handledOpenToken) {
    setHandledOpenToken(openToken)
    if (openToken != null) setExpanded(true)
  }

  const { blockers, warnings, issues } = readiness
  const blocked = blockers.length > 0
  // Collapsed, issues are just a small chip with their count — a nudge, not an alarm; the list opens on click.
  const label = issues.length === 0
    ? 'Ready to cook'
    : [blockers.length > 0 ? `${blockers.length} to fix` : '', warnings.length > 0 ? `${warnings.length} warning${warnings.length === 1 ? '' : 's'}` : ''].filter(Boolean).join(' · ')

  return (
    <div className="flex flex-shrink-0 flex-col border-b border-[var(--flow-border)]">
      <div style={{ display: 'flex', alignItems: 'center', padding: '6px 14px' }}>
        {issues.length === 0 ? (
          <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--flow-success)' }}>✓ {label}</span>
        ) : (
          <button
            type="button"
            onClick={() => setExpanded((current) => !current)}
            aria-expanded={expanded}
            aria-label={`${label} — ${expanded ? 'hide' : 'show'} details`}
            title={blockers.length > 0
              ? `${label}: these must be fixed before cooking, ordering or creating visuals for the whole recipe`
              : `${label}: cooking, ordering and whole-recipe visuals are available`}
            style={{
              display: 'inline-flex', alignItems: 'center', gap: 4,
              padding: '2px 8px', borderRadius: 999, cursor: 'pointer',
              // Red while something blocks cooking/ordering; amber when only warnings remain.
              border: `1px solid ${blocked ? 'var(--flow-danger-border)' : 'var(--flow-warning-border)'}`,
              background: blocked ? 'var(--flow-danger-soft)' : 'var(--flow-warning-soft)',
              fontSize: 11, fontWeight: 700, color: blocked ? 'var(--flow-danger)' : 'var(--flow-warning)',
            }}
          >
            <span aria-hidden>{blocked ? '✖' : '⚠'}</span>
            <span aria-hidden>{issues.length}</span>
            <span aria-hidden style={{ fontSize: 9, color: 'var(--flow-text-subtle)' }}>{expanded ? '▾' : '▸'}</span>
          </button>
        )}
      </div>
      {expanded && issues.length > 0 && (
        <ul style={{ listStyle: 'none', margin: 0, padding: '0 8px 8px', display: 'flex', flexDirection: 'column', gap: 4, maxHeight: 240, overflowY: 'auto' }}>
          {[...blockers, ...warnings].map((issue, index) => (
            <li key={`${issue.code}-${issue.processId}-${issue.nodeId}-${index}`}>
              <button
                type="button"
                onClick={() => onNavigate(issue)}
                disabled={issue.processId == null}
                style={{
                  width: '100%', display: 'flex', gap: 6, alignItems: 'flex-start', textAlign: 'left',
                  padding: '6px 8px', borderRadius: 8, fontSize: 11, lineHeight: 1.4, cursor: issue.processId == null ? 'default' : 'pointer',
                  border: `1px solid ${issue.severity === 'BLOCKER' ? 'var(--flow-danger-border)' : 'var(--flow-warning-border)'}`,
                  background: issue.severity === 'BLOCKER' ? 'var(--flow-danger-soft)' : 'var(--flow-warning-soft)',
                  color: 'var(--flow-text)',
                }}
              >
                <span aria-hidden>{issue.severity === 'BLOCKER' ? '✖' : '⚠'}</span>
                <span>
                  {issue.message}
                  <span className="sr-only">{issue.severity === 'BLOCKER' ? ' (must be fixed)' : ' (warning)'}</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
