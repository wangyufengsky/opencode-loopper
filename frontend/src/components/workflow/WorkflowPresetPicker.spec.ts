import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { workflowApi } from '@/api/workflow'
import type { WorkflowGraph } from '@/types/domain'
import WorkflowPresetPicker from './WorkflowPresetPicker.vue'
import { preset, template } from './workflowTestFixtures'

vi.mock('@/api/workflow', () => ({ workflowApi: { presets: vi.fn(), preset: vi.fn() } }))
const api = vi.mocked(workflowApi)
let wrapper: VueWrapper | undefined
const row = { id: 'analysis.read', version: 1, title: '资料分析', description: '阅读资料并交付结论' }
const button = (name: string) => wrapper!.findAll('button').find(value => value.text().includes(name))!
beforeEach(() => { vi.resetAllMocks(); api.presets.mockResolvedValue({ items: [row], nextCursor: null }); api.preset.mockResolvedValue(preset()) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined })
async function render() { wrapper = mount(WorkflowPresetPicker, { props: { graph: template().graph } }); await flushPromises() }
describe('preset picker', () => {
  it('loads summaries first and inserts an exact version only after explicit input selection', async () => {
    await render(); expect(api.preset).not.toHaveBeenCalled(); expect(wrapper!.emitted('insert')).toBeUndefined()
    await button('资料分析').trigger('click'); await flushPromises()
    expect(api.preset).toHaveBeenCalledWith('analysis.read', 1); expect(wrapper!.text()).toContain('通用助手 · 版本 3')
    expect(wrapper!.emitted('insert')).toBeUndefined()
    await wrapper!.get('select').setValue('NODE|review|result'); await button('添加到画布').trigger('click')
    const graph = wrapper!.emitted('insert')![0]![0] as WorkflowGraph
    expect(graph.nodes).toHaveLength(2); expect(graph.edges).toHaveLength(1); expect(graph.nodes[1]!.roleRevisionId).toBe('role-v3')
  })
  it('keeps the selected version visible when the current source has disappeared', async () => {
    await render(); await button('资料分析').trigger('click'); await flushPromises()
    await wrapper!.get('select').setValue('NODE|review|result'); await wrapper!.setProps({ graph: { ...template().graph, nodes: [] } })
    await button('添加到画布').trigger('click'); expect(wrapper!.get('[role=alert]').text()).toContain('来源已变化'); expect(wrapper!.emitted('insert')).toBeUndefined()
  })
  it('does not let a late detail replace a newly searched catalog', async () => {
    let resolve!: (value: ReturnType<typeof preset>) => void
    api.preset.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    await render(); await button('资料分析').trigger('click')
    api.presets.mockResolvedValue({ items: [], nextCursor: null })
    await wrapper!.get('input').setValue('没有结果'); await wrapper!.get('input').trigger('keydown', { key: 'Enter' }); await flushPromises()
    resolve(preset()); await flushPromises(); expect(wrapper!.find('.workflow-preset-detail').exists()).toBe(false)
    expect(api.presets).toHaveBeenLastCalledWith('没有结果', ''); expect(wrapper!.emitted('insert')).toBeUndefined()
  })
  it('disables insertion while the owning canvas has an unresolved command', async () => {
    await render(); await button('资料分析').trigger('click'); await flushPromises(); await wrapper!.setProps({ disabled: true })
    expect(button('添加到画布').attributes('disabled')).toBeDefined(); await button('添加到画布').trigger('click'); expect(wrapper!.emitted('insert')).toBeUndefined()
  })
  it('keeps pagination in the submitted search and resets the cursor for a new search', async () => {
    api.presets.mockResolvedValueOnce({ items: [row], nextCursor: 'first-page' }).mockResolvedValue({ items: [], nextCursor: null })
    await render(); await wrapper!.get('input').setValue('设计')
    await button('更多预设').trigger('click'); await flushPromises(); expect(api.presets).toHaveBeenLastCalledWith('', 'first-page')
    await button('搜索').trigger('click'); await flushPromises(); expect(api.presets).toHaveBeenLastCalledWith('设计', '')
  })
})
