import { sleep } from '../api'

/**
 * Polls `read` until `done` says the value is terminal, backing off from 1s to 5s. Throws on timeout.
 * `onValue` sees every polled value (for stage timing).
 */
export async function pollUntil<T>(
  read: () => Promise<T>,
  done: (value: T) => boolean,
  timeoutSeconds: number,
  what: string,
  onValue?: (value: T) => void | Promise<void>,
): Promise<T> {
  const deadline = Date.now() + timeoutSeconds * 1000
  let delay = 1000
  for (;;) {
    const value = await read()
    await onValue?.(value)
    if (done(value)) return value
    if (Date.now() + delay > deadline) throw new Error(`${what}: timed out after ${timeoutSeconds}s`)
    await sleep(delay)
    delay = Math.min(5000, Math.round(delay * 1.5))
  }
}
