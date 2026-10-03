import { api, ApiError, type TaskSummaryQuery } from '@/api/client'
import type { TaskListItem, TaskStatus } from '@/types/domain'
import type { TaskPort, W2Navigation, W2Route } from '../shared/types'
import type { ResourceScope } from '@/foundation/contracts/types'
import { demoTaskStatusGroups } from '@/mock/demoData'
import { userFacingError } from '@/utils/displayLabels'
import { createCoreOwner, initialCoreState, sameDto, type CoreState } from './owner'

export const taskStatusOptions = [ ['ALL', '全部'], ['ACTIVE', '处理中'], ['SUCCESSFUL', '已成功'], ['WAITING_INPUT', '等待输入'],
  ['PAUSED', '已暂停'], ['JUDGING', '评审中'], ['AWAITING_DECISION', '等待处置'], ['COMPLETED', '已确认完成'],
  ['SUPERSEDED', '已由新任务接续'], ['SUCCEEDED', '已成功'], ['FAILED', '已失败'], ['CANCELLED', '已取消'] ] as const
type StatusFilter = typeof taskStatusOptions[number][0] | 'TERMINATED'
export interface TaskFilters { status: StatusFilter; project: string; type: 'ALL' | 'TEMPLATE' | 'STANDARD'; order: 'NEWEST' | 'OLDEST'; archive: 'ACTIVE' | 'ARCHIVED' | 'ALL'; search: string; grouped: boolean }
function value(query: W2Route['query'], key: string): string { const value = query[key]; return Array.isArray(value) ? value[0] ?? '' : value ?? '' }
export function readTaskFilters(query: W2Route['query']): TaskFilters {
  const status = value(query, 'status').toUpperCase(), archive = value(query, 'archive')
  return { status: taskStatusOptions.some(item => item[0] === status) || status === 'TERMINATED' ? status as StatusFilter : 'ALL',
    project: value(query, 'project') || 'ALL', type: value(query, 'type') === 'template' ? 'TEMPLATE' : value(query, 'type') === 'standard' ? 'STANDARD' : 'ALL',
    order: value(query, 'order') === 'oldest' ? 'OLDEST' : 'NEWEST', archive: archive === 'archived' ? 'ARCHIVED' : archive === 'all' ? 'ALL' : 'ACTIVE',
    search: value(query, 'q'), grouped: value(query, 'group') === 'project' }
}
export function taskFilterQuery(filters: TaskFilters): Record<string, string> {
  return { ...(filters.status !== 'ALL' ? { status: filters.status } : {}), ...(filters.type !== 'ALL' ? { type: filters.type.toLowerCase() } : {}),
    ...(filters.project !== 'ALL' ? { project: filters.project } : {}), ...(filters.order === 'OLDEST' ? { order: 'oldest' } : {}),
    ...(filters.archive !== 'ACTIVE' ? { archive: filters.archive.toLowerCase() } : {}), ...(filters.search.trim() ? { q: filters.search.trim() } : {}),
    ...(filters.grouped ? { group: 'project' } : {}) }
}
export function taskSummaryQuery(filters: TaskFilters): TaskSummaryQuery {
  return { projectId: filters.project === 'ALL' ? undefined : filters.project,
    ...(['ACTIVE', 'SUCCESSFUL', 'TERMINATED'].includes(filters.status) ? { statusGroup: filters.status === 'ACTIVE' ? 'PROCESSING' as const : filters.status as 'SUCCESSFUL' | 'TERMINATED' }
      : filters.status === 'ALL' ? {} : { status: [filters.status] }),
    taskType: filters.type === 'ALL' ? undefined : filters.type, archive: filters.archive, q: filters.search.trim() || undefined, order: filters.order === 'OLDEST' ? 'oldest' : 'newest' }
}
export const taskDestination = (task: TaskListItem) => task.sourceRunId ? `/template-tasks/source-runs/${task.sourceRunId}` : task.documentRunId ? `/template-tasks/document-runs/${task.documentRunId}` : `/tasks/${task.id}`
const terminal: TaskStatus[] = ['COMPLETED', 'SUPERSEDED', 'SUCCEEDED', 'FAILED', 'CANCELLED']
export function canArchiveTask(task: TaskListItem) {
  return !!(task.sourceRunId || task.documentRunId) ? ['COMPLETED', 'CANCELLED'].includes(task.sourceState ?? task.documentState ?? '')
    && (!task.linkedTaskId || terminal.includes(task.status as TaskStatus)) : terminal.includes(task.status as TaskStatus)
}
export function projectDemoTasks(tasks: readonly TaskListItem[], filters: TaskFilters): TaskListItem[] {
  const query = filters.search.trim().toLocaleLowerCase('zh-CN')
  return tasks.filter(task => filters.archive === 'ALL' || (filters.archive === 'ARCHIVED' ? task.archived : !task.archived))
    .filter(task => filters.status === 'ALL' || (filters.status === 'ACTIVE' ? demoTaskStatusGroups[task.status as TaskStatus] === 'PROCESSING'
      : filters.status === 'SUCCESSFUL' ? demoTaskStatusGroups[task.status as TaskStatus] === 'SUCCESSFUL'
      : filters.status === 'TERMINATED' ? demoTaskStatusGroups[task.status as TaskStatus] === 'TERMINATED' : task.status === filters.status))
    .filter(task => filters.type === 'ALL' || (filters.type === 'TEMPLATE' ? task.executionMode === 'TEMPLATE_REPORT' : task.executionMode !== 'TEMPLATE_REPORT'))
    .filter(task => filters.project === 'ALL' || task.projectId === filters.project)
    .filter(task => !query || [task.title, task.goal, task.projectName, task.branch].some(text => text.toLocaleLowerCase('zh-CN').includes(query)))
    .sort((left, right) => (filters.order === 'NEWEST' ? -1 : 1) * (Date.parse(left.updatedAt) - Date.parse(right.updatedAt)))
}
export interface TasksState extends CoreState { filters: TaskFilters; selectedId?: string; clock: number; preparing: boolean }
export function createTasksController(port: TaskPort, navigation: W2Navigation, route: W2Route, client = api) {
  let resources: ResourceScope | undefined, debounce: ReturnType<typeof setTimeout> | undefined, initialRead: Promise<void> | undefined, initialized = false
  const owner = createCoreOwner<TasksState>('tasks-list', { ...initialCoreState(), filters: readTaskFilters(route.query), clock: Date.now(), preparing: false }, {
    attachReads: lease => {
      resources = lease
      const clock = setInterval(() => { if (lease.isActive()) owner.set({ clock: Date.now() }) }, 1000)
      lease.own(() => { clearInterval(clock); if (debounce !== undefined) clearTimeout(debounce); debounce = undefined; port.invalidateTaskSummaries(); if (resources === lease) resources = undefined })
    }, extraLeave: state => state.preparing ? { kind: 'BLOCK', reason: '正在核对原任务版本，请先等待。', recoveryAction: '等待核对完成' } : undefined,
  })
  async function reload(append = false) {
    if (port.getSnapshot().usingDemo) return
    await port.loadTaskSummaries(taskSummaryQuery(owner.getSnapshot().filters), append)
    if (port.getSnapshot().error) throw new Error(userFacingError(port.getSnapshot().error, '任务列表读取失败'))
  }
  function initialize() {
    if (initialRead) return initialRead
    if (initialized) return Promise.resolve()
    const token = owner.capture()
    initialRead = (async () => { try {
      if (!port.getSnapshot().usingDemo) { await port.loadProjects(); if (token.isCurrent() && owner.viewCount() > 0) await reload() }
      if (token.isCurrent()) initialized = true
    } catch (error) { if (token.isCurrent()) owner.set({ error: userFacingError(error, '任务加载失败') }) }
    finally { initialRead = undefined } })()
    return initialRead
  }
  function scheduleReload() {
    if (!initialized || port.getSnapshot().usingDemo || !resources?.isActive()) return
    port.invalidateTaskSummaries()
    if (debounce !== undefined) clearTimeout(debounce)
    const lease = resources
    debounce = setTimeout(() => {
      debounce = undefined
      if (!lease.isActive()) return
      const token = owner.capture()
      void reload().catch(error => { if (token.isCurrent() && lease.isActive()) owner.set({ error: userFacingError(error, '任务列表读取失败') }) })
    }, 180)
  }
  function acceptQuery(query: W2Route['query']) {
    if (owner.locked()) return
    const filters = readTaskFilters(query)
    if (!sameDto(filters, owner.getSnapshot().filters)) { const previous = owner.getSnapshot().filters; owner.set({ filters }); if (!sameDto(taskSummaryQuery(previous), taskSummaryQuery(filters))) scheduleReload() }
  }
  function change(patch: Partial<TaskFilters>) {
    if (owner.locked() || owner.getSnapshot().preparing) return
    const previous = owner.getSnapshot().filters, filters = { ...previous, ...patch }
    owner.set({ filters })
    void navigation.go({ path: '/tasks', query: taskFilterQuery(filters) }, true)
    if (!sameDto(taskSummaryQuery(previous), taskSummaryQuery(filters))) scheduleReload()
  }
  async function refresh() {
    if (owner.locked()) { await owner.recover(); return }
    const token = owner.capture(); owner.set({ error: '' })
    try { await Promise.all([port.loadProjects(true), reload()]) }
    catch (error) { if (token.isCurrent()) owner.set({ error: userFacingError(error, '任务刷新失败') }) }
  }
  async function archive(task: TaskListItem) {
    if (owner.locked() || owner.getSnapshot().preparing || (!task.archived && !canArchiveTask(task))) return false
    const archived = !task.archived, token = owner.capture()
    owner.set({ preparing: true, error: '' })
    try {
      if (task.sourceRunId || task.documentRunId) {
        const source = !!task.sourceRunId, id = task.sourceRunId ?? task.documentRunId!
        const current = source ? await client.sourceTemplate(id) : await client.documentTemplate(id)
        if (!token.isCurrent()) return false
        const body = { requestKey: crypto.randomUUID(), expectedVersion: current.version }, action = archived ? 'archive' : 'unarchive'
        return await owner.mutate({ endpoint: `/template-tasks/${source ? 'source' : 'document'}-runs/${encodeURIComponent(id)}/${source ? `controls/${action}` : action}`, method: 'POST', body,
          requestKey: body.requestKey, versions: { expectedVersion: body.expectedVersion } }, {
          label: archived ? '归档任务' : '恢复归档任务', idempotent: true,
          write: async original => source ? await client.sourceTemplateCommand(id, action, original) : await client.documentTemplateCommand(id, action, original),
          lookup: async () => { const found = source ? await client.sourceTemplate(id) : await client.documentTemplate(id); return found.archived === archived ? { kind: 'ACCEPTED', receipt: found } : { kind: 'UNCONFIRMED' } },
          read: async (_, apply) => { await reload(); apply(() => owner.set({ message: archived ? '任务已归档，可随时恢复' : '任务已恢复到活动列表' })) },
        })
      }
      return await owner.mutate({ endpoint: `/tasks/${encodeURIComponent(task.id)}/archive`, method: archived ? 'PUT' : 'DELETE', body: undefined }, {
        label: archived ? '归档任务' : '恢复归档任务', write: () => port.setTaskArchived(task.id, archived),
        lookup: async () => { const found = await client.getTask(task.id); return !!found.archived === archived ? { kind: 'ACCEPTED', receipt: undefined } : { kind: 'UNCONFIRMED' } },
        read: async (_, apply) => { await reload(); apply(() => owner.set({ message: archived ? '任务已归档，可随时恢复' : '任务已恢复到活动列表' })) },
      })
    } catch (error) { if (token.isCurrent()) owner.set({ error: userFacingError(error, '任务归档状态更新失败') }); return false }
    finally { if (token.isCurrent()) owner.set({ preparing: false }) }
  }
  async function remove(task: TaskListItem) {
    if (owner.locked() || owner.getSnapshot().preparing || !task.archived || task.documentRunId || task.sourceRunId) return false
    return owner.mutate({ endpoint: `/tasks/${encodeURIComponent(task.id)}`, method: 'DELETE', body: undefined }, {
      label: '永久删除历史任务', write: () => port.deleteArchivedTask(task.id),
      lookup: async () => { try { await client.getTask(task.id); return { kind: 'UNCONFIRMED' } } catch (error) { if (error instanceof ApiError && error.status === 404) return { kind: 'ACCEPTED', receipt: undefined }; throw error } },
      read: async (_, apply) => { await reload(); apply(() => owner.set({ selectedId: undefined, message: '历史任务已永久删除' })) },
    })
  }
  return { ...owner, initialize, acceptQuery, change, refresh, archive, remove, reload,
    select: (selectedId?: string) => owner.set({ selectedId }),
    reset: () => change(readTaskFilters({})),
    metric: (status: StatusFilter) => change({ status: owner.getSnapshot().filters.status === status ? 'ALL' : status, archive: 'ACTIVE' }),
  }
}
