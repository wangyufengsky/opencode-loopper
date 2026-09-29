import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { workflowRuns } from '@/api/workflowRuns'
import { ApiError } from '@/api/client'
import WorkflowFinish from './WorkflowFinish.vue'
import type { WorkflowFinish as Finish, WorkflowRequirementState } from '@/types/domain'
vi.mock('@/api/workflowRuns', () => ({ workflowRuns: { finishStatus: vi.fn(), finish: vi.fn() } }))
const api = vi.mocked(workflowRuns); let wrapper: VueWrapper | undefined
const status = (state: WorkflowRequirementState = 'RUNNING', decided = false): Finish => ({
  requirementId: 'req', state, version: 7, pending: { attempts: state === 'STOPPING' ? 1 : 0, resources: state === 'STOPPING' ? 2 : 0 },
  intent: decided ? { requirementId: 'req', targetState: 'COMPLETED', reason: '保留现有成果', planRevision: 2, requestedVersion: 6, requestedAt: 'now', finalizedAt: state === 'STOPPING' ? null : 'later' } : null,
})
beforeEach(() => { vi.resetAllMocks(); api.finishStatus.mockResolvedValue(status()); api.finish.mockResolvedValue({ id: 'req', revision: 2, version: 8, state: 'STOPPING', layoutVersion: 0 }) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })
const button = (text: string) => wrapper!.findAll('button').find(node => node.text() === text)!
async function render() { wrapper = mount(WorkflowFinish, { props: { requirement: 'req', version: 7, state: 'RUNNING', disabled: false } }); await flushPromises() }
async function fill() { await button('提前结束需求').trigger('click'); await wrapper!.get('select').setValue('COMPLETED'); await wrapper!.get('textarea').setValue('用户决定保留成果') }
describe('human requirement finish', () => {
  it('requires an explicit outcome and reason before submitting the observed requirement version', async () => {
    await render(); await button('提前结束需求').trigger('click'); expect(button('确认结束需求').attributes('disabled')).toBeDefined()
    await wrapper!.get('select').setValue('COMPLETED'); await wrapper!.get('textarea').setValue('用户决定保留成果')
    expect(wrapper!.text()).toContain('不表示未执行的检查已经通过'); expect(api.finish).not.toHaveBeenCalled()
    api.finishStatus.mockResolvedValue(status('STOPPING', true)); await wrapper!.get('form').trigger('submit'); await flushPromises()
    expect(api.finish.mock.calls[0]).toEqual(['req', expect.objectContaining({ expectedVersion: 7, target: 'COMPLETED', reason: '用户决定保留成果' })])
    expect(wrapper!.text()).toContain('正在结束需求'); expect(wrapper!.text()).toContain('1 次节点执行，2 项运行或目录记录'); expect(wrapper!.emitted('changed')).toHaveLength(1)
  })
  it('retries an uncertain result with the exact original key and body, retaining the chosen outcome', async () => {
    await render(); await fill(); api.finish.mockRejectedValueOnce(new Error('network'))
    await wrapper!.get('form').trigger('submit'); await flushPromises(); const original = api.finish.mock.calls[0]
    expect(wrapper!.get('fieldset').attributes('disabled')).toBeDefined(); expect(button('返回画布').attributes('disabled')).toBeDefined()
    api.finishStatus.mockResolvedValue(status('STOPPING', true)); await button('重试原结束操作').trigger('click'); await flushPromises()
    expect(api.finish.mock.calls[1]).toEqual(original); expect(wrapper!.text()).toContain('正在结束需求')
  })
  it('refreshes an accepted operation without repeating the write when status reading fails', async () => {
    await render(); await fill(); api.finishStatus.mockRejectedValueOnce(new Error('offline'))
    await wrapper!.get('form').trigger('submit'); await flushPromises()
    api.finishStatus.mockResolvedValue(status('COMPLETED', true)); await button('刷新操作结果').trigger('click'); await flushPromises()
    expect(api.finish).toHaveBeenCalledTimes(1); expect(wrapper!.text()).toContain('人工认定成功'); expect(wrapper!.text()).toContain('节点原有交付物、检查与审查结论均保留')
  })
  it('keeps the reason editable after a version conflict and allows explicit retry with the new observed version', async () => {
    await render(); await fill(); api.finish.mockRejectedValueOnce(new ApiError('已变化', 409, { code: 'WORKFLOW_VERSION_CONFLICT' }))
    await wrapper!.get('form').trigger('submit'); await flushPromises()
    expect(wrapper!.get('textarea').element.value).toBe('用户决定保留成果'); expect(wrapper!.get('fieldset').attributes('disabled')).toBeUndefined()
    await wrapper!.setProps({ version: 9 }); await flushPromises(); api.finishStatus.mockResolvedValue(status('STOPPING', true))
    await wrapper!.get('form').trigger('submit'); await flushPromises(); expect(api.finish.mock.calls[1]![1].expectedVersion).toBe(9)
    expect(api.finish.mock.calls[1]![1].requestKey).not.toBe(api.finish.mock.calls[0]![1].requestKey)
  })
  it('shows only persisted finish evidence after reopening and escapes user-provided reasons', async () => {
    const ended = status('COMPLETED', true); ended.intent!.reason = '<script>alert(1)</script>'; api.finishStatus.mockResolvedValue(ended)
    await render(); expect(wrapper!.text()).toContain('人工认定成功'); expect(wrapper!.find('script').exists()).toBe(false); expect(wrapper!.text()).toContain('<script>')
    expect(button('提前结束需求')).toBeUndefined(); expect(api.finish).not.toHaveBeenCalled()
  })
  it('does not label normal completion as a user decision or offer termination while stopping', async () => {
    api.finishStatus.mockResolvedValue(status('COMPLETED')); await render(); expect(wrapper!.text()).toBe('')
    await wrapper!.setProps({ state: 'STOPPING' }); await flushPromises(); expect(button('提前结束需求')).toBeUndefined()
  })
  it('honors unsaved node input before opening and protects a nonempty reason on navigation', async () => {
    await render(); const guard = vi.fn().mockReturnValue(false); await wrapper!.setProps({ beforeOpen: guard })
    await button('提前结束需求').trigger('click'); expect(wrapper!.find('form').exists()).toBe(false)
    guard.mockReturnValue(true); await fill(); vi.spyOn(window, 'confirm').mockReturnValue(false)
    expect((wrapper!.vm as unknown as { canLeave(): boolean }).canLeave()).toBe(false)
  })
})
