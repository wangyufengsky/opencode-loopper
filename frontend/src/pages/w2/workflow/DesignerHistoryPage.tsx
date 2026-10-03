import { useLayoutEffect, useRef, useState } from 'react'
import { Input, Select } from 'antd'
import { UiActionButton, UiContextPanel, UiField, UiSelectableList } from '@/foundation/components'
import { semanticLabel } from '@/foundation/semanticRegistry'
import { PageChrome, PageLink, queryString } from '@/pages/w2/shared'
import type { W2PageProps } from '@/pages/w2/shared/types'
import { formatDateTime } from '@/utils/dateTime'
import { statusLabel } from '@/utils/displayLabels'
import { createDesignerHistoryController, historyActions, historyStatus, type HistoryStatus } from './designerHistoryController'
import { unresolved } from './controllerCore'
import { CommandNotice, focusSelection, ReadNotice, usePageOwner } from './pageParts'

export function DesignerHistoryPage(props: W2PageProps & { controller?: ReturnType<typeof createDesignerHistoryController> }) {
  const navigation = useRef(props.navigation); navigation.current = props.navigation
  const [owner] = useState(() => props.controller ?? createDesignerHistoryController({ query: props.route.query,
    syncQuery: query => { void navigation.current.go({ path: '/designs', query }, true) },
    clearPointer: id => { try { const raw = sessionStorage.getItem('opencode-loopper.designer-workspace'); if (raw && JSON.parse(raw).sessionId === id) sessionStorage.removeItem('opencode-loopper.designer-workspace') } catch { /* Browser hint is not server authority. */ } },
  }))
  const s = usePageOwner(props, owner), trigger = useRef<HTMLElement | null>(null)
  const query = JSON.stringify(props.route.query)
  useLayoutEffect(() => { owner.route(props.route.query) }, [owner, query])
  const selected = s.rows.find(row => row.id === s.selectedId), availability = unresolved(s.command) ? { kind: 'disabled' as const, reason: '请先恢复原操作。' } : { kind: 'enabled' as const }
  const gate = selected ? historyActions(selected) : null
  const designTarget = (edit: boolean) => ({ path: '/designer', query: { sessionId: selected!.id, projectId: selected!.projectId, ...(edit ? { mode: 'edit' } : {}) } })
  return <PageChrome title={semanticLabel('nav.designs')} objectKey="nav.designs" actions={<>
    <UiActionButton actionKey="ui.refresh" busy={s.loading} onAction={() => { void owner.load() }} />
    <UiActionButton actionKey="workflow.newRequirement" variant="primary" onAction={() => { void props.navigation.go({ path: '/requirements/new', query: queryString(props.route, 'projectId') ? { projectId: queryString(props.route, 'projectId') } : {} }) }} />
  </>} status={<><ReadNotice error={s.error} busy={s.loading} retry={() => { void owner.load() }}>正在读取历史设计…</ReadNotice>
    <ReadNotice error={s.projectError} retry={() => { void owner.projects() }} /><CommandNotice command={s.command} recover={() => { void owner.recover() }} />{s.notice && <p role="status">{s.notice}</p>}</>}
    context={<UiContextPanel open={!!selected} title={selected?.goal || '设计详情'} returnFocus={trigger} onClose={() => owner.select('')}>
      {selected && gate && <><p>{selected.projectName}</p><p>{historyStatus(selected)}</p><time dateTime={selected.updatedAt}>更新于 {formatDateTime(selected.updatedAt)}</time>
        {selected.taskState && <p>任务：{statusLabel(selected.taskState)}</p>}{selected.archivedAt && <p>归档于 {formatDateTime(selected.archivedAt)}</p>}
        <div className="w2-actions">
          {gate.retryStop && <UiActionButton actionKey="designer.retryStop" target={selected.goal} variant="danger" availability={availability} onAction={() => { void owner.act(selected, 'stop') }} />}
          {gate.task && <PageLink navigation={props.navigation} to={`/tasks/${encodeURIComponent(selected.taskId!)}/design`}>查看设计</PageLink>}
          {gate.resume && <><UiActionButton actionKey="designer.continue" onAction={() => { void props.navigation.go(designTarget(false)) }} /><UiActionButton actionKey="designer.editSettings" onAction={() => { void props.navigation.go(designTarget(true)) }} /></>}
          {gate.archive && <UiActionButton actionKey={selected.archived ? 'designer.restoreArchive' : 'designer.archive'} availability={availability} target={selected.goal} onAction={() => { void owner.act(selected, selected.archived ? 'restore' : 'archive') }} />}
          {!gate.retryStop && !gate.task && !gate.resume && !gate.archive && <p>只读记录</p>}
        </div></>}
    </UiContextPanel>}>
    <div className="w2-module-toolbar">
      <UiField labelKey="field.query">{field => <Input {...field} aria-label="搜索历史设计" value={s.filters.q} placeholder="搜索目标、项目或工作包" onChange={event => owner.filters({ q: event.target.value })} />}</UiField>
      <UiField labelKey="field.project">{field => <Select {...field} aria-label="按项目筛选设计" value={s.filters.projectId} onChange={projectId => owner.filters({ projectId })} options={[{ value: 'ALL', label: '全部项目' }, ...s.projects.map(project => ({ value: project.id, label: project.name }))]} />}</UiField>
      <UiField labelKey="field.status">{field => <Select {...field} aria-label="按状态筛选设计" value={s.filters.status} onChange={(status: HistoryStatus) => owner.filters({ status })} options={['ALL', 'CONFIRMED', 'PROCESSING', 'REVIEWING', 'WAITING_INPUT', 'SESSION_ERROR'].map((value, i) => ({ value, label: ['全部状态', '已确认成任务', '处理中', '待确认', '等待输入', '已停止/异常'][i] }))} />}</UiField>
      <UiField labelKey="field.archive">{field => <Select {...field} aria-label="选择设计归档范围" value={s.filters.archive} onChange={archive => owner.filters({ archive })} options={[{ value: 'ACTIVE', label: '未归档' }, { value: 'ARCHIVED', label: `已归档（${s.facets.ARCHIVED_TOTAL ?? 0}）` }, { value: 'ALL', label: '全部设计' }]} />}</UiField>
      <UiField labelKey="field.sort">{field => <Select {...field} aria-label="按更新时间排序设计" value={s.filters.order} onChange={order => owner.filters({ order })} options={[{ value: 'newest', label: '最新更新优先' }, { value: 'oldest', label: '最早更新优先' }]} />}</UiField>
    </div>
    <p className="w2-summary">可继续 {s.facets.RESUMABLE_TOTAL ?? 0} · 已确认 {s.facets.CONFIRMED_TOTAL ?? 0} · 已归档 {s.facets.ARCHIVED_TOTAL ?? 0}</p>
    <UiSelectableList items={s.rows} selectedKey={s.selectedId} labelKey="nav.designs" getKey={row => row.id} getName={row => row.goal || '未命名设计'}
      onSelect={row => { focusSelection(trigger); owner.select(row.id === s.selectedId ? '' : row.id) }} renderItem={row => <div className="w2-record"><strong>{row.goal || '未命名设计'}</strong><span>{row.projectName}</span><span>{historyStatus(row)}</span><time dateTime={row.updatedAt}>{formatDateTime(row.updatedAt)}</time></div>} />
    {!s.loading && !s.error && !s.rows.length && <section className="w2-empty"><h2>还没有历史设计</h2></section>}
    {s.cursor && <UiActionButton actionKey="ui.loadMore" target="历史设计" busy={s.loading} onAction={() => { void owner.load(true) }} />}
  </PageChrome>
}
