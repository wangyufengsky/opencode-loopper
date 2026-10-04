import type { TaskSummaryQuery } from '@/api/client'
import type { Project, RuntimeInfo, Task, TaskListItem } from '@/types/domain'
import type { SkinDefinition } from '@/themes/types'
import type { SnapshotPort, LeaveDecision } from '@/foundation/contracts/types'
import type { NavigationRequest } from '@/foundation/contracts/navigation'
import type { AcceptedHandoff } from '@/foundation/contracts/receipt'

export interface W2Route {
  path: string
  fullPath: string
  query: Readonly<Record<string, string | string[] | null | undefined>>
  params: Readonly<Record<string, string | string[]>>
}
export type W2Target = string | { path: string; query?: Record<string, string | number | null | undefined>; hash?: string }
export type W2LeaveGuard = (request?: NavigationRequest) => LeaveDecision
export interface W2Navigation {
  go(to: W2Target, replace?: boolean, handoff?: AcceptedHandoff): Promise<boolean>
  goAccepted(to: W2Target, permit: AcceptedHandoff): Promise<boolean>
  back(): void
  registerGuard(read: W2LeaveGuard): () => void
  guardChanged(): void
}
export interface TaskPortSnapshot {
  usingDemo: boolean
  projects: Project[]
  tasks: Task[]
  taskItems: TaskListItem[]
  taskFacets: Record<string, number>
  taskNextCursor?: string
  runtime?: RuntimeInfo
  error?: string
  loading: boolean
}
/** Projection of the single TypeScript application owner; no new SSE or task writer. */
export interface TaskPort extends SnapshotPort<TaskPortSnapshot> {
  loadProjects(refresh?: boolean): Promise<Project[]>
  loadTaskSummaries(query?: TaskSummaryQuery, append?: boolean): Promise<void>
  invalidateTaskSummaries(): void
  setTaskArchived(id: string, archived: boolean): Promise<void>
  deleteArchivedTask(id: string): Promise<void>
  refreshRuntime(): Promise<RuntimeInfo | undefined>
  startRuntime(): Promise<RuntimeInfo | undefined>
  restartRuntime(): Promise<RuntimeInfo | undefined>
  activateDemo(): void
  deactivateDemo(): Promise<void>
  replaceProject(project: Project): void
  addProject(project: Project): void
  removeProject(id: string): void
}
/** Retained for the committed application route lifetime, not a replayed React view effect. */
export interface W2Lifecycle { retain(key: object, dispose: () => void): void }
export interface W2PageProps {
  route: W2Route
  navigation: W2Navigation
  legacy: { task: TaskPort }
  skin: SkinDefinition
  setSkin(id: string): void
  lifecycle: W2Lifecycle
}
export function queryString(route: W2Route, key: string): string {
  const value = route.query[key]
  return Array.isArray(value) ? value[0] ?? '' : value ?? ''
}
