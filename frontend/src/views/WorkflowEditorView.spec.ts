import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, resolveDialog } from '@/pages/w6-tests/workflow/react-test-root'
import { mountPageApplication } from '@/pages/w6-tests/workflow/application-test-root'

import { workflowApi } from '@/api/workflow'
import { ApiError } from '@/api/client'
import { WorkflowEditorPage } from '@/pages/w5/workflow'
import { preset, template } from '@/components/workflow/workflowTestFixtures'
vi.mock('@/api/workflow', () => ({ workflowApi: { get: vi.fn(), create: vi.fn(), revise: vi.fn(), layout: vi.fn(), validate: vi.fn(), presets: vi.fn(), preset: vi.fn() } }))
const api = vi.mocked(workflowApi)
let wrapper: Awaited<ReturnType<typeof mountPageApplication>> | undefined
beforeEach(() => { vi.resetAllMocks(); vi.spyOn(window, 'confirm').mockReturnValue(true); api.get.mockResolvedValue(template()); api.validate.mockResolvedValue([]) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })
async function render(path = '/workflows/example') { wrapper = await mountPageApplication(WorkflowEditorPage,path,['/workflows/:id','/workflows/new','/workflows'],['/workflows/:id','/workflows/new']); return wrapper }
const keys:Record<string,string>={ '添加节点':'workflow.addNode','更多工具':'workflow.tools','预设工作模块':'workflow.presets','只读分析':'workflow.addReadonly','文件工作':'workflow.addWrite','人工检查':'workflow.addHuman','人工检查由你补充结果并确认':'workflow.addHuman','删除节点':'workflow.deleteSelection','流程设置':'workflow.settings','保存流程':'workflow.save','重试保存':'receipt.retryOriginal','草稿另存为新流程':'workflow.copyDefinition','重新加载':'ui.refresh','检查流程':'workflow.validate','节点列表':'workflow.nodeList' }
const button=(label:string)=>wrapper!.findAll('button').find(node=>node.attributes('data-semantic')===keys[label]||node.attributes('aria-label')===label)!
async function clickButton(label:string) { if(label.startsWith('关闭')) {await wrapper!.get('aside:not([hidden]) button[data-semantic="ui.close"]').trigger('click');return}; if(label==='重试保存') { const retry=wrapper!.findAll('button').find(node=>['receipt.retryOriginal','receipt.readOriginal'].includes(node.attributes('data-semantic')??''))!;await retry.trigger('click');return };if(!button(label)){const add=['预设工作模块','只读分析','文件工作','人工检查','人工检查由你补充结果并确认'].includes(label);await button(add?'添加节点':'更多工具').trigger('click')};await button(label).trigger('click'); if(['删除节点','草稿另存为新流程'].includes(label))await resolveDialog(wrapper!,true) }
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
    await wrapper!.get('.workflow-preset-detail select').setValue('NODE|review|result'); await wrapper!.get('.workflow-preset-detail button[data-semantic="workflow.addNode"]').trigger('click')
    expect(wrapper!.findAll('.workflow-node')).toHaveLength(2); await waitForConnections(1); expect(wrapper!.find('.workflow-preset-detail').exists()).toBe(false)
    expect(api.create).not.toHaveBeenCalled(); expect(api.revise).not.toHaveBeenCalled()
    await wrapper!.get('button[data-semantic="workflow.undo"]').trigger('click'); expect(wrapper!.findAll('.workflow-node')).toHaveLength(1); expect(wrapper!.findAll('.workflow-wire')).toHaveLength(0)
  })
  it('edits nodes, removes them with confirmation and supports undo', async () => {
    await render(); await clickButton('人工检查由你补充结果并确认'); expect(wrapper!.findAll('.workflow-node')).toHaveLength(2)
    await clickButton('删除节点'); await vi.waitFor(() => expect(wrapper!.findAll('[role="dialog"]').filter(dialog => getComputedStyle(dialog.element.closest('.ant-modal-wrap') ?? dialog.element).display !== 'none')).toHaveLength(0)); expect(wrapper!.findAll('.workflow-node')).toHaveLength(1)
    await wrapper!.get('button[data-semantic="workflow.undo"]').trigger('click'); expect(wrapper!.findAll('.workflow-node')).toHaveLength(2)
  })
  it('freezes an uncertain save and retries the same command', async () => {
    await render(); await clickButton('流程设置'); await wrapper!.get('input').setValue('改名后的流程')
    api.revise.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({ id: 'example', revision: 3, version: 4, layoutVersion: 4, state: 'ACTIVE' }); api.layout.mockResolvedValue({ id: 'example', revision: 3, version: 4, layoutVersion: 5, state: 'ACTIVE' })
    api.get.mockResolvedValue(template({revision:3,version:4,layoutVersion:5})); await clickButton('保存流程'); await flushPromises(); expect(wrapper!.get('fieldset').attributes('disabled')).toBeDefined()
    await clickButton('重试保存'); await flushPromises(); expect(api.revise.mock.calls[0]).toEqual(api.revise.mock.calls[1]); expect(wrapper!.text()).toContain('已保存')
  })
  it('retains an unknown save across attempted leaving and reload, then leaves after the original retry succeeds', async () => {
    const router = await render(); await clickButton('流程设置'); await wrapper!.get('input').setValue('待确认流程')
    api.revise.mockRejectedValueOnce(new Error('unknown receipt')).mockResolvedValue({ id: 'example', revision: 3, version: 4, layoutVersion: 4, state: 'ACTIVE' })
    api.layout.mockResolvedValue({ id: 'example', revision: 3, version: 4, layoutVersion: 5, state: 'ACTIVE' })
    api.get.mockResolvedValue(template({revision:3,version:4,layoutVersion:5})); await clickButton('保存流程'); await flushPromises(); const original = api.revise.mock.calls[0]
    vi.mocked(window.confirm).mockClear()
    await router.navigate('/workflows'); await flushPromises()
    expect(router.router.state.location.pathname).toBe('/workflows/example'); expect(window.confirm).not.toHaveBeenCalled()
    expect(wrapper!.text()).toContain('请重试原保存操作'); const reads = api.get.mock.calls.length
    await clickButton('重新加载'); await flushPromises(); expect(api.get).toHaveBeenCalledTimes(reads)
    await clickButton('重试保存'); await flushPromises(); expect(api.revise.mock.calls[1]).toEqual(original)
    await router.navigate('/workflows'); await flushPromises(); expect(router.router.state.location.pathname).toBe('/workflows')
  })
  it('blocks leaving while a save is running and only rereads its accepted result after a failed readback', async () => {
    const router = await render(); await clickButton('流程设置'); await wrapper!.get('input').setValue('处理中流程')
    let resolve!: (value: Awaited<ReturnType<typeof workflowApi.revise>>) => void
    api.revise.mockImplementation(() => new Promise(done => { resolve = done }))
    api.layout.mockResolvedValue({ id: 'example', revision: 3, version: 4, layoutVersion: 5, state: 'ACTIVE' })
    await clickButton('保存流程'); await router.navigate('/workflows'); await flushPromises()
    expect(router.router.state.location.pathname).toBe('/workflows/example'); expect(wrapper!.text()).toContain('请等待结果')
    api.get.mockRejectedValueOnce(new Error('readback offline'))
    resolve({ id: 'example', revision: 3, version: 4, layoutVersion: 4, state: 'ACTIVE' }); await flushPromises()
    await router.navigate('/workflows'); await flushPromises(); expect(router.router.state.location.pathname).toBe('/workflows/example')
    api.get.mockResolvedValue(template({revision:3,version:4,layoutVersion:5})); await clickButton('重试保存'); await flushPromises(); expect(api.revise).toHaveBeenCalledOnce(); expect(api.layout).toHaveBeenCalledOnce()
    await router.navigate('/workflows'); await flushPromises(); expect(router.router.state.location.pathname).toBe('/workflows')
  })
  it('retains the local draft on version conflict and offers an explicit new copy', async () => {
    await render(); await clickButton('流程设置'); await wrapper!.get('input').setValue('本地草稿'); api.revise.mockRejectedValue(new ApiError('流程已被修改，请重新读取', 409))
    await clickButton('保存流程'); await flushPromises(); expect(wrapper!.get<HTMLInputElement>('input').element.value).toBe('本地草稿'); expect(button('草稿另存为新流程').exists()).toBe(true)
    api.create.mockResolvedValue({ id: 'copy', revision: 1, version: 0, layoutVersion: 0, state: 'ACTIVE' }); api.get.mockResolvedValue(template({ id: 'copy', title: '本地草稿' }))
    await clickButton('草稿另存为新流程'); await flushPromises(); expect(api.create.mock.calls[0]![0].title).toBe('本地草稿')
  })
  it('does not expose an editable stale canvas after load failure', async () => {
    api.get.mockRejectedValue(new Error('unavailable')); await render(); expect(wrapper!.find('.workflow-editor-canvas').exists()).toBe(false); expect(button('保存流程').attributes('disabled')).toBeDefined()
  })
  it('keeps built-in definitions read-only', async () => {
    const value = template({ builtin: true }); value.graph.inputs = [{ name: 'documents', title: '需求原文', kind: 'DOCUMENT', required: false }]
    api.get.mockResolvedValue(value); await render(); await clickButton('流程设置'); expect(wrapper!.get('fieldset').attributes('disabled')).toBeDefined(); expect(wrapper!.text()).toContain('内置流程只读'); expect(wrapper!.find('.workflow-port').exists()).toBe(false)
    const select = wrapper!.get<HTMLSelectElement>('.workflow-binding select'); expect(select.element.value).toBe('DOCUMENT'); expect(select.element.selectedOptions[0]!.textContent).toBe('上传文档')
  })
  it('edits uploaded-document declarations together with their consumers and supports undo', async () => {
    const value = template(); value.graph.inputs = [{ name: 'source', title: '资料', kind: 'TEXT', required: false }]
    value.graph.nodes[0]!.inputs = [{ name: 'material', source: 'REQUIREMENT', sourceId: 'source', output: null, kind: 'TEXT', required: false }]
    api.get.mockResolvedValue(value); await render(); await clickButton('流程设置')
    await wrapper!.findAll('.workflow-binding input')[1]!.setValue('documents'); await wrapper!.get('.workflow-binding select').setValue('DOCUMENT')
    await clickButton('检查流程'); await flushPromises()
    expect(api.validate.mock.calls.at(-1)![0]).toMatchObject({ inputs: [{ name: 'documents', kind: 'DOCUMENT' }], nodes: [{ inputs: [{ sourceId: 'documents', kind: 'DOCUMENT' }] }] })
    await wrapper!.get('button[data-semantic="workflow.undo"]').trigger('click'); await wrapper!.get('button[data-semantic="workflow.undo"]').trigger('click')
    await clickButton('检查流程'); await flushPromises()
    expect(api.validate.mock.calls.at(-1)![0]).toMatchObject({ inputs: [{ name: 'source', kind: 'TEXT' }], nodes: [{ inputs: [{ sourceId: 'source', kind: 'TEXT' }] }] })
  })
  it('allows users to remain on an unsaved draft when navigating away', async () => {
    const router = await render(); await clickButton('流程设置'); await wrapper!.get('input').setValue('未保存名称'); vi.mocked(window.confirm).mockReturnValue(false)
    const navigation=router.navigate('/workflows'); await flushPromises(); expect(wrapper!.find('[role="dialog"]').exists()).toBe(true); await resolveDialog(wrapper!,false); await navigation; expect(router.router.state.location.pathname).toBe('/workflows/example')
  })
  it('starts with a clear canvas and keeps edited values when closing and reopening context', async () => {
    await render(); expect(wrapper!.find('aside:not([hidden])').exists()).toBe(false)
    expect(wrapper!.find('.workflow-module-rail').exists()).toBe(false)
    await clickButton('流程设置'); await wrapper!.get('input').setValue('保留名称')
    await wrapper!.get('.workflow-canvas').trigger('click'); expect(wrapper!.find('aside:not([hidden])').exists()).toBe(false)
    await clickButton('流程设置'); expect(wrapper!.get<HTMLInputElement>('input').element.value).toBe('保留名称')
    await wrapper!.get('input').trigger('keydown', { key: 'Escape' }); expect(wrapper!.find('aside:not([hidden])').exists()).toBe(false)
    expect(wrapper!.text()).toContain('未保存')
  })
  it('switches nodes and edges exclusively, treats repeated selection as stable and clears stale selection on undo', async () => {
    const value = template(); value.graph.nodes.push({ ...value.graph.nodes[0]!, id: 'other', title: '后续检查' }); value.graph.edges.push({ id: 'edge', from: 'review', to: 'other', outcome: null })
    api.get.mockResolvedValue(value); await render()
    await waitForConnections(1)
    await wrapper!.findAll('.workflow-node')[0]!.trigger('click'); const panel = wrapper!.get('aside:not([hidden])').element
    await wrapper!.findAll('.workflow-node')[0]!.trigger('click'); expect(wrapper!.get('aside:not([hidden])').element).toBe(panel)
    await wrapper!.findAll('.workflow-node')[1]!.trigger('click'); expect(wrapper!.get<HTMLInputElement>('aside:not([hidden]) input').element.value).toBe('后续检查')
    await wrapper!.get('.workflow-wire-hit').trigger('click'); expect(wrapper!.find('.workflow-node.selected').exists()).toBe(false); expect(wrapper!.find('[data-workflow-node-editor]').exists()).toBe(false)
    expect(wrapper!.get('.workflow-wire-hit').attributes('aria-pressed')).toBe('true')
    await clickButton('关闭连接设置'); expect(wrapper!.find('aside:not([hidden])').exists()).toBe(false)
    await clickButton('人工检查'); expect(wrapper!.find('aside:not([hidden])').exists()).toBe(true)
    await wrapper!.get('button[data-semantic="workflow.undo"]').trigger('click'); expect(wrapper!.find('aside:not([hidden])').exists()).toBe(false); expect(wrapper!.find('.workflow-port').exists()).toBe(false)
    await wrapper!.get('button[data-semantic="workflow.redo"]').trigger('click'); expect(wrapper!.find('aside:not([hidden])').exists()).toBe(false)
  })
  it('finds offscreen nodes by name without saving or dirtying the workflow', async () => {
    const value = template(); value.layout.positions.review = { x: 6000, y: 7000 }; api.get.mockResolvedValue(value)
    await render(); await clickButton('节点列表'); await wrapper!.get('[aria-label="搜索节点"]').setValue(value.graph.nodes[0]!.title)
    await wrapper!.get('.workflow-node-list button').trigger('click'); await flushPromises()
    expect(wrapper!.find('aside:not([hidden])').exists()).toBe(true); expect(wrapper!.text()).not.toContain('未保存'); expect(api.layout).not.toHaveBeenCalled()
    expect(wrapper!.get('.react-flow__viewport').attributes('style')).not.toContain('translate(32px, 36px)')
  })

})
