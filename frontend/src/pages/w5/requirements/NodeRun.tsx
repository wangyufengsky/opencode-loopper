import { useEffect, useMemo, useState } from 'react'
import type { WorkflowNode, WorkflowNodeSummary } from '@/types/domain'
import { WorkflowCommandEvidence, WorkflowOutputReport } from '@/pages/w5/workflow'
import { RichDocument } from '@/pages/w3/shared/RichDocument'
import { ReadOnlyCode } from '@/pages/w3/shared/ReadOnlyCode'
import { UiConfirmDialog } from '@/foundation/components'
import { workflowReasonLabel, workflowStateLabel } from '@/utils/displayLabels'
import { outcomeTitle } from '@/components/workflow/graph'
import type { W2PageProps } from '@/pages/w2/shared'
import { createNodeController, type NodeController, type NodeBody } from './nodeController'
import type { RequirementController } from './controller'
import { Action, CommandNotice, Labeled, ReadNotice, useProtectedOwner, useRequirementOwner } from './parts'
import { AttemptFiles, AttemptKnowledge, AttemptPartial, FixedInputContent } from './Content'
import type { UiActionKey } from '@/foundation/semanticRegistry'

export function NodeRun({ page, parent, node, summary, version, controller }: { page: W2PageProps; parent?: RequirementController; node: WorkflowNode; summary?: WorkflowNodeSummary; version: number; controller?: NodeController }) {
  const candidate = useMemo(() => controller ?? createNodeController(parent?.id ?? 'req', { version, node, summary }), [controller, parent?.id, node.id])
  const owner = useProtectedOwner(candidate), s = useRequirementOwner(page, owner, parent), [stop, setStop] = useState(false), [attemptConfirm, setAttemptConfirm] = useState<{ id: string; revision: number } | null>(null)
  useEffect(() => { if (parent) owner.bindParent(parent); owner.updateContext({ version, node, summary }) }, [owner, parent, version, node, summary])
  const details = owner.details(), process = s.metadata?.commandState || s.metadata?.modelState, readonly = owner.locked() || s.loading || !s.readable || !!parent && !parent.canStartWrite(owner)
  const scope = (name: string, direction: 'inputs' | 'outputs' = 'inputs') => ({ requirement: owner.requirement, node: owner.nodeId, attempt: s.selected, name, direction })
  const isDocument = (value: unknown) => !!value && typeof value === 'object' && 'type' in value && ['DESIGN_DOCUMENT', 'ASSESSMENT_DOCUMENT', 'HISTORY_DOCUMENT', 'SNAPSHOT_DOCUMENT'].includes(String(value.type))
  const bodyActions: Array<[NodeBody, UiActionKey, boolean]> = [['input', 'workflow.fixedInput', true], ['result', 'workflow.deliverables', !!s.metadata?.deliveryAccepted], ['command', 'workflow.commandEvidence', !!s.metadata?.commandState], ['activity', 'workflow.modelActivity', !!s.metadata?.modelState], ['knowledge', 'workflow.knowledgeEvidence', !!s.metadata?.modelState], ['partial', 'workflow.partialReport', details.moduleId === 'system.review.snapshot' && s.metadata?.state === 'SUCCEEDED' && !!s.metadata.stopConfirmed]]
  return <section className="w5-section" aria-label="节点执行详情" data-node-owner={owner.nodeId}>
    <h2>{details.title}</h2><p>{workflowStateLabel(summary?.state ?? 'PENDING')}</p>
    <CommandNotice command={s.command} recover={() => { void owner.recover() }} /><ReadNotice error={s.error} loading={s.loading} retry={() => { void owner.refresh() }} />
    {!s.metadata ? <><p>节点尚未开始，可选择单步执行或运行至此处。</p><details open><summary>任务与完成标准</summary><p>{node.task}</p><p>{node.completion.criterion}</p></details></> : <>
      <Labeled label="执行尝试"><select aria-label="执行尝试" value={s.selected} disabled={owner.locked()} onChange={event => { const id = event.target.value, decision = owner.canLeave(); if (decision.kind === 'CONFIRM_DISCARD') setAttemptConfirm({ id, revision: s.draftRevision }); else if (decision.kind === 'ALLOW') void owner.choose(id) }}>{s.history.map(attempt => <option key={attempt.id} value={attempt.id}>第 {attempt.ordinal} 次 · {workflowStateLabel(attempt.state)}</option>)}</select></Labeled>
      {s.cursor && <Action action="ui.loadMore" target="执行尝试" onClick={() => { void owner.list(true) }} />}
      <p>第 {s.metadata.ordinal} 次 · {workflowStateLabel(s.metadata.state)} · {s.metadata.roleName || (details.kind === 'SYSTEM' ? '程序执行' : '人工处理')}{s.metadata.roleRevisionNumber ? ` · 角色版本 ${s.metadata.roleRevisionNumber}` : ''}</p>
      {s.metadata.queueState === 'QUEUED' && <p>等待工作目录可用。</p>}{(s.metadata.suspended || s.metadata.state === 'FAILED' && s.metadata.errorCode) && <p role="alert">{workflowReasonLabel(s.metadata.errorCode)}</p>}
      {s.metadata.deliveryAccepted && !owner.terminal() && <p>已收到交付物，正在等待执行与资源收尾。</p>}
      {owner.current() && process && !owner.terminal() && <div className="w5-toolbar">{s.metadata.suspended && <Action action={process === 'STOPPING' ? 'workflow.nodeStopCheck' : 'workflow.nodeResume'} disabled={readonly} onClick={() => { void owner.process('resume') }} />}{process !== 'STOPPING' ? <Action action="workflow.nodeStop" disabled={readonly} danger onClick={() => setStop(true)} /> : <span>等待停止确认</span>}</div>}
      <details><summary>本次任务与完成标准</summary><p>{details.task}</p><p>{details.completion.criterion}</p></details>
      <nav className="w5-toolbar" aria-label="节点内容">{bodyActions.filter(([_body, _action, visible]) => visible).map(([body, action]) => <Action key={body} action={action} onClick={() => owner.show(body)} />)}</nav>
      {s.open === 'input' && s.input && <section><h3>本次固定输入</h3><p>{s.input.objective}</p>{s.input.values.map(value => <article key={value.name}><h4>{value.name}</h4><p>{value.source === 'NODE' ? '来自上游已接受交付物' : '来自需求公共资料'}</p>{['CODE', 'DOCUMENT'].includes(value.kind) ? <AttemptFiles key={`${s.selected}/${value.name}`} page={page} scope={scope(value.name)} archive={isDocument(value.content)} changes={value.kind === 'CODE'} /> : value.reference ? <FixedInputContent page={page} scope={scope(value.name)} input={value} review={details.moduleId === 'system.review.dual' && value.kind === 'DECISION'} /> : value.kind === 'TEXT' ? <RichDocument content={String(value.content)} skin={page.skin} allowImages={false} /> : details.moduleId === 'system.review.dual' && value.kind === 'DECISION' ? <WorkflowOutputReport moduleId={details.moduleId} name="review" value={value} skin={page.skin} /> : <ReadOnlyCode content={JSON.stringify(value.content, null, 2)} language="json" />}</article>)}</section>}
      {s.open === 'result' && s.result && <section><h3>本次交付物</h3><p>{s.result.delivery.summary}</p>{s.result.delivery.outcome && <p>{outcomeTitle(details, s.result.delivery.outcome)}</p>}{Object.entries(s.result.delivery.outputs).map(([name, value]) => <article key={name}><h4>{details.outputs.find(field => field.name === name)?.title ?? '交付内容'}</h4>{['CODE', 'DOCUMENT'].includes(value.kind) ? <AttemptFiles page={page} scope={scope(name, 'outputs')} archive={isDocument(value.content)} changes={value.kind === 'CODE'} /> : <WorkflowOutputReport moduleId={details.moduleId} name={name} value={value} skin={page.skin} />}</article>)}</section>}
      {s.open === 'command' && <><Action action="ui.refresh" target="执行记录" onClick={() => { owner.patch({ commandEvidence: null }); void owner.loadBody('command') }} />{s.commandEvidence && <WorkflowCommandEvidence evidence={s.commandEvidence} terminal={owner.terminal()} repository={['system.repository.snapshot', 'system.git.history', 'system.review.snapshot'].includes(details.moduleId ?? '')} history={details.moduleId === 'system.git.history'} reviewSource={details.moduleId === 'system.review.snapshot'} skin={page.skin} />}</>}
      {s.open === 'knowledge' && <AttemptKnowledge page={page} scope={scope('knowledge')} />}
      {s.open === 'partial' && bodyActions.some(([kind, , visible]) => kind === 'partial' && visible) && <AttemptPartial page={page} scope={scope('partial')} />}
      {s.open === 'activity' && <section><ReadNotice error="" /><p>{s.activity?.detail}</p>{s.activity?.truncated && <p>仅展示最近的有界日志片段。</p>}<Action action="ui.refresh" target="模型日志" onClick={() => { void owner.loadBody('activity') }} />{s.activity?.parts.map(part => <article key={part.id}><strong>{part.label || '执行记录'}</strong><pre>{part.content}</pre></article>)}</section>}
      {owner.human() && <form onSubmit={event => { event.preventDefault(); void owner.complete() }}><h3>填写人工结果</h3><fieldset className="w5-fields" disabled={readonly}>
        <Labeled label="结果说明"><textarea value={s.humanSummary} rows={3} maxLength={4000} required onChange={event => owner.change({ humanSummary: event.target.value })} /></Labeled>
        {!!details.outcomes.length && <Labeled label="业务结果"><select value={s.outcome} required onChange={event => owner.change({ outcome: event.target.value })}><option value="">请选择</option>{details.outcomes.map(value => <option key={value} value={value}>{outcomeTitle(details, value)}</option>)}</select></Labeled>}
        {details.outputs.map(field => <Labeled key={field.name} label={field.title}><textarea value={s.values[field.name] ?? ''} required={field.required} rows={3} onChange={event => owner.change({ values: { ...s.values, [field.name]: event.target.value } })} /></Labeled>)}
      </fieldset><Action action="workflow.completeHuman" disabled={readonly} busy={s.command.busy} onClick={() => { void owner.complete() }} /></form>}
    </>}
    <UiConfirmDialog open={stop} title="停止当前节点" confirmActionKey="workflow.nodeStop" policy={readonly ? { kind: 'block', reason: '节点身份或许可已变化，请先核对原操作。' } : { kind: 'allow' }} onCancel={() => setStop(false)} onConfirm={() => { setStop(false); void owner.process('stop') }}>程序将先确认停止并保留已有成果，后续派发也会暂停。</UiConfirmDialog>
    <UiConfirmDialog open={!!attemptConfirm} title="切换执行尝试" confirmActionKey="ui.discardChanges" policy={owner.locked() || attemptConfirm?.revision !== s.draftRevision ? { kind: 'block', reason: '原结果未确认或草稿已变化，请保留当前输入。' } : { kind: 'allow' }} onCancel={() => setAttemptConfirm(null)} onConfirm={() => { if (attemptConfirm?.revision === s.draftRevision && !owner.locked()) void owner.choose(attemptConfirm.id, true); setAttemptConfirm(null) }}>当前人工输入尚未提交，切换后会放弃本地草稿。</UiConfirmDialog>
  </section>
}
