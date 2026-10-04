import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import { UiActionButton, UiConfirmDialog, UiContextPanel } from '@/foundation/components'
import { semanticName } from '@/foundation/semanticRegistry'
import { PageChrome, PageLink, SkinControl } from '@/pages/w2/shared'
import type { W2PageProps } from '@/pages/w2/shared/types'
import { useProtectedOwner, useW4Owner } from '@/pages/w4/shared/parts'
import { WorkflowCanvasView } from '@/react/workflow/WorkflowCanvasReact'
import type { WorkflowCanvasHandle } from '@/react/workflow/types'
import { outcomeTitle } from '@/components/workflow/graph'
import { createWorkflowEditorController, type EditorContext, type WorkflowEditorController } from './editorController'
import { WorkflowNodeEditor } from './WorkflowNodeEditor'
import { WorkflowPublicInputs } from './WorkflowPublicInputs'
import { WorkflowPresetPicker } from './WorkflowPresetPicker'
import './workflow-editor.css'

const contextTitles: Record<EditorContext, string> = { none: '', flow: '流程设置', node: '节点设置', edge: '连接设置', add: '添加节点', tools: '流程工具', nodes: '查找节点', presets: '预设工作模块' }
export function WorkflowEditorPage(props: W2PageProps & { controller?: WorkflowEditorController }) {
  const routeId = props.route.params.id, id = Array.isArray(routeId) ? routeId[0] : routeId
  const candidate = useMemo(() => props.controller ?? createWorkflowEditorController({ id, goAccepted: (to, permit) => props.navigation.goAccepted(to, permit) }), [id, props.controller, props.navigation])
  const owner = useProtectedOwner(candidate), s = useW4Owner(props, owner), canvas = useRef<WorkflowCanvasHandle | undefined>(undefined), trigger = useRef<HTMLElement | null>(null)
  const [confirm, setConfirm] = useState<{ kind: 'node' | 'edge' | 'copy' | 'reload'; id?: string; revision: number } | null>(null), [query, setQuery] = useState('')
  const node = s.draft.graph.nodes.find(n => n.id === s.selected), edge = s.draft.graph.edges.find(e => e.id === s.selectedEdge), parent = edge && s.draft.graph.nodes.find(n => n.id === edge.from), locked = owner.locked(), pending = !['IDLE', 'SETTLED'].includes(s.savePhase)
  const availability = locked ? { kind: 'disabled' as const, reason: s.base?.builtin ? '内置流程只读，请复制为自定义流程。' : pending ? '请先恢复原保存操作。' : '请等待读取或检查完成。' } : { kind: 'enabled' as const }
  function remember() { trigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null }
  function context(value: EditorContext) { remember(); owner.context(value) }
  const pendingCanvasFocus = useRef<{ node?: string } | null>(null)
  // Restore after the context panel has completed its own focus restoration.
  useLayoutEffect(() => { if (s.context === 'none' && pendingCanvasFocus.current) { const value = pendingCanvasFocus.current; pendingCanvasFocus.current = null; canvas.current?.focus(value.node) } }, [s.context])
  function dismiss() { pendingCanvasFocus.current = { node: s.selected || undefined }; owner.dismiss(); if (s.context === 'none') { pendingCanvasFocus.current = null; canvas.current?.focus(s.selected || undefined) } }
  function locate(id: string) { owner.select(id); canvas.current?.reveal(id); canvas.current?.focus(id) }
  function ask(kind: 'node' | 'edge' | 'copy' | 'reload', selected?: string) { remember(); setConfirm({ kind, id: selected, revision: owner.getSnapshot().draftRevision }) }
  const confirmationBlocked = !confirm || s.saveBusy || pending || s.validating || s.loading || s.draftRevision !== confirm.revision || (confirm.kind === 'node' || confirm.kind === 'edge') && locked
  function confirmAction() {
    if (!confirm || confirmationBlocked) return
    if (confirm.kind === 'node' && confirm.id) owner.remove(confirm.id)
    if (confirm.kind === 'edge' && confirm.id) owner.removeEdge(confirm.id)
    if (confirm.kind === 'copy') void owner.save(true)
    if (confirm.kind === 'reload') void owner.load()
    setConfirm(null)
  }
  function keyboard(event: React.KeyboardEvent<HTMLElement>) {
    const target = event.target as HTMLElement
    if (event.key === 'Escape') { dismiss(); return }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z' && !target.closest('input, textarea, select, [contenteditable="true"]')) { event.preventDefault(); owner.history(!event.shiftKey) }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'y' && !target.closest('input, textarea, select, [contenteditable="true"]')) { event.preventDefault(); owner.history(false) }
  }
  return <PageChrome title={s.draft.title} objectKey="object.workflow" actions={<><PageLink navigation={props.navigation} to="/workflows" aria-label={semanticName('nav.back', '流程库')}>返回流程库</PageLink><SkinControl {...props} /><UiActionButton actionKey={s.base?.builtin ? 'workflow.copyDefinition' : pending ? s.layoutAccepted ? 'receipt.readOriginal' : 'receipt.retryOriginal' : 'workflow.save'} variant="primary" busy={s.saveBusy} availability={!s.ready || s.loading || s.validating ? { kind: 'disabled', reason: '请先读取流程。' } : { kind: 'enabled' }} onAction={() => { void owner.save() }} /></>}
    status={<><p data-workflow-save-state>{s.base?.builtin ? '程序内置 · 只读' : pending ? '保存未结清' : s.dirty ? '未保存' : '已保存'}{s.base && ` · 图版本 ${s.base.revision} · 布局版本 ${s.base.layoutVersion}`}</p>
      {s.error && <div role="alert" className="w3-critical">{s.error}<UiActionButton actionKey="ui.refresh" availability={pending ? { kind: 'disabled', reason: '请先恢复原保存操作。' } : { kind: 'enabled' }} onAction={() => s.dirty ? ask('reload') : void owner.load()} /></div>}
      {(pending || s.saveError) && <section className="w3-critical" role={s.savePhase === 'UNKNOWN' || s.saveError ? 'alert' : 'status'} data-operation-phase={s.savePhase}><p>{s.savePhase === 'SENDING' ? '保存正在发送，请等待结果并保留原操作。' : s.savePhase === 'UNKNOWN' ? '保存结果尚未确认，请重试原保存操作。' : s.savePhase === 'ACCEPTED_READBACK' ? '写入已接受，请继续读取原结果或完成原页面交接。' : s.savePhase === 'PARTIAL_REJECTION' ? '图定义已接受，但布局已明确拒绝。请按原请求键和原版本重试布局保存；仍有冲突时保留此操作。' : '原保存已明确拒绝，本地草稿已保留。'}{s.saveError}</p>{s.graphAccepted && <p>图定义已接受。{s.layoutAccepted ? '布局也已接受，后续恢复只读取原结果。' : '后续保存沿用原图回执和布局请求键。'}</p>}{pending && <UiActionButton actionKey={s.layoutAccepted ? 'receipt.readOriginal' : 'receipt.retryOriginal'} busy={s.saveBusy} onAction={() => { void owner.recover() }} />}{s.conflict && !pending && <UiActionButton actionKey="workflow.copyDefinition" target="本地草稿" onAction={() => ask('copy')} />}</section>}
      {s.notice && <p role="status">{s.notice}</p>}{s.loading && <p role="status">读取流程…</p>}{s.validating && <p role="status">检查流程…</p>}
      {!!s.diagnostics.length && <details className="workflow-diagnostics" open><summary>流程检查提示</summary><ul>{s.diagnostics.map((item, i) => <li key={i}>{item.message}</li>)}</ul></details>}
    </>}
    context={<UiContextPanel open={s.context !== 'none'} title={contextTitles[s.context]} returnFocus={trigger} onClose={dismiss}>
      <div className="workflow-react-context" onKeyDown={keyboard}>
        {s.context === 'flow' && <><fieldset disabled={locked} className="workflow-fields"><label>流程名称<input value={s.draft.title} maxLength={120} onChange={e => owner.title(e.target.value)} /></label><label>流程说明<textarea value={s.draft.description} maxLength={4000} rows={3} onChange={e => owner.description(e.target.value)} /></label></fieldset><WorkflowPublicInputs graph={s.draft.graph} disabled={locked} onChange={owner.graph} /></>}
        {s.context === 'node' && node && <WorkflowNodeEditor node={node} graph={s.draft.graph} disabled={locked} readonlyReason={s.base?.builtin ? '内置流程只读，请复制后调整。' : pending ? '请先恢复原保存操作。' : undefined} onChange={owner.patchNode} onRemove={() => ask('node', node.id)} onRoleLabel={owner.roleLabel} />}
        {s.context === 'edge' && edge && <fieldset disabled={locked} className="workflow-fields"><legend>连接条件</legend><p>{parent?.title} → {s.draft.graph.nodes.find(n => n.id === edge.to)?.title}</p><label>何时继续<select value={edge.outcome || ''} onChange={e => owner.edgeOutcome(edge.id, e.target.value)}><option value="">完成后继续</option>{parent?.outcomes.map(outcome => <option key={outcome} value={outcome}>{outcomeTitle(parent, outcome)}</option>)}</select></label><UiActionButton actionKey="workflow.deleteSelection" target="连接" variant="danger" onAction={() => ask('edge', edge.id)} /></fieldset>}
        {s.context === 'add' && <section className="workflow-add-menu"><p>添加工作节点，或选择含标准配置的预设模块。</p><UiActionButton actionKey="workflow.addReadonly" availability={availability} onAction={() => { owner.add('free.readonly'); const n = owner.getSnapshot().selected; if (n) locate(n) }} /><UiActionButton actionKey="workflow.addWrite" availability={availability} onAction={() => { owner.add('free.write'); const n = owner.getSnapshot().selected; if (n) locate(n) }} /><UiActionButton actionKey="workflow.addHuman" availability={availability} onAction={() => { owner.add('human'); const n = owner.getSnapshot().selected; if (n) locate(n) }} /><UiActionButton actionKey="workflow.presets" availability={availability} onAction={() => owner.context('presets')} /></section>}
        {s.context === 'presets' && <WorkflowPresetPicker graph={s.draft.graph} disabled={locked} onClose={dismiss} onInsert={(graph, node) => { owner.insert(graph, node); locate(node.id) }} />}
        {s.context === 'tools' && <section className="workflow-tools"><UiActionButton actionKey="workflow.nodeList" onAction={() => owner.context('nodes')} /><UiActionButton actionKey="workflow.validate" availability={availability} busy={s.validating} onAction={() => { void owner.validate() }} /><UiActionButton actionKey="workflow.autoLayout" availability={availability} onAction={owner.autoLayout} /><UiActionButton actionKey="ui.refresh" target="流程" availability={pending ? { kind: 'disabled', reason: '请先恢复原保存操作。' } : { kind: 'enabled' }} onAction={() => s.dirty ? ask('reload') : void owner.load()} /><p>拖动节点调整位置；Ctrl + 滚轮缩放，空白区域拖动平移。方向键移动所选节点，Delete 删除，Esc 取消选择或连接。</p></section>}
        {s.context === 'nodes' && <section className="workflow-node-list"><input aria-label="搜索节点" placeholder="搜索名称或任务说明" value={query} onChange={e => setQuery(e.target.value)} />{s.draft.graph.nodes.filter(node => `${node.title} ${node.task}`.toLowerCase().includes(query.toLowerCase())).map(node => <button key={node.id} type="button" aria-label={semanticName('ui.focus', node.title)} onClick={() => locate(node.id)}><strong>{node.title}</strong><span>{node.task}</span></button>)}</section>}
      </div>
    </UiContextPanel>}>
    <section className="workflow-editor-react" data-workflow-editor="react" onKeyDown={keyboard}><div className="workflow-editor-toolbar w2-actions"><UiActionButton actionKey="workflow.addNode" availability={availability} expanded={s.context === 'add'} onAction={() => context('add')} /><UiActionButton actionKey="workflow.settings" expanded={s.context === 'flow'} onAction={() => context('flow')} /><UiActionButton actionKey="workflow.undo" availability={s.undoCount ? availability : { kind: 'disabled', reason: '当前没有可撤销的修改。' }} onAction={() => owner.history(true)} /><UiActionButton actionKey="workflow.redo" availability={s.redoCount ? availability : { kind: 'disabled', reason: '当前没有可重做的修改。' }} onAction={() => owner.history(false)} /><UiActionButton actionKey="workflow.tools" expanded={s.context === 'tools'} onAction={() => context('tools')} /></div>
      {s.ready && <div className="workflow-editor-canvas"><WorkflowCanvasView graph={s.draft.graph} layout={s.draft.layout} selected={s.selected} selectedEdge={s.selectedEdge} readonly={locked} connecting={s.connecting} roleNames={s.roleNames} onSelect={owner.select} onEdge={owner.selectEdge} onConnect={owner.join} onConnectPair={owner.joinPair} onLayout={owner.layout} onRemove={id => ask('node', id)} onCancel={dismiss} onReady={handle => { canvas.current = handle }} /></div>}
    </section>
    <UiConfirmDialog open={!!confirm} title={confirm?.kind === 'node' ? '删除节点' : confirm?.kind === 'edge' ? '删除连接' : confirm?.kind === 'reload' ? '重新加载流程' : '草稿另存为新流程'} confirmActionKey={confirm?.kind === 'copy' ? 'workflow.copyDefinition' : confirm?.kind === 'reload' ? 'ui.discardChanges' : 'workflow.deleteSelection'} onCancel={() => setConfirm(null)} returnFocus={trigger} policy={confirmationBlocked ? { kind: 'block', reason: '当前操作或草稿已变化，请关闭后重新确认。' } : { kind: 'allow' }} onConfirm={confirmAction}>
      {confirm?.kind === 'node' ? '删除这个节点及其连接？已绑定它的输入需要先调整。' : confirm?.kind === 'edge' ? '删除这条连接？' : confirm?.kind === 'reload' ? '重新加载会放弃未保存的本地修改。' : '保留本地草稿并创建新流程？原流程与历史版本保持可读取。'}
    </UiConfirmDialog>
  </PageChrome>
}
