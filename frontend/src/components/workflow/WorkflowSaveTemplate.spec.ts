import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type ReactTestRoot } from '@/pages/w6-tests/workflow/react-test-root'
import { workflowRuns } from '@/api/workflowRuns'
import { ApiError } from '@/api/client'
import type { WorkflowTemplatePreview } from '@/types/domain'
import { requirement } from './workflowRunTestFixtures'

import { WorkflowSaveTemplate } from '@/pages/w6-tests/workflow/command-panels'
vi.mock('@/api/workflowRuns', () => ({ workflowRuns: { previewTemplate: vi.fn(), saveTemplate: vi.fn() } }))
const api = vi.mocked(workflowRuns), source = requirement()
const preview = (extra: Partial<WorkflowTemplatePreview> = {}): WorkflowTemplatePreview => ({ mode: 'CURRENT', sourceRevision: 2, initialAvailable: true, graph: structuredClone(source.graph), layout: structuredClone(source.layout), fixedPlanningNodes: [], sha256: 'a'.repeat(64), diagnostics: [], ...extra })
let wrapper: ReactTestRoot | undefined
async function render() { wrapper = mount(WorkflowSaveTemplate, { props: { requirement: source.id, revision: 2, title: source.title, graph: source.graph, layout: source.layout } }); await flushPromises() }
const keys:Record<string,string>={'确认保存为新流程':'workflow.saveTemplateConfirm','返回任务画布':'nav.back','重试原保存操作':'receipt.retryOriginal','重新预览':'ui.retry'}; const button=(text:string)=>wrapper!.findAll('button').find(value=>value.attributes('data-semantic')===keys[text])!
beforeEach(() => { vi.resetAllMocks(); api.previewTemplate.mockResolvedValue(preview()); api.saveTemplate.mockResolvedValue({ id: 'new-template', revision: 1, version: 0, layoutVersion: 0, state: 'ACTIVE' }) })
afterEach(() => wrapper?.unmount())
describe('save a requirement plan as a template', () => {
  it('defaults to the captured current steps, previews transformations, and creates only after explicit save', async () => {
    api.previewTemplate.mockResolvedValue(preview({ fixedPlanningNodes: ['原文分批'] })); await render()
    expect(wrapper!.get<HTMLSelectElement>('select').element.value).toBe('CURRENT'); expect(api.saveTemplate).not.toHaveBeenCalled(); expect(wrapper!.text()).toContain('人工确认固定步骤')
    await button('确认保存为新流程').trigger('click'); await flushPromises()
    expect(api.saveTemplate.mock.calls[0]![1]).toMatchObject({ selection: { expectedRevision: 2, mode: 'CURRENT', graph: source.graph, layout: source.layout }, previewSha256: 'a'.repeat(64) })
    expect(wrapper!.get('a').attributes('href')).toBe('/workflows/new-template'); expect(wrapper!.text()).toContain('原任务及画布中的未保存修改仍然保留')
  })
  it('requires a new preview after selecting the first-execution structure', async () => {
    await render(); let finish!: (value: WorkflowTemplatePreview) => void
    api.previewTemplate.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    await wrapper!.get('select').setValue('INITIAL'); expect(button('确认保存为新流程').attributes('disabled')).toBeDefined()
    finish(preview({ mode: 'INITIAL', sourceRevision: 1, sha256: 'b'.repeat(64) })); await flushPromises()
    await button('确认保存为新流程').trigger('click'); await flushPromises(); expect(api.saveTemplate.mock.calls[0]![1]).toMatchObject({ selection: { mode: 'INITIAL' }, previewSha256: 'b'.repeat(64) })
  })
  it('keeps the same frozen save request after timeout and blocks closing or editing while its result is unknown', async () => {
    await render(); api.saveTemplate.mockRejectedValueOnce(new Error('offline'))
    await button('确认保存为新流程').trigger('click'); await flushPromises(); const original = structuredClone(api.saveTemplate.mock.calls[0])
    expect(button('返回任务画布').attributes('disabled')).toBeDefined(); expect(wrapper!.get('fieldset').attributes('disabled')).toBeDefined()
    expect((wrapper!.owners<{ canLeave(): {kind:string} }>()[0]!).canLeave().kind).not.toBe('ALLOW')
    await button('重试原保存操作').trigger('click'); await flushPromises(); expect(api.saveTemplate.mock.calls[1]).toEqual(original); expect(wrapper!.text()).toContain('已保存为自定义流程')
  })
  it('retains text after a definitive conflict and requires another preview', async () => {
    await render(); await wrapper!.get('input').setValue('新的模板名称'); api.saveTemplate.mockRejectedValueOnce(new ApiError('计划版本已变化', 409, { code: 'WORKFLOW_VERSION_CONFLICT' }))
    await button('确认保存为新流程').trigger('click'); await flushPromises(); expect(wrapper!.get<HTMLInputElement>('input').element.value).toBe('新的模板名称')
    expect(button('返回任务画布').attributes('disabled')).toBeUndefined(); expect(button('确认保存为新流程').attributes('disabled')).toBeDefined()
    await button('重新预览').trigger('click'); await flushPromises(); expect(api.previewTemplate).toHaveBeenCalledTimes(2)
  })
  it('disables initial mode before any node has executed', async () => {
    api.previewTemplate.mockResolvedValue(preview({ initialAvailable: false })); await render()
    expect(wrapper!.get('option[value=INITIAL]').attributes('disabled')).toBeDefined(); expect(wrapper!.text()).toContain('任务尚未执行')
  })
  it('dismisses preview selection without closing or losing an uncertain save operation', async () => {
    await render(); await wrapper!.get(`.workflow-node[data-node-id="${source.graph.nodes[0]!.id}"]`).trigger('click'); await flushPromises()
    expect(wrapper!.find('aside:not([hidden])').exists()).toBe(true)
    api.saveTemplate.mockRejectedValueOnce(new Error('offline')); await button('确认保存为新流程').trigger('click'); await flushPromises()
    await wrapper!.get('aside:not([hidden]) button[data-semantic="ui.close"]').trigger('click'); await flushPromises()
    expect(wrapper!.find('aside:not([hidden])').exists()).toBe(false); expect(wrapper!.emitted('close')).toBeUndefined()
    expect(button('返回任务画布').attributes('disabled')).toBeDefined()
    await button('重试原保存操作').trigger('click'); await flushPromises(); expect(api.saveTemplate.mock.calls[1]).toEqual(api.saveTemplate.mock.calls[0])
  })

})
