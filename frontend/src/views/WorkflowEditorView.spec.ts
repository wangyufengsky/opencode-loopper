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
const button = (label: string) => wrapper!.findAll('button').find(node => node.text() === label)!
describe('workflow authoring', () => {
  it('adds a preset with its bound parent and supports undo before any save', async () => {
    api.presets.mockResolvedValue({ items: [preset()], nextCursor: null }); api.preset.mockResolvedValue(preset())
    await render(); await button('预设工作模块').trigger('click'); await flushPromises()
    await wrapper!.get('.workflow-preset-list button').trigger('click'); await flushPromises()
    await wrapper!.get('.workflow-preset-detail select').setValue('NODE|review|result'); await button('添加到画布').trigger('click')
    expect(wrapper!.findAll('.workflow-node')).toHaveLength(2); expect(wrapper!.findAll('.workflow-wire')).toHaveLength(1); expect(wrapper!.find('.workflow-presets').exists()).toBe(false)
    expect(api.create).not.toHaveBeenCalled(); expect(api.revise).not.toHaveBeenCalled()
    await wrapper!.get('[aria-label="撤销修改"]').trigger('click'); expect(wrapper!.findAll('.workflow-node')).toHaveLength(1); expect(wrapper!.findAll('.workflow-wire')).toHaveLength(0)
  })
  it('edits nodes, removes them with confirmation and supports undo', async () => {
    await render(); await button('人工检查由你补充结果并确认').trigger('click'); expect(wrapper!.findAll('.workflow-node')).toHaveLength(2)
    await button('删除节点').trigger('click'); expect(window.confirm).toHaveBeenCalled(); expect(wrapper!.findAll('.workflow-node')).toHaveLength(1)
    await wrapper!.get('[aria-label="撤销修改"]').trigger('click'); expect(wrapper!.findAll('.workflow-node')).toHaveLength(2)
  })
  it('freezes an uncertain save and retries the same command', async () => {
    await render(); await wrapper!.get('input').setValue('改名后的流程')
    api.revise.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({ id: 'example', revision: 3, version: 4, layoutVersion: 4, state: 'ACTIVE' }); api.layout.mockResolvedValue({ id: 'example', revision: 3, version: 4, layoutVersion: 5, state: 'ACTIVE' })
    await button('保存流程').trigger('click'); await flushPromises(); expect(wrapper!.get('fieldset').attributes('disabled')).toBeDefined()
    await button('重试保存').trigger('click'); await flushPromises(); expect(api.revise.mock.calls[0]).toEqual(api.revise.mock.calls[1]); expect(wrapper!.text()).toContain('流程已保存')
  })
  it('retains the local draft on version conflict and offers an explicit new copy', async () => {
    await render(); await wrapper!.get('input').setValue('本地草稿'); api.revise.mockRejectedValue(new ApiError('流程已被修改，请重新读取', 409))
    await button('保存流程').trigger('click'); await flushPromises(); expect(wrapper!.get('input').element.value).toBe('本地草稿'); expect(button('草稿另存为新流程').exists()).toBe(true)
    api.create.mockResolvedValue({ id: 'copy', revision: 1, version: 0, layoutVersion: 0, state: 'ACTIVE' }); api.get.mockResolvedValue(template({ id: 'copy', title: '本地草稿' }))
    await button('草稿另存为新流程').trigger('click'); await flushPromises(); expect(api.create.mock.calls[0]![0].title).toBe('本地草稿')
  })
  it('does not expose an editable stale canvas after load failure', async () => {
    api.get.mockRejectedValue(new Error('unavailable')); await render(); expect(wrapper!.find('.workflow-studio').exists()).toBe(false); expect(button('保存流程').attributes('disabled')).toBeDefined()
  })
  it('keeps built-in definitions read-only', async () => {
    const value = template({ builtin: true }); value.graph.inputs = [{ name: 'documents', title: '需求原文', kind: 'DOCUMENT', required: false }]
    api.get.mockResolvedValue(value); await render(); expect(wrapper!.get('fieldset').attributes('disabled')).toBeDefined(); expect(wrapper!.text()).toContain('复制为自定义流程'); expect(wrapper!.find('.workflow-port').exists()).toBe(false)
    const select = wrapper!.get<HTMLSelectElement>('.workflow-binding select'); expect(select.element.value).toBe('DOCUMENT'); expect(select.element.selectedOptions[0]!.textContent).toBe('上传文档')
  })
  it('edits uploaded-document declarations together with their consumers and supports undo', async () => {
    const value = template(); value.graph.inputs = [{ name: 'source', title: '资料', kind: 'TEXT', required: false }]
    value.graph.nodes[0]!.inputs = [{ name: 'material', source: 'REQUIREMENT', sourceId: 'source', output: null, kind: 'TEXT', required: false }]
    api.get.mockResolvedValue(value); await render()
    await wrapper!.findAll('.workflow-binding input')[1]!.setValue('documents'); await wrapper!.get('.workflow-binding select').setValue('DOCUMENT')
    await button('检查流程').trigger('click'); await flushPromises()
    expect(api.validate.mock.calls.at(-1)![0]).toMatchObject({ inputs: [{ name: 'documents', kind: 'DOCUMENT' }], nodes: [{ inputs: [{ sourceId: 'documents', kind: 'DOCUMENT' }] }] })
    await wrapper!.get('[aria-label="撤销修改"]').trigger('click'); await wrapper!.get('[aria-label="撤销修改"]').trigger('click')
    await button('检查流程').trigger('click'); await flushPromises()
    expect(api.validate.mock.calls.at(-1)![0]).toMatchObject({ inputs: [{ name: 'source', kind: 'TEXT' }], nodes: [{ inputs: [{ sourceId: 'source', kind: 'TEXT' }] }] })
  })
  it('allows users to remain on an unsaved draft when navigating away', async () => {
    const router = await render(); await wrapper!.get('input').setValue('未保存名称'); vi.mocked(window.confirm).mockReturnValue(false)
    await router.push('/workflows'); expect(router.currentRoute.value.path).toBe('/workflows/example'); expect(window.confirm).toHaveBeenCalled()
  })
})
