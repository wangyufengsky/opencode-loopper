import { describe, expect, it } from 'vitest'
import { canvasArea, captureCanvasRuntime, CANVAS_RUNTIME_STORAGE, readCanvasPreferences, writeCanvasPreference } from './canvasRuntime'

function memory() {
  const values = new Map<string, string>()
  return { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => { values.set(key, value) } }
}
describe('route-scoped canvas rollout preferences', () => {
  it('defaults all canvas routes and document previews to React', () => {
    for (const route of ['/workflows/new', '/workflows/builtin.workflow.development', '/requirements/req', '/ppt/deck', '/tasks/task', '/roles', '/designer', '/tasks/task/design', '/knowledge/history', '/tools']) {
      expect(captureCanvasRuntime(route, memory())).toBe('react')
    }
    expect(canvasArea('/tasks/task/design')).toBe('documents')
  })
  it('changes only the selected route family for future instances', () => {
    const storage = memory(), mounted = captureCanvasRuntime('/requirements/req', storage)
    expect(writeCanvasPreference(storage, 'workflow', 'vue')).toBe(true)
    expect(mounted).toBe('react')
    expect(captureCanvasRuntime('/workflows/new', storage)).toBe('vue')
    expect(captureCanvasRuntime('/ppt/deck', storage)).toBe('react')
  })
  it('ignores corrupt values and unavailable browser storage', () => {
    const storage = memory()
    storage.setItem(CANVAS_RUNTIME_STORAGE, '{broken')
    expect(captureCanvasRuntime('/roles', storage)).toBe('react')
    storage.setItem(CANVAS_RUNTIME_STORAGE, '{"roles":"vue","ppt":"unknown","private":"value"}')
    expect(readCanvasPreferences(storage)).toEqual({ roles: 'vue' })
    expect(writeCanvasPreference(undefined, 'roles', 'vue')).toBe(false)
  })
})
