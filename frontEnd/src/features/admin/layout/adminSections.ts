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
  // Runtime config sections: the path's last segment is the backend section id (/api/v1/admin/config/{id}).
  { path: 'config/models', label: 'Models & Credits', icon: '🤖' },
  { path: 'config/runtime', label: 'Threads & Queues', icon: '🧵' },
  { path: 'config/generation', label: 'Generation', icon: '🎛️' },
  { path: 'config/images', label: 'Image Profiles', icon: '🖼️' },
  { path: 'config/mail', label: 'OTP & Mail', icon: '✉️' },
  { path: 'config/keys', label: 'Keys', icon: '🔑' },
]
