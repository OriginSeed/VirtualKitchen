import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { useNotifications } from '../../../shared/components/notifications/notificationContext'
import { JobTracker, JobTrackerContext } from './jobTracker'
import { isDocumentHidden } from './pollTiming'

/**
 * Provides the app-wide {@link JobTracker} (see jobTracker.ts). Mounted above the routes, in
 * main.tsx, so tracked jobs keep being polled across route changes.
 */
export function JobTrackerProvider({ children }: { children: ReactNode }) {
  const { notifySuccess, notifyError, notifyInfo } = useNotifications()
  const [tracker] = useState(() => new JobTracker())

  useEffect(() => {
    tracker.setNotifier({ success: notifySuccess, error: notifyError, info: notifyInfo })
  }, [tracker, notifySuccess, notifyError, notifyInfo])

  useEffect(() => {
    // Also re-arms polling after StrictMode's dev-only unmount/remount cycle.
    tracker.resume()
    return () => tracker.pause()
  }, [tracker])

  useEffect(() => {
    // Hidden tabs poll slowly; coming back polls at once (see pollTiming.ts).
    const onVisibilityChange = () => tracker.setHidden(isDocumentHidden())
    document.addEventListener('visibilitychange', onVisibilityChange)
    return () => document.removeEventListener('visibilitychange', onVisibilityChange)
  }, [tracker])

  return <JobTrackerContext.Provider value={tracker}>{children}</JobTrackerContext.Provider>
}
