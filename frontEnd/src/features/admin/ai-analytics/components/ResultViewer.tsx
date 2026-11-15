import { useEffect, useMemo, useState } from 'react'
import { API_CONFIG } from '../../../../api/config'
import { AiAnalyticsApi } from '../api/aiAnalyticsApi'
import type { AiResult } from '../api/aiAnalyticsApi'
import { useApiData } from '../../../../shared/hooks/useApiData'
import ZoomableImage from '../../../../shared/components/image-viewer/ZoomableImage'
import {
  costBasisLabel,
  formatBytes,
  formatCost,
  formatMs,
  formatTokens,
  humanize,
} from '../model/analyticsFormat'
import { LoadState, StatusBadge } from './common'

interface ResultViewerProps {
  requestId: string
  onClose: () => void
}

const PROVENANCE_LABELS: Record<string, string> = {
  RETAINED: 'Exact output of this request',
  JOB_RESULT: 'Final result stored on the generation job',
  CURRENT_ASSET: 'Read from the asset this request produced',
  CURRENT_ASSET_MAY_BE_NEWER: 'Current asset — may have been regenerated since',
  REUSED_FROM: 'Reused output of an earlier request',
}

/** Resolves a stored media URL; locally stored narration audio is served by the backend under a relative path. */
function mediaUrl(url: string): string {
  try {
    return new URL(url, API_CONFIG.baseURL).toString()
  } catch {
    return url
  }
}

/** A JSON result pretty-printed, with a one-line summary of a generated recipe flow when it looks like one. */
function JsonView({ text }: { text: string }) {
  const parsed = useMemo(() => {
    try {
      return JSON.parse(text.trim().replace(/^```(?:json)?\s*|```$/g, '')) as unknown
    } catch {
      return undefined
    }
  }, [text])
  if (parsed === undefined) return <pre className="aia-pre">{text}</pre>

  const summary: string[] = []
  if (parsed && typeof parsed === 'object') {
    const root = parsed as Record<string, unknown>
    const subprocesses = Array.isArray(root.subprocesses) ? root.subprocesses.length : null
    const main = root.mainProcess as Record<string, unknown> | undefined
    const nodes = main && Array.isArray(main.nodes) ? main.nodes.length : null
    if (main) summary.push('Main process')
    if (nodes !== null) summary.push(`${nodes} nodes in main process`)
    if (subprocesses !== null) summary.push(`${subprocesses} subprocess(es)`)
    if (Array.isArray(root.operations)) summary.push(`${root.operations.length} edit operation(s)`)
  }
  return (
    <>
      {summary.length > 0 && <p className="aia-summary">{summary.join(' · ')}</p>}
      <pre className="aia-pre">{JSON.stringify(parsed, null, 2)}</pre>
    </>
  )
}

function ResultBody({ result }: { result: AiResult }) {
  if (!result.retained) {
    return (
      <div className="aia-alert">
        <p>{result.message}</p>
        {result.errorMessage && <p className="aia-error-text">Error: {result.errorMessage}</p>}
      </div>
    )
  }
  if (result.kind === 'IMAGE' && result.url) {
    return (
      <figure className="aia-media">
        <ZoomableImage src={mediaUrl(result.url)} alt="Generated output" caption="Generated output" loading="lazy" />
        <figcaption className="aia-muted">
          {[result.imageWidth && result.imageHeight ? `${result.imageWidth}×${result.imageHeight}` : null,
            result.mimeType, result.sizeBytes ? formatBytes(result.sizeBytes) : null].filter(Boolean).join(' · ')}
        </figcaption>
      </figure>
    )
  }
  if (result.kind === 'AUDIO' && result.url) {
    return (
      <figure className="aia-media">
        <audio controls preload="none" src={mediaUrl(result.url)} />
        <figcaption className="aia-muted">
          {[result.audioDurationMs ? formatMs(result.audioDurationMs) : null, result.mimeType,
            result.sizeBytes ? formatBytes(result.sizeBytes) : null].filter(Boolean).join(' · ')}
        </figcaption>
      </figure>
    )
  }
  const text = result.content ?? ''
  return result.kind === 'JSON' ? <JsonView text={text} /> : <pre className="aia-pre aia-pre--wrap">{text}</pre>
}

