import { describe, expect, it, vi } from 'vitest'
import { createW4TaskBoundary } from './w4TaskBoundary'

describe('W4 does not delegate Task state or commands to Pinia', () => {
  it('owns no alternate state subscription and exposes an immutable empty compatibility projection', () => {
    const boundary = createW4TaskBoundary(), notified = vi.fn(), snapshot = boundary.port.getSnapshot(), release = boundary.port.subscribe(notified)
    expect(Object.isFrozen(snapshot)).toBe(true); expect(snapshot.tasks).toEqual([])
    boundary.dispose(); release(); expect(notified).not.toHaveBeenCalled()
  })
  it('rejects legacy command entry points instead of introducing a second writer', async () => {
    const boundary = createW4TaskBoundary()
    await expect(boundary.port.setTaskArchived('A', true)).rejects.toThrow('当前页面独立管理')
    await expect(boundary.port.deleteArchivedTask('A')).rejects.toThrow('当前页面独立管理')
    await expect(boundary.port.startRuntime()).rejects.toThrow('当前页面独立管理')
    expect(() => boundary.port.activateDemo()).toThrow('当前页面独立管理')
    boundary.dispose()
  })
})
