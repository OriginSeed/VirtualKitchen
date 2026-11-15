import { useId } from 'react'
import { describeExistingAssets, existingAssetNoun, hasExistingAssets, type ExistingAssetCounts } from '../model/existingAssets'
import '../../styles/modals.css'

/**
 * Asks what an AI run does with images and narration the steps already have: keep them (default — only what is
 * missing is generated) or regenerate and replace them. Renders nothing when nothing exists yet.
 */
export default function ExistingAssetsChoice({ counts, overwrite, onChange, disabled }: {
  counts: ExistingAssetCounts
  overwrite: boolean
  onChange: (overwrite: boolean) => void
  disabled?: boolean
}) {
  const name = useId()
  if (!hasExistingAssets(counts)) return null
  const noun = existingAssetNoun(counts)
  return (
    <fieldset className="recipe-ai-existing" disabled={disabled}>
      <legend>Existing {noun}</legend>
      <div className="recipe-ai-existing-summary">{describeExistingAssets(counts)}</div>
      <label className={`recipe-ai-existing-option${!overwrite ? ' is-checked' : ''}`}>
        <input type="radio" name={name} checked={!overwrite} onChange={() => onChange(false)} />
        <span>
          <strong>Skip them</strong> — keep the existing {noun}; only steps without one are generated.
        </span>
      </label>
      <label className={`recipe-ai-existing-option${overwrite ? ' is-checked' : ''}`}>
        <input type="radio" name={name} checked={overwrite} onChange={() => onChange(true)} />
        <span>
          <strong>Regenerate and replace</strong> — create new {noun} for every step. Uses more credits.
        </span>
      </label>
    </fieldset>
  )
}
