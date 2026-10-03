import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { workflowApi } from '@/api/workflow'
import { ApiError } from '@/api/client'
import WorkflowEditorView from './WorkflowEditorView.vue'
import { preset, template } from '@/components/workflow/workflowTestFixtures'
vi.mock('@/api/workflow', () => ({ workflowApi: { get: vi.fn(), create: vi.fn(), revise: vi.fn(), layout: vi.fn(), validate: vi.fn(), presets: vi.fn(), preset: vi.fn() } }))
const api = vi.mocked(workflowApi)
let wrapper: VueWrapper | undefined
beforeEach(() => { vi.resetAllMocks(); vi.spyOn(window, 'confirm').mockReturnValue(true); api.get.mockResolvedValue(template()); api.validate.mockResolvedValue([]) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })
async function render(path = '/workflows/example') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/workflows', component: { template: '<div />' } }, { path: '/workflows/new', component: WorkflowEditorView }, { path: '/workflows/:id', component: WorkflowEditorView }] })
  await router.push(path); await router.isReady(); wrapper = mount(RouterView, { global: { plugins: [router], stubs: { Icon: true, WorkflowRolePicker: true } } }); await flushPromises(); return router
}
const button = (label: string) => wrapper!.findAll('button').find(node => node.text() === label || node.attributes('aria-label') === label)!
async function clickButton(label: string) {
  if (!button(label)) {
    const add = ['预设工作模块', '只读分析', '文件工作', '人工检查', '人工检查由你补充结果并确认'].includes(label)
    await button(add ? '添加节点' : '更多工具').trigger('click')
  }
  await button(label).trigger('click')
}
async function waitForConnections(count: number) {
  expect(wrapper!.find('[data-canvas-runtime="react"]').exists()).toBe(true)
  // Real React Flow measures handles before rendering their edges.
  await vi.waitFor(() => expect(wrapper!.findAll('.workflow-wire')).toHaveLength(count))
}
describe('workflow authoring', () => {
  it('adds a preset with its bound parent and supports undo before any save', async () => {
    api.presets.mockResolvedValue({ items: [preset()], nextCursor: null }); api.preset.mockResolvedValue(preset())
    await render(); await clickButton('预设工作模块'); await flushPromises()
    await wrapper!.get('.workflow-preset-list button').trigger('click'); await flushPromises()
    await wrapper!.get('.workflow-preset-detail select').setValue('NODE|review|result'); await clickButton('添加到画布')
    expect(wrapper!.findAll('.workflow-node')).toHaveLength(2); await waitForConnections(1); expect(wrapper!.find('.workflow-presets').exists()).toBe(false)
    expect(api.create).not.toHaveBeenCalled(); expect(api.revise).not.toHaveBeenCalled()
    await wrapper!.get('[aria-label="撤销修改"]').trigger('click'); expect(wrapper!.findAll('.workflow-node')).toHaveLength(1); expect(wrapper!.findAll('.workflow-wire')).toHaveLength(0)
  })
  it('edits nodes, removes them with confirmation and supports undo', async () => {
    await render(); await clickButton('人工检查由你补充结果并确认'); expect(wrapper!.findAll('.workflow-node')).toHaveLength(2)
    await clickButton('删除节点'); expect(window.confirm).toHaveBeenCalled(); expect(wrapper!.findAll('.workflow-node')).toHaveLength(1)
    await wrapper!.get('[aria-label="撤销修改"]').trigger('click'); expect(wrapper!.findAll('.workflow-node')).toHaveLength(2)
  })
  it('freezes an uncertain save and retries the same command', async () => {
    await render(); await clickButton('流程设置'); await wrapper!.get('input').setValue('改名后的流程')
    api.revise.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({ id: 'example', revision: 3, version: 4, layoutVersion: 4, state: 'ACTIVE' }); api.layout.mockResolvedValue({ id: 'example', revision: 3, version: 4, layoutVersion: 5, state: 'ACTIVE' })
    await clickButton('保存流程'); await flushPromises(); expect(wrapper!.get('fieldset').attributes('disabled')).toBeDefined()
    await clickButton('重试保存'); await flushPromises(); expect(api.revise.mock.calls[0]).toEqual(api.revise.mock.calls[1]); expect(wrapper!.text()).toContain('流程已保存')
  })
  it('retains the local draft on version conflict and offers an explicit new copy', async () => {
    await render(); await clickButton('流程设置'); await wrapper!.get('input').setValue('本地草稿'); api.revise.mockRejectedValue(new ApiError('流程已被修改，请重新读取', 409))
    await clickButton('保存流程'); await flushPromises(); expect(wrapper!.get('input').element.value).toBe('本地草稿'); expect(button('草稿另存为新流程').exists()).toBe(true)
    api.create.mockResolvedValue({ id: 'copy', revision: 1, version: 0, layoutVersion: 0, state: 'ACTIVE' }); api.get.mockResolvedValue(template({ id: 'copy', title: '本地草稿' }))
    await clickButton('草稿另存为新流程'); await flushPromises(); expect(api.create.mock.calls[0]![0].title).toBe('本地草稿')
  })
  it('does not expose an editable stale canvas after load failure', async () => {
    api.get.mockRejectedValue(new Error('unavailable')); await render(); expect(wrapper!.find('.workflow-studio').exists()).toBe(false); expect(button('保存流程').attributes('disabled')).toBeDefined()
  })
  it('keeps built-in definitions read-only', async () => {
    const value = template({ builtin: true }); value.graph.inputs = [{ name: 'documents', title: '需求原文', kind: 'DOCUMENT', required: false }]
    api.get.mockResolvedValue(value); await render(); await clickButton('流程设置'); expect(wrapper!.get('fieldset').attributes('disabled')).toBeDefined(); expect(wrapper!.text()).toContain('复制为自定义流程'); expect(wrapper!.find('.workflow-port').exists()).toBe(false)
    const select = wrapper!.get<HTMLSelectElement>('.workflow-binding select'); expect(select.element.value).toBe('DOCUMENT'); expect(select.element.selectedOptions[0]!.textContent).toBe('上传文档')
  })
  it('edits uploaded-document declarations together with their consumers and supports undo', async () => {
    const value = template(); value.graph.inputs = [{ name: 'source', title: '资料', kind: 'TEXT', required: false }]
    value.graph.nodes[0]!.inputs = [{ name: 'material', source: 'REQUIREMENT', sourceId: 'source', output: null, kind: 'TEXT', required: false }]
    api.get.mockResolvedValue(value); await render(); await clickButton('流程设置')
    await wrapper!.findAll('.workflow-binding input')[1]!.setValue('documents'); await wrapper!.get('.workflow-binding select').setValue('DOCUMENT')
    await clickButton('检查流程'); await flushPromises()
    expect(api.validate.mock.calls.at(-1)![0]).toMatchObject({ inputs: [{ name: 'documents', kind: 'DOCUMENT' }], nodes: [{ inputs: [{ sourceId: 'documents', kind: 'DOCUMENT' }] }] })
    await wrapper!.get('[aria-label="撤销修改"]').trigger('click'); await wrapper!.get('[aria-label="撤销修改"]').trigger('click')
    await clickButton('检查流程'); await flushPromises()
    expect(api.validate.mock.calls.at(-1)![0]).toMatchObject({ inputs: [{ name: 'source', kind: 'TEXT' }], nodes: [{ inputs: [{ sourceId: 'source', kind: 'TEXT' }] }] })
  })
  it('allows users to remain on an unsaved draft when navigating away', async () => {
    const router = await render(); await clickButton('流程设置'); await wrapper!.get('input').setValue('未保存名称'); vi.mocked(window.confirm).mockReturnValue(false)
    await router.push('/workflows'); expect(router.currentRoute.value.path).toBe('/workflows/example'); expect(window.confirm).toHaveBeenCalled()
  })
  it('starts with a clear canvas and keeps edited values when closing and reopening context', async () => {
    await render(); expect(wrapper!.find('.workflow-context-panel').exists()).toBe(false)
    expect(wrapper!.find('.workflow-module-rail').exists()).toBe(false)
    await clickButton('流程设置'); await wrapper!.get('input').setValue('保留名称')
    await wrapper!.get('.workflow-canvas').trigger('click'); expect(wrapper!.find('.workflow-context-panel').exists()).toBe(false)
    await clickButton('流程设置'); expect(wrapper!.get<HTMLInputElement>('input').element.value).toBe('保留名称')
    await wrapper!.get('input').trigger('keydown', { key: 'Escape' }); expect(wrapper!.find('.workflow-context-panel').exists()).toBe(false)
    expect(wrapper!.text()).toContain('未保存')
  })
  it('switches nodes and edges exclusively, treats repeated selection as stable and clears stale selection on undo', async () => {
    const value = template(); value.graph.nodes.push({ ...value.graph.nodes[0]!, id: 'other', title: '后续检查' }); value.graph.edges.push({ id: 'edge', from: 'review', to: 'other', outcome: null })
    api.get.mockResolvedValue(value); await render()
    await waitForConnections(1)
    await wrapper!.findAll('.workflow-node')[0]!.trigger('click'); const panel = wrapper!.get('.workflow-context-panel').element
    await wrapper!.findAll('.workflow-node')[0]!.trigger('click'); expect(wrapper!.get('.workflow-context-panel').element).toBe(panel)
    await wrapper!.findAll('.workflow-node')[1]!.trigger('click'); expect(wrapper!.get<HTMLInputElement>('aside[aria-label="节点设置"] input').element.value).toBe('后续检查')
    await wrapper!.get('.workflow-wire-hit').trigger('click'); expect(wrapper!.find('.workflow-node.selected').exists()).toBe(false); expect(wrapper!.find('aside[aria-label="节点设置"]').exists()).toBe(false)
    expect(wrapper!.get('.workflow-wire-hit').attributes('aria-pressed')).toBe('true')
    await clickButton('关闭连接设置'); expect(wrapper!.find('.workflow-context-panel').exists()).toBe(false)
    await clickButton('人工检查'); expect(wrapper!.find('aside[aria-label="节点设置"]').exists()).toBe(true)
    await wrapper!.get('[aria-label="撤销修改"]').trigger('click'); expect(wrapper!.find('.workflow-context-panel').exists()).toBe(false); expect(wrapper!.find('.workflow-port').exists()).toBe(false)
    await wrapper!.get('[aria-label="重做修改"]').trigger('click'); expect(wrapper!.find('.workflow-context-panel').exists()).toBe(false)
  })
  it('finds offscreen nodes by name without saving or dirtying the workflow', async () => {
    const value = template(); value.layout.positions.review = { x: 6000, y: 7000 }; api.get.mockResolvedValue(value)
    await render(); await clickButton('节点列表'); await wrapper!.get('[aria-label="搜索节点"]').setValue(value.graph.nodes[0]!.title)
    await wrapper!.get('.workflow-node-list button').trigger('click'); await flushPromises()
    expect(wrapper!.find('aside[aria-label="节点设置"]').exists()).toBe(true); expect(wrapper!.text()).not.toContain('未保存'); expect(api.layout).not.toHaveBeenCalled()
    expect(wrapper!.get('.workflow-canvas-world').attributes('style')).not.toContain('translate(32px, 36px)')
  })

})
