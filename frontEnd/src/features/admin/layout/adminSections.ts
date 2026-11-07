/** One entry in the admin sub-navbar. `path` is relative to /kitchen/admin. */
export interface AdminSection {
  path: string
  label: string
  icon: string
}

export const ADMIN_BASE = '/kitchen/admin'

/** Every admin feature, in sub-navbar order. The first one is where /kitchen/admin lands. */
export const ADMIN_SECTIONS: AdminSection[] = [
  { path: 'ai-analytics', label: 'AI Analytics', icon: '📊' },
  { path: 'ai-evaluation', label: 'AI Evaluation', icon: '🧪' },
  // Every runtime config section, under its own side nav (see config/ConfigLayout).
  { path: 'config', label: 'Config', icon: '⚙️' },
]
