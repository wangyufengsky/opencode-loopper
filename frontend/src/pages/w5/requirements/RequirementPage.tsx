import { useMemo, useRef, useState } from 'react'
import { PageChrome, PageLink, SkinControl, type W2PageProps } from '@/pages/w2/shared'
import { UiConfirmDialog, UiContextPanel } from '@/foundation/components'
import { SemanticIcon, semanticName } from '@/foundation/semanticRegistry'
import { WorkflowCanvasView as ReactWorkflowCanvasView } from '@/react/workflow/WorkflowCanvasReact'
import type { WorkflowCanvasHandle } from '@/react/workflow/types'
import { WorkflowNodeEditor, WorkflowPresetPicker, WorkflowPublicInputs } from '@/pages/w5/workflow'
import { workflowReasonLabel, workflowStateLabel } from '@/utils/displayLabels'
import { autoLayout, clone, outcomeTitle } from '@/components/workflow/graph'
import type { WorkflowGraph, WorkflowLayout } from '@/types/domain'
import { createRequirementController } from './controller'
import { createCandidatesController } from './candidatesController'
import { createPublicationController } from './publicationController'
import { NodeRun } from './NodeRun'
import { PlanDiff } from './PlanDiff'
import { FinishPanel } from './Finish'
import { CandidatesPanel } from './Candidates'
import { PublicationPanel } from './Publication'
import { SaveTemplatePanel } from './SaveTemplate'
import { PublicValues } from './Inputs'
import { Action, CommandNotice, Labeled, ReadNotice, useProtectedOwner, useRequirementOwner, RequirementContextPanel } from './parts'
import './requirements.css'

