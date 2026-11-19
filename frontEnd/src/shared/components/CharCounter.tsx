import { charCount, nearLimit } from '../inputLimits'

/** "7,950 / 8,000" under a limited text box, shown only near the limit; turns to the warning colour at it. */
export default function CharCounter({ length, max }: { length: number; max: number }) {
  if (!nearLimit(length, max)) return null
  const atLimit = length >= max
  return (
    <div
      aria-live="polite"
      style={{
        marginTop: 4,
        fontSize: 11,
        textAlign: 'right',
        color: atLimit ? 'var(--flow-warning, #b45309)' : 'var(--flow-text-subtle, #6b7280)',
      }}
    >
      {charCount(length, max)}{atLimit ? ' — limit reached' : ''}
    </div>
  )
}
