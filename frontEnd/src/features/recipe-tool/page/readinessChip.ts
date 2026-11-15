/**
 * How the Recipe Tool header shows readiness: green when the recipe can be cooked, ordered and shared as a
 * reproducible recipe; red while something blocks that; amber when only warnings remain. Pure, so it is tested.
 */

export type ReadinessChipTone = 'ready' | 'blocked' | 'warning'

export type ReadinessChip = {
  tone: ReadinessChipTone
  /** Short text on the chip. */
  label: string
  /** Longer explanation (tooltip / accessible name). */
  title: string
}

export const readinessChip = (counts: { blockers: number; warnings: number }): ReadinessChip => {
  const { blockers, warnings } = counts
  if (blockers > 0) {
    return {
      tone: 'blocked',
      label: `${blockers} to fix`,
      title: `${blockers} ${blockers === 1 ? 'issue blocks' : 'issues block'} cooking, ordering and others following this recipe`
        + (warnings > 0 ? `, plus ${warnings} ${warnings === 1 ? 'warning' : 'warnings'}` : '')
        + '. Click to show the first one.',
    }
  }
  if (warnings > 0) {
    return {
      tone: 'warning',
      label: `Ready · ${warnings} ${warnings === 1 ? 'warning' : 'warnings'}`,
      title: `Ready to cook. ${warnings} ${warnings === 1 ? 'warning is' : 'warnings are'} worth a look but block nothing. Click to show.`,
    }
  }
  return { tone: 'ready', label: 'Ready to cook', title: 'Everything checks out: this recipe can be cooked, ordered and followed by others.' }
}
