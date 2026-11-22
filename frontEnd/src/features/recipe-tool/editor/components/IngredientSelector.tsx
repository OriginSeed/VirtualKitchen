import { useState } from 'react'
import '../../../../styles/flow-ui.css'
import '../../styles/RecipePropertiesPanel.css'
import SearchableSelect, { type SearchableSelectOption } from '../../../../shared/components/SearchableSelect'
import {
  CUSTOM_INGREDIENT_ID,
  getIngredientCategoryLabel,
  getIngredientCategoryOrder,
  getIngredientDefaultUnit,
  getIngredientsByCategory,
  isIngredientId,
  useIngredientCatalog,
  type IngredientId,
} from '../../../../shared/catalog/ingredientCatalog'
import { CUSTOM_PREPARATION_STYLE_ID, DEFAULT_PREPARATION_STYLE_ID } from '../../../../shared/catalog/preparationStyleCatalog'
import { getAllowedPreparationStyles, isStepFieldEnabled } from '../../../../shared/catalog/actionSchemaCatalog'
import { CUSTOM_UNIT_ID, isQuantifiableUnit, type UnitId } from '../../../../shared/catalog/unitCatalog'
import type { StepActionId } from '../../../../shared/catalog/actionCatalog'
import type { ActionOnIngredient } from '../../model/recipeStepData'
import { PreparationStyleSelect, UnitSelect } from './StepFieldInputs'
import { useDraft } from '../../../../shared/drafts/useDraft'

// The full global catalog (the database's ingredients), grouped by category, searchable by
// aliases — never restricted to what the Kitchen inventory currently holds.
// Built once per catalog version (the catalog only changes when it's reloaded).
let cachedOptions: { version: number; options: SearchableSelectOption[] } | null = null

const getIngredientOptions = (catalogVersion: number): SearchableSelectOption[] => {
  if (cachedOptions?.version !== catalogVersion) {
    const byCategory = getIngredientsByCategory()
    const options = getIngredientCategoryOrder().flatMap((category) =>
      (byCategory[category] ?? []).map((item) => ({
        value: item.id,
        label: item.name,
        icon: item.icon,
        category: getIngredientCategoryLabel(category),
        keywords: item.aliases,
      }))
    )
    cachedOptions = { version: catalogVersion, options }
  }
  return cachedOptions.options
}

type IngredientSelectorProps = {
  /** Ingredient ids already on this step, excluded from the picker so the same ingredient isn't added twice (custom ingredients excepted). */
  excludeIngredientIds: IngredientId[]
  /** The step's current action — decides which of quantity/unit/preparation style apply at all (catalog action schema). */
  action: StepActionId | ''
  onAdd: (ingredient: ActionOnIngredient) => void
  /** Where the half-filled row is kept until the ingredient is added (see shared/drafts); null keeps nothing. */
  draftKey?: string | null
  /**
   * Edit mode: the existing Action On entry to start from. `onAdd` then receives the edited entry
   * (Save), and `onCancel` closes the editor without changing anything. Nothing is kept as a draft.
   */
  editing?: ActionOnIngredient
  onCancel?: () => void
}

type IngredientDraft = {
  ingredientIdValue: string
  customIngredientName: string
  quantity: string
  unit: UnitId | ''
  /** The unit's own name when `unit` is "custom" (absent in drafts saved before custom units). */
  customUnit?: string
  notes: string
  preparationStyleId: string
  customPreparationStyle: string
}

const EMPTY_INGREDIENT_DRAFT: IngredientDraft = {
  ingredientIdValue: '',
  customIngredientName: '',
  quantity: '1',
  unit: 'piece',
  notes: '',
  preparationStyleId: '',
  customPreparationStyle: '',
}

/** The form filled in from an existing Action On entry (edit mode). */
const draftFromEntry = (entry: ActionOnIngredient): IngredientDraft => ({
  ingredientIdValue: entry.ingredientId,
  customIngredientName: entry.customIngredientName ?? '',
  quantity: entry.quantity != null ? String(entry.quantity) : '',
  unit: entry.unit,
  customUnit: entry.customUnit ?? '',
  notes: entry.notes ?? '',
  preparationStyleId: entry.preparationStyleId ?? '',
  customPreparationStyle: entry.customPreparationStyle ?? '',
})

