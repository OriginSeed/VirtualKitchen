import { useEffect, useMemo, useState } from 'react'
import {
  describeOutcome,
  describeRef,
  type CheckOutcome,
  type RecipeBranchTag,
  type RecipeTimelineCheck,
  type RecipeTimelineFlow,
  type RecipeTimelineSection,
  type RecipeTimelineStep,
  type StepContinuation,
  type InstructionPart,
} from '../model/recipeView'
import RecipeImage from './RecipeImage'
import { scrollToAnchor } from './scrollToAnchor'

type RecipeStepsTimelineProps = {
  sections: RecipeTimelineSection[]
  /** Opens a step in the editor (owners only). */
  onOpenStep?: (processId: number, nodeId: string) => void
}

type OpenStep = RecipeStepsTimelineProps['onOpenStep']

type Navigate = (anchorId: string) => void

const stepsLabel = (count: number) => `${count} step${count === 1 ? '' : 's'}`

/**
 * The recipe's steps as a numbered vertical timeline. A recipe with subprocesses reads as stages
 * (each subprocess, prepared before the stage that uses it, then the main process), with an
 * overview to jump between them; long recipes start with only the first stage open. A recipe with
 * a single process reads as one plain list of steps, with no stage chrome at all.
 */
export default function RecipeStepsTimeline({ sections, onOpenStep }: RecipeStepsTimelineProps) {
  const isStaged = sections.length > 1
  // Every stage starts open; the reader can fold any of them away.
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(() => new Set())
  // A printed recipe shows every stage, whatever the reader had folded away.
  useEffect(() => {
    const expandAll = () => setCollapsed(new Set())
    window.addEventListener('beforeprint', expandAll)
    return () => window.removeEventListener('beforeprint', expandAll)
  }, [])

  // Which stage each anchor (the stage itself, or any step/check in it) lives in.
  const sectionOfAnchor = useMemo(() => {
    const map = new Map<string, string>()
    for (const section of sections) {
      map.set(section.anchorId, section.key)
      for (const item of section.items) map.set(item.anchorId, section.key)
      for (const flow of section.flows ?? []) map.set(flow.anchorId, section.key)
    }
    return map
  }, [sections])

  const toggle = (key: string) => setCollapsed((current) => {
    const next = new Set(current)
    if (next.has(key)) next.delete(key)
    else next.add(key)
    return next
  })

  // Links can point into a collapsed stage: open it first, then scroll once it has rendered.
  const navigate: Navigate = (anchorId) => {
    const sectionKey = sectionOfAnchor.get(anchorId)
    if (sectionKey && collapsed.has(sectionKey)) {
      setCollapsed((current) => {
        const next = new Set(current)
        next.delete(sectionKey)
        return next
      })
      window.requestAnimationFrame(() => scrollToAnchor(anchorId))
      return
    }
    scrollToAnchor(anchorId)
  }

  return (
    <div className="rp-steps">
      {isStaged && (
        <nav className="rp-stage-overview" aria-label="Stages">
          {sections.map((section, index) => (
            <button key={section.key} type="button" className="rp-stage-chip" onClick={() => navigate(section.anchorId)}>
              <span className="rp-stage-chip-number" aria-hidden>{index + 1}</span>
              <span className="rp-stage-chip-title">{section.title}</span>
              <span className="rp-stage-chip-count">{section.stepCount}</span>
            </button>
          ))}
        </nav>
      )}

      {sections.map((section, index) => {
        const isOpen = !isStaged || !collapsed.has(section.key)
        const listId = `${section.anchorId}-steps`
        return (
          <section key={section.key} id={section.anchorId} className={`rp-part${isOpen ? '' : ' is-collapsed'}`} aria-label={section.title}>
            {isStaged && (
              <h3 className="rp-part-heading">
                <button
                  type="button"
                  className="rp-part-header"
                  aria-expanded={isOpen}
                  aria-controls={listId}
                  onClick={() => toggle(section.key)}
                >
                  <span className="rp-part-label">Stage {index + 1}</span>
                  <span className="rp-part-text">
                    <span className="rp-part-title">{section.title}</span>
                    {section.summary && <span className="rp-part-summary">{section.summary}</span>}
                  </span>
                  <span className="rp-part-count">{stepsLabel(section.stepCount)}</span>
                  <span className="rp-part-chevron" aria-hidden>▾</span>
                </button>
              </h3>
            )}
            {isOpen && section.flows && (
              // A Preparation: each independent flow is one numbered step, its steps explained as points.
              <ol id={listId} className="rp-timeline">
                {section.flows.map((flow) => <FlowItem key={flow.key} flow={flow} navigate={navigate} onOpenStep={onOpenStep} />)}
              </ol>
            )}
            {isOpen && !section.flows && (
              <ol id={listId} className="rp-timeline">
                {section.items.map((item) =>
                  item.kind === 'step'
                    ? <StepItem key={item.key} step={item} navigate={navigate} onOpenStep={onOpenStep} />
                    : <CheckItem key={item.key} check={item} navigate={navigate} />)}
              </ol>
            )}
          </section>
        )
      })}
    </div>
  )
}

