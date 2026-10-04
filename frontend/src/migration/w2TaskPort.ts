import type { TaskApplicationOwner } from '@/stores/taskStore'
import { api } from '@/api/client'
import { demoProjects, demoRuntime } from '@/mock/demoData'
import type { TaskPort, TaskPortSnapshot } from '@/pages/w2/shared/types'
import { captureDto } from '@/foundation/contracts/immutable'

type Store = TaskApplicationOwner
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T

/** A scoped projection of the single pure TypeScript application owner; no additional task stream. */
export function createW2TaskPort(store: Store): { port: TaskPort; dispose(): void } {
  let active = true, projectEpoch = 0, runtimeEpoch = 0, ownsSummaries = false
  const listeners = new Set<() => void>()
  function capture(): TaskPortSnapshot {
    return captureDto(copy({ usingDemo: store.usingDemo, projects: store.projects, tasks: store.tasks, taskItems: store.taskItems,
      taskFacets: store.taskFacets, taskNextCursor: store.taskNextCursor, runtime: store.runtime, error: store.error, loading: store.loading }))
  }
  let snapshot = capture()
  const stop = store.subscribe(() => {
    if (!active) return
    snapshot = capture()
    for (const listener of listeners) listener()
  })
  async function runtime(action: 'read' | 'start' | 'restart') {
    if (!active) throw new Error('页面已离开，不能启动新操作')
    const ticket = ++runtimeEpoch, demo = store.usingDemo
    const value = demo ? copy(demoRuntime) : await (action === 'read' ? api.getRuntime() : action === 'start' ? api.startRuntime() : api.restartRuntime())
    if (active && ticket === runtimeEpoch && store.usingDemo === demo) { store.runtime = value; store.error = undefined }
    return value
  }
  const port: TaskPort = {
    getSnapshot: () => snapshot,
    subscribe: listener => { if (!active) return () => undefined; listeners.add(listener); return () => { listeners.delete(listener) } },
    async loadProjects(refresh = false) {
      if (!active) return snapshot.projects
      const ticket = ++projectEpoch, demo = store.usingDemo
      try {
        const value = demo ? copy(demoProjects) : await api.getProjects(refresh)
        if (active && ticket === projectEpoch && store.usingDemo === demo) { store.projects = value; store.error = undefined }
        return value
      } catch (failure) {
        // Preserve the legacy read contract (cached rows plus a visible error), scoped to this page.
        if (active && ticket === projectEpoch && store.usingDemo === demo) store.error = failure instanceof Error ? failure.message : '项目列表加载失败'
        return snapshot.projects
      }
    },
    loadTaskSummaries: async (query = {}, append = false) => { if (active) { ownsSummaries = true; await store.loadTaskSummaries(query, append) } },
    invalidateTaskSummaries: () => store.invalidateTaskSummaries(),
    setTaskArchived: async (id, archived) => { if (!active) throw new Error('页面已离开'); await store.setTaskArchived(id, archived) },
    deleteArchivedTask: async id => { if (!active) throw new Error('页面已离开'); await store.deleteArchivedTask(id) },
    refreshRuntime: () => runtime('read'), startRuntime: () => runtime('start'), restartRuntime: () => runtime('restart'),
    activateDemo: () => { if (active) { projectEpoch++; runtimeEpoch++; store.activateDemo() } },
    deactivateDemo: async () => { if (active) { projectEpoch++; runtimeEpoch++; await store.deactivateDemo() } },
    replaceProject(project) { if (active) store.projects = store.projects.map(item => item.id === project.id ? project : item) },
    addProject(project) { if (active) store.projects = [...store.projects, project] },
    removeProject(id) { if (active) store.projects = store.projects.filter(item => item.id !== id) },
  }
  return { port, dispose() { if (!active) return; active = false; projectEpoch++; runtimeEpoch++; stop(); listeners.clear(); if (ownsSummaries) store.invalidateTaskSummaries() } }
}
