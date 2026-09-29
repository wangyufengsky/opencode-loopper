import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import WorkflowCandidates from './WorkflowCandidates.vue'
import { candidate } from './workflowRunTestFixtures'
import { workflowRuns } from '@/api/workflowRuns'
vi.mock('@/api/workflowRuns', () => ({ workflowRuns: { candidates: vi.fn(), candidate: vi.fn(), rejectCandidate: vi.fn() } }))
const api = vi.mocked(workflowRuns); let wrapper: VueWrapper | undefined
const button = (name: string) => wrapper!.findAll('button').find(node => node.text() === name)!
beforeEach(() => { vi.resetAllMocks(); vi.spyOn(window, 'confirm').mockReturnValue(true); const draft = candidate(); api.candidates.mockResolvedValue({ items: [{ ...draft, sourceState: 'RUNNING', createdAt: '' }], nextCursor: null }); api.candidate.mockResolvedValue(draft) })
afterEach(() => { wrapper?.unmount(); vi.restoreAllMocks() })
async function render() { wrapper = mount(WorkflowCandidates, { props: { requirement: 'req' } }); await flushPromises(); await wrapper.get('.workflow-candidate-list button').trigger('click'); await flushPromises() }
describe('candidate review', () => {
  it('keeps rejection identity on an unknown response and never repeats an accepted rejection after readback fails', async () => {
    await render(); await wrapper!.get('input').setValue('先调整设计'); api.rejectCandidate.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({ id: 'req', revision: 2, version: 8, layoutVersion: 4, state: 'PAUSED' })
    await button('退回候选').trigger('click'); await flushPromises(); expect(button('收起候选').attributes('disabled')).toBeDefined()
    api.candidates.mockRejectedValueOnce(new Error('offline')); await button('重试原操作').trigger('click'); await flushPromises(); expect(api.rejectCandidate.mock.calls[0]).toEqual(api.rejectCandidate.mock.calls[1]); expect(button('刷新操作结果')).toBeDefined()
    await button('刷新操作结果').trigger('click'); await flushPromises(); expect(api.rejectCandidate).toHaveBeenCalledTimes(2); expect(wrapper!.emitted('changed')).toBeDefined()
  })
  it('drops late details when the user switches to history and does not auto-review a draft', async () => {
    await render(); let resolve!: (value: ReturnType<typeof candidate>) => void; api.candidate.mockReturnValueOnce(new Promise(done => { resolve = done }))
    await wrapper!.get('.workflow-candidate-list button').trigger('click'); await wrapper!.get('select').setValue(''); await flushPromises(); resolve(candidate()); await flushPromises()
    expect(wrapper!.find('.workflow-candidate-detail').exists()).toBe(false); expect(wrapper!.emitted('review')).toBeUndefined(); expect(api.candidates.mock.calls.at(-1)![1]).toBe('')
  })
})
