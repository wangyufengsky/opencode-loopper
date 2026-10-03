import { useMemo, useState } from 'react'
import { PlanDiff } from './PlanDiff'
import { UiConfirmDialog } from '@/foundation/components'
import { workflowCandidateStateLabel } from '@/utils/displayLabels'
import type { W2PageProps } from '@/pages/w2/shared'
import type { RequirementController } from './controller'
import { createCandidatesController } from './candidatesController'
import { Action, CommandNotice, Labeled, ReadNotice, RequirementContextPanel, useRequirementOwner } from './parts'

export function CandidatesPanel({ page, parent, controller, visible, onClose }: { page: W2PageProps; parent?: RequirementController; controller?: ReturnType<typeof createCandidatesController>; visible: boolean; onClose: () => void }) {
  const owner = useMemo(() => controller ?? createCandidatesController(parent?.id ?? 'req'), [controller, parent?.id]), s = useRequirementOwner(page, owner, parent), [confirm, setConfirm] = useState<{ revision: number; id: string; version: number } | null>(null)
  // Open is explicit user context; rendering a closed panel never starts a candidate read.
  if (parent) owner.bindParent(parent)
  const blocked = owner.locked() || !!parent && !parent.canStartWrite(owner), selected = s.selected
  return <RequirementContextPanel open={visible || owner.locked()} title="候选计划" onClose={onClose} readPolicy={owner.canLeave} discard={() => { owner.close(true); onClose() }}>
    {!s.open && visible && <Action action="ui.open" target="候选列表" onClick={owner.show} />}
    <Action action="ui.refresh" target="候选计划" busy={s.loading} disabled={owner.locked()} onClick={() => { owner.patch({ open: true }); void owner.list() }} />
    <Labeled label="候选状态"><select aria-label="候选状态" disabled={blocked} value={s.filter} onChange={event => owner.filter(event.target.value)}><option value="PENDING">待确认</option><option value="">全部历史</option></select></Labeled>
    <CommandNotice command={s.command} recover={() => { void owner.recover() }} /><ReadNotice error={s.error} loading={s.loading} retry={() => { void owner.list() }} />
    <ul className="w5-list">{s.rows.map(row => <li key={row.id}><strong>{row.sourceTitle}</strong><p>基于计划 {row.baseRevision} · {workflowCandidateStateLabel(row.state)}{row.stale && row.state === 'PENDING' ? ' · 基准已过期' : ''}</p><Action action="selection.select" target={row.sourceTitle} disabled={blocked} onClick={() => { void owner.read(row.id) }} /></li>)}</ul>
    {s.cursor && <Action action="ui.loadMore" target="候选计划" disabled={blocked} onClick={() => { void owner.list(true) }} />}
    {selected && <section><h3>{selected.sourceTitle} 提出的计划</h3><p>新增 {selected.changes.added.length} 个节点，修改 {selected.changes.changed.length} 个节点，移除 {selected.changes.removed.length} 个节点。</p>
      <PlanDiff before={selected.originalGraph} after={selected.graph} /><details><summary>完整计划变更</summary><ul>{selected.graph.nodes.map(node => <li key={node.id}>{node.title}：{node.task}</li>)}</ul><p>受保护节点 {selected.changes.protectedNodes.length} 个 · 受影响节点 {selected.changes.affected.length} 个</p></details>
      {selected.stale && selected.state === 'PENDING' && <p role="alert">当前计划已有新版本，请退回这份过期候选后重新规划。</p>}{!selected.sourceCompleted && selected.state === 'PENDING' && <p>来源节点尚未成功收尾，可以先查看草案。</p>}
      {selected.appliedRevision && <p>应用结果保存在计划版本 {selected.appliedRevision}；这里保留原始候选。</p>}{selected.decisionReason && <p>{selected.decisionReason}</p>}{selected.diagnostics.map((issue, index) => <p key={index}>{issue.message}</p>)}
      {parent && <Action action="workflow.reviewCandidate" disabled={blocked} onClick={() => { parent.review(selected); onClose() }} />}
      {selected.state === 'PENDING' && <><Labeled label="退回说明"><input maxLength={4000} value={s.reason} disabled={blocked} onChange={event => owner.changeReason(event.target.value)} /></Labeled><Action action="workflow.rejectCandidate" disabled={blocked} danger onClick={() => setConfirm({ revision: s.draftRevision, id: selected.id, version: selected.version })} /></>}
    </section>}
    <UiConfirmDialog open={!!confirm} title="退回候选计划" confirmActionKey="workflow.rejectCandidate" policy={blocked || confirm?.revision !== s.draftRevision || confirm.id !== selected?.id || confirm.version !== selected?.version ? { kind: 'block', reason: '原候选或退回说明已经变化，请重新确认。' } : { kind: 'allow' }} onCancel={() => setConfirm(null)} onConfirm={() => { setConfirm(null); void owner.reject() }}>当前生效计划和来源节点交付物将保留。</UiConfirmDialog>
  </RequirementContextPanel>
}
