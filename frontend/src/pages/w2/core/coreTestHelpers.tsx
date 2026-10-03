import { vi } from 'vitest'
import { FoundationProvider } from '@/foundation/provider'
import { skins } from '@/themes/registry'
import type { TaskPortSnapshot, TaskPort, W2PageProps, W2LeaveGuard } from '../shared'
import { captureDto } from '@/foundation/contracts/immutable'
import type { Project, Task } from '@/types/domain'
import { demoRuntime } from '@/mock/demoData'
import type { ReactNode } from 'react'

export const projectFixture: Project = { id: 'p', name: '项目 A', rootPath: '/project', documentPath: '/project/docs', status: 'READY', executionMode: 'DIRECT',
  version: 4, updatedAt: '2026-08-05T00:00:00Z', taskCount: 2, openDesignerSessionCount: 1, stackProfileState: 'READY', stackTechnologyFamilies: ['java'], stackComponentCount: 2 }
export const taskFixtures: Task[] = [
  { id: 'new-a', projectId: 'p', projectName: '项目 A', title: 'A 的新任务', goal: 'A new', branch: 'DIRECT', worktreePath: '/a', status: 'SUCCEEDED', hasDesignHistory: true, attemptCount: 1, maxAttempts: 3, createdAt: '2026-08-05T08:00:00Z', updatedAt: '2026-08-05T09:00:00Z' },
  { id: 'old-a', projectId: 'p', projectName: '项目 A', title: 'A 的旧任务', goal: 'A old', branch: 'DIRECT', worktreePath: '/a', status: 'FAILED', hasDesignHistory: true, attemptCount: 2, maxAttempts: 3, createdAt: '2026-08-03T08:00:00Z', updatedAt: '2026-08-03T09:00:00Z' },
  { id: 'middle-b', projectId: 'b', projectName: '项目 B', title: 'B 的任务', goal: 'B middle', branch: 'DIRECT', worktreePath: '/b', status: 'SUCCEEDED', hasDesignHistory: true, attemptCount: 1, maxAttempts: 3, createdAt: '2026-08-04T08:00:00Z', updatedAt: '2026-08-04T09:00:00Z' },
]
export function coreFixture(initial: Partial<TaskPortSnapshot> = {}) {
  let snapshot: TaskPortSnapshot = captureDto({ usingDemo: false, projects: [projectFixture], tasks: taskFixtures,
    taskItems: taskFixtures, taskFacets: { SUCCESSFUL: 2, PROCESSING: 0, TERMINATED: 1, ARCHIVED_TOTAL: 0 }, runtime: demoRuntime, loading: false, ...initial })
  const listeners = new Set<() => void>(), retained = new Map<object, () => void>(), guards = new Set<W2LeaveGuard>()
  const publish = (patch: Partial<TaskPortSnapshot>) => { snapshot = captureDto({ ...snapshot, ...patch }); listeners.forEach(listener => listener()) }
  const port: TaskPort = {
    getSnapshot: () => snapshot, subscribe: listener => { listeners.add(listener); return () => { listeners.delete(listener) } },
    loadProjects: vi.fn(async () => snapshot.projects), loadTaskSummaries: vi.fn(async () => undefined), invalidateTaskSummaries: vi.fn(),
    setTaskArchived: vi.fn(async (id, archived) => publish({ tasks: snapshot.tasks.map(task => task.id === id ? { ...task, archived } : task), taskItems: snapshot.taskItems.map(task => task.id === id ? { ...task, archived } : task) })),
    deleteArchivedTask: vi.fn(async id => publish({ tasks: snapshot.tasks.filter(task => task.id !== id), taskItems: snapshot.taskItems.filter(task => task.id !== id) })),
    refreshRuntime: vi.fn(async () => snapshot.runtime), startRuntime: vi.fn(async () => snapshot.runtime), restartRuntime: vi.fn(async () => snapshot.runtime),
    activateDemo: vi.fn(() => publish({ usingDemo: true, error: undefined })), deactivateDemo: vi.fn(async () => publish({ usingDemo: false, error: undefined })),
    addProject: vi.fn(project => publish({ projects: [...snapshot.projects, project] })), replaceProject: vi.fn(project => publish({ projects: snapshot.projects.map(item => item.id === project.id ? project : item) })), removeProject: vi.fn(id => publish({ projects: snapshot.projects.filter(item => item.id !== id) })),
  }
  const props: W2PageProps = { legacy: { task: port }, skin: skins[0]!, setSkin: vi.fn(),
    route: { path: '/tasks', fullPath: '/tasks', query: {}, params: {} },
    navigation: { go: vi.fn(async () => true), goAccepted: vi.fn(async () => true), back: vi.fn(), guardChanged: vi.fn(), registerGuard: guard => { guards.add(guard); return () => { guards.delete(guard) } } },
    lifecycle: { retain: (key, dispose) => { retained.set(key, dispose) } },
  }
  return { props, port, publish, retained, guards, dispose: () => { retained.forEach(dispose => dispose()); retained.clear() } }
}
export const coreFrame = (children: ReactNode, skin = skins[0]!) => <FoundationProvider skin={skin} reducedMotion>{children}</FoundationProvider>
export function setupCoreDom() {
  vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
  const style = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation(element => style(element))
}
export function deferred<T>() { let resolve!: (value: T) => void, reject!: (error: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
