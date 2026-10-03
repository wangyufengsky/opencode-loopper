import { workflowApi } from '@/api/workflow'
import type { WorkflowReceipt, WorkflowTemplateSummary } from '@/types/domain'
import type { AcceptedHandoff, OperationOwner } from '@/foundation/contracts/receipt'
import { userFacingError } from '@/utils/displayLabels'
import { noCommand, pageController, unresolved, type CommandStatus } from './controllerCore'

export interface WorkflowLibraryState {
  rows: WorkflowTemplateSummary[]; query: string; kind: string; cursor: string; loading: boolean; error: string; selectedId: string
  command: CommandStatus; resultId: string
}
type CopyBody = { requestKey: string; sourceRevision: number; title: string }
type ArchiveBody = { requestKey: string; expectedVersion: number }
export function createWorkflowLibraryController(options: {
  api?: typeof workflowApi
  goAccepted: (to: string, handoff: AcceptedHandoff) => Promise<boolean>
  key?: () => string
}) {
  const port = options.api ?? workflowApi
  const core = pageController<WorkflowLibraryState>('workflow-library', { rows: [], query: '', kind: 'ALL', cursor: '', loading: false,
    error: '', selectedId: '', command: noCommand, resultId: '' })
  const state = core.owner.getSnapshot
  let operation: OperationOwner<CopyBody | ArchiveBody, WorkflowReceipt> | null = null
  async function load(more = false): Promise<boolean> {
    if (!core.active()) return false
    const current = core.ticket('list'), s = state()
    core.patch({ loading: true, error: '', ...(!more ? { rows: [], cursor: '' } : {}) })
    try {
      const page = await port.list(s.query, s.kind, more ? s.cursor : '')
      if (!current.current()) return false
      core.patch({ rows: more ? [...s.rows, ...page.items] : page.items, cursor: page.nextCursor ?? '' }); return true
    } catch (failure) { if (current.current()) core.patch({ error: userFacingError(failure, '流程暂时无法读取，请重试。') }); return false }
    finally { if (current.current()) core.patch({ loading: false }) }
  }
  async function act(row: WorkflowTemplateSummary, action: 'copy' | 'archive') {
    if (unresolved(state().command) || !core.active() || (action === 'archive' && row.builtin)) return
    const requestKey = (options.key ?? (() => crypto.randomUUID()))()
    const body: CopyBody | ArchiveBody = action === 'copy'
      ? { requestKey, sourceRevision: row.headRevision, title: `${row.title} 副本`.slice(0, 120) }
      : { requestKey, expectedVersion: row.version }
    const current = core.command<CopyBody | ArchiveBody, WorkflowReceipt>({
      label: action === 'copy' ? '复制流程' : '删除流程',
      input: { endpoint: `/workflows/templates/${encodeURIComponent(row.id)}${action === 'copy' ? '/copy' : ''}`, method: action === 'copy' ? 'POST' : 'DELETE', body, requestKey,
        versions: action === 'copy' ? { sourceRevision: row.headRevision } : { expectedVersion: row.version } },
      capability: { kind: 'IDEMPOTENT_KEY' },
      write: identity => action === 'copy' ? port.copy(row.id, identity.body as CopyBody) : port.archive(row.id, identity.body as ArchiveBody),
      handoffTarget: action === 'copy' ? receipt => `/workflows/${encodeURIComponent(receipt.id)}` : undefined,
      changed: command => core.patch({ command }),
      read: async (receipt, context) => {
        if (!core.active()) throw new Error('页面读取已暂停，请回到原页面恢复已接受的操作。')
        if (action === 'copy') {
          context.apply(() => core.patch({ resultId: receipt.id }))
          if (!await options.goAccepted(`/workflows/${encodeURIComponent(receipt.id)}`, current.prepareHandoff())) throw new Error('副本已创建，导航未完成。请打开原副本，不能再次复制。')
        } else if (!await load()) throw new Error('删除已接受，列表尚未刷新。请重新读取原结果。')
      },
    })
    operation = current
    try { await current.execute() } catch { /* The operation snapshot retains the original identity and error. */ }
  }
  async function recover() {
    if (!operation || operation.getSnapshot().busy) return
    try { if (operation.getSnapshot().accepted) await operation.retryReadback(); else await operation.recoverWrite() } catch { /* Visible owner status. */ }
  }
  core.setStart(() => { void load() })
  return { ...core.owner, load, act, recover, getOperation: () => operation,
    query(query: string) { core.patch({ query }) },
    search() { core.invalidate('list'); void load() },
    kind(kind: string) { core.invalidate('list'); core.patch({ kind }); void load() },
    select(selectedId: string) { core.patch({ selectedId }) },
  }
}
