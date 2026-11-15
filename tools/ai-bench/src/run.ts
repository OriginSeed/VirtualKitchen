/** One bench run: a fresh recipe, run through one model combination, measured end to end. */

import { mkdirSync, writeFileSync } from 'node:fs'
import { importFlow, toFlowDoc } from './flow'
import type { FlowDoc } from './flowDoc.ts'
import { join, relative } from 'node:path'
import type { ApiClient } from './api'
import type { Combo, RecipeInput, Stage } from './config'
import type { Process } from '../../../frontEnd/src/types/process'
import type { RunRecord, SpeechStage } from './types'
import { runGeneration, saveGeneratedProcesses } from './stages/generate'
import { runVisualization } from './stages/visualize'
import { runNarration } from './stages/narrate'
import { summarizeSteps } from './stages/steps'
import { collectServerMetrics } from './stages/metrics'
import { assessQuality } from './quality'

export type RunContext = {
  runId: string
  outDir: string
  user: ApiClient
  admin: ApiClient | null
  stages: Stage[]
  timeouts: Record<Stage, number>
  downloadAssets: boolean
  onRecipeCreated: (recipeId: number) => void
  log: (line: string) => void
}

/**
 * What the combos of one group share, per recipe: the flow a text model generated (so it is generated
 * once and copied into every other run) and the narration of that flow (so it is spoken once). Both
 * hold the pending promise, so runs in flight at the same time wait for the first instead of
 * starting their own.
 */
export class GroupShare {
  private readonly flows = new Map<string, Promise<{ flow: FlowDoc; from: string; file?: string }>>()
  private readonly narrations = new Map<string, Promise<{ stage: SpeechStage; from: string } | null>>()

  flow(key: string) { return this.flows.get(key) }
  offerFlow(key: string, flow: Promise<{ flow: FlowDoc; from: string; file?: string }>) {
    // A failed generation is each waiting run's own failure; nothing may be left unhandled here.
    flow.catch(() => {})
    this.flows.set(key, flow)
  }

  narration(key: string) { return this.narrations.get(key) }
  offerNarration(key: string, narration: Promise<{ stage: SpeechStage; from: string } | null>) {
    this.narrations.set(key, narration)
  }
}

const safe = (value: string) => value.replace(/[^A-Za-z0-9._-]+/g, '_').slice(0, 80)

const EXTENSIONS: Record<string, string> = {
  'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp',
  'audio/mpeg': 'mp3', 'audio/mp3': 'mp3', 'audio/wav': 'wav', 'audio/x-wav': 'wav', 'audio/ogg': 'ogg', 'audio/webm': 'webm',
}

async function download(api: ApiClient, url: string, dir: string, name: string, outDir: string): Promise<string | undefined> {
  try {
    const { bytes, contentType } = await api.download(url)
    const fromUrl = /\.([a-z0-9]{2,4})(?:\?|$)/i.exec(url)?.[1]
    const extension = EXTENSIONS[(contentType ?? '').split(';')[0].trim()] ?? fromUrl ?? 'bin'
    const file = join(dir, `${safe(name)}.${extension}`)
    writeFileSync(file, bytes)
    return relative(outDir, file).replaceAll('\\', '/')
  } catch {
    return undefined
  }
}

/** Generates the recipe's flow with the combo's text model and saves it; throws when it can't. */
async function generateFlow(
  ctx: RunContext, api: ApiClient, recipeId: number, recipe: RecipeInput, combo: Combo, record: RunRecord, artifactDir: string,
): Promise<{ flow: FlowDoc; processes: Process[] }> {
  const generation = await runGeneration(api, recipeId, recipe.text, combo.text!, ctx.timeouts.text)
  record.stages.text = generation.stage
  if (!generation.result || !generation.stage.jobId) throw new Error(`generation: ${generation.stage.error ?? 'no result'}`)
  writeFileSync(join(artifactDir, 'generation.json'), JSON.stringify(generation.result, null, 2))

  const { processes, problems } = await saveGeneratedProcesses(api, recipeId, generation.stage.jobId, generation.result, generation.stage)
  if (problems.length > 0) {
    generation.stage.status = 'partial'
    generation.stage.error = `converter problems: ${problems.join('; ')}`
    record.quality = assessQuality(record, [], [], problems)
    throw new Error(generation.stage.error)
  }
  const flow = toFlowDoc(recipe.id, recipe.text, generation.stage.resolvedModels[0] ?? combo.text!, processes,
    generation.stage.warnings, generation.stage.repairs)
  return { flow, processes }
}

