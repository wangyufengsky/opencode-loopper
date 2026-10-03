import { useEffect, useMemo, useState } from 'react'
import type { W2PageProps } from '@/pages/w2/shared'
import { PageLink } from '@/pages/w2/shared'
import { UiConfirmDialog, UiContextPanel } from '@/foundation/components'
import { WorkflowCanvasView as ReactWorkflowCanvasView } from '@/react/workflow/WorkflowCanvasReact'
import { WorkflowNodeEditor } from '@/pages/w5/workflow'
import { autoLayout } from '@/components/workflow/graph'
import type { WorkflowGraph, WorkflowLayout } from '@/types/domain'
import type { RequirementController } from './controller'
import { createSaveTemplateController } from './templateController'
import { Action, CommandNotice, Labeled, ReadNotice, useRequirementOwner } from './parts'

export function SaveTemplatePanel({ page, parent, captured, onClose }: { page: W2PageProps; parent: RequirementController; captured: { revision: number; title: string; graph: WorkflowGraph; layout: WorkflowLayout }; onClose: () => void }) {
  const owner = useMemo(() => createSaveTemplateController(parent.id, captured), [parent, captured]), s = useRequirementOwner(page, owner, parent)
  useEffect(() => { parent.setExportOwner(owner); owner.bindParent(parent); return () => parent.setExportOwner(undefined) }, [owner, parent])
  useEffect(() => { if (s.saved) parent.patch({ exporting: false }) }, [s.saved, parent])
  const [discardRevision, setDiscardRevision] = useState<number | null>(null)
  const requestClose = () => { const policy = owner.canLeave(); if (policy.kind === 'ALLOW') onClose(); else if (policy.kind === 'CONFIRM_DISCARD') setDiscardRevision(policy.draftRevision) }
  const node = s.preview?.graph.nodes.find(value => value.id === s.selected), edge = s.preview?.graph.edges.find(value => value.id === s.edge)
  return <section className="w5-section" role="dialog" aria-modal="false" aria-label="另存为流程模板">
    <header className="w5-toolbar"><h2>另存为流程模板</h2><Action action="nav.back" disabled={owner.locked()} onClick={requestClose} /></header>
    <CommandNotice command={s.command} recover={() => { void owner.recover() }} /><ReadNotice error={s.error} loading={s.loading} retry={() => { void owner.read() }} />
    {s.saved ? <section role="status">已保存为自定义流程。<PageLink to={`/workflows/${encodeURIComponent(s.saved.id)}`} navigation={page.navigation}>打开新流程</PageLink><p>原任务及画布中的未保存修改仍然保留。</p></section> : <>
      <fieldset className="w5-fields" disabled={owner.locked()}><Labeled label="模板名称"><input maxLength={120} required value={s.title} onChange={event => owner.change({ title: event.target.value })} /></Labeled><Labeled label="模板说明"><textarea maxLength={4000} rows={2} value={s.description} onChange={event => owner.change({ description: event.target.value })} /></Labeled><Labeled label="保存结构"><select value={s.mode} onChange={event => owner.mode(event.target.value as 'CURRENT' | 'INITIAL')}><option value="CURRENT">保留当前步骤（默认）</option><option value="INITIAL" disabled={!s.initialAvailable}>使用首次执行时的结构</option></select></Labeled></fieldset>
      <p>{s.mode === 'CURRENT' ? '保存此刻画布上的步骤，包括未保存的调整。节点中的具体路径、章节和任务说明会保留。' : '使用本任务第一次开始节点执行时的计划版本，保留原有动态分批能力；之后新增或修改的节点不纳入本次模板。'}</p>{!s.initialAvailable && <p>任务尚未执行，目前可保存当前步骤。</p>}
      {s.preview && <>{!!s.preview.fixedPlanningNodes.length && <p>已展开的程序规划将改为人工确认固定步骤，避免下次重复分批：{s.preview.fixedPlanningNodes.join('、')}。</p>}<div className="w5-save-preview"><ReactWorkflowCanvasView graph={s.preview.graph} layout={{ ...s.preview.layout, positions: { ...autoLayout(s.preview.graph), ...s.preview.layout.positions } }} readonly selected={s.selected} selectedEdge={s.edge} roleNames={s.roleNames} onSelect={id => owner.select(id)} onEdge={id => owner.select('', id)} onConnect={() => {}} onLayout={() => {}} onRemove={() => {}} onCancel={() => owner.select('')} />
        <UiContextPanel open={!!node || !!edge} title={node ? '预览节点' : '预览连接'} onClose={() => owner.select('')}>{node ? <WorkflowNodeEditor node={node} graph={s.preview.graph} disabled removeDisabled readonlyReason="这是将要保存的模板。保存后可打开新流程继续编辑。" onChange={() => {}} onRemove={() => {}} onRoleLabel={(id, label) => owner.patch({ roleNames: { ...owner.getSnapshot().roleNames, [id]: label } })} /> : edge && <p>{s.preview.graph.nodes.find(item => item.id === edge.from)?.title} → {s.preview.graph.nodes.find(item => item.id === edge.to)?.title}</p>}</UiContextPanel></div>
        {!!s.preview.graph.inputs.length && <p>新任务需要提供：{s.preview.graph.inputs.map(value => value.title).join('、')}。</p>}{!!s.preview.diagnostics.length && <details><summary>模板还有 {s.preview.diagnostics.length} 项配置待补充</summary>{s.preview.diagnostics.map((issue, index) => <p key={index}>{issue.message}</p>)}</details>}</>}
      <Action action="workflow.saveTemplateConfirm" disabled={!s.preview || s.loading || owner.locked() || !s.title.trim()} busy={s.command.busy} onClick={() => { void owner.save() }} />
    </>}
    {s.dirty && !owner.locked() && <Action action="ui.discardChanges" target="模板草稿" onClick={requestClose} />}
    <UiConfirmDialog open={discardRevision !== null} title="放弃模板草稿？" confirmActionKey="ui.discardChanges" policy={owner.locked() || discardRevision !== s.draftRevision ? { kind: 'block', reason: '原操作或草稿已经变化，请重新核对。' } : { kind: 'allow' }} onCancel={() => setDiscardRevision(null)} onConfirm={() => { if (!owner.locked() && discardRevision === s.draftRevision) { owner.discard(); onClose() }; setDiscardRevision(null) }}>任务画布中的计划草稿保持原状。</UiConfirmDialog>
  </section>
}
