import { enableAutoUnmount, flushPromises, mount } from '@/pages/w6-tests/knowledge-ppt-template/react-test-root'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {ProgressProjection as TemplateTaskProgressPanel} from '@/pages/w6-tests/knowledge-ppt-template/task-panels'
import type { Task } from '@/types/domain'
import { CANVAS_RUNTIME_STORAGE } from '@/migration/canvasRuntime'
enableAutoUnmount(afterEach)
beforeEach(() => localStorage.removeItem(CANVAS_RUNTIME_STORAGE))
afterEach(() => localStorage.removeItem(CANVAS_RUNTIME_STORAGE))

function task(status: Task['status'] = 'RUNNING'): Task {
  return { id: 't', projectId: 'p', projectName: '项目', title: '报告', goal: '', branch: 'main', worktreePath: '',
    status, attemptCount: 2, maxAttempts: 12, createdAt: '', updatedAt: '', executionMode: 'TEMPLATE_REPORT',
    templateProgress: { reviewBatches: 25, contributorBatches: 5, completedReviews: 20, completedContributors: 0,
      activeBatches: 1, failedBatches: 0, repairRound: 0, documentPath: '/reports/task/round' } }
}
describe('Template progress', () => {
  it('shows lightweight steps and conditional finding reviews without recursive planning', async () => {
    const value = task()
    value.templateProgress = { ...value.templateProgress!, reviewBatches: 8, contributorBatches: 1, completedReviews: 4,
      currentPhase: 'ANALYSIS', steps: [{ key: 'SNAPSHOT', label: '准备范围', state: 'COMPLETE' },
        { key: 'ANALYSIS', label: '代码分析', state: 'ACTIVE' }, { key: 'SNAPSHOT_REVIEW', label: '问题复核与报告', state: 'PENDING' }],
      snapshot: { mode: 'DATE_INCREMENTAL', targetSha: 'target', baselineSha: 'base', planRevision: 1, supplements: 0, lightweight: true,
        phases: [{ label: '代码分析', total: 8, completed: 4 }, { label: '问题复核', total: 1, completed: 0 }] } }
    const wrapper = mount(TemplateTaskProgressPanel, { props: { task: value }, global: { stubs: { SnapshotReviewBatchesPanel: true, TemplateBatchRecoveryPanel: true } } })
    await flushPromises()
    expect(wrapper.findAll('[data-canvas-kind="template-progress"] .react-flow__node')).toHaveLength(3)
    expect(wrapper.find('[data-canvas-runtime="react"] .react-flow').exists()).toBe(true)
    expect(wrapper.findAll('.template-progress>p:not(.w4-muted)')).toHaveLength(2)
    expect(wrapper.text()).toContain('轻量审查')
    expect(wrapper.text()).toContain('仅发现候选问题的批次增加复核')
    expect(wrapper.text()).toContain('无问题结论不另行复核')
    expect(wrapper.text()).not.toContain('计划修订')
    expect(wrapper.text()).not.toContain('补充批次')
  })
  it('shows remaining work including analysis batches that have no session yet', () => {
    const wrapper = mount(TemplateTaskProgressPanel, { props: { task: task() }, global: { stubs: { ElProgress: true } } })
    expect(wrapper.text()).toContain('已完成 20/30 个分析批次')
    expect(wrapper.find('[aria-label="剩余 10 个"]').exists()).toBe(true)
    expect(wrapper.findAll('.template-progress>p:not(.w4-muted)').at(-1)?.text()).toContain('人员贡献 · 0/5')
    expect(wrapper.text()).toContain('/reports/task/round')
  })
  it('keeps report review separate from finished analysis and shows the repair round', () => {
    const value = task('JUDGING')
    value.templateProgress = { ...value.templateProgress!, completedReviews: 25, completedContributors: 5, activeBatches: 0, repairRound: 1 }
    const wrapper = mount(TemplateTaskProgressPanel, { props: { task: value }, global: { stubs: { ElProgress: true } } })
    expect(wrapper.find('[aria-label="剩余 0 个"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('评审报告')
    expect(wrapper.text()).toContain('第 1 轮返修')
    expect(wrapper.text()).not.toContain('已完成任务')
  })
  it('shows final cleanup after deterministic validation without claiming a review', () => {
    const value = task('AWAITING_DECISION')
    value.templateProgress = { ...value.templateProgress!, dualReviewRequired: false }
    const wrapper = mount(TemplateTaskProgressPanel, { props: { task: value }, global: { stubs: { ElProgress: true } } })
    expect(wrapper.text()).toContain('完成收尾')
    expect(wrapper.text()).not.toContain('评审报告')
  })
  it('does not invent totals before evidence has been collected', () => {
    const value = task()
    value.templateProgress = { ...value.templateProgress!, reviewBatches: null, contributorBatches: null }
    const wrapper = mount(TemplateTaskProgressPanel, { props: { task: value }, global: { stubs: { ElProgress: true } } })
    expect(wrapper.text()).toContain('采集完成后显示分析批次总数')
    expect(wrapper.text()).not.toContain('剩余')
  })
  it('mounts server steps when they arrive and tears down the React root when they disappear', async () => {
    const value = task('PAUSED')
    const wrapper = mount(TemplateTaskProgressPanel, { props: { task: value }, global: { stubs: { ElButton: true } } })
    expect(wrapper.find('.react-flow').exists()).toBe(false)
    await wrapper.setProps({ task: { ...value, templateProgress: { ...value.templateProgress!, steps: [{ key: 'review', label: '独立复核', state: 'ACTIVE' }] } } })
    await flushPromises()
    expect(wrapper.get('.template-step').attributes('aria-current')).toBe('step')
    const host = wrapper.get('.readonly-diagram').element
    await wrapper.setProps({ task: value })
    expect(wrapper.find('.react-flow').exists()).toBe(false)
    expect(host.isConnected).toBe(false)
    expect(wrapper.text()).toContain('已完成 20/30 个分析批次')
  })
  it('Vue rollback preserves both the template step sequence and nested stage details', () => {
    localStorage.setItem(CANVAS_RUNTIME_STORAGE, JSON.stringify({ documents: 'vue', tasks: 'vue' }))
    const value = task('PAUSED')
    value.templateProgress = { ...value.templateProgress!, steps: [{ key: 'review', label: '独立复核', state: 'INTERRUPTED' }] }
    value.stages = [{ id: 'stage', ordinal: 1, objective: '原执行规范', status: 'PAUSED', attempts: [] }]
    const wrapper = mount(TemplateTaskProgressPanel, { props: { task: value }, global: { stubs: { ElButton: true } } })
    expect(wrapper.find('[data-canvas-kind="template-progress"][data-canvas-runtime="react"]').exists()).toBe(true)
    expect(wrapper.find('.template-step.interrupted').text()).toContain('独立复核')
    expect(wrapper.find('[data-canvas-kind="stages"][data-canvas-runtime="react"]').exists()).toBe(true)
    expect(wrapper.find('.react-flow').exists()).toBe(true)
    expect(wrapper.text()).toContain('原执行规范')
    expect(wrapper.text()).toContain('已完成 20/30 个分析批次')
  })
})
