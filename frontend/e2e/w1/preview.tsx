/** Standalone W1 test bench. Mock content/transport, no product route or browser-history ownership. */
import { StrictMode, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { Input, Alert, Space } from 'antd'
import { skins, resolveSkin } from '../../src/themes/registry'
import { FoundationProvider } from '../../src/foundation/provider'
import { SemanticIcon, semanticLabel } from '../../src/foundation/semanticRegistry'
import { UiShell, UiActionButton, UiContextPanel, UiConfirmDialog, UiSelectableList, UiSelectableTable, UiField, UiDisclosure } from '../../src/foundation/components'
import { createSnapshotController } from '../../src/foundation/contracts/controller'
import { createOperationOwner, type OperationOwner } from '../../src/foundation/contracts/receipt'
import { createNavigationGate, decideLeave, toClosePolicy, type NavigationRequest } from '../../src/foundation/contracts/navigation'
import { createOwnerScope } from '../../src/foundation/contracts/scope'
import type { ResourceScope } from '../../src/foundation/contracts/types'

type Scenario = 'clean' | 'dirty' | 'pending' | 'unknown' | 'accepted' | 'file' | 'read-only' | 'malformed' | 'nested'
type View = { selected: string; draft: string; revision: number; dirty: boolean }
type Body = { requestKey?: string; title: string }
type Receipt = { id: string }
const records = [{ id: 'project-1', name: '工作流迁移验证', status: '进行中' }, { id: 'project-2', name: '知识库整理', status: '准备中' }]
const host = document.getElementById('w1-root')!
let root: Root | null = null, epoch = 0, scenario: Scenario = 'clean'
let skinId = 'spdb', reducedOverride: boolean | undefined
let writes = 0, reads = 0, deletes = 0, navigations = 0, projections = 0, nestedConfirmations = 0
let pendingResolve: ((receipt: Receipt) => void) | undefined
let holdNextWrite = false
let confirmation: ((answer: boolean) => void) | undefined
let inputFiles: readonly File[] = [], operation: OperationOwner<Body, Receipt>
const writeInputs: { requestKey?: string; body: Readonly<Body>; fileNames: string[]; sameFiles: boolean }[] = []
const owned = { listeners: 0, raf: 0, timers: 0, observers: 0, callbacks: 0 }
const retired: { key?: string; phase: string; fileNames: string[] }[] = []
const uiListeners = new Set<() => void>()
let notification = '', dialog: 'delete' | 'leave' | null = null, noticeVersion = 0, triggerVisible = true, innerDialog = false
function announce(message: string) { notification = message; noticeVersion++; for (const listener of uiListeners) listener() }
function attachResources(resources: ResourceScope) {
  const token = resources.capture()
  const listener = () => { if (token.isCurrent()) owned.callbacks++ }
  document.addEventListener('w1-instance-probe', listener); owned.listeners++
  resources.own(() => { document.removeEventListener('w1-instance-probe', listener); owned.listeners-- })
  const timer = setInterval(() => { if (token.isCurrent()) owned.callbacks++ }, 1000); owned.timers++
  resources.own(() => { clearInterval(timer); owned.timers-- })
  let frame = 0
  const tick = () => { if (token.isCurrent()) { owned.callbacks++; frame = requestAnimationFrame(tick) } }
  frame = requestAnimationFrame(tick); owned.raf++
  resources.own(() => { cancelAnimationFrame(frame); owned.raf-- })
  const observer = new ResizeObserver(() => { if (token.isCurrent()) owned.callbacks++ })
  observer.observe(host); owned.observers++
  resources.own(() => { observer.disconnect(); owned.observers-- })
}
let controller = makeController()
function makeController() {
  return createSnapshotController<View>({ identity: { domain: 'w1-test-bench', id: 'projection', epoch },
    initial: { selected: '', draft: '工作流迁移验证', revision: 0, dirty: false }, attachReads: attachResources,
    canLeave: view => decideLeave({ operations: [], dirty: view.dirty,
      unsentFiles: inputFiles.length, draftRevision: view.revision }),
  })
}
const navigationScope = createOwnerScope({ domain: 'w1-test-bench', id: 'navigation', epoch: 1 })
const gate = createNavigationGate({ scope: navigationScope,
  canLeave: (request: NavigationRequest) => controller.canLeave(request),
  confirmDiscard: () => new Promise<boolean>(resolve => { confirmation = resolve; dialog = 'leave'; announce('请确认是否放弃普通草稿或未发送文件。') }),
  navigate: async destination => { navigations++; announce(`模拟导航：${destination}；未改变浏览器 history。`); return true },
})
function makeOperation() {
  const hasKey = scenario !== 'read-only'
  return createOperationOwner<Body, Receipt>({ owner: controller.identity, label: '模拟保存',
    input: { endpoint: '/mock/w1/save', method: 'POST', requestKey: hasKey ? `mock-w1-${epoch}` : undefined,
      body: { ...(hasKey ? { requestKey: `mock-w1-${epoch}` } : {}), title: '冻结的模拟正文' }, files: inputFiles },
    capability: hasKey ? { kind: 'IDEMPOTENT_KEY' } : { kind: 'READ_ORIGINAL', readOriginal: async () => { reads++; return { kind: 'UNCONFIRMED' } } },
    write: async identity => {
      writes++; writeInputs.push({ requestKey: identity.requestKey, body: identity.body,
        fileNames: identity.files.map(file => file.name), sameFiles: identity.files.every((file, index) => file === inputFiles[index]) })
      if (scenario === 'pending' || holdNextWrite) { holdNextWrite = false; return new Promise<Receipt>(resolve => { pendingResolve = resolve }) }
      if ((scenario === 'unknown' && writes === 1) || scenario === 'read-only') throw new Error('模拟网络回执丢失')
      if (scenario === 'malformed') return new Date(0) as unknown as Receipt
      return { id: 'mock-receipt-1' }
    },
    read: async (_receipt, context) => { reads++; if (scenario === 'accepted' && reads === 1) throw new Error('模拟只读回填失败')
      context.apply(() => { projections++; inputFiles = []; const view = controller.getSnapshot(); controller.project({ ...view, dirty: false }) })
    }, handoffTarget: receipt => `/mock/${receipt.id}`,
  })
}
function settle(action: Promise<unknown>, operationFeedback = false) {
  const original = operation
  void action.then(() => {
    if (operation === original && !original.getSnapshot().retired && operationFeedback && original.getSnapshot().phase === 'SETTLED') announce('原操作核对完成（模拟数据）。')
  }).catch(error => {
    if (operation === original && !original.getSnapshot().retired) announce(error instanceof Error ? error.message : '模拟操作失败')
  })
}
function select(id: string) {
  const view = controller.getSnapshot()
  if (id !== view.selected && controller.canLeave().kind !== 'ALLOW') { announce('选择被原操作或草稿保护阻止。'); return }
  controller.project({ ...view, selected: id })
}
function closePanel(discard = false) {
  const view = controller.getSnapshot(), policy = controller.canLeave()
  if (policy.kind === 'BLOCK') { announce(policy.reason); return }
  if (!discard && policy.kind === 'CONFIRM_DISCARD') return
  if (discard) inputFiles = []
  controller.project({ ...view, selected: '', ...(discard ? { dirty: false, draft: '工作流迁移验证', revision: view.revision + 1 } : {}) })
}
function navigate() { settle(gate.navigate({ destination: '/mock/previous' }).then(result => announce(`离开结果：${result.kind}`))) }

function Bench() {
  const view = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const receipt = useSyncExternalStore(operation.subscribe, operation.getSnapshot)
  useSyncExternalStore(listener => { uiListeners.add(listener); return () => { uiListeners.delete(listener) } }, () => noticeVersion)
  const [skin, setSkin] = useState(skinId), [advanced, setAdvanced] = useState(false), [expanded, setExpanded] = useState(false)
  const trigger = useRef<HTMLButtonElement | null>(null)
  const innerTrigger = useRef<HTMLButtonElement | HTMLAnchorElement | null>(null)
  useEffect(() => controller.attachView(), [])
  const policy = controller.canLeave(), busy = receipt.busy
  const disabled = receipt.phase === 'SENDING' || receipt.phase === 'UNKNOWN' || receipt.phase === 'ACCEPTED_READBACK'
  const critical = disabled || view.dirty || inputFiles.length > 0
  return <FoundationProvider skin={resolveSkin(skin)} reducedMotion={reducedOverride}>
    <UiShell navigation={<><h2><SemanticIcon semanticKey="app.workspace" />Loopper</h2><p>基础工程验收</p><UiActionButton actionKey="nav.back" onAction={navigate} />
      <label htmlFor="skin">皮肤</label><select id="skin" value={skin} onChange={event => { skinId = event.target.value; setSkin(skinId) }}>
        {skins.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}</select></>}
      header={<><strong>React / Ant 基础组件</strong><span style={{ marginLeft: 16 }}>模拟数据 · 独立测试入口 · 非生产页面</span></>}
      status={<div data-testid="critical-status">{critical && <Alert type={disabled ? 'warning' : 'info'} showIcon
        icon={<SemanticIcon semanticKey={receipt.phase === 'UNKNOWN' ? 'status.unknown' : receipt.phase === 'SENDING' ? 'status.sending' : receipt.phase === 'ACCEPTED_READBACK' ? 'status.recovery' : 'status.dirty'} />}
        title={receipt.phase === 'UNKNOWN' ? '结果未知：保留原身份，等待显式恢复' : receipt.phase === 'SENDING' ? '操作进行中：不得离开或重发' : receipt.phase === 'ACCEPTED_READBACK' ? receipt.recovery.kind === 'BLOCKED' ? receipt.recovery.explanation : '已接受：只读恢复，不重复写入' : '未保存草稿或未发送文件：离开需确认'} />}
        {notification && <p role="status">{notification}</p>}</div>}
      context={<UiContextPanel open={!!view.selected} title="项目上下文" closePolicy={toClosePolicy(policy)} returnFocus={trigger}
        expanded={expanded} onExpandedChange={setExpanded} onClose={() => closePanel()} onConfirmClose={() => closePanel(true)}>
        <p>详细内容仅在选中后显示。关闭面板不会取消服务端命令。</p>
        <UiField id="draft-title" labelKey="field.title" required hint="普通草稿由单一 owner 保留。">
          {props => <Input {...props} value={view.draft} disabled={disabled} onChange={event => controller.project({ ...view, draft: event.target.value, dirty: true, revision: view.revision + 1 })} />}
        </UiField>
        <Space wrap><UiActionButton actionKey="ui.save" variant="primary" busy={busy} availability={receipt.phase !== 'IDLE' ? { kind: 'disabled', reason: '已有原操作，请使用相应恢复入口。' } : { kind: 'enabled' }} onAction={() => settle(operation.execute(), true)} />
          <UiActionButton actionKey="ui.delete" variant="danger" availability={policy.kind === 'BLOCK' ? { kind: 'disabled', reason: policy.reason } : { kind: 'enabled' }} onAction={() => { dialog = 'delete'; announce('删除需明确确认。') }} /></Space>
        {receipt.phase === 'UNKNOWN' && <UiActionButton actionKey={receipt.recovery.kind === 'RETRY_IDENTICAL' ? 'receipt.retryOriginal' : 'receipt.readOriginal'}
          availability={receipt.recovery.kind === 'BLOCKED' ? { kind: 'disabled', reason: receipt.recovery.explanation } : { kind: 'enabled' }}
          onAction={() => settle(receipt.recovery.kind === 'RETRY_IDENTICAL' ? operation.recoverWrite() : operation.readOriginal(), true)} />}
        {receipt.phase === 'ACCEPTED_READBACK' && <UiActionButton actionKey="receipt.readOriginal" availability={receipt.recovery.kind === 'BLOCKED' ? { kind: 'disabled', reason: receipt.recovery.explanation } : { kind: 'enabled' }} onAction={() => settle(operation.retryReadback(), true)} />}
        <UiDisclosure titleKey="section.advanced" open={advanced} onOpenChange={setAdvanced}><p>高级操作保留可发现入口，无隐式写入。</p></UiDisclosure>
      </UiContextPanel>}
      footer={<output data-testid="receipt-phase">{receipt.phase}</output>}>
      <div data-testid="main-background" onClick={event => { if (event.target === event.currentTarget && view.selected) closePanel() }}>
        <h1>最近项目</h1><p>选中一个对象查看上下文，主画面保持简洁。</p>
        {triggerVisible && <button ref={trigger} data-testid="select-trigger" type="button" onClick={() => select('project-1')}>选择工作流迁移验证</button>}
        <UiSelectableList items={records} getKey={item => item.id} getName={item => item.name} selectedKey={view.selected} onSelect={item => select(item.id)}
          renderItem={item => <><SemanticIcon semanticKey="object.project" /><span style={{ marginLeft: 12 }}>{item.name}</span></>} />
        <UiSelectableTable items={records} getKey={item => item.id} getName={item => item.name} selectedKey={view.selected} onSelect={item => select(item.id)}
          columns={[{ key: 'name', titleKey: 'field.title', render: item => item.name }, { key: 'status', titleKey: 'field.status', render: item => item.status }]} />
      </div>
    </UiShell>
    <UiConfirmDialog open={dialog === 'delete' || dialog === 'leave'} title={semanticLabel(dialog === 'delete' ? 'ui.delete' : 'ui.cancelEditing')}
      confirmActionKey={dialog === 'delete' ? 'ui.delete' : 'ui.discardChanges'} returnFocus={trigger}
      policy={policy.kind === 'BLOCK' ? { kind: 'block', reason: policy.reason } : { kind: 'allow' }}
      onCancel={() => { dialog = null; confirmation?.(false); confirmation = undefined; announce('已留在当前页面。') }}
      onConfirm={() => { if (policy.kind === 'BLOCK') return; if (dialog === 'delete') deletes++
        else { inputFiles = []; controller.project({ ...controller.getSnapshot(), dirty: false }); confirmation?.(true); confirmation = undefined }
        dialog = null; announce('明确确认完成（模拟数据）。') }}><p>确认只处理本次指定动作。所有内容为模拟数据。</p>
        {scenario === 'nested' && <><UiActionButton id="inner-dialog-trigger" actionKey="ui.open" buttonRef={innerTrigger}
          onAction={() => { innerDialog = true; announce('打开内层模拟确认。') }} />
          <UiConfirmDialog open={innerDialog} title="内层模拟确认" confirmActionKey="ui.discardChanges" returnFocus={innerTrigger}
            onCancel={() => { innerDialog = false; announce('关闭内层模拟确认。') }}
            onConfirm={() => { nestedConfirmations++; innerDialog = false; announce('内层模拟确认完成。') }}><p>仅验证嵌套portal与焦点，无业务写入。</p></UiConfirmDialog></>}
      </UiConfirmDialog>
  </FoundationProvider>
}

function mount() { if (root) return; root = createRoot(host); root.render(<StrictMode><Bench /></StrictMode>) }
function unmount() { root?.unmount(); root = null }
async function reset(next: Scenario = 'clean', skin = 'spdb', reduced?: boolean) {
  unmount()
  if (operation) { retired.push({ key: operation.identity.requestKey, phase: operation.getSnapshot().phase, fileNames: operation.identity.files.map(file => file.name) }); operation.retire(true) }
  controller.retire(true); confirmation?.(false); confirmation = undefined
  scenario = next; skinId = skin; reducedOverride = reduced; epoch++; writes = reads = deletes = navigations = projections = nestedConfirmations = 0
  pendingResolve = undefined; holdNextWrite = false; writeInputs.length = 0; notification = ''; noticeVersion++; dialog = null; innerDialog = false
  triggerVisible = true
  inputFiles = next === 'file' ? [new File(['mock-only'], '模拟.txt', { type: 'text/plain' })] : []
  controller = makeController(); operation = makeOperation()
  if (!controller.ownOperation(operation)) throw new Error('模拟操作未归属当前唯一controller')
  if (next !== 'clean') controller.project({ ...controller.getSnapshot(), selected: 'project-1', dirty: next === 'dirty' })
  if (['pending', 'unknown', 'accepted', 'read-only', 'malformed'].includes(next)) { settle(operation.execute(), true); await Promise.resolve(); await Promise.resolve() }
  mount()
}
const audit = {
  reset, mount, unmount, navigate,
  holdNextWrite: () => { if (operation.getSnapshot().phase !== 'IDLE') throw new Error('只有当前IDLE模拟操作可注入等待'); holdNextWrite = true },
  forceExit: () => { unmount(); controller.retire(true) },
  hideTrigger: () => { triggerVisible = false; announce('模拟原触发元素已移除。') },
  finishPending: () => pendingResolve?.({ id: 'mock-receipt-1' }),
  snapshot: () => ({ scenario, writes, reads, deletes, navigations, projections, nestedConfirmations, phase: operation.getSnapshot().phase,
    accepted: operation.getSnapshot().accepted, retiredOperation: operation.getSnapshot().retired,
    receipt: operation.getSnapshot().receipt, recovery: operation.getSnapshot().recovery,
    key: operation.identity.requestKey, body: operation.identity.body, fileNames: operation.identity.files.map(file => file.name),
    writeInputs: [...writeInputs], retired: [...retired], owned: { ...owned }, viewCount: controller.viewCount(), view: controller.getSnapshot(), policy: controller.canLeave(), mounted: !!root }),
  edit: (title: string) => controller.project({ ...controller.getSnapshot(), draft: title, dirty: true, revision: controller.getSnapshot().revision + 1 }),
  save: () => operation.execute(), recover: () => operation.recoverWrite(), read: () => operation.readOriginal(), retryRead: () => operation.retryReadback(),
}
declare global { interface Window { __foundationAudit: typeof audit } }
window.__foundationAudit = audit
void reset()
