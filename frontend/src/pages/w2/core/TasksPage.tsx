import { Alert, Input, Spin } from 'antd'
import { useEffect, useState } from 'react'
import { UiActionButton, UiConfirmDialog, UiContextPanel, UiSelectableTable, type UiTableColumn } from '@/foundation/components'
import { SemanticIcon } from '@/foundation/semanticRegistry'
import { formatCompactDateTime } from '@/utils/dateTime'
import { documentTemplateStateLabel, sourceTemplateStateLabel, statusLabel, userFacingError } from '@/utils/displayLabels'
import type { TaskListItem } from '@/types/domain'
import { PageChrome, PageLink, useLeaveGuard, useOwnerSnapshot, type W2PageProps } from '../shared'
import { CoreStatus } from './CoreUi'
import { createTasksController, canArchiveTask, taskDestination, projectDemoTasks, taskStatusOptions, type TaskFilters } from './tasksController'
import { useCoreOwner } from './useCoreOwner'
import './core.css'

function taskState(task: TaskListItem) {
  if (task.sourceRunId && (!task.linkedTaskId || !['EXECUTING', 'COMPLETED', 'CANCELLED'].includes(task.sourceState ?? ''))) return sourceTemplateStateLabel(task.sourceState ?? '')
  if (task.documentRunId && (!task.linkedTaskId || !['EXECUTING', 'COMPLETED', 'CANCELLED'].includes(task.documentState ?? ''))) return documentTemplateStateLabel(task.documentState ?? '')
  return statusLabel(task.status)
}
export function TasksPage(props: W2PageProps) {
  const [owner] = useState(() => createTasksController(props.legacy.task, props.navigation, props.route))
  const state = useCoreOwner(props, owner), task = useOwnerSnapshot(props.legacy.task)
  const [deleting, setDeleting] = useState<TaskListItem>(), [expanded, setExpanded] = useState(false)
  useLeaveGuard(props, owner.canLeave)
  useEffect(() => { void owner.initialize() }, [owner])
  useEffect(() => owner.acceptQuery(props.route.query), [owner, props.route.query])
  const filters = state.filters
  const items = task.usingDemo ? projectDemoTasks(task.tasks, filters) : task.taskItems
  const projects = (task.usingDemo ? [...new Map(task.tasks.map(item => [item.projectId, { id: item.projectId, name: item.projectName }])).values()] : task.projects).slice().sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'))
  const noProjects = !task.usingDemo && !task.projects.length
  const selected = items.find(item => item.id === state.selectedId)
  const busy = owner.locked() || state.preparing
  const availability = busy ? { kind: 'disabled' as const, reason: '请先等待、核对或恢复原任务操作。' } : { kind: 'enabled' as const }
  const create = () => props.navigation.go(noProjects ? '/projects' : { path: '/requirements/new', query: typeof props.route.query.projectId === 'string' ? { projectId: props.route.query.projectId } : {} })
  const columns: UiTableColumn<TaskListItem>[] = [
    { key: 'title', titleKey: 'field.title', render: item => <><PageLink className="task-link core-task-title" navigation={props.navigation} to={taskDestination(item)}>{item.title}</PageLink><span className="core-path">{item.branch}</span></> },
    { key: 'status', titleKey: 'field.status', render: item => <><span>{taskState(item)}</span>{item.status === 'RETRY_WAIT' && <p>{item.retryDueAt ? Math.max(0, Math.ceil((Date.parse(item.retryDueAt) - state.clock) / 1000)) : 0}s</p>}</> },
    { key: 'progress', titleKey: 'field.progress', render: item => item.documentRunId || item.sourceTemplateId || item.executionMode === 'TEMPLATE_REPORT'
      ? <PageLink navigation={props.navigation} to={taskDestination(item)}>查看进度</PageLink> : <span>{item.attemptCount}/{item.maxAttempts}</span> },
    ...(!filters.grouped ? [{ key: 'project', titleKey: 'field.project' as const, render: (item: TaskListItem) => item.projectName }] : []),
    { key: 'updated', titleKey: 'field.updatedAt', render: item => <time dateTime={item.updatedAt}>{formatCompactDateTime(item.updatedAt)}</time> },
  ]
  const groups = filters.grouped ? [...items.reduce((groups, item) => { const group = groups.get(item.projectId) ?? { name: item.projectName, items: [] as TaskListItem[] }; group.items.push(item); groups.set(item.projectId, group); return groups }, new Map<string, { name: string; items: TaskListItem[] }>())]
    .sort((a, b) => a[1].name.localeCompare(b[1].name, 'zh-CN')) : [['all', { name: '', items }] as const]
  const choose = (label: string, value: string, options: readonly (readonly [string, string])[], change: (value: string) => void) => <label>{label}<select aria-label={label} value={value} disabled={busy} onChange={event => change(event.target.value)}>{options.map(([value, text]) => <option key={value} value={value}>{text}</option>)}</select></label>
  return <div className="core-page"><PageChrome objectKey="page.tasks" title="任务控制台" actions={<>
    <UiActionButton actionKey="ui.refresh" availability={availability} onAction={() => { void owner.refresh() }} />
    <UiActionButton actionKey={noProjects ? 'project.register' : 'task.create'} variant="primary" availability={availability} onAction={() => { void create() }} />
  </>} status={<><CoreStatus state={state} recover={owner.recover} retryOriginal={owner.retryOriginal} />
    {task.error && !task.usingDemo && <Alert role="alert" title="任务加载失败" type="error" description={<><p>{userFacingError(task.error)}</p><UiActionButton actionKey="settings.toggleDemo" target="载入演示" availability={availability} onAction={props.legacy.task.activateDemo} /></>} />}
  </>} context={<UiContextPanel open={!!selected} title={selected?.title ?? '任务详情'} expanded={expanded} onExpandedChange={setExpanded}
    onClose={() => owner.select(undefined)} closePolicy={busy ? { kind: 'block', reason: '原任务操作尚未确认，请先处理。' } : { kind: 'allow' }}>
    {selected && <div className="core-task-detail">
      <p>{taskState(selected)}</p><p>{selected.goal}</p>
      <dl><div><dt>项目</dt><dd>{selected.projectName}</dd></div><div><dt>执行分支</dt><dd className="core-path">{selected.branch}</dd></div><div><dt>更新于</dt><dd>{formatCompactDateTime(selected.updatedAt)}</dd></div></dl>
      <div className="core-actions">
        <UiActionButton actionKey="task.open" target={selected.title} onAction={() => { void props.navigation.go(taskDestination(selected)) }} />
        {selected.hasDesignHistory && (!(selected.documentRunId || selected.sourceRunId) || selected.linkedTaskId) && selected.executionMode !== 'TEMPLATE_REPORT'
          && <UiActionButton actionKey="task.openDesign" target={selected.title} onAction={() => { void props.navigation.go(`/tasks/${selected.linkedTaskId || selected.id}/design`) }} />}
        {(selected.archived || canArchiveTask(selected)) && <UiActionButton actionKey={selected.archived ? 'task.restoreArchive' : 'task.archive'} target={selected.title} availability={availability} onAction={() => { void owner.archive(selected) }} />}
        {selected.archived && !selected.documentRunId && !selected.sourceRunId && <UiActionButton actionKey="task.delete" target={selected.title} variant="danger" availability={availability} onAction={() => setDeleting(selected)} />}
      </div>
    </div>}
  </UiContextPanel>}>
    {noProjects && !task.loading && <section className="core-empty"><SemanticIcon semanticKey="object.project" /><h2>先登记一个项目</h2><UiActionButton actionKey="project.register" onAction={() => { void props.navigation.go('/projects') }} /></section>}
    {(!noProjects || items.length > 0) && <>
      <section className="core-metrics" aria-label="任务概览与快速筛选">
        {([['ACTIVE', '处理中', 'PROCESSING', 'status.running'], ['SUCCESSFUL', '已成功', 'SUCCESSFUL', 'status.success'], ['WAITING_INPUT', '需要输入', 'WAITING_INPUT', 'status.waiting'], ['TERMINATED', '已终止', 'TERMINATED', 'status.cancelled']] as const).map(([status, label, facet, semantic]) => <button key={status} type="button" disabled={busy} aria-pressed={filters.status === status} onClick={() => owner.metric(status)}>
          <span><SemanticIcon semanticKey={semantic} />{label}</span><strong>{task.taskFacets[facet] ?? 0}</strong>
        </button>)}
      </section>
      <div className="core-toolbar" aria-label="按状态筛选">{taskStatusOptions.map(([status, label]) => <button key={status} type="button" className="ant-btn ant-btn-default" disabled={busy} aria-pressed={filters.status === status} onClick={() => owner.change({ status })}>{label}</button>)}</div>
      <div className="core-toolbar" aria-label="任务筛选">
        <Input aria-label="搜索任务" placeholder="搜索标题、目标或项目…" value={filters.search} disabled={busy} style={{ width: 240 }} onChange={event => owner.change({ search: event.target.value })} />
        {choose('按任务类型筛选', filters.type, [['ALL', '全部类型'], ['TEMPLATE', '模板任务'], ['STANDARD', '普通任务']], value => owner.change({ type: value as TaskFilters['type'] }))}
        {choose('按项目筛选任务', filters.project, [['ALL', '全部项目'], ...projects.map(project => [project.id, project.name] as const)], value => owner.change({ project: value }))}
        {choose('选择归档范围', filters.archive, [['ACTIVE', '活动任务'], ['ARCHIVED', `已归档（${task.taskFacets.ARCHIVED_TOTAL ?? 0}）`], ['ALL', '全部任务']], value => owner.change({ archive: value as TaskFilters['archive'] }))}
        {choose('按更新时间排序', filters.order, [['NEWEST', '最新更新优先'], ['OLDEST', '最早更新优先']], value => owner.change({ order: value as TaskFilters['order'] }))}
        <label><input type="checkbox" checked={filters.grouped} disabled={busy} onChange={event => owner.change({ grouped: event.target.checked })} />按项目分组</label>
        <UiActionButton actionKey="ui.resetFilters" availability={availability} onAction={owner.reset} />
      </div>
      <p aria-live="polite">{items.length} 个任务 · {task.usingDemo ? '演示数据' : '实时数据'}</p>
      {task.loading ? <Spin aria-label="正在读取任务" /> : items.length ? <div className="task-groups">{groups.map(([id, group]) => <section key={id}>
        {filters.grouped && <h2 className="task-group-header">{group.name} · {group.items.length} 个任务</h2>}
        <UiSelectableTable items={group.items} getKey={item => item.id} getName={item => item.title} selectedKey={state.selectedId} onSelect={item => owner.select(item.id)} columns={columns} />
      </section>)}</div> : <section className="core-empty"><h2>{task.tasks.length ? '没有匹配的任务' : '还没有任务'}</h2>
        <UiActionButton actionKey={task.tasks.length ? 'ui.resetFilters' : 'task.create'} onAction={() => { if (task.tasks.length) owner.reset(); else void create() }} />
      </section>}
      {task.taskNextCursor && <UiActionButton actionKey="ui.loadMore" busy={task.loading} availability={availability} onAction={() => { void owner.reload(true).catch(() => undefined) }} />}
    </>}
    <UiConfirmDialog open={!!deleting} title="永久删除历史任务？" confirmActionKey="task.delete" target={deleting?.title} policy={busy ? { kind: 'block', reason: '请先核对原任务操作。' } : { kind: 'allow' }}
      onCancel={() => setDeleting(undefined)} onConfirm={() => { if (deleting && !busy) { const item = deleting; setDeleting(undefined); void owner.remove(item) } }}>
      将永久删除“{deleting?.title}”的任务记录、验证、评审和关联设计历史。项目文件、Git 分支与 worktree 不会删除。此操作无法恢复。
    </UiConfirmDialog>
  </PageChrome></div>
}
