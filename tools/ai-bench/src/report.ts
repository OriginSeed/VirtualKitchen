/** summary.csv (numbers per combo × stage) and report.html (side-by-side outputs per recipe × combo). */

import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import type { RunRecord, StageBase } from './types'
import type { Stage } from './config'

const STAGES: Stage[] = ['text', 'image', 'speech']
/**
 * Which analytics rows ("<requestType>/<operationContext>") belong to which stage. The image stage
 * also writes its image prompts with TEXT calls, so those count toward the image stage.
 */
const belongsTo = (stage: Stage, key: string) => {
  const promptWriting = key.endsWith('/IMAGE_PROMPT_GENERATION')
  if (stage === 'text') return key.startsWith('TEXT_TO_TEXT/') && !promptWriting
  if (stage === 'image') return key.startsWith('TEXT_TO_IMAGE/') || promptWriting
  return key.startsWith('TEXT_TO_AUDIO/')
}

const percentile = (values: number[], p: number) => {
  if (values.length === 0) return null
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)]
}
const mean = (values: number[]) => (values.length ? values.reduce((a, b) => a + b, 0) / values.length : null)
const round = (value: number | null, digits = 0) => (value === null ? '' : String(Math.round(value * 10 ** digits) / 10 ** digits))

export type SummaryRow = Record<string, string>

export function summarize(records: RunRecord[]): SummaryRow[] {
  const combos = [...new Set(records.map((record) => record.combo))]
  const rows: SummaryRow[] = []
  for (const combo of combos) {
    const runs = records.filter((record) => record.combo === combo)
    for (const stage of STAGES) {
      const present: StageBase[] = runs.flatMap((record) => {
        const value: StageBase | undefined = record.stages[stage]
        return value && value.status !== 'skipped' ? [value] : []
      })
      if (present.length === 0) continue
      // A reused stage made no AI call (its flow was copied, or its audio taken from another run of
      // the group), so it has no latency, model or success of its own to average.
      const stageRuns = present.filter((value) => !value.reused)
      const rate = (count: number) => (stageRuns.length ? count / stageRuns.length : null)
      const latencies = stageRuns.filter((value) => value.status !== 'failed').map((value) => value.clientMs)
      const serverBuckets = runs.flatMap((record) => Object.entries(record.server?.byOperation ?? {})
        .filter(([key]) => belongsTo(stage, key)).map(([, bucket]) => bucket))
      const serverCalls = serverBuckets.reduce((sum, bucket) => sum + bucket.calls, 0)
      const sum = (pick: (bucket: (typeof serverBuckets)[number]) => number) => serverBuckets.reduce((total, bucket) => total + pick(bucket), 0)
      const config = runs[0]?.adminConfig
      const row: SummaryRow = {
        combo,
        stage,
        adminConfig: Object.entries(config ?? {})
          .flatMap(([section, values]) => Object.entries(values).map(([key, value]) => `${section}.${key}=${value}`)).join(' '),
        models: [...new Set(stageRuns.flatMap((value) => value.resolvedModels))].join(' '),
        runs: String(stageRuns.length),
        reused: String(present.length - stageRuns.length),
        successRate: round(rate(stageRuns.filter((value) => value.status === 'ok').length), 3),
        fallbackRate: round(rate(stageRuns.filter((value) => value.usedFallback).length), 3),
        p50Ms: round(percentile(latencies, 50)),
        p95Ms: round(percentile(latencies, 95)),
        maxMs: round(latencies.length ? Math.max(...latencies) : null),
        serverCalls: String(serverCalls),
        avgQueueWaitMs: round(serverCalls ? sum((bucket) => bucket.queueWaitMs) / serverCalls : null),
        avgExecutionMs: round(serverCalls ? sum((bucket) => bucket.executionMs) / serverCalls : null),
        totalTokens: String(sum((bucket) => bucket.totalTokens)),
        costTotal: round(sum((bucket) => bucket.cost), 4),
        avgSteps: '', avgWarnings: '', avgRepairs: '', imageFailureRate: '', audioSecondsPerStep: '',
      }
      if (stage === 'text') {
        const quality = runs.map((record) => record.quality).filter((value) => !!value)
        row.avgSteps = round(mean(quality.map((value) => value.stepCount)), 1)
        row.avgWarnings = round(mean(quality.map((value) => value.warningCount)), 2)
        row.avgRepairs = round(mean(quality.map((value) => value.repairCount)), 2)
      }
      if (stage === 'image') {
        const images = runs.map((record) => record.stages.image).filter((value) => !!value)
        const total = images.reduce((acc, value) => acc + value.totalSteps, 0)
        row.imageFailureRate = round(total ? images.reduce((acc, value) => acc + value.failed, 0) / total : null, 3)
      }
      if (stage === 'speech') {
        const speech = runs.flatMap((record) => (record.stages.speech && !record.stages.speech.reused ? [record.stages.speech] : []))
        const ready = speech.reduce((acc, value) => acc + value.ready, 0)
        row.audioSecondsPerStep = round(ready ? speech.reduce((acc, value) => acc + value.audioSeconds, 0) / ready : null, 1)
      }
      rows.push(row)
    }
  }
  return rows
}

