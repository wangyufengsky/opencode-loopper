import { api } from '@/api/client'
import type { DesignerHistoryItem, DesignerStopResult, Project } from '@/types/domain'
import type { DesignerHistoryQuery } from '@/api/client'
import type { OperationOwner } from '@/foundation/contracts/receipt'
import { userFacingError } from '@/utils/displayLabels'
import { noCommand, pageController, unresolved, type CommandStatus } from './controllerCore'

export type HistoryStatus = 'ALL' | 'CONFIRMED' | 'PROCESSING' | 'REVIEWING' | 'WAITING_INPUT' | 'SESSION_ERROR'
export interface HistoryFilters { projectId: string; status: HistoryStatus; archive: 'ACTIVE' | 'ARCHIVED' | 'ALL'; order: 'newest' | 'oldest'; q: string }
export interface HistoryState { rows: DesignerHistoryItem[]; facets: Record<string, number>; cursor: string; loading: boolean; error: string;
  projects: Project[]; projectError: string; filters: HistoryFilters; selectedId: string; command: CommandStatus; notice: string }
const queryValue = (value: unknown): string => Array.isArray(value) ? String(value[0] ?? '') : typeof value === 'string' ? value : ''
export function historyFilters(query: Record<string, unknown>): HistoryFilters {
  const status = queryValue(query.status).toUpperCase()
  return { projectId: queryValue(query.projectId) || 'ALL', status: ['ALL', 'CONFIRMED', 'PROCESSING', 'REVIEWING', 'WAITING_INPUT', 'SESSION_ERROR'].includes(status) ? status as HistoryStatus : 'ALL',
    archive: queryValue(query.archive) === 'archived' ? 'ARCHIVED' : queryValue(query.archive) === 'all' ? 'ALL' : 'ACTIVE',
    order: queryValue(query.order) === 'oldest' ? 'oldest' : 'newest', q: queryValue(query.q) }
}
export function historyRouteQuery(filters: HistoryFilters) {
  return { ...(filters.projectId !== 'ALL' ? { projectId: filters.projectId } : {}), ...(filters.status !== 'ALL' ? { status: filters.status } : {}),
    ...(filters.archive !== 'ACTIVE' ? { archive: filters.archive.toLowerCase() } : {}), ...(filters.order !== 'newest' ? { order: filters.order } : {}), ...(filters.q.trim() ? { q: filters.q.trim() } : {}) }
}
export function historyStatus(item: DesignerHistoryItem): string {
  if (item.state === 'CANCELLED') return '已取消'
  if (item.state === 'STOPPING') return '正在停止'
  if (item.archived) return '已归档'
  if (item.taskId || item.draftStatus === 'CONFIRMED') return '已确认成任务'
  if (item.state === 'WAITING_INPUT') return '等待输入'
  if (item.state === 'SESSION_ERROR' || item.workflowPhase === 'FAILED') return '已停止/异常'
  if (['REVIEWING', 'COMPLETED'].includes(item.state) || ['REVIEWING_PACKAGE', 'FINAL_REVIEW', 'COMPLETED'].includes(item.workflowPhase))
    return ['FINAL_REVIEW', 'COMPLETED'].includes(item.workflowPhase) ? '总体待确认' : '工作包待确认'
  return '处理中'
}
export function historyActions(item: DesignerHistoryItem) {
  if (item.stopRetryAvailable) return { retryStop: true, task: false, resume: false, archive: false }
  if (item.taskId || item.draftStatus === 'CONFIRMED') return { retryStop: false, task: !!item.taskId, resume: false, archive: false }
  if (item.state === 'CANCELLED') return { retryStop: false, task: false, resume: false, archive: false }
  return { retryStop: false, task: false, resume: item.resumable, archive: true }
}
type HistoryPort = Pick<typeof api, 'listDesignerHistoryPage' | 'getProjects' | 'archiveDesignerSession' | 'restoreDesignerSession' | 'stopDesignerSession' | 'getDesignerSession'>
type HistoryBody = { id: string; action: 'archive' | 'restore' | 'stop' }
type HistoryReceipt = { id: string; action: HistoryBody['action']; stop?: DesignerStopResult }
export function createDesignerHistoryController(options: { api?: HistoryPort; query?: Record<string, unknown>; syncQuery?: (query: Record<string, string>) => void;
  clearPointer?: (id: string) => void } = {}) {
  const port = options.api ?? api
  const core = pageController<HistoryState>('designer-history', { rows: [], facets: {}, cursor: '', loading: false, error: '', projects: [], projectError: '',
    filters: historyFilters(options.query ?? {}), selectedId: '', command: noCommand, notice: '' })
  const state = core.owner.getSnapshot
  let cancelReload: (() => void) | undefined
  let operation: OperationOwner<HistoryBody, HistoryReceipt> | null = null
  async function load(append = false): Promise<boolean> {
    if (!core.active()) return false
    cancelReload?.(); cancelReload = undefined
    const current = core.ticket('list'), s = state(), f = s.filters
    core.patch({ loading: true, error: '' })
    const input: DesignerHistoryQuery = { projectId: f.projectId === 'ALL' ? undefined : f.projectId, status: f.status === 'ALL' ? undefined : f.status === 'SESSION_ERROR' ? 'FAILED' : f.status,
      archive: f.archive, order: f.order, q: f.q.trim() || undefined, ...(append && s.cursor ? { cursor: s.cursor } : {}) }
    try {
      const page = await port.listDesignerHistoryPage(input)
      if (!current.current()) return false
      core.patch({ rows: append ? [...s.rows, ...page.items] : page.items, facets: page.facets, cursor: page.nextCursor ?? '' }); return true
    } catch (failure) { if (current.current()) core.patch({ error: userFacingError(failure, '无法读取历史设计，请重试。') }); return false }
    finally { if (current.current()) core.patch({ loading: false }) }
  }
  function filters(changes: Partial<HistoryFilters>, sync = true) {
    const next = { ...state().filters, ...changes }
    if (JSON.stringify(next) === JSON.stringify(state().filters)) return
    // Invalidate BEFORE the debounce, so an old append cannot enter the new query.
    core.invalidate('list'); cancelReload?.()
    core.patch({ filters: next, cursor: '', loading: false, selectedId: '' })
    if (sync) options.syncQuery?.(historyRouteQuery(next))
    if (!core.active()) return
    const timer = setTimeout(() => { cancelReload?.(); cancelReload = undefined; void load() }, 180)
    cancelReload = core.own(() => clearTimeout(timer)) as () => void
  }
  async function projects() {
    if (!core.active()) return
    const current = core.ticket('projects'); core.patch({ projectError: '' })
    try { const projects = await port.getProjects(); if (current.current()) core.patch({ projects: [...projects].sort((a, b) => a.name.localeCompare(b.name, 'zh-CN')) }) }
    catch (failure) { if (current.current()) core.patch({ projectError: userFacingError(failure, '项目列表读取失败，请重试。') }) }
  }
  async function act(item: DesignerHistoryItem, action: HistoryBody['action']) {
    const permission = historyActions(item)
    if (!core.active() || unresolved(state().command) || (action === 'stop' ? !permission.retryStop : !permission.archive)) return
    const current = core.command<HistoryBody, HistoryReceipt>({ label: action === 'stop' ? '重试停止' : action === 'archive' ? '归档设计' : '恢复设计',
      input: { endpoint: `/designer-sessions/${encodeURIComponent(item.id)}${action === 'stop' ? '/stop' : '/archive'}`, method: action === 'restore' ? 'DELETE' : action === 'archive' ? 'PUT' : 'POST', body: { id: item.id, action } },
      capability: { kind: 'READ_ORIGINAL', readOriginal: async identity => {
        const value = await port.getDesignerSession(identity.body.id)
        const accepted = action === 'stop' ? value.state === 'CANCELLED' : value.archived === (action === 'archive')
        return accepted ? { kind: 'ACCEPTED', receipt: { id: item.id, action } } : { kind: 'UNCONFIRMED' }
      } },
      write: async identity => {
        if (action === 'stop') return { id: identity.body.id, action, stop: await port.stopDesignerSession(identity.body.id) }
        if (action === 'archive') await port.archiveDesignerSession(identity.body.id)
        else await port.restoreDesignerSession(identity.body.id)
        return { id: identity.body.id, action }
      },
      changed: command => core.patch({ command }),
      read: async (receipt, context) => {
        if (!core.active()) throw new Error('页面读取已暂停，请显式恢复原操作。')
        if (!await load()) throw new Error('操作已接受，历史列表读取失败。请重新读取。')
        context.apply(() => {
          if (receipt.action === 'archive') options.clearPointer?.(receipt.id)
          const stop = receipt.stop
          core.patch({ notice: stop?.failedSessions ? `仍有 ${stop.failedSessions} 个远端会话未确认停止` : stop?.pendingFinalizations
            ? `远端已停止，仍有 ${stop.pendingFinalizations} 项本地状态待收束` : receipt.action === 'stop' ? '设计会话已停止，历史记录已保留' : receipt.action === 'archive' ? '设计已归档，完整记录仍然保留' : '设计已恢复，可以继续或修改' })
        })
      },
    })
    operation = current
    try { await current.execute() } catch { /* Owner recovery remains visible. */ }
  }
  async function recover() {
    if (!operation || operation.getSnapshot().busy) return
    try { if (operation.getSnapshot().accepted) await operation.retryReadback(); else await operation.readOriginal() } catch { /* Retain original operation. */ }
  }
  core.setStart(() => { void load(); void projects() })
  return { ...core.owner, load, projects, filters, act, recover, getOperation: () => operation,
    route(query: Record<string, unknown>) { filters(historyFilters(query), false) }, select(selectedId: string) { core.patch({ selectedId }) },
  }
}