function BranchTag({ branch }: { branch?: RecipeBranchTag }) {
  if (!branch) return null
  return (
    <div className="rp-branch-tag">
      <span aria-hidden>🔀</span> Only if “{branch.answer}”{branch.question && <span>· {branch.question}</span>}
    </div>
  )
}

/** The step's sentence: amounts emphasised, earlier results linking to where they're made. */
function Instruction({ parts, navigate }: { parts: InstructionPart[]; navigate: Navigate }) {
  return (
    <>
      {parts.map((part, index) => {
        if (part.kind === 'amount') return <strong key={index} className="rp-amount">{part.text}</strong>
        if (part.kind === 'product' && part.anchorId) {
          const anchorId = part.anchorId
          return (
            <button key={index} type="button" className="rp-product-link" onClick={() => navigate(anchorId)} title={part.source ? `From ${part.source}` : undefined}>
              {part.text}
            </button>
          )
        }
        return <span key={index}>{part.text}</span>
      })}
    </>
  )
}

function OpenInEditor({ processId, nodeId, onOpenStep, title = 'Open this step in the editor' }: {
  processId: number
  nodeId: string
  onOpenStep: OpenStep
  title?: string
}) {
  if (!onOpenStep) return null
  return (
    <button type="button" className="rp-open-step" onClick={() => onOpenStep(processId, nodeId)} title={title}>
      Open in editor ↗
    </button>
  )
}

/**
 * One preparation flow as ONE step of the recipe: "Prepare Marinated Chicken", its steps as explanatory points in
 * cooking order, the output it makes, and one way into the editor for the whole flow.
 */
function FlowItem({ flow, navigate, onOpenStep }: { flow: RecipeTimelineFlow; navigate: Navigate; onOpenStep: OpenStep }) {
  // Steps with their own images show them beside each point; an older flow has one image for the whole card.
  const hasImage = !flow.stepImages && Boolean(flow.imageUrl)
  return (
    <li id={flow.anchorId} className="rp-timeline-item">
      <span className="rp-step-number" aria-hidden>{flow.number || '?'}</span>
      <article className={`rp-card rp-step-card rp-flow-card${hasImage ? ' has-image' : ''}`} aria-label={flow.title}>
        <div className="rp-step-body">
          <h4 className="rp-step-heading"><span className="rp-step-inline-icon" aria-hidden>🔪</span>{flow.title}</h4>
          <ul className="rp-flow-points">
            {flow.items.map((item) => (item.kind === 'step' ? (
              <li key={item.key} id={item.anchorId} className={`rp-flow-point${flow.stepImages ? ' has-image' : ''}`}>
                <div className="rp-flow-point-row">
                  <div className="rp-flow-point-copy">
                    <p className="rp-flow-point-text"><Instruction parts={item.instruction} navigate={navigate} /></p>
                    {item.explanation && <p className="rp-flow-point-note">{item.explanation}</p>}
                  </div>
                  {flow.stepImages && (
                    <div className="rp-flow-point-media">
                      <RecipeImage src={item.imageUrl} alt={`${flow.title}: ${item.name}`} caption={item.instructionText || item.name} fallbackIcon={item.icon} />
                    </div>
                  )}
                </div>
              </li>
            ) : (
              <li key={item.key} id={item.anchorId} className="rp-flow-point is-check">
                <p className="rp-flow-point-text"><span aria-hidden>🔍</span> {item.question}</p>
              </li>
            )))}
          </ul>
          <div className={`rp-flow-output${flow.outputName ? '' : ' is-missing'}`}>
            {flow.outputName ? <>Output: <strong>{flow.outputName}</strong></> : 'No named output yet — give the last step an Expected Output'}
          </div>
          <OpenInEditor processId={flow.processId} nodeId={flow.nodeId} onOpenStep={onOpenStep} title="Open this preparation in the editor" />
        </div>

        {/* An older preparation's one image for the whole flow (a storyboard of its steps when it has several). */}
        {hasImage && (
          <div className="rp-step-media">
            <RecipeImage src={flow.imageUrl} alt={flow.title} caption={flow.title} fallbackIcon="🔪" />
          </div>
        )}
      </article>
    </li>
  )
}

