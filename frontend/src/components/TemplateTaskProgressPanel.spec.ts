import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import TemplateTaskProgressPanel from './TemplateTaskProgressPanel.vue'
import type { Task } from '@/types/domain'

function task(status: Task['status'] = 'RUNNING'): Task {
  return { id: 't', projectId: 'p', projectName: '项目', title: '报告', goal: '', branch: 'main', worktreePath: '',
    status, attemptCount: 2, maxAttempts: 12, createdAt: '', updatedAt: '', executionMode: 'TEMPLATE_REPORT',
    templateProgress: { reviewBatches: 25, contributorBatches: 5, completedReviews: 20, completedContributors: 0,
      activeBatches: 1, failedBatches: 0, repairRound: 0, documentPath: '/reports/task/round' } }
}
describe('Template progress', () => {
  it('shows remaining work including analysis batches that have no session yet', () => {
    const wrapper = mount(TemplateTaskProgressPanel, { props: { task: task() }, global: { stubs: { ElProgress: true } } })
    expect(wrapper.text()).toContain('已完成 20 / 30 个分析批次')
    expect(wrapper.text()).toContain('剩余 10 个')
    expect(wrapper.text()).toContain('人员贡献 0 / 5')
    expect(wrapper.text()).toContain('/reports/task/round')
  })
  it('keeps report review separate from finished analysis and shows the repair round', () => {
    const value = task('JUDGING')
    value.templateProgress = { ...value.templateProgress!, completedReviews: 25, completedContributors: 5, activeBatches: 0, repairRound: 1 }
    const wrapper = mount(TemplateTaskProgressPanel, { props: { task: value }, global: { stubs: { ElProgress: true } } })
    expect(wrapper.text()).toContain('剩余 0 个')
    expect(wrapper.text()).toContain('评审报告')
    expect(wrapper.text()).toContain('第 1 轮返修')
    expect(wrapper.text()).not.toContain('已完成任务')
  })
  it('does not invent totals before evidence has been collected', () => {
    const value = task()
    value.templateProgress = { ...value.templateProgress!, reviewBatches: null, contributorBatches: null }
    const wrapper = mount(TemplateTaskProgressPanel, { props: { task: value }, global: { stubs: { ElProgress: true } } })
    expect(wrapper.text()).toContain('采集完成后显示分析批次总数')
    expect(wrapper.text()).not.toContain('剩余')
  })
})
