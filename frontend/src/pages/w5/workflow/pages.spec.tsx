import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { workflowApi } from '@/api/workflow'
import { api, ApiError } from '@/api/client'
import { FoundationProvider } from '@/foundation/provider'
import { semanticName } from '@/foundation/semanticRegistry'
import { skins } from '@/themes/registry'
import { pageProps, foundationDOM, deferred, flush } from '@/pages/w2/workflow/page.test-support'
import { preset, template } from '@/components/workflow/workflowTestFixtures'
import type { WorkflowReceipt } from '@/types/domain'
import { WorkflowEditorPage } from './WorkflowEditorPage'
import { createWorkflowEditorController } from './editorController'
vi.mock('@/api/workflow', () => ({ workflowApi: { get: vi.fn(), create: vi.fn(), revise: vi.fn(), layout: vi.fn(), copy: vi.fn(), validate: vi.fn(), presets: vi.fn(), preset: vi.fn() } }))
const calls = vi.mocked(workflowApi), disposers: (() => void)[] = []
beforeEach(() => { vi.resetAllMocks(); foundationDOM(); calls.get.mockResolvedValue(template()); calls.validate.mockResolvedValue([]); calls.presets.mockResolvedValue({ items: [] }); vi.spyOn(api, 'getRoles').mockResolvedValue({ items: [] }) })
afterEach(() => { cleanup(); for (const dispose of disposers.splice(0)) dispose(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
const settle = () => act(async () => { await flush(); await flush() })
function frame(node: React.ReactNode, skin = skins[0]!) { return <FoundationProvider skin={skin} reducedMotion>{node}</FoundationProvider> }
async function mountEditor(strict = false) { const props = pageProps('/workflows/example'), owner = createWorkflowEditorController({ id: 'example' }); props.route = { ...props.route, params: { id: 'example' } }; const retained = new Map<object, () => void>(); props.lifecycle.retain = (key, dispose) => { retained.set(key, dispose) }; const element = frame(<WorkflowEditorPage {...props} controller={owner} />), view = render(strict ? <StrictMode>{element}</StrictMode> : element); disposers.push(() => { for (const dispose of retained.values()) dispose() }); await settle(); return { view, owner, props } }
it('real Editor uses actual React Flow and clean default canvas; selected context is stable/exclusive and Escape returns focus', async () => {
  const value = template(), second = { ...value.graph.nodes[0]!, id: 'other', title: '后续检查' }; value.graph.nodes.push(second); value.graph.edges.push({ id: 'e', from: 'review', to: 'other', outcome: null }); calls.get.mockResolvedValue(value)
  const { view, owner } = await mountEditor(); expect(view.getByRole('heading', { level: 1 }).textContent).toBe('交付流程'); expect(view.container.querySelector('[data-canvas-runtime="react"]')).toBeTruthy(); expect(view.container.querySelector<HTMLElement>('[data-foundation-component="context"]')!.hidden).toBe(true)
  fireEvent.click(view.container.querySelector('[data-node-id="review"]')!); await settle(); const context = view.getByRole('complementary'); fireEvent.click(view.container.querySelector('[data-node-id="review"]')!); await settle(); expect(view.getByRole('complementary')).toBe(context)
  await vi.waitFor(() => expect(view.container.querySelector('.workflow-wire-hit')).toBeTruthy()); fireEvent.click(view.container.querySelector('.workflow-wire-hit')!); await settle(); expect(owner.getSnapshot()).toMatchObject({ selected: '', selectedEdge: 'e' }); expect(view.getByText('连接条件')).toBeTruthy()
  fireEvent.keyDown(view.getByRole('complementary'), { key: 'Escape' }); await settle(); expect(owner.getSnapshot().context).toBe('none'); expect(view.container.querySelector('.workflow-canvas')).toBe(document.activeElement)
})
it('preset binds its original parent, adds measured real edge once and undo before save removes both', async () => {
  calls.presets.mockResolvedValue({ items: [preset()] }); calls.preset.mockResolvedValue(preset()); const { view, owner } = await mountEditor()
  fireEvent.click(view.getByRole('button', { name: semanticName('workflow.addNode') })); await settle(); fireEvent.click(view.getByRole('button', { name: semanticName('workflow.presets') })); await settle(); fireEvent.click(view.getByRole('button', { name: semanticName('selection.select', '资料分析') })); await settle()
  fireEvent.change(view.getByRole('combobox', { name: '参考资料' }), { target: { value: 'NODE|review|result' } }); fireEvent.click(view.getByRole('button', { name: semanticName('workflow.addNode', '资料分析') })); await settle(); expect(owner.getSnapshot().draft.graph.nodes).toHaveLength(2); expect(owner.getSnapshot().draft.graph.edges).toHaveLength(1); await vi.waitFor(() => expect(view.container.querySelector('.workflow-wire-hit')).toBeTruthy()); expect(view.container.querySelector('.workflow-presets')).toBeNull()
  fireEvent.click(view.getByRole('button', { name: semanticName('workflow.undo') })); await settle(); expect(owner.getSnapshot().draft.graph.nodes).toHaveLength(1); expect(view.container.querySelector('.workflow-wire-hit')).toBeNull(); expect(calls.create).not.toHaveBeenCalled(); expect(calls.revise).not.toHaveBeenCalled()
})
it('unknown save actually locks structured fields, preserves critical status after close and keeps same identity on three skins', async () => {
  const { view, owner, props } = await mountEditor(), pending = deferred<WorkflowReceipt>(); calls.revise.mockReturnValue(pending.promise)
  fireEvent.click(view.getByRole('button', { name: semanticName('workflow.settings') })); await settle(); fireEvent.change(view.getByRole('textbox', { name: '流程名称' }), { target: { value: '未决名称' } }); fireEvent.click(view.getByRole('button', { name: semanticName('workflow.save') })); await settle()
  expect(view.getByRole('textbox', { name: '流程名称' }).matches(':disabled')).toBe(true); const original = owner.originalSave()!, key = original.graphKey
  for (const skin of skins) { view.rerender(frame(<WorkflowEditorPage {...props} skin={skin} controller={owner} />, skin)); await settle(); expect(owner.originalSave()!.graphKey).toBe(key); expect(calls.revise).toHaveBeenCalledTimes(1) }
  pending.reject(new Error('回执未知')); await settle(); expect(view.container.querySelector('[data-operation-phase="UNKNOWN"]')).toBeTruthy(); fireEvent.keyDown(view.getByRole('complementary'), { key: 'Escape' }); await settle(); expect(owner.getSnapshot().context).toBe('none'); expect(view.container.querySelector('[data-operation-phase="UNKNOWN"]')).toBeTruthy(); expect(owner.canLeave().kind).toBe('BLOCK'); expect(owner.originalSave()).toBe(original)
})
it('definite partial rejection exposes the original layout retry while blocking fields, copy and navigation through three skins', async () => {
  const { view, owner, props } = await mountEditor()
  calls.revise.mockResolvedValue({ id: 'example', revision: 3, version: 4, layoutVersion: 4, state: 'ACTIVE' })
  calls.layout.mockRejectedValueOnce(new ApiError('布局版本冲突', 409)).mockRejectedValueOnce(new ApiError('原布局仍有冲突', 409)).mockResolvedValue({ id: 'example', revision: 3, version: 4, layoutVersion: 5, state: 'ACTIVE' })
  calls.get.mockResolvedValue(template({ title: '部分成功草稿', revision: 3, headRevision: 3, version: 4, layoutVersion: 5 }))
  fireEvent.click(view.getByRole('button', { name: semanticName('workflow.settings') })); await settle()
  fireEvent.change(view.getByRole('textbox', { name: '流程名称' }), { target: { value: '部分成功草稿' } })
  fireEvent.click(view.getByRole('button', { name: semanticName('workflow.save') })); await settle()
  const original = owner.originalSave()!, layoutCall = calls.layout.mock.calls[0]!
  for (const skin of skins) {
    view.rerender(frame(<WorkflowEditorPage {...props} skin={skin} controller={owner} />, skin)); await settle()
    const status = view.container.querySelector<HTMLElement>('[data-operation-phase="PARTIAL_REJECTION"]')!
    expect(status.textContent).toContain('图定义已接受，但布局已明确拒绝'); expect(status.textContent).not.toContain('尚未确认')
    expect(view.getByRole('textbox', { name: '流程名称' }).matches(':disabled')).toBe(true)
    expect(view.queryByRole('button', { name: semanticName('workflow.copyDefinition', '本地草稿') })).toBeNull()
    expect(owner.canLeave().kind).toBe('BLOCK'); expect(owner.originalSave()).toBe(original); expect(calls.revise).toHaveBeenCalledTimes(1); expect(calls.layout).toHaveBeenCalledTimes(1); expect(calls.get).toHaveBeenCalledTimes(1)
  }
  const retry = () => within(view.container.querySelector<HTMLElement>('[data-operation-phase="PARTIAL_REJECTION"]')!).getByRole('button', { name: semanticName('receipt.retryOriginal') })
  fireEvent.click(retry()); await settle()
  expect(owner.originalSave()).toBe(original); expect(owner.canLeave().kind).toBe('BLOCK'); expect(calls.layout.mock.calls[1]).toEqual(layoutCall); expect(calls.get).toHaveBeenCalledTimes(1)
  fireEvent.click(retry()); await settle()
  expect(calls.layout.mock.calls[2]).toEqual(layoutCall); expect(calls.revise).toHaveBeenCalledTimes(1); expect(calls.layout).toHaveBeenCalledTimes(3); expect(calls.get).toHaveBeenCalledTimes(2)
  expect(view.container.querySelector('[data-operation-phase="PARTIAL_REJECTION"]')).toBeNull(); expect(view.getByRole('textbox', { name: '流程名称' }).matches(':disabled')).toBe(false); expect(owner.canLeave().kind).toBe('ALLOW'); expect(view.getByText('流程已保存')).toBeTruthy()
})
it('public document declaration rename/type updates consumers atomically; two undo steps recover both changes', async () => {
  const value = template(); value.graph.inputs = [{ name: 'source', title: '资料', kind: 'TEXT', required: false }]; value.graph.nodes[0]!.inputs = [{ name: 'material', source: 'REQUIREMENT', sourceId: 'source', output: null, kind: 'TEXT', required: false }]; calls.get.mockResolvedValue(value)
  const { view, owner } = await mountEditor(); fireEvent.click(view.getByRole('button', { name: semanticName('workflow.settings') })); await settle(); fireEvent.change(view.getByRole('textbox', { name: '引用名称' }), { target: { value: 'documents' } }); fireEvent.change(view.getByRole('combobox', { name: '内容类型' }), { target: { value: 'DOCUMENT' } }); await settle()
  expect(owner.getSnapshot().draft.graph.nodes[0]!.inputs[0]).toMatchObject({ sourceId: 'documents', kind: 'DOCUMENT' }); await act(async () => { await owner.validate() }); expect(calls.validate.mock.calls.at(-1)![0]).toMatchObject({ inputs: [{ name: 'documents', kind: 'DOCUMENT' }] })
  fireEvent.click(view.getByRole('button', { name: semanticName('workflow.undo') })); fireEvent.click(view.getByRole('button', { name: semanticName('workflow.undo') })); await settle(); expect(owner.getSnapshot().draft.graph.inputs[0]).toMatchObject({ name: 'source', kind: 'TEXT' }); expect(owner.getSnapshot().draft.graph.nodes[0]!.inputs[0]).toMatchObject({ sourceId: 'source', kind: 'TEXT' })
})
it('node removal is a real guarded confirmation and preserves dependencies; ordinary context close retains draft', async () => {
  const { view, owner } = await mountEditor(); fireEvent.click(view.getByRole('button', { name: semanticName('workflow.addNode') })); await settle(); fireEvent.click(view.getByRole('button', { name: semanticName('workflow.addHuman') })); await settle(); expect(owner.getSnapshot().draft.graph.nodes).toHaveLength(2)
  const node = owner.getSnapshot().draft.graph.nodes[1]!; fireEvent.click(view.getByRole('button', { name: semanticName('workflow.deleteSelection', node.title) })); await settle(); expect(owner.getSnapshot().draft.graph.nodes).toHaveLength(2); fireEvent.click(within(view.getByRole('dialog')).getByRole('button', { name: semanticName('workflow.deleteSelection') })); await settle(); expect(owner.getSnapshot().draft.graph.nodes).toHaveLength(1)
  fireEvent.click(view.getByRole('button', { name: semanticName('workflow.undo') })); await settle(); expect(owner.getSnapshot().draft.graph.nodes).toHaveLength(2); fireEvent.click(view.getByRole('button', { name: semanticName('workflow.settings') })); fireEvent.change(view.getByRole('textbox', { name: '流程名称' }), { target: { value: '保留名称' } }); fireEvent.keyDown(view.getByRole('textbox', { name: '流程名称' }), { key: 'Escape' }); await settle(); fireEvent.click(view.getByRole('button', { name: semanticName('workflow.settings') })); await settle(); expect((view.getByRole('textbox', { name: '流程名称' }) as HTMLInputElement).value).toBe('保留名称'); expect(owner.canLeave().kind).toBe('CONFIRM_DISCARD')
})
it('offscreen locate/reveal is a presentation-only viewport update and keyboard node arrows make one real undoable layout', async () => {
  const value = template(); value.layout.positions.review = { x: 6000, y: 4000 }; calls.get.mockResolvedValue(value); const { view, owner } = await mountEditor(); const original = JSON.stringify(owner.getSnapshot().draft.layout)
  fireEvent.click(view.getByRole('button', { name: semanticName('workflow.tools') })); await settle(); fireEvent.click(view.getByRole('button', { name: semanticName('workflow.nodeList') })); await settle(); fireEvent.click(view.getByRole('button', { name: semanticName('ui.focus', '人工验收') })); await settle(); expect(owner.getSnapshot().dirty).toBe(false); expect(JSON.stringify(owner.getSnapshot().draft.layout)).toBe(original); expect(owner.getSnapshot().undoCount).toBe(0)
  const article = view.container.querySelector('[data-node-id="review"]')!; fireEvent.keyDown(article, { key: 'ArrowRight' }); await settle(); expect(owner.getSnapshot().draft.layout.positions.review!.x).toBe(6024); expect(owner.getSnapshot().undoCount).toBe(1); fireEvent.keyDown(article, { key: 'z', ctrlKey: true }); await settle(); expect(owner.getSnapshot().draft.layout.positions.review!.x).toBe(6000)
})
it('builtin context is read-only including document declarations but existing viewport can move without draft dirty', async () => {
  const value = template({ builtin: true }); value.graph.inputs = [{ name: 'documents', title: '需求原文', kind: 'DOCUMENT', required: false }]; calls.get.mockResolvedValue(value); const { view, owner } = await mountEditor()
  fireEvent.click(view.getByRole('button', { name: semanticName('workflow.settings') })); await settle(); expect(view.getByRole('textbox', { name: '流程名称' }).matches(':disabled')).toBe(true); expect((view.getByRole('combobox', { name: '内容类型' }) as HTMLSelectElement).value).toBe('DOCUMENT'); expect(view.container.querySelector('.workflow-port')).toBeNull()
  fireEvent.click(view.getByRole('button', { name: '放大画布' })); await settle(); expect(owner.getSnapshot().dirty).toBe(false); expect(owner.getSnapshot().draft.layout.zoom).toBeGreaterThan(1)
})
it('load failure under real root StrictMode cannot expose stale editable canvas or revive a retired owner', async () => {
  calls.get.mockRejectedValue(new Error('offline')); const { view, owner } = await mountEditor(true); expect(view.container.querySelector('[data-canvas-runtime]')).toBeNull(); expect(owner.getSnapshot().ready).toBe(false); expect(view.getByRole('button', { name: semanticName('workflow.save') }).matches(':disabled')).toBe(true); view.unmount(); for (const dispose of disposers.splice(0)) dispose(); const snapshot = JSON.stringify(owner.getSnapshot()); await owner.load(); expect(JSON.stringify(owner.getSnapshot())).toBe(snapshot)
})
