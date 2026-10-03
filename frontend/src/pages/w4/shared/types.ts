import type { LeaveDecision } from '@/foundation/contracts/types'
import type { NavigationRequest } from '@/foundation/contracts/navigation'
import type { Task } from '@/types/domain'
import type { W2PageProps } from '@/pages/w2/shared/types'

/** Registered children hold their own original command/draft identities. */
export interface TaskChildOwner {
  canLeave(request?: NavigationRequest): LeaveDecision
  retire(forced?: boolean): unknown
}
export interface TaskParentPort {
  registerChild(owner: TaskChildOwner): () => void
  /** Only the caller's ordinary dirty draft is exempt; its BLOCK and all siblings still block. */
  canStartWrite(caller?: TaskChildOwner): boolean
  /** Authoritative REST reload; does not publish a command or accept a foreign task. */
  refresh(): Promise<unknown>
}
export interface TaskPanelProps {
  task: Task
  page: W2PageProps
  parent: TaskParentPort
}
