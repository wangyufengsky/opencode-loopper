import type { TaskPort, TaskPortSnapshot } from '@/pages/w2/shared/types'
import { captureDto } from '@/foundation/contracts/immutable'

/** W4 owns its Task/Inbox/Recovery state. No Pinia subscription or alternate writer. */
export function createW4TaskBoundary(): { port: TaskPort; dispose(): void } {
  const snapshot = captureDto<TaskPortSnapshot>({ usingDemo: false, projects: [], tasks: [], taskItems: [], taskFacets: {}, loading: false })
  const unsupported = () => { throw new Error('该任务由当前页面独立管理，请使用原任务操作入口。') }
  const port: TaskPort = {
    getSnapshot: () => snapshot,
    subscribe: () => () => {},
    loadProjects: async () => [],
    loadTaskSummaries: async () => {},
    invalidateTaskSummaries: () => {},
    setTaskArchived: async () => unsupported(),
    deleteArchivedTask: async () => unsupported(),
    refreshRuntime: async () => undefined,
    startRuntime: async () => unsupported(),
    restartRuntime: async () => unsupported(),
    activateDemo: unsupported,
    deactivateDemo: async () => unsupported(),
    replaceProject: unsupported,
    addProject: unsupported,
    removeProject: unsupported,
  }
  return { port, dispose() {} }
}