const csvCell = (value: string) => (/[",\n]/.test(value) ? `"${value.replaceAll('"', '""')}"` : value)

const escape = (value: unknown) => String(value ?? '')
  .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;')

const seconds = (ms: number | undefined) => (ms === undefined ? '–' : `${(ms / 1000).toFixed(1)}s`)

export function writeReports(outDir: string, records: RunRecord[], baseUrl: string) {
  const summary = summarize(records)
  const columns = summary.length ? Object.keys(summary[0]) : ['combo', 'stage']
  writeFileSync(join(outDir, 'summary.csv'),
    [columns.join(','), ...summary.map((row) => columns.map((column) => csvCell(row[column] ?? '')).join(','))].join('\n') + '\n')
  writeFileSync(join(outDir, 'report.html'), renderHtml(records, summary, columns, baseUrl))
}

const media = (file: string | undefined, url: string | null | undefined, baseUrl: string) =>
  file ?? (url ? (/^https?:/.test(url) ? url : `${baseUrl}${url}`) : null)

function renderCell(record: RunRecord, baseUrl: string): string {
  const { text, image, speech } = record.stages
  const images = new Map((image?.steps ?? []).map((step) => [step.stepId, step]))
  const audio = new Map((speech?.steps ?? []).map((step) => [step.stepId, step]))
  const cost = record.server ? record.server.totals.cost.toFixed(4) + (record.server.currency ? ` ${record.server.currency}` : '') : '–'
  const badge = (label: string, stage: StageBase | undefined) => {
    if (!stage) return ''
    if (stage.reused) {
      return `<span class="badge reused" title="No AI call: taken from ${escape(stage.reused)}">${label} reused · ${escape(stage.resolvedModels.join(',') || stage.requestedModel || 'flow')}</span>`
    }
    return `<span class="badge ${stage.status}${stage.usedFallback ? ' fallback' : ''}" title="${escape(stage.error ?? '')}">${label} ${seconds(stage.clientMs)} · ${escape(stage.resolvedModels.join(',') || stage.requestedModel)}${stage.usedFallback ? ' ⚠fallback' : ''}</span>`
  }
  const flow = record.flow
  const flowLine = !flow ? '' : flow.source === 'generated'
    ? `flow generated by this run${flow.file ? ` · <a href="${escape(flow.file)}">flow.json</a>` : ''}`
    : flow.source === 'reused'
      ? `flow copied from ${escape(flow.from)}${speech?.reused ? ` · audio from ${escape(speech.reused)}` : ''}`
      : `flow from file ${escape(flow.file)}${speech?.reused ? ` · audio from ${escape(speech.reused)}` : ''}`
  const q = record.quality
  const steps = record.steps.map((step, index) => {
    const img = images.get(step.nodeId)
    const src = media(img?.file, img?.imageUrl, baseUrl)
    const sound = audio.get(step.nodeId)
    const audioSrc = media(sound?.file, sound?.audioUrl, baseUrl)
    return `<li class="${step.kind}">
      ${src ? `<a href="${escape(src)}" target="_blank"><img loading="lazy" src="${escape(src)}" alt=""></a>` : img ? `<div class="noimg" title="${escape(img.error ?? '')}">no image</div>` : ''}
      <div class="step"><b>${index + 1}. ${escape(step.title || '(untitled)')}</b>${step.duration ? ` <span class="dur">${escape(step.duration)}</span>` : ''}
        ${step.processType !== 'MAIN' ? `<span class="proc">${escape(step.processName)}</span>` : ''}
        <p>${escape(step.description)}</p>
        ${audioSrc ? `<audio controls preload="none" src="${escape(audioSrc)}"></audio>` : sound ? `<span class="err">${escape(sound.status)} ${escape(sound.failureReason ?? '')}</span>` : ''}
      </div></li>`
  }).join('')
  return `<div class="run">
    <div class="head">#${record.repeat} · ${record.status === 'ok' ? '✅' : '❌'} ${seconds(record.totalMs)} · cost ${escape(cost)}${record.recipeId ? ` · recipe ${record.recipeId}` : ''}</div>
    <div class="badges">${badge('text', text)}${badge('image', image)}${badge('speech', speech)}</div>
    ${flowLine ? `<div class="q">${flowLine}</div>` : ''}
    ${record.error ? `<div class="err">${escape(record.error)}</div>` : ''}
    ${q ? `<div class="q">steps ${q.stepCount} · conditions ${q.conditionCount} · warnings ${q.warningCount} · repairs ${q.repairCount} · no-duration ${q.stepsWithoutDuration} · empty desc ${q.emptyDescriptions} · dup titles ${q.duplicateTitles}${q.danglingEdges || q.isolatedNodes ? ` · ⚠ graph: ${q.danglingEdges} dangling, ${q.isolatedNodes} isolated` : ''}</div>` : ''}
    ${text?.warnings.length ? `<details><summary>warnings</summary><ul>${text.warnings.map((w) => `<li>${escape(w)}</li>`).join('')}</ul></details>` : ''}
    <ol class="steps">${steps}</ol>
  </div>`
}

function renderHtml(records: RunRecord[], summary: SummaryRow[], columns: string[], baseUrl: string): string {
  const recipes = [...new Set(records.map((record) => record.recipe))]
  const combos = [...new Set(records.map((record) => record.combo))]
  const runId = records[0]?.runId ?? ''
  const comboHeader = (combo: string) => {
    const first = records.find((record) => record.combo === combo)
    const config = first?.adminConfig
    const group = first?.group ? `<div class="muted" style="font-weight:400;font-size:12px">group ${first.group}: shared flow</div>` : ''
    const settings = Object.entries(config ?? {})
      .flatMap(([section, values]) => Object.entries(values).map(([key, value]) => `${section}.${key}=${value}`))
    return `${escape(combo)}${group}${settings.length ? `<div class="muted" style="font-weight:400;font-size:12px">${escape(settings.join(', '))}</div>` : ''}`
  }
  const grid = recipes.map((recipe) => `<tr><th class="recipe">${escape(recipe)}</th>${combos.map((combo) => `<td>${
    records.filter((record) => record.recipe === recipe && record.combo === combo).map((record) => renderCell(record, baseUrl)).join('') || '–'
  }</td>`).join('')}</tr>`).join('')
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>AI Bench ${escape(runId)}</title>
<style>
:root{--bg:#fff;--fg:#1d1d1f;--muted:#6b6b70;--line:#e3e3e8;--card:#f7f7f9;--ok:#1f7a3a;--bad:#b42318;--warn:#a15c00}
@media (prefers-color-scheme:dark){:root{--bg:#141416;--fg:#ececf0;--muted:#9a9aa2;--line:#2c2c31;--card:#1c1c20;--ok:#5ccf7e;--bad:#ff7a6e;--warn:#f0b35a}}
body{margin:0;padding:16px;background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,sans-serif}
h1{font-size:20px;margin:0 0 4px}h2{font-size:16px;margin:24px 0 8px}.muted{color:var(--muted)}
.scroll{overflow-x:auto}table{border-collapse:collapse}th,td{border:1px solid var(--line);padding:6px 8px;vertical-align:top;text-align:left}
table.summary td{font-variant-numeric:tabular-nums;white-space:nowrap}
table.grid td{min-width:340px;max-width:460px}th.recipe{position:sticky;left:0;background:var(--bg)}
.run{background:var(--card);border-radius:8px;padding:8px;margin-bottom:8px}.head{font-weight:600}
.badges{display:flex;flex-wrap:wrap;gap:4px;margin:4px 0}.badge{border:1px solid var(--line);border-radius:999px;padding:1px 8px;font-size:12px}
.badge.reused{color:var(--muted);border-style:dashed}.badge.ok{color:var(--ok)}.badge.failed{color:var(--bad)}.badge.partial,.badge.fallback{color:var(--warn)}
.err{color:var(--bad);font-size:12px}.q{color:var(--muted);font-size:12px}
ol.steps{list-style:none;padding:0;margin:6px 0 0}ol.steps li{display:flex;gap:8px;padding:6px 0;border-top:1px solid var(--line)}
ol.steps li.condition{opacity:.85;font-style:italic}ol.steps img{width:96px;height:96px;object-fit:cover;border-radius:6px;flex:none}
.noimg{width:96px;height:96px;flex:none;display:grid;place-items:center;border:1px dashed var(--bad);border-radius:6px;color:var(--bad);font-size:12px}
.step p{margin:2px 0;font-size:13px}.dur,.proc{font-size:11px;color:var(--muted);margin-left:4px}audio{width:100%;height:28px;margin-top:2px}
</style></head><body>
<h1>AI Bench</h1>
<div class="muted">run ${escape(runId)} · ${records.length} runs · ${recipes.length} recipes × ${combos.length} combos · ${escape(baseUrl)}</div>
<h2>Summary by combo × stage</h2>
<div class="scroll"><table class="summary"><tr>${columns.map((column) => `<th>${escape(column)}</th>`).join('')}</tr>
${summary.map((row) => `<tr>${columns.map((column) => `<td>${escape(row[column])}</td>`).join('')}</tr>`).join('')}</table></div>
<p class="muted">Latency = client wall time per stage (failed runs excluded). "reused" counts runs whose flow was copied or whose audio was taken from another run of the group: they made no AI call for that stage and are left out of its figures. Server columns come from AI analytics (need an admin account); the image row includes the text calls that write image prompts.</p>
<h2>Outputs</h2>
<div class="scroll"><table class="grid"><tr><th></th>${combos.map((combo) => `<th>${comboHeader(combo)}</th>`).join('')}</tr>${grid}</table></div>
</body></html>`
}
