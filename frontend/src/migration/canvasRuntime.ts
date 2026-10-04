export type CanvasRuntime = 'react'
export type CanvasArea = 'workflow' | 'ppt' | 'tasks' | 'roles' | 'documents'
export const CANVAS_RUNTIME_STORAGE = 'loopper.canvas.runtime.v1'
export const canvasAreas: ReadonlyArray<{ id: CanvasArea; title: string }> = [
  { id: 'workflow', title: '流程创作与需求画布' },
  { id: 'ppt', title: 'PPT 画布与缩略预览' },
  { id: 'tasks', title: '任务阶段与执行步骤' },
  { id: 'roles', title: '角色流程图' },
  { id: 'documents', title: '设计与文档中的图示' },
]
export type CanvasPreferences = Partial<Record<CanvasArea, CanvasRuntime>>
export type CanvasPreferenceStorage = Pick<Storage, 'getItem' | 'setItem'>

export function canvasArea(path: string): CanvasArea {
  if (/^\/(workflows|requirements)(\/|$)/.test(path)) return 'workflow'
  if (/^\/ppt(\/|$)/.test(path)) return 'ppt'
  if (/^\/tasks\/[^/]+\/?$/.test(path)) return 'tasks'
  if (/^\/roles\/?$/.test(path)) return 'roles'
  return 'documents'
}
export function readCanvasPreferences(storage?: CanvasPreferenceStorage): CanvasPreferences {
  try {
    const value: unknown = JSON.parse(storage?.getItem(CANVAS_RUNTIME_STORAGE) || '{}')
    if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
    return Object.fromEntries(canvasAreas.flatMap(({ id }) => {
      const runtime = (value as Record<string, unknown>)[id]
      return runtime === 'react' ? [[id, runtime]] : []
    }))
  } catch { return {} }
}
export function resolveCanvasRuntime(path: string, preferences: CanvasPreferences): CanvasRuntime {
  return preferences[canvasArea(path)] ?? 'react'
}
export function writeCanvasPreference(storage: CanvasPreferenceStorage | undefined, area: CanvasArea, runtime: CanvasRuntime): boolean {
  try {
    if (!storage) return false
    storage.setItem(CANVAS_RUNTIME_STORAGE, JSON.stringify({ ...readCanvasPreferences(storage), [area]: runtime }))
    return true
  } catch { return false }
}

/** Capture once. Preference updates never replace a mounted view or its File/draft owner. */
export function captureCanvasRuntime(path: string, storage?: CanvasPreferenceStorage): CanvasRuntime {
  return resolveCanvasRuntime(path, readCanvasPreferences(storage))
}