/**
 * Small "add an ingredient" row for a step's Action On: pick from the app's shared ingredient
 * catalog (catalog/ingredientCatalog.ts, the same list the AI generator uses) or name a custom
 * ingredient, then set this step's own quantity/unit/preparation for it. With `editing`, the same
 * form edits an existing entry instead — same pickers, same validation.
 */
export default function IngredientSelector({ excludeIngredientIds, action, onAdd, draftKey = null, editing, onCancel }: IngredientSelectorProps) {
  const quantityRelevant = isStepFieldEnabled(action, 'quantity')

  // What's typed here is kept as a draft until the ingredient is added, so selecting another step
  // or leaving the recipe and coming back doesn't lose it. Editing an entry keeps no draft.
  const draft = useDraft<IngredientDraft>(editing ? null : draftKey, editing ? draftFromEntry(editing) : EMPTY_INGREDIENT_DRAFT)
  const { ingredientIdValue, customIngredientName, quantity, unit, notes, preparationStyleId, customPreparationStyle } = draft.value
  const customUnit = draft.value.customUnit ?? ''
  const field = <K extends keyof IngredientDraft>(key: K) => (value: IngredientDraft[K]) =>
    draft.setValue((current) => ({ ...current, [key]: value }))
  const setCustomIngredientName = field('customIngredientName')
  const setQuantity = field('quantity')
  const setUnit = field('unit')
  const setNotes = field('notes')
  const setPreparationStyleId = field('preparationStyleId')
  const setCustomPreparationStyle = field('customPreparationStyle')
  const setCustomUnit = field('customUnit')
  const [error, setError] = useState<string | null>(null)

  const options = getIngredientOptions(useIngredientCatalog()).filter((option) => option.value === CUSTOM_INGREDIENT_ID || !excludeIngredientIds.includes(option.value))
  const selectedIngredientId: IngredientId | '' = isIngredientId(ingredientIdValue) ? ingredientIdValue : ''
  const unitQuantifiable = isQuantifiableUnit(unit)

  // Pre-fills the unit from the ingredient's own catalog default and the preparation style to
  // `medium` only when the current action allows it for this ingredient — deliberately applied
  // here, at the moment an ingredient is actually picked, rather than as each field's useState
  // initial value: this form doesn't remount when the step's action changes.
  const handleSelectIngredient = (value: string) => {
    const allowed = getAllowedPreparationStyles(action, value)
    draft.setValue((current) => ({
      ...current,
      ingredientIdValue: value,
      unit: getIngredientDefaultUnit(value),
      preparationStyleId: allowed.includes(DEFAULT_PREPARATION_STYLE_ID) ? DEFAULT_PREPARATION_STYLE_ID : '',
      customPreparationStyle: '',
    }))
  }

  const reset = () => {
    draft.clear()
    setError(null)
  }

  const handleAdd = () => {
    if (!selectedIngredientId) {
      setError('Choose an ingredient')
      return
    }
    if (selectedIngredientId === CUSTOM_INGREDIENT_ID && !customIngredientName.trim()) {
      setError('Enter a name for the custom ingredient')
      return
    }
    if (quantityRelevant && unit === CUSTOM_UNIT_ID && !customUnit.trim()) {
      setError('Enter a name for the custom unit (e.g. knob)')
      return
    }

    let quantityNumber: number | null = null
    if (quantityRelevant && unitQuantifiable && quantity.trim()) {
      quantityNumber = Number(quantity)
      if (!Number.isFinite(quantityNumber) || quantityNumber < 0) {
        setError('Enter a valid quantity')
        return
      }
    }

    const preparationRelevant = getAllowedPreparationStyles(action, selectedIngredientId).length > 0
    onAdd({
      ingredientId: selectedIngredientId,
      customIngredientName: selectedIngredientId === CUSTOM_INGREDIENT_ID ? customIngredientName.trim() : undefined,
      quantity: quantityNumber,
      unit: quantityRelevant ? unit : '',
      customUnit: quantityRelevant && unit === CUSTOM_UNIT_ID ? customUnit.trim() : undefined,
      notes: notes.trim() || undefined,
      preparationStyleId: preparationRelevant ? preparationStyleId : '',
      customPreparationStyle: preparationRelevant && preparationStyleId === CUSTOM_PREPARATION_STYLE_ID ? customPreparationStyle.trim() : undefined,
    })
    // The edit form closes once saved (the caller unmounts it), so there's nothing to reset.
    if (!editing) reset()
  }

  return (
    <div
      className="flex flex-col gap-2 rounded-xl border p-3"
      style={{ border: `1px solid ${editing ? 'var(--flow-accent)' : 'var(--flow-border)'}`, background: editing ? 'var(--flow-surface)' : 'var(--flow-surface-muted)' }}
    >
      <div className="flex flex-wrap items-end gap-2">
        <div style={{ minWidth: 'min(200px, 100%)', flex: 1 }}>
          <label className="flow-properties-label">Ingredient</label>
          <SearchableSelect
            value={ingredientIdValue}
            onChange={handleSelectIngredient}
            options={options}
            placeholder="Search ingredients (name or alias)"
          />
        </div>
        {quantityRelevant && (
          <>
            <div style={{ width: 80 }}>
              <label className="flow-properties-label">Quantity</label>
              <input
                className="flow-properties-input"
                value={unitQuantifiable ? quantity : ''}
                disabled={!unitQuantifiable}
                onChange={(e) => setQuantity(e.target.value)}
                placeholder={unitQuantifiable ? '2' : '—'}
              />
            </div>
            <div style={{ width: 120 }}>
              <label className="flow-properties-label">Unit</label>
              <UnitSelect ingredientId={selectedIngredientId} value={unit} onChange={setUnit} />
            </div>
          </>
        )}
      </div>

      {quantityRelevant && unit === CUSTOM_UNIT_ID && (
        <input
          className="flow-properties-input"
          value={customUnit}
          onChange={(e) => setCustomUnit(e.target.value)}
          placeholder="Custom unit, e.g. knob or thumb-sized piece"
          aria-label="Custom unit name"
        />
      )}

      {selectedIngredientId === CUSTOM_INGREDIENT_ID && (
        <input
          className="flow-properties-input"
          value={customIngredientName}
          onChange={(e) => setCustomIngredientName(e.target.value)}
          placeholder="Custom ingredient name"
        />
      )}

      <div className="flex flex-wrap gap-2">
        <PreparationStyleSelect
          action={action}
          ingredientId={selectedIngredientId}
          value={preparationStyleId}
          customValue={customPreparationStyle}
          onChange={(styleId, custom) => {
            setPreparationStyleId(styleId)
            setCustomPreparationStyle(custom)
          }}
        />
        <input
          className="flow-properties-input"
          style={{ flex: 1, minWidth: 160 }}
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          placeholder="Notes — optional"
        />
      </div>

      {error && <div style={{ fontSize: 12, color: '#dc2626', fontWeight: 600 }}>{error}</div>}

      <div className="flex gap-2">
        <button
          type="button"
          onClick={handleAdd}
          style={{ padding: '6px 14px', borderRadius: 8, border: '1px solid var(--flow-accent)', background: 'var(--flow-accent)', color: 'white', fontSize: 12, fontWeight: 700, cursor: 'pointer' }}
        >
          {editing ? 'Save' : '+ Add Ingredient'}
        </button>
        {editing && onCancel && (
          <button
            type="button"
            onClick={onCancel}
            style={{ padding: '6px 14px', borderRadius: 8, border: '1px solid var(--flow-border)', background: 'var(--flow-surface)', color: 'var(--flow-text-muted)', fontSize: 12, fontWeight: 700, cursor: 'pointer' }}
          >
            Cancel
          </button>
        )}
      </div>
    </div>
  )
}