/** Modal that fetches one request's AI result on demand, next to its efficiency figures. */
export default function ResultViewer({ requestId, onClose }: ResultViewerProps) {
  const [attemptId, setAttemptId] = useState<string | undefined>(undefined)
  const [full, setFull] = useState(false)
  const [showPrompt, setShowPrompt] = useState(false)
  const key = `${requestId}|${attemptId ?? ''}|${full}|${showPrompt}`
  const { data: result, error, loading, reload } = useApiData(key, () =>
    AiAnalyticsApi.result(requestId, { attemptId, full, prompt: showPrompt }))

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const m = result?.metrics
  const attempts = result?.attempts ?? []
  const copy = () => {
    if (result?.content) void navigator.clipboard?.writeText(result.content)
  }

  return (
    <div className="aia-modal-backdrop" onClick={onClose}>
      <div className="aia-modal" role="dialog" aria-modal="true" aria-label="AI result" onClick={(e) => e.stopPropagation()}>
        <header className="aia-modal-header">
          <div>
            <h2>AI result</h2>
            <p className="aia-muted aia-mono">{requestId}</p>
          </div>
          <button type="button" className="aia-icon-button" aria-label="Close" onClick={onClose}>×</button>
        </header>

        {m && (
          <div className="aia-efficiency" aria-label="Efficiency">
            <span><StatusBadge status={m.status} /></span>
            <span><b>Model</b> {m.model ?? m.providerModelId ?? 'Unknown'}</span>
            <span><b>Tokens</b> {formatTokens(m.inputTokens)} in / {formatTokens(m.outputTokens)} out</span>
            <span>
              <b>Cost</b> {m.totalCost === null ? 'Unavailable' : formatCost(m.totalCost, m.currency)}
              {m.totalCost !== null && ` · ${costBasisLabel(m.costBasis)}`}
              {m.costComplete === false && m.totalCost !== null && ' · partial'}
            </span>
            <span><b>Latency</b> {formatMs(m.latencyMs)}</span>
            <span><b>Calls</b> {m.attemptCount ?? '—'}{m.transportAttempts && m.transportAttempts > 1 ? ` (${m.transportAttempts - 1} retry)` : ''}</span>
            {m.validationPassed !== null && <span><b>Validation</b> {m.validationPassed ? 'Passed' : 'Failed'}</span>}
          </div>
        )}

        <div className="aia-modal-toolbar">
          {attempts.length > 1 && (
            <label className="aia-inline-field">
              <span>Output of</span>
              <select value={attemptId ?? ''} onChange={(e) => setAttemptId(e.target.value || undefined)}>
                <option value="">Accepted result</option>
                {attempts.map((a) => (
                  <option key={a.attemptId} value={a.attemptId} disabled={!a.hasOutput}>
                    Call {a.callIndex} · {humanize(a.purpose)} · {humanize(a.status)}
                    {a.validationPassed === false ? ' · failed validation' : ''}
                    {!a.hasOutput ? ' · no output' : ''}
                  </option>
                ))}
              </select>
            </label>
          )}
          <label className="aia-inline-field">
            <input type="checkbox" checked={showPrompt} onChange={(e) => setShowPrompt(e.target.checked)} />
            <span>Show prompt</span>
          </label>
          {result?.content && <button type="button" className="aia-link" onClick={copy}>Copy</button>}
          {result?.truncated && (
            <button type="button" className="aia-link" onClick={() => setFull(true)}>
              Load full ({result.contentLength.toLocaleString()} chars)
            </button>
          )}
        </div>

        {result?.provenance && (
          <p className={`aia-provenance${result.provenance === 'CURRENT_ASSET_MAY_BE_NEWER' ? ' aia-provenance--warn' : ''}`}>
            {PROVENANCE_LABELS[result.provenance] ?? humanize(result.provenance)}
            {result.provenanceNote && ` — ${result.provenanceNote}`}
          </p>
        )}

        <LoadState loading={loading && !result} error={error} onRetry={reload} />

        {result && (
          <div className={showPrompt ? 'aia-compare' : undefined}>
            {showPrompt && (
              <section>
                <h3>Prompt</h3>
                {result.prompt ? <pre className="aia-pre aia-pre--wrap">{result.prompt}</pre> : <p className="aia-muted">Prompt not retained.</p>}
              </section>
            )}
            <section>
              {showPrompt && <h3>Output</h3>}
              <ResultBody result={result} />
            </section>
          </div>
        )}

        {attempts.some((a) => a.errorMessage) && (
          <section className="aia-attempt-errors">
            <h3>Errors</h3>
            <ul>
              {attempts.filter((a) => a.errorMessage).map((a) => (
                <li key={a.attemptId}><b>Call {a.callIndex}</b> ({humanize(a.errorCategory)}): {a.errorMessage}</li>
              ))}
            </ul>
          </section>
        )}
      </div>
    </div>
  )
}
