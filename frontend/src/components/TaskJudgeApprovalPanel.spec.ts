import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import TaskJudgeApprovalPanel from './TaskJudgeApprovalPanel.vue'

afterEach(() => vi.restoreAllMocks())
describe('human review decision', () => {
  it('requires confirmation, sends the displayed generation, and reloads after acceptance', async () => {
    const view = { available: true, approved: false, taskVersion: 7, cycleId: 'cycle', cycleVersion: 0, reviewBatchId: 'batch' }
    vi.spyOn(api, 'getJudgeApproval').mockResolvedValueOnce(view).mockResolvedValue({ ...view, available: false, approved: true })
    const send = vi.spyOn(api, 'approveJudges').mockResolvedValue({ ...view, available: false, approved: true })
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mount(TaskJudgeApprovalPanel, { props: { taskId: 'task', taskVersion: 7 }, global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(send).not.toHaveBeenCalled()
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(confirm).toHaveBeenCalledOnce()
    expect(send).toHaveBeenCalledWith('task', { expectedTaskVersion: 7, cycleId: 'cycle', expectedCycleVersion: 0, reviewBatchId: 'batch' })
    expect(wrapper.text()).toContain('已由人工认定通过')
    expect(wrapper.emitted('reload')).toHaveLength(1)
  })
  it('does not approve after the user cancels confirmation', async () => {
    vi.spyOn(api, 'getJudgeApproval').mockResolvedValue({ available: true, approved: false, taskVersion: 7, cycleId: 'cycle', cycleVersion: 0, reviewBatchId: 'batch' })
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const send = vi.spyOn(api, 'approveJudges')
    const wrapper = mount(TaskJudgeApprovalPanel, { props: { taskId: 'task' }, global: { plugins: [ElementPlus] } })
    await flushPromises(); await wrapper.get('button').trigger('click'); await flushPromises()
    expect(send).not.toHaveBeenCalled()
  })
})
