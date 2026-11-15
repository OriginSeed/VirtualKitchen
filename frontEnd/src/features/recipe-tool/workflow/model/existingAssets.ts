/**
 * "Keep or replace": what an AI run does with images and narration the steps already have. Keeping them is the
 * default (only what is missing is generated); replacing them generates everything again. Pure, so it is unit-tested.
 */

export type ExistingAssetCounts = { images?: number; narrations?: number }

/** Whether there is anything a run could keep or replace (the choice is only shown then). */
export const hasExistingAssets = (counts: ExistingAssetCounts): boolean =>
  (counts.images ?? 0) > 0 || (counts.narrations ?? 0) > 0

/** e.g. "3 steps already have an image and 2 have narration." Empty when nothing exists. */
export const describeExistingAssets = (counts: ExistingAssetCounts): string => {
  const steps = (count: number) => `${count} step${count === 1 ? '' : 's'}`
  const has = (count: number) => (count === 1 ? 'has' : 'have')
  const images = counts.images ?? 0
  const narrations = counts.narrations ?? 0
  if (images > 0 && narrations > 0) {
    return `${steps(images)} already ${has(images)} an image and ${steps(narrations)} ${has(narrations)} narration.`
  }
  if (images > 0) return `${steps(images)} already ${has(images)} an image.`
  if (narrations > 0) return `${steps(narrations)} already ${has(narrations)} narration.`
  return ''
}

/** What the existing images and narration become: the noun used in the choice's labels. */
export const existingAssetNoun = (counts: ExistingAssetCounts): string => {
  const images = (counts.images ?? 0) > 0
  const narrations = (counts.narrations ?? 0) > 0
  if (images && narrations) return 'images and narration'
  return narrations ? 'narration' : 'images'
}
