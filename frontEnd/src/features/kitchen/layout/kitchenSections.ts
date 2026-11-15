/** One entry in the My Kitchen side navigation. `path` is relative to /kitchen ('' is the Overview). */
export interface KitchenSection {
  path: string
  label: string
  icon: string
  /** Sections sharing a group are listed under its heading. */
  group?: string
}

export const KITCHEN_BASE = '/kitchen'

/** My Kitchen's sections, in side-nav order. */
export const KITCHEN_SECTIONS: KitchenSection[] = [
  { path: '', label: 'Overview', icon: '🏠' },
  { path: 'recipes', label: 'My Recipes', icon: '📖' },
  { path: 'ingredients', label: 'Ingredients', icon: '🥕', group: 'Pantry' },
  { path: 'equipment', label: 'Equipment', icon: '🍳', group: 'Pantry' },
  { path: 'shop', label: 'Shop', icon: '🛒' },
  { path: 'orders', label: 'Orders', icon: '🧾' },
]

export const kitchenSectionPath = (section: KitchenSection) =>
  section.path ? `${KITCHEN_BASE}/${section.path}` : KITCHEN_BASE

/** The sections in order, with a group heading wherever a new group starts. */
export const groupKitchenSections = (sections: KitchenSection[] = KITCHEN_SECTIONS) => {
  const rows: Array<{ heading: string } | { section: KitchenSection }> = []
  let currentGroup: string | undefined
  for (const section of sections) {
    if (section.group && section.group !== currentGroup) rows.push({ heading: section.group })
    currentGroup = section.group
    rows.push({ section })
  }
  return rows
}

/**
 * Which top-level navbar area a signed-in path belongs to: Discover, Admin, Account settings, or (everything
 * else under /kitchen — sections, the Recipe Tool, recipe orders) My Kitchen.
 */
export type KitchenArea = 'kitchen' | 'discover' | 'admin' | 'settings'

export const kitchenAreaOf = (pathname: string): KitchenArea => {
  if (pathname.startsWith(`${KITCHEN_BASE}/discover`)) return 'discover'
  if (pathname.startsWith(`${KITCHEN_BASE}/admin`)) return 'admin'
  if (pathname.startsWith(`${KITCHEN_BASE}/settings`)) return 'settings'
  return 'kitchen'
}
