import { useApiData } from '../../../../shared/hooks/useApiData'
import { GroupedBarList } from '../../ai-analytics/components/charts'
import { LoadState, Stat } from '../../ai-analytics/components/common'
import { formatCost, formatMs, formatPercent, withSamples } from '../../ai-analytics/model/analyticsFormat'
import { AiEvaluationApi } from '../api/aiEvaluationApi'
import type { ModelComparison, QueryParams, Rubric } from '../api/aiEvaluationApi'
import { costPerPoint, formatRating, formatScore } from '../model/evaluationFormat'

/**
 * Models side by side on the three separate axes. Quality only counts scored sets; cost and time count every set
 * of the model in the period. Each figure says how many samples it rests on.
 */
export default function EvaluationComparePage({ params, rubric }: { params: QueryParams; rubric: Rubric }) {
  const comparison = useApiData(`compare?${JSON.stringify(params)}`, () => AiEvaluationApi.compare(params))
  const models = comparison.data?.models ?? []
  const tagLabel = (id: string) => rubric.defectTags.find((t) => t.id === id)?.label ?? id

  return (
    <section className="aia-page" aria-label="Model comparison">
      <LoadState loading={comparison.loading && !comparison.data} error={comparison.error} onRetry={comparison.reload} />
      {comparison.data?.truncated && <p className="aia-notes">Only the most recent records were read; narrow the period.</p>}
      {comparison.data && models.length === 0 && (
        <p className="aia-empty">No step images from any model in this period.</p>
      )}

      {models.length > 0 && (
        <>
          <div className="aie-model-cards">
            {models.map((model) => (
              <article key={model.modelKey} className="aia-card" aria-label={model.modelKey}>
                <h3>
                  {model.modelKey}
                  {model.tier && <span className="aia-tag">{model.tier === 'OPEN_SOURCE' ? 'open source' : 'paid'}</span>}
                </h3>
                {model.lowSample && (
                  <p className="aia-provenance aia-provenance--warn">
                    Only {model.scoredSets} scored set{model.scoredSets === 1 ? '' : 's'}; score at least {rubric.lowSampleSets} before trusting quality figures.
                  </p>
                )}
                <h4 className="aie-axis">Quality</h4>
                <div className="aia-stats">
                  <Stat label="Recipe score" value={formatScore(model.recipeScore.value)} note={`${model.scoredSets} scored sets`} />
                  <Stat label="Accepted" value={formatPercent(model.acceptanceRate)} note={`of ${model.verdictSets} with a verdict`} />
                  <Stat label="Hard fails" value={formatPercent(model.hardFailRate)} />
                  <Stat label="Images with defects" value={formatPercent(model.defectRate)} note={`${model.scoredImages} scored images`} />
                </div>
                <h4 className="aie-axis">Cost</h4>
                <div className="aia-stats">
                  <Stat label="Per image" value={formatCost(model.costPerImage.value, model.currency)} note={`${model.images} images`} />
                  <Stat label="Per recipe set" value={formatCost(model.costPerSet.value, model.currency)} note={`${model.costPerSet.samples} sets`} />
                  <Stat label="Per accepted set" value={formatCost(model.costPerAcceptedSet, model.currency)}
                    note="rejected sets paid for too" />
                  <Stat label="Per score point" value={formatCost(costPerPoint(model.costPerSet.value, model.recipeScore.value), model.currency)}
                    note="lower is better" />
                </div>
                <h4 className="aie-axis">Time</h4>
                <div className="aia-stats">
                  <Stat label="Image p50 / p95" value={`${formatMs(model.p50ImageMs.value)} / ${formatMs(model.p95ImageMs.value)}`} />
                  <Stat label="Recipe set p50 / p95" value={`${formatMs(model.p50SetMs.value)} / ${formatMs(model.p95SetMs.value)}`} />
                  <Stat label="Queue wait (avg)" value={formatMs(model.avgQueueWaitMs.value)} />
                  <Stat label="Failed requests" value={String(model.failedRequests)} />
                </div>
                {model.topDefects.length > 0 && (
                  <p className="aia-small aia-muted">
                    Most common defects: {model.topDefects.map((d) => `${tagLabel(d.tag)} (${d.count})`).join(', ')}
                  </p>
                )}
              </article>
            ))}
          </div>

          <div className="aia-grid-2">
            <div className="aia-card">
              <h3>Mean rating by dimension (1–5)</h3>
              <GroupedBarList ariaLabel="Mean rating per quality dimension and model" max={5}
                emptyText="No scored images yet."
                format={(v) => formatRating(v)}
                groups={rubric.dimensions.map((dimension) => ({
                  label: `${dimension.label} · weight ${dimension.weight}`,
                  data: models.filter((m) => m.scoredImages > 0).map((m) => ({
                    label: m.modelKey,
                    value: m.dimensions[dimension.id]?.value ?? null,
                    detail: `${m.dimensions[dimension.id]?.samples ?? 0} ratings`,
                  })),
                }))} />
            </div>
            <div className="aia-card">
              <h3>All models</h3>
              <ComparisonTable models={models} />
            </div>
          </div>
        </>
      )}
    </section>
  )
}

function ComparisonTable({ models }: { models: ModelComparison[] }) {
  return (
    <div className="aia-table-scroll">
      <table className="aia-table aia-table--compact">
        <thead>
          <tr>
            <th>Model</th>
            <th className="aia-num">Score</th>
            <th className="aia-num">Accepted</th>
            <th className="aia-num">Cost / image</th>
            <th className="aia-num">Cost / accepted set</th>
            <th className="aia-num">p95 image</th>
            <th className="aia-num">Sets</th>
          </tr>
        </thead>
        <tbody>
          {models.map((m) => (
            <tr key={m.modelKey}>
              <td>{m.modelKey}{m.lowSample && <span className="aia-tag aia-tag--warn" title="Few scored sets">low sample</span>}</td>
              <td className="aia-num">{withSamples(formatScore(m.recipeScore.value), m.recipeScore.samples)}</td>
              <td className="aia-num">{formatPercent(m.acceptanceRate)}</td>
              <td className="aia-num">{formatCost(m.costPerImage.value, m.currency)}</td>
              <td className="aia-num">{formatCost(m.costPerAcceptedSet, m.currency)}</td>
              <td className="aia-num">{formatMs(m.p95ImageMs.value)}</td>
              <td className="aia-num">{m.sets}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