export function RequirementPage(props: W2PageProps) {
  const id = String(props.route.params.id ?? ''), candidate = useMemo(() => createRequirementController(id), [id]), owner = useProtectedOwner(candidate), s = useRequirementOwner(props, owner)
  const candidates = useMemo(() => { const result = createCandidatesController(owner.id); result.bindParent(owner); return result }, [owner])
  const publication = useMemo(() => { const result = createPublicationController(owner.id, s.base?.revision ?? 0); result.bindParent(owner); return result }, [owner])
  const canvas = useRef<WorkflowCanvasHandle | undefined>(undefined), [publicationOpen, setPublicationOpen] = useState(false), [exported, setExported] = useState<{ revision: number; title: string; graph: WorkflowGraph; layout: WorkflowLayout } | null>(null)
  const [confirmation, setConfirmation] = useState<{ kind: 'remove' | 'edge' | 'discard' | 'reload' | 'cancel' | 'context'; key?: string; revision: number; contextRevision?: number; target?: () => void } | null>(null)
  const [expanded, setExpanded] = useState(false)
  const locked = owner.lockedForUi(), planning = owner.planning(), node = s.graph.nodes.find(value => value.id === s.selected), edge = s.graph.edges.find(value => value.id === s.edgeId), summary = s.execution?.execution.nodes.find(value => value.nodeKey === s.selected)
  const sourceReady = !s.proposal || s.proposal.sourceCompleted || !!s.execution?.execution.nodes.some(value => value.nodeKey === s.proposal!.nodeKey && value.latestAttemptId === s.proposal!.attemptId && value.state === 'SUCCEEDED')
  const dispatchVisible = ['PENDING_START', 'RUNNING', 'PAUSED', 'STALLED'].includes(owner.state() ?? '')
  const ask = (kind: NonNullable<typeof confirmation>['kind'], key?: string, target?: () => void) => setConfirmation({ kind, key, target, revision: s.draftRevision, contextRevision: (() => { const d = owner.contextDecision(); return d.kind === 'CONFIRM_DISCARD' ? d.draftRevision : undefined })() })
  const context = (target: () => void) => { const decision = owner.contextDecision(); if (decision.kind === 'ALLOW') target(); else if (decision.kind === 'CONFIRM_DISCARD') ask('context', undefined, target) }
  const dismiss = () => context(() => { const selected = s.selected; owner.dismiss(); canvas.current?.focus(selected) })
  function doConfirm() {
    if (!confirmation || s.draftRevision !== confirmation.revision || owner.locked()) return
    const operation = confirmation; setConfirmation(null)
    if (operation.kind === 'remove') owner.remove(operation.key!)
    if (operation.kind === 'edge') owner.removeEdge(operation.key!)
    if (operation.kind === 'discard') owner.discard()
    if (operation.kind === 'reload') { owner.discard(); void owner.load() }
    if (operation.kind === 'cancel') void owner.cancel()
    // Context drafts belong to their child owner; its panel provides discard.
    if (operation.kind === 'context') { const latest = owner.contextDecision(); if (latest.kind === 'BLOCK' || latest.kind === 'CONFIRM_DISCARD' && latest.draftRevision !== operation.contextRevision) return; if (owner.discardContextDrafts()) operation.target?.() }
  }
  function exportTemplate() { if (!s.base || locked || s.proposal || owner.contextDecision().kind !== 'ALLOW') return; owner.patch({ exporting: true }); setExported({ revision: s.base.revision, title: s.base.title, graph: clone(s.graph), layout: clone(s.layout) }) }
  return <PageChrome title={s.base?.title || '需求流程'} objectKey="object.requirement" actions={<><PageLink to="/requirements" navigation={props.navigation} aria-label={semanticName('nav.back', '需求任务')}><SemanticIcon semanticKey="nav.back" />需求任务</PageLink><SkinControl {...props} /><Action action="ui.refresh" target="执行状态" disabled={owner.lockedForUi()} onClick={() => { void owner.refresh() }} /><Action action="ui.refresh" target="需求计划" disabled={owner.locked()} onClick={() => { const decision = owner.canLeave(); if (decision.kind === 'CONFIRM_DISCARD') ask('reload'); else if (decision.kind === 'ALLOW') void owner.load() }} /></>} status={<>
    <ReadNotice error={s.error} loading={s.loading || s.refreshing} retry={() => { if (s.command.accepted || owner.locked()) void owner.recover(); else void owner.refresh() }} /><CommandNotice command={s.command} recover={() => { void owner.recover() }} />
    {s.modelError && <ReadNotice error={s.modelError} loading={s.modelLoading} retry={() => { void owner.initializeModel(true) }} />}{s.notice && <p role="status">{s.notice}</p>}{s.base && <p>{workflowStateLabel(owner.state() ?? s.base.state)} · 计划版本 {s.base.revision}{s.execution?.control.reasonCode && ` · ${workflowReasonLabel(s.execution.control.reasonCode)}`}{s.dirty && <span role="status"> · 计划有未保存的修改。</span>}</p>}
    {s.planStage && <div className="w5-pending-stages" role="status">{s.graphReceipt ? `计划结构已接受（版本 ${s.graphReceipt.revision}）。` : '计划结构尚待确认。'}{s.layoutReceipt ? `布局已接受（版本 ${s.layoutReceipt.layoutVersion}），正在读取原结果。` : s.planStage === 'layout' ? '布局尚待确认，恢复沿用原布局请求。' : ''}</div>}
    {s.proposal && <section className="workflow-proposal-banner" role="status"><p>{owner.proposalReadonly() ? '候选计划只读预览，当前生效计划保持不变。' : '候选计划尚未生效，请核对变更后显式应用。'}</p><Action action="ui.close" target="候选预览" disabled={locked} onClick={() => { if (owner.proposalReadonly()) owner.discard(); else ask('discard') }} /></section>}
  </>}>
    <div className="w5-requirement" data-w5-workspace="requirement">
      {!s.base ? <p>读取需求后可查看计划和执行记录。</p> : <>
        <div className="w5-main-scene" hidden={!!exported} inert={!!exported}><div className="w5-toolbar">
          <Action action="workflow.flowInputs" disabled={owner.contextDecision().kind === 'BLOCK'} onClick={() => context(() => owner.toggle('flow'))} />
          <Action action="workflow.nodeList" disabled={owner.contextDecision().kind === 'BLOCK'} onClick={() => context(() => owner.toggle('nodes'))} />
          {planning && <Action action="workflow.addNode" disabled={locked} onClick={() => context(() => owner.toggle('add'))} />}
          <Action action="workflow.moreTools" disabled={owner.contextDecision().kind === 'BLOCK'} onClick={() => context(() => owner.toggle('tools'))} />
          {planning && <><Action action="workflow.undo" disabled={locked || !s.undo.length} onClick={() => owner.history(true)} /><Action action="workflow.redo" disabled={locked || !s.redo.length} onClick={() => owner.history(false)} /><Action action={s.proposal ? 'workflow.applyCandidate' : 'workflow.savePlanning'} disabled={locked || owner.proposalReadonly() || !sourceReady} busy={s.command.busy} onClick={() => { void owner.save() }} /></>}
          {!planning && s.dirty && <Action action="workflow.saveLayout" disabled={locked} busy={s.command.busy} onClick={() => { void owner.save() }} />}
          {owner.state() === 'PLANNING' && <Action action="workflow.confirmPlan" disabled={locked || owner.graphDirty()} onClick={() => { void owner.confirm() }} />}
          {dispatchVisible && <Action action="workflow.continuous" disabled={!owner.executable() || s.modelLoading} onClick={() => { void owner.run('CONTINUOUS') }} />}
          {s.execution?.control.configured && ['ACTIVE', 'WAITING'].includes(s.execution.control.state) && <Action action="workflow.pause" disabled={locked} onClick={() => { void owner.pause() }} />}
        </div>
        <FinishPanel page={props} parent={owner} version={s.execution?.execution.version ?? s.base.version} state={owner.state() ?? s.base.state} visible={s.surface === 'tools'} />
        <div className="w5-workspace"><ReactWorkflowCanvasView graph={s.graph} layout={s.layout} selected={s.selected} selectedEdge={s.edgeId} readonly={!planning || locked} movable={!locked} connecting={s.connecting} roleNames={s.roleNames} states={Object.fromEntries((s.execution?.execution.nodes ?? []).map(value => [value.nodeKey, value.state]))} onReady={handle => { canvas.current = handle }} onSelect={key => { if (key === s.selected && !s.edgeId) return; context(() => { owner.select(key); canvas.current?.focus(key) }) }} onEdge={key => context(() => owner.select('', key))} onConnect={owner.join} onConnectPair={owner.joinPair} onLayout={owner.setLayout} onRemove={key => ask('remove', key)} onCancel={dismiss} />
          <RequirementContextPanel open={!!node || !!edge} title={node?.title || '节点连接'} expanded={expanded} onExpandedChange={setExpanded} readPolicy={owner.contextDecision} discard={() => { if (owner.discardContextDrafts()) dismiss() }} onClose={dismiss}>
            {node && <>{planning ? <WorkflowNodeEditor node={node} graph={s.graph} disabled={locked || owner.protectedKeys().has(node.id)} removeDisabled={locked || !owner.canRemove(node.id)} readonlyReason={owner.protectedKeys().has(node.id) ? '这个节点已执行或属于保留区域，任务和输入保持冻结。' : undefined} onChange={owner.patchNode} onRemove={() => ask('remove', node.id)} onRoleLabel={(key, value) => owner.patch({ roleNames: { ...owner.getSnapshot().roleNames, [key]: value } })} /> : <NodeRun page={props} parent={owner} node={node} summary={summary} version={s.execution?.execution.version ?? s.base.version} />}
              {dispatchVisible && <div className="w5-toolbar"><Action action="workflow.single" disabled={!owner.executable() || s.modelLoading && node.kind === 'WORK'} onClick={() => { void owner.run('SINGLE', node.id) }} /><Action action="workflow.until" disabled={!owner.executable() || s.modelLoading} onClick={() => { void owner.run('UNTIL', node.id) }} /></div>}</>}
            {edge && <><p>{s.graph.nodes.find(item => item.id === edge.from)?.title} → {s.graph.nodes.find(item => item.id === edge.to)?.title}</p><Labeled label="执行条件"><select disabled={locked || !planning || owner.protectedKeys().has(edge.to)} value={edge.outcome ?? ''} onChange={event => owner.edgeOutcome(edge.id, event.target.value || null)}><option value="">前置节点成功完成</option>{s.graph.nodes.find(item => item.id === edge.from)?.outcomes.map(value => <option key={value} value={value}>{outcomeTitle(s.graph.nodes.find(item => item.id === edge.from), value)}</option>)}</select></Labeled><Action action="workflow.deleteSelection" disabled={locked || !planning || owner.protectedKeys().has(edge.to)} danger onClick={() => ask('edge', edge.id)} /></>}
          </RequirementContextPanel>
          <RequirementContextPanel open={s.surface === 'flow'} title="需求与资料" readPolicy={owner.contextDecision} discard={() => { if (owner.discardContextDrafts()) dismiss() }} onClose={dismiss}>
            <p>{s.base.objective}</p>{s.execution && <PublicValues page={props} parent={owner} />}
            {s.graph.nodes.some(value => value.kind === 'WORK') && <section><Labeled label="执行模型"><select disabled={locked} value={s.model ? `${s.model.providerId}/${s.model.modelId}` : ''} onFocus={() => { if (!s.models.length) void owner.loadModels() }} onChange={event => { const value = s.models.find(model => model.id === event.target.value); if (value) owner.chooseModel({ providerId: value.provider, modelId: value.model, thinking: s.model?.thinking ?? null }) }}><option value="">选择模型</option>{s.model && !s.models.some(value => value.provider === s.model?.providerId && value.model === s.model?.modelId) && <option value={`${s.model.providerId}/${s.model.modelId}`}>{s.model.providerId} / {s.model.modelId}</option>}{s.models.map(value => <option key={value.id} value={value.id}>{value.label || value.id}</option>)}</select></Labeled><ReadNotice error={s.modelError} loading={s.modelLoading || s.modelCatalogLoading} retry={() => { void owner.initializeModel(true) }} /></section>}
            {planning && <WorkflowPublicInputs graph={s.graph} disabled={locked || owner.inputsFrozen()} onChange={graph => owner.change(graph)} />}
          </RequirementContextPanel>
          <UiContextPanel open={s.surface === 'nodes'} title="查找节点" onClose={dismiss}><ul className="w5-list">{s.graph.nodes.map(value => <li key={value.id}><strong>{value.title}</strong><p>{workflowStateLabel(s.execution?.execution.nodes.find(item => item.nodeKey === value.id)?.state ?? 'PENDING')}</p><Action action="selection.select" target={value.title} onClick={() => { if (owner.select(value.id)) { canvas.current?.reveal(value.id); canvas.current?.focus(value.id) } }} /></li>)}</ul></UiContextPanel>
          <UiContextPanel open={s.surface === 'add'} title="添加节点" onClose={dismiss}><Action action="workflow.addNode" target="自由只读任务" disabled={locked} onClick={() => owner.add('free.readonly')} /><p>自由只读任务</p><Action action="workflow.addNode" target="自由写入任务" disabled={locked} onClick={() => owner.add('free.write')} /><p>自由写入任务</p><Action action="workflow.addNode" target="人工检查" disabled={locked} onClick={() => owner.add('human')} /><p>人工检查</p><Action action="ui.open" target="预设工作模块" disabled={locked} onClick={() => owner.patch({ presets: true, surface: 'none' })} /></UiContextPanel>
          <RequirementContextPanel open={s.surface === 'tools'} title="更多操作" readPolicy={owner.contextDecision} discard={() => { if (owner.discardContextDrafts()) dismiss() }} onClose={dismiss}>
            {planning && <Action action="workflow.autoLayout" disabled={locked} onClick={() => owner.setLayout({ ...s.layout, positions: autoLayout(s.graph) })} />}
            <Action action="workflow.reviewCandidate" disabled={locked} onClick={() => { owner.patch({ surface: 'candidates' }); candidates.show() }} />
            {!owner.beforeStart() && <Action action="workflow.publicationSources" disabled={locked} onClick={() => { owner.patch({ surface: 'none' }); setPublicationOpen(true); publication.show() }} />}
            {!owner.beforeStart() && !owner.terminal() && <Action action="workflow.adjustPlan" disabled={locked || s.editing} onClick={() => { void owner.beginEdit() }} />}
            <Action action="workflow.saveTemplate" disabled={locked || !!s.proposal} onClick={exportTemplate} />{s.dirty && <Action action="ui.discardChanges" disabled={locked} onClick={() => ask('discard')} />}
            {owner.beforeStart() && <Action action="workflow.cancelRequirement" disabled={locked} danger onClick={() => ask('cancel')} />}
            {s.base && <PlanDiff before={s.proposal?.originalGraph ?? s.base.graph} after={s.graph} />}<details><summary>计划配置检查</summary>{s.base.diagnostics.map((issue, index) => <p key={index}>{issue.message}</p>)}</details>
          </RequirementContextPanel>
          <CandidatesPanel page={props} parent={owner} controller={candidates} visible={s.surface === 'candidates'} onClose={() => { owner.patch({ surface: 'none' }) }} />
          <PublicationPanel page={props} parent={owner} controller={publication} revision={s.base.revision} visible={publicationOpen} onClose={() => setPublicationOpen(false)} />
          {s.presets && <UiContextPanel open title="预设工作模块" onClose={() => owner.patch({ presets: false })}><WorkflowPresetPicker graph={s.graph} disabled={locked} onInsert={owner.addPreset} onClose={() => owner.patch({ presets: false })} /></UiContextPanel>}
        </div>
        {!!s.execution?.control.checkpoints.length && <section aria-label="节点检查点">{s.execution.control.checkpoints.map(point => <Labeled key={point.attemptId} label={`${s.graph.nodes.find(value => value.id === point.nodeKey)?.title ?? '节点'} 已完成，请确认后继续`}><input type="checkbox" checked={s.checked.includes(point.attemptId)} disabled={locked} onChange={event => owner.checkpoint(point.attemptId, event.target.checked)} /></Labeled>)}</section>}</div>
        {exported && <SaveTemplatePanel page={props} parent={owner} captured={exported} onClose={() => { owner.patch({ exporting: false }); setExported(null) }} />}
      </>}
    </div>
    <UiConfirmDialog open={!!confirmation} title={confirmation?.kind === 'cancel' ? '取消尚未执行的需求' : confirmation?.kind === 'remove' ? '移除节点和连接' : '确认处理当前计划'} confirmActionKey={confirmation?.kind === 'cancel' ? 'workflow.cancelRequirement' : confirmation?.kind === 'remove' || confirmation?.kind === 'edge' ? 'ui.delete' : 'ui.discardChanges'} policy={owner.locked() || confirmation?.revision !== s.draftRevision || confirmation?.kind === 'context' && (() => { const d = owner.contextDecision(); return d.kind === 'BLOCK' || d.kind === 'CONFIRM_DISCARD' && d.draftRevision !== confirmation.contextRevision })() ? { kind: 'block', reason: '原操作或草稿状态已经变化，请重新核对。' } : { kind: 'allow' }} onCancel={() => setConfirmation(null)} onConfirm={doConfirm}>已有执行和交付物历史保留。普通未保存草稿需要明确放弃。</UiConfirmDialog>
  </PageChrome>
}
