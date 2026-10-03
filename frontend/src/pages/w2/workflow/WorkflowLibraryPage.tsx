import { useRef, useState } from 'react'
import { Input, Select } from 'antd'
import { UiActionButton, UiConfirmDialog, UiContextPanel, UiField, UiSelectableList } from '@/foundation/components'
import { semanticLabel, semanticName } from '@/foundation/semanticRegistry'
import { PageChrome, PageLink } from '@/pages/w2/shared'
import type { W2PageProps } from '@/pages/w2/shared/types'
import type { WorkflowTemplateSummary } from '@/types/domain'
import { createWorkflowLibraryController } from './workflowLibraryController'
import { unresolved } from './controllerCore'
import { CommandNotice, focusSelection, ReadNotice, usePageOwner } from './pageParts'

export function WorkflowLibraryPage(props: W2PageProps & { controller?: ReturnType<typeof createWorkflowLibraryController> }) {
  const [owner] = useState(() => props.controller ?? createWorkflowLibraryController({ goAccepted: (to, permit) => props.navigation.goAccepted(to, permit) }))
  const s = usePageOwner(props, owner), trigger = useRef<HTMLElement | null>(null)
  const [deleting, setDeleting] = useState<WorkflowTemplateSummary | null>(null)
  const selected = s.rows.find(row => row.id === s.selectedId), locked = unresolved(s.command)
  return <PageChrome title={semanticLabel('nav.workflows')} objectKey="object.workflow" actions={<UiActionButton actionKey="workflow.newDefinition" variant="primary" onAction={() => { void props.navigation.go('/workflows/new') }} />}
    status={<><ReadNotice error={s.error} busy={s.loading} retry={() => { void owner.load() }}>正在读取流程…</ReadNotice><CommandNotice command={s.command} recover={() => { void owner.recover() }} /></>}
    context={<UiContextPanel open={!!selected} title={selected?.title ?? '流程详情'} returnFocus={trigger} onClose={() => owner.select('')}>
      {selected && <><p>{selected.description || '暂无说明'}</p><p>{selected.builtin ? '程序内置 · 只读' : '自定义'} · 版本 {selected.headRevision}</p>
        <div className="w2-actions"><PageLink navigation={props.navigation} to={`/workflows/${encodeURIComponent(selected.id)}`} aria-label={semanticName('ui.open', selected.title)}>{selected.builtin ? '查看流程' : '编辑流程'}</PageLink>
          <PageLink navigation={props.navigation} to={{ path: '/requirements/new', query: { template: selected.id } }} aria-label={semanticName('workflow.newRequirement', selected.title)}>使用流程</PageLink>
          <UiActionButton actionKey="workflow.copyDefinition" target={selected.title} availability={locked ? { kind: 'disabled', reason: '请先恢复原操作。' } : { kind: 'enabled' }} onAction={() => { void owner.act(selected, 'copy') }} />
          {!selected.builtin && <UiActionButton actionKey="workflow.deleteDefinition" target={selected.title} variant="danger" availability={locked ? { kind: 'disabled', reason: '请先恢复原操作。' } : { kind: 'enabled' }} onAction={() => setDeleting(selected)} />}
        </div></>}
    </UiContextPanel>}>
    <form className="w2-module-toolbar" onSubmit={event => { event.preventDefault(); owner.search() }}>
      <UiField labelKey="field.query">{field => <Input {...field} aria-label="搜索流程" placeholder="搜索名称或说明" value={s.query} onChange={event => owner.query(event.target.value)} />}</UiField>
      <UiField labelKey="field.scope">{field => <Select {...field} aria-label="流程来源" value={s.kind} onChange={owner.kind} options={[{ value: 'ALL', label: '全部流程' }, { value: 'BUILTIN', label: '程序内置' }, { value: 'CUSTOM', label: '我的流程' }]} />}</UiField>
      <UiActionButton actionKey="ui.search" busy={s.loading} onAction={owner.search} />
    </form>
    <UiSelectableList items={s.rows} selectedKey={s.selectedId} labelKey="object.workflow" getKey={row => row.id} getName={row => row.title}
      onSelect={row => { focusSelection(trigger); owner.select(row.id === s.selectedId ? '' : row.id) }} renderItem={row => <div className="w2-record"><strong>{row.title}</strong><span>{row.description || '暂无说明'}</span><span>{row.builtin ? '程序内置' : '自定义'} · 版本 {row.headRevision}</span></div>} />
    {!s.loading && !s.error && !s.rows.length && <section className="w2-empty"><h2>{s.query ? '没有找到匹配的流程' : '这里还没有流程'}</h2><p>{s.query ? '试试其他关键词，或切换流程来源。' : '从自由任务或人工检查开始，保存自己的工作安排。'}</p></section>}
    {s.cursor && <UiActionButton actionKey="ui.loadMore" target="流程" busy={s.loading} onAction={() => { void owner.load(true) }} />}
    <UiConfirmDialog open={!!deleting} title="删除流程" confirmActionKey="workflow.deleteDefinition" target={deleting?.title} onCancel={() => setDeleting(null)}
      policy={locked ? { kind: 'block', reason: '原操作尚未确认，请先恢复。' } : { kind: 'allow' }} onConfirm={() => { if (deleting && !locked) { void owner.act(deleting, 'archive'); setDeleting(null) } }}>
      删除“{deleting?.title}”？已创建的任务与历史版本会保留。
    </UiConfirmDialog>
  </PageChrome>
}