function StepItem({ step, navigate, onOpenStep }: { step: RecipeTimelineStep; navigate: Navigate; onOpenStep: OpenStep }) {
  const hasImage = Boolean(step.imageUrl)

  return (
    <li id={step.anchorId} className="rp-timeline-item">
      <span className="rp-step-number" aria-hidden>{step.number}</span>

      <article className={`rp-card rp-step-card${hasImage ? ' has-image' : ''}`} aria-label={`Step ${step.number}`}>
        <div className="rp-step-body">
          <BranchTag branch={step.branch} />

          {step.showName && (
            <h4 className="rp-step-heading">
              <span className="rp-step-inline-icon" aria-hidden>{step.icon}</span>
              {step.name}
            </h4>
          )}

          <p className="rp-step-instruction">
            {!step.showName && <span className="rp-step-inline-icon" aria-hidden>{step.icon}</span>}
            {step.continues && <span className="rp-continues" title="Carries on with what is already cooking">Continue</span>}
            <Instruction parts={step.instruction} navigate={navigate} />
          </p>


          {step.explanation && <p className="rp-step-explanation">{step.explanation}</p>}

          {step.readyWhen && (
            <div className="rp-look-for">
              <span aria-hidden>👀</span>
              <span><strong>Ready when:</strong> {step.readyWhen}</span>
            </div>
          )}

          <Continuation continuation={step.continuation} navigate={navigate} />
          <OpenInEditor processId={step.processId} nodeId={step.nodeId} onOpenStep={onOpenStep} />
        </div>

        {hasImage && (
          <div className="rp-step-media">
            <RecipeImage
              src={step.imageUrl}
              alt={`Step ${step.number}: ${step.name}`}
              caption={`Step ${step.number} — ${step.instructionText || step.name}`}
              fallbackIcon={step.icon}
            />
          </div>
        )}
      </article>
    </li>
  )
}

function Continuation({ continuation, navigate }: { continuation?: StepContinuation; navigate: Navigate }) {
  if (!continuation) return null

  if (continuation.kind === 'end') {
    return <span className="rp-continuation">🏁 This path ends here</span>
  }

  if (continuation.kind === 'choice') {
    return (
      <span className="rp-continuation">
        → Next, continue with
        {continuation.targets.map((target, index) => (
          <span key={target.key}>
            {index > 0 && (index === continuation.targets.length - 1 ? ' and ' : ', ')}
            <button type="button" className="rp-continuation-link" onClick={() => navigate(target.anchorId)}>
              {describeRef(target, false)}
            </button>
          </span>
        ))}
      </span>
    )
  }

  return (
    <button type="button" className="rp-continuation" onClick={() => navigate(continuation.target.anchorId)}>
      {continuation.direction === 'back' ? '↻ Then go back to' : '→ Then skip ahead to'} {describeRef(continuation.target)}
    </button>
  )
}

const outcomeIcon = (outcome: CheckOutcome) => {
  if (outcome.direction === 'back') return '↻'
  return outcome.tone === 'yes' ? '✓' : '✕'
}

function CheckItem({ check, navigate }: { check: RecipeTimelineCheck; navigate: Navigate }) {
  return (
    <li id={check.anchorId} className="rp-timeline-item is-check">
      {/* Checks are decisions, not steps: a marker instead of a number, so numbers match the step count. */}
      <span className="rp-step-number" aria-hidden>?</span>

      <article className="rp-card rp-check-card" aria-label="Check">
        <BranchTag branch={check.branch} />
        <span className="rp-check-eyebrow"><span aria-hidden>🔍</span> Check</span>
        <h4 className="rp-check-question">{check.question}</h4>
        {check.notes && <p className="rp-check-notes"><strong>How to check:</strong> {check.notes}</p>}

        {check.outcomes.length > 0 && (
          <div className="rp-outcomes">
            {check.outcomes.map((outcome) => (
              <button
                key={outcome.key}
                type="button"
                className={`rp-outcome is-${outcome.tone}`}
                onClick={() => navigate(outcome.target.anchorId)}
              >
                <span className="rp-outcome-icon" aria-hidden>{outcomeIcon(outcome)}</span>
                <span className="rp-outcome-text">
                  <span className="rp-outcome-answer">{outcome.answer}</span>
                  <span className="rp-outcome-action">{describeOutcome(outcome)}</span>
                </span>
              </button>
            ))}
          </div>
        )}
      </article>
    </li>
  )
}
