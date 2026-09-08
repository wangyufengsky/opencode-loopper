import { subscribeTaskEvents, type TaskEventStream } from '@/api/client'
import type { TaskEvent } from '@/types/domain'

/** Owns one subscription and its two independent coalescing timers. */
export function createTaskEventSubscription(handlers: {
  receive: (id: string, event: TaskEvent) => void
  overview: (id: string) => Promise<unknown>
  audit: (id: string) => Promise<unknown>
  state: (state: 'connected' | 'reconnecting' | 'idle') => void
  error: (cause: unknown) => void
  needsOverview: (type: string) => boolean
}) {
  let stream: TaskEventStream | undefined
  let generation = 0
  let snapshotTimer: number | undefined
  let auditTimer: number | undefined

  function stop() {
    generation += 1
    stream?.close()
    stream = undefined
    if (snapshotTimer !== undefined) window.clearTimeout(snapshotTimer)
    if (auditTimer !== undefined) window.clearTimeout(auditTimer)
    snapshotTimer = auditTimer = undefined
    handlers.state('idle')
  }

  function watch(id: string) {
    stop()
    const current = generation
    handlers.state('reconnecting')
    const report = (cause: unknown) => { if (current === generation) handlers.error(cause) }
    stream = subscribeTaskEvents(id, event => {
      if (current !== generation) return
      handlers.receive(id, event)
      if (handlers.needsOverview(event.type) && snapshotTimer === undefined) {
        snapshotTimer = window.setTimeout(() => {
          snapshotTimer = undefined
          if (current === generation) void handlers.overview(id).catch(report)
        }, 180)
      }
      if (/^(attempt|session|verification|judge|error|artifact)\./.test(event.type) && auditTimer === undefined) {
        auditTimer = window.setTimeout(() => {
          auditTimer = undefined
          if (current === generation) void handlers.audit(id).catch(report)
        }, 180)
      }
    }, state => { if (current === generation) handlers.state(state) })
  }
  return { watch, stop }
}
