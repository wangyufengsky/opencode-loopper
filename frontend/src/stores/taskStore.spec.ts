import { describe, expect, it } from 'vitest'
import { demoTasks } from '@/mock/demoData'
import { reduceTaskEvent, requiresTaskSnapshot } from '@/stores/taskStore'

describe('task SSE reducer', () => {
  it('updates task state from a persisted status event', () => {
    const next = reduceTaskEvent(demoTasks[0]!, { id: 'evt-1', type: 'task.status', at: '2026-08-04T10:20:00+08:00', data: { status: 'VERIFYING' } })
    expect(next.status).toBe('VERIFYING')
    expect(next.updatedAt).toBe('2026-08-04T10:20:00+08:00')
    expect(demoTasks[0]!.status).toBe('RUNNING')
  })

  it('ignores a malformed status without corrupting current state', () => {
    const next = reduceTaskEvent(demoTasks[0]!, { id: 'evt-2', type: 'task.status', at: '2026-08-04T10:20:00+08:00', data: { status: 'NOT_A_STATUS' } })
    expect(next.status).toBe('RUNNING')
  })

  it('keeps the Task running when a session error is recorded', () => {
    const next = reduceTaskEvent(demoTasks[0]!, { id: 'evt-3', type: 'error', at: '2026-08-04T10:21:00+08:00', data: { layer: 'SESSION', code: 'SSE_DISCONNECTED' } })
    expect(next.status).toBe('RUNNING')
  })

  it('only moves to FAILED when a terminal task state is published', () => {
    const next = reduceTaskEvent(demoTasks[0]!, { id: 'evt-4', type: 'task.status', at: '2026-08-04T10:22:00+08:00', data: { status: 'FAILED', layer: 'TASK' } })
    expect(next.status).toBe('FAILED')
  })

  it('accepts the persisted backend event form with a state field', () => {
    const next = reduceTaskEvent(demoTasks[0]!, { id: 'evt-5', type: 'task.status', at: '2026-08-04T10:23:00+08:00', data: { state: 'PAUSED' } })
    expect(next.status).toBe('PAUSED')
  })

  it('refreshes compact persisted lifecycle events but does not poll log noise', () => {
    expect(requiresTaskSnapshot('session.failed')).toBe(true)
    expect(requiresTaskSnapshot('verification.failed')).toBe(true)
    expect(requiresTaskSnapshot('task.status')).toBe(true)
    expect(requiresTaskSnapshot('log.appended')).toBe(false)
  })
})
