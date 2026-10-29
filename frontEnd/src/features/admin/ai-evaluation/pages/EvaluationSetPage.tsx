import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useApiData } from '../../../../shared/hooks/useApiData'
import { errorMessage } from '../../../../shared/errors'
import { useNotifications } from '../../../../shared/components/notifications/notificationContext'
import { KeyValue, LoadState } from '../../ai-analytics/components/common'
import { formatCost, formatDateTime, formatMs } from '../../ai-analytics/model/analyticsFormat'
import { AiEvaluationApi } from '../api/aiEvaluationApi'
import type { ImageSaveResult, QueryParams, Rubric, SetDetail, SetEvaluation } from '../api/aiEvaluationApi'
import ImageScoreCard from '../components/ImageScoreCard'
import VerdictPanel from '../components/VerdictPanel'
import { mediaUrl } from '../components/media'
import { STATUS_LABELS, formatScore, setRoute } from '../model/evaluationFormat'
import { VerdictLabel } from './EvaluationQueuePage'

interface Props {
  setKey: string
  rubric: Rubric
  params: QueryParams
}

/** One recipe's images side by side: the scene anchor for reference, a rating card per step, and the verdict. */
export default function EvaluationSetPage({ setKey, rubric, params }: Props) {
  const navigate = useNavigate()
  const { notifyInfo, notifyError } = useNotifications()
  const detail = useApiData(`set:${setKey}`, () => AiEvaluationApi.set(setKey))
  const [enlarged, setEnlarged] = useState<{ url: string; caption: string } | null>(null)
  const [findingNext, setFindingNext] = useState(false)

  const applySet = (evaluation: SetEvaluation) => detail.mutate((data) => withSet(data, evaluation))
  const applyImage = (result: ImageSaveResult) => detail.mutate((data) => withSet({
    ...data,
    images: data.images.map((image) => image.requestId === result.image.id ? { ...image, evaluation: result.image } : image),
  }, result.set))

  const nextUnscored = async () => {
    setFindingNext(true)
    try {
      const page = await AiEvaluationApi.sets({ ...params, status: 'UNSCORED', sort: 'unscored', page: 0, size: 2 })
      const next = page.page.items.find((set) => set.setKey !== setKey)
      if (next) navigate(setRoute(next.setKey))
      else notifyInfo('No unscored sets left for these filters.')
    } catch (error) {
      notifyError(errorMessage(error, 'Could not find the next set'))
    } finally {
      setFindingNext(false)
    }
  }

  const data = detail.data
  const summary = data?.summary
  return (
    <section className="aia-page" aria-label="Image set">
      <div className="aia-toolbar">
        <Link to="/kitchen/admin/ai-evaluation" className="aia-link">← All image sets</Link>
        <button type="button" className="aia-button" onClick={nextUnscored} disabled={findingNext}>
          {findingNext ? 'Finding…' : 'Next unscored set →'}
        </button>
      </div>
      <LoadState loading={detail.loading && !data} error={detail.error} onRetry={detail.reload} />
      {data && summary && (
        <>
          <div className="aia-card">
            <h3>{summary.recipeName ?? 'Unknown recipe'}</h3>
            <dl className="aia-kvs">
              <KeyValue label="Model" value={summary.mixedModels ? `${summary.modelKey} (mixed: ${summary.models.join(', ')})` : summary.modelKey} />
              <KeyValue label="Generated" value={formatDateTime(summary.startedAt)} />
              <KeyValue label="Images" value={`${summary.imageCount}${summary.failedRequests ? ` · ${summary.failedRequests} failed` : ''}`} />
              <KeyValue label="Set cost" value={formatCost(summary.totalCost, summary.currency) + (summary.costComplete ? '' : ' (partial)')} />
              <KeyValue label="Set time" value={formatMs(summary.spanMs)} />
              <KeyValue label="Status" value={`${STATUS_LABELS[summary.status]} · ${summary.scoredImages}/${summary.imageCount} images`} />
              <KeyValue label="Recipe score" value={formatScore(data.evaluation?.recipeScore)}
                title={`${rubric.meanWeight} × mean image score + ${rubric.worstWeight} × worst image score`} />
              <KeyValue label="Verdict" value={<VerdictLabel set={summary} />} />
            </dl>
          </div>

          <div className="aie-set-top">
            {data.sceneImageUrl && (
              <figure className="aia-card aie-scene">
                <h3>Scene anchor</h3>
                <button type="button" className="aie-image-button"
                  onClick={() => setEnlarged({ url: mediaUrl(data.sceneImageUrl as string), caption: 'Scene anchor' })}>
                  <img src={mediaUrl(data.sceneImageUrl)} alt="Scene anchor for the recipe" loading="lazy" />
                </button>
                <figcaption className="aia-muted aia-small">Every step image is drawn against this scene; judge consistency against it.</figcaption>
              </figure>
            )}
            <VerdictPanel setKey={setKey} evaluation={data.evaluation} rubric={rubric} onSaved={applySet} />
          </div>

          {data.images.length === 0
            ? <p className="aia-empty">No step image succeeded in this run.</p>
            : (
              <div className="aie-image-grid">
                {data.images.map((image) => (
                  <ImageScoreCard key={image.requestId} setKey={setKey} image={image} rubric={rubric}
                    onSaved={applyImage} onEnlarge={(url, caption) => setEnlarged({ url, caption })} />
                ))}
              </div>
            )}
        </>
      )}
      {enlarged && <ImageModal {...enlarged} onClose={() => setEnlarged(null)} />}
    </section>
  )
}

/** The set's evaluation and the summary fields derived from it, after a save. */
function withSet(data: SetDetail, evaluation: SetEvaluation): SetDetail {
  const scored = evaluation.scoredImages
  const status = evaluation.verdict && scored >= data.summary.imageCount ? 'SCORED'
    : evaluation.verdict || scored > 0 ? 'PARTIAL' : 'UNSCORED'
  return {
    ...data,
    evaluation,
    summary: {
      ...data.summary,
      status,
      scoredImages: scored,
      verdict: evaluation.verdict,
      recipeScore: evaluation.recipeScore,
      accepted: evaluation.verdict ? evaluation.accepted : null,
      hardFail: evaluation.hardFail,
    },
  }
}

function ImageModal({ url, caption, onClose }: { url: string; caption: string; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="aia-modal-backdrop" onClick={onClose}>
      <div className="aia-modal" role="dialog" aria-modal="true" aria-label={caption} onClick={(e) => e.stopPropagation()}>
        <div className="aia-modal-header">
          <h2>{caption}</h2>
          <button type="button" className="aia-button" onClick={onClose}>Close</button>
        </div>
        <figure className="aia-media aie-media-full"><img src={url} alt={caption} /></figure>
      </div>
    </div>
  )
}
