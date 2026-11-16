/**
 * Which steps of the Recipe Process page are shown together under ONE image. A storyboard draws one panel for
 * several steps and puts the panel's image on the last of them; the page then shows that image once, with the
 * panel's steps as points, instead of a picture on one step and nothing on the others.
 *
 * Pure (no imports), so it is unit-tested. The grouping is derived, never stored: it only holds while the panel's
 * image is still the image on its last step, so a step whose image was replaced or a storyboard that no longer
 * matches the recipe simply forms no group.
 */

/** What grouping needs of a timeline item (a step or a check). */
export type GroupableItem = {
  kind: 'step' | 'check'
  key: string
  /** Steps only: the recipe node behind the item. */
  nodeId?: string
  /** Steps only: the image the step shows, if any. */
  imageUrl?: string
}

/** What grouping needs of a storyboard panel. */
export type StoryboardPanelRef = {
  panelId: string
  description: string
  imageUrl?: string | null
  sourceStepIds: string[]
}

/** Several consecutive items shown under one image; the last step is the one that carries the image. */
export type StoryboardImageGroup = {
  key: string
  /** The panel's short title ("Marinate chicken"). */
  title: string
  imageUrl: string
  /** Item keys in page order; at least two steps. */
  itemKeys: string[]
}

/**
 * The groups among `items` (one process's items in page order, or one preparation flow's). For each panel whose
 * image sits on one of its steps, the group is that step plus the panel's steps directly before it that have no
 * image of their own. `includeChecks` lets a check between two of those steps stay inside the group (preparation
 * flows list checks as plain points); without it a check ends the group, so it keeps its own card.
 * A panel that covers a single step forms no group: that step is an ordinary step with its image.
 */
export const groupItemsByStoryboard = (
  items: GroupableItem[],
  panels: StoryboardPanelRef[],
  options: { includeChecks?: boolean } = {},
): StoryboardImageGroup[] => {
  const taken = new Set<string>()
  const groups: StoryboardImageGroup[] = []
  for (const panel of panels) {
    if (!panel.imageUrl) continue
    const sources = new Set(panel.sourceStepIds)
    let anchor = -1
    items.forEach((item, index) => {
      if (item.kind === 'step' && item.nodeId != null && sources.has(item.nodeId) && item.imageUrl === panel.imageUrl
        && !taken.has(item.key)) anchor = index
    })
    if (anchor < 0) continue

    const before: string[] = []
    let pendingChecks: string[] = []
    for (let index = anchor - 1; index >= 0; index -= 1) {
      const item = items[index]
      if (taken.has(item.key)) break
      if (item.kind === 'check') {
        if (!options.includeChecks) break
        pendingChecks.unshift(item.key)
        continue
      }
      if (item.nodeId == null || !sources.has(item.nodeId) || item.imageUrl) break
      before.unshift(item.key, ...pendingChecks)
      pendingChecks = []
    }
    if (before.length === 0) continue

    const itemKeys = [...before, items[anchor].key]
    itemKeys.forEach((key) => taken.add(key))
    groups.push({ key: `group:${items[anchor].key}`, title: panel.description.trim(), imageUrl: panel.imageUrl, itemKeys })
  }
  const position = new Map(items.map((item, index) => [item.key, index]))
  return groups.sort((a, b) => (position.get(a.itemKeys[0]) ?? 0) - (position.get(b.itemKeys[0]) ?? 0))
}

/** A stretch of items shown together: a group under its image, or a single item on its own. */
export type ItemSegment<T> = { key: string; group?: StoryboardImageGroup; items: T[] }

/** `items` cut into segments in page order: each group once (where its first item is), every other item alone. */
export const segmentItems = <T extends { key: string }>(items: T[], groups: StoryboardImageGroup[]): ItemSegment<T>[] => {
  const groupOf = new Map<string, StoryboardImageGroup>()
  groups.forEach((group) => group.itemKeys.forEach((key) => groupOf.set(key, group)))
  const byKey = new Map(items.map((item) => [item.key, item]))
  const segments: ItemSegment<T>[] = []
  const emitted = new Set<string>()
  for (const item of items) {
    const group = groupOf.get(item.key)
    if (!group) {
      segments.push({ key: item.key, items: [item] })
    } else if (!emitted.has(group.key)) {
      emitted.add(group.key)
      segments.push({ key: group.key, group, items: group.itemKeys.flatMap((key) => byKey.get(key) ?? []) })
    }
  }
  return segments
}

/** The image a step is shown under when it has none of its own: its group's. */
export const groupImageByItemKey = (groups: StoryboardImageGroup[]): Map<string, string> => {
  const images = new Map<string, string>()
  groups.forEach((group) => group.itemKeys.forEach((key) => images.set(key, group.imageUrl)))
  return images
}