/** Builds the recipe from an existing flow: no AI call. The text stage records what it was taken from. */
async function copyFlow(api: ApiClient, recipeId: number, flow: FlowDoc, from: string, record: RunRecord): Promise<Process[]> {
  const started = Date.now()
  const processes = await importFlow(api, recipeId, flow)
  record.stages.text = {
    status: 'ok', requestedModel: flow.textModel ?? undefined, resolvedModels: flow.textModel ? [flow.textModel] : [],
    usedFallback: false, clientMs: Date.now() - started, stageMs: {}, warnings: flow.warnings, repairs: flow.repairs,
    saveMs: Date.now() - started, reused: from,
  }
  return processes
}

export async function runOne(
  ctx: RunContext, recipe: RecipeInput, combo: Combo, repeat: number, share: GroupShare | null = null,
): Promise<RunRecord> {
  const api = ctx.user.fork()
  const started = Date.now()
  const record: RunRecord = {
    runId: ctx.runId, recipe: recipe.id, combo: combo.name, repeat, startedAt: new Date(started).toISOString(),
    totalMs: 0, status: 'failed', fallback: false, stages: {}, steps: [], apiStats: api.stats,
  }
  const artifactDir = join(ctx.outDir, 'artifacts', safe(recipe.id), safe(combo.name), String(repeat))
  mkdirSync(artifactDir, { recursive: true })
  const tag = `[${recipe.id} × ${combo.name} #${repeat}]`

  try {
    const created = await api.post<{ id: number }>('/api/v1/process-templates', {
      name: `bench ${ctx.runId} ${recipe.id} ${combo.name} #${repeat}`.slice(0, 120),
      description: 'Created by tools/ai-bench',
    })
    record.recipeId = created.id
    ctx.onRecipeCreated(created.id)

    // The flow: from the recipe's flow file, else the group's (generated once, copied into every other
    // run), else generated by this run. Images and narration are made from its saved steps.
    const here = `${combo.name} #${repeat}`
    const flowKey = `${recipe.id}\u0000${combo.flowText ?? ''}`
    const sharedFlow = recipe.flow ? undefined : share?.flow(flowKey)
    let processes: Process[]
    if (recipe.flow) {
      ctx.log(`${tag} recipe ${created.id}: building from flow file`)
      processes = await copyFlow(api, created.id, recipe.flow, `file ${recipe.source}`, record)
      record.flow = { source: 'file', file: recipe.source }
    } else if (sharedFlow) {
      let source: Awaited<typeof sharedFlow>
      try {
        source = await sharedFlow
      } catch (error) {
        throw new Error(`no flow: the group's generation failed (${(error as Error).message})`)
      }
      ctx.log(`${tag} recipe ${created.id}: copying the flow of ${source.from}`)
      processes = await copyFlow(api, created.id, source.flow, source.from, record)
      record.flow = { source: 'reused', from: source.from, file: source.file }
    } else {
      if (!combo.text) throw new Error('no flow: this combo has no "text" model and its group has not generated one')
      ctx.log(`${tag} recipe ${created.id}: generating process with ${combo.text}`)
      const generating = generateFlow(ctx, api, created.id, recipe, combo, record, artifactDir)
      const flowFile = join(artifactDir, 'flow.json')
      const relativeFlowFile = relative(ctx.outDir, flowFile).replaceAll('\\', '/')
      // Offered before it resolves, so runs of the group started meanwhile wait for it.
      share?.offerFlow(flowKey, generating.then(({ flow }) => ({ flow, from: here, file: relativeFlowFile })))
      const generated = await generating
      processes = generated.processes
      writeFileSync(flowFile, JSON.stringify(generated.flow, null, 2))
      if (share) {
        const flowsDir = join(ctx.outDir, 'flows')
        mkdirSync(flowsDir, { recursive: true })
        writeFileSync(join(flowsDir, `${safe(recipe.id)}.${safe(generated.flow.textModel ?? 'flow')}.flow.json`),
          JSON.stringify(generated.flow, null, 2))
      }
      record.flow = { source: 'generated', file: relativeFlowFile }
      ctx.log(`${tag} generated in ${record.stages.text?.clientMs}ms (${record.stages.text?.resolvedModels.join(',')})`)
    }
    writeFileSync(join(artifactDir, 'processes.json'), JSON.stringify(processes, null, 2))
    record.steps = summarizeSteps(processes)
    ctx.log(`${tag} ${record.steps.length} nodes saved`)

    // Image then speech, one after the other, so each stage's latency isn't skewed by the other.
    if (ctx.stages.includes('image') && combo.image) {
      ctx.log(`${tag} visualizing with ${combo.image}`)
      const image = await runVisualization(api, created.id, processes, combo.image, ctx.timeouts.image)
      record.stages.image = image
      if (ctx.downloadAssets) {
        await Promise.all(image.steps.filter((step) => step.imageUrl).map(async (step) => {
          step.file = await download(api, step.imageUrl!, artifactDir, `img-${step.stepId}`, ctx.outDir)
        }))
      }
      ctx.log(`${tag} images ${image.succeeded}/${image.totalSteps} in ${image.clientMs}ms`)
    }
    if (ctx.stages.includes('speech') && combo.speech) {
      const narrate = async (): Promise<SpeechStage> => {
        ctx.log(`${tag} narrating with ${combo.speech}`)
        const speech = await runNarration(api, created.id, processes, combo.speech!, ctx.timeouts.speech)
        if (ctx.downloadAssets) {
          await Promise.all(speech.steps.filter((step) => step.audioUrl).map(async (step) => {
            step.file = await download(api, step.audioUrl!, artifactDir, `audio-${step.stepId}`, ctx.outDir)
          }))
        }
        ctx.log(`${tag} narration ${speech.ready}/${speech.narratable} in ${speech.clientMs}ms`)
        return speech
      }
      // One narration per flow and speech model in a group: the steps and their ids are the same in
      // every copy of the flow, so the first run's audio stands for all of them.
      const narrationKey = `${recipe.flow ? `file ${recipe.source}` : flowKey}\u0000${combo.speech}`
      const earlier = await share?.narration(narrationKey)
      if (earlier) {
        record.stages.speech = { ...earlier.stage, clientMs: 0, reused: earlier.from }
        ctx.log(`${tag} narration reused from ${earlier.from}`)
      } else if (share && !share.narration(narrationKey)) {
        const narrating = narrate()
        // A narration that produced no audio is not offered: the next run then narrates for itself.
        share.offerNarration(narrationKey, narrating.then((stage) => (stage.ready > 0 ? { stage, from: here } : null), () => null))
        record.stages.speech = await narrating
      } else {
        record.stages.speech = await narrate()
      }
    }

    record.quality = assessQuality(record, processes as Process[], record.steps, [])
    const stageList = Object.values(record.stages)
    record.status = stageList.every((stage) => stage.status === 'ok' || stage.status === 'skipped') ? 'ok' : 'failed'
    if (record.status === 'failed') {
      record.error = stageList.filter((stage) => stage.error).map((stage) => stage.error).join(' | ') || 'a stage did not fully succeed'
    }
  } catch (error) {
    record.error = (error as Error).message
    ctx.log(`${tag} FAILED: ${record.error}`)
  }

  record.fallback = Object.values(record.stages).some((stage) => stage.usedFallback)
  if (ctx.admin && record.recipeId) {
    try {
      record.server = await collectServerMetrics(ctx.admin, record.recipeId)
    } catch (error) {
      ctx.log(`${tag} could not read server analytics: ${(error as Error).message}`)
    }
  }
  record.totalMs = Date.now() - started
  return record
}
