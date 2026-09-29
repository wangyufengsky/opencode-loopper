import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkflowSnapshotSummary from './WorkflowSnapshotSummary.vue'
const planned = { version: 1, type: 'SNAPSHOT_PLAN', complete: true, unitCount: 72, excludedCount: 1, batchCount: 2, conditionalReviews: true }
const reported = { version: 1, type: 'SNAPSHOT_DOCUMENT', complete: true, sourceCount: 72, excludedCount: 1, batchCount: 2, draftCount: 2, reviewedCount: 1, candidateCount: 1, supportedCount: 1, fileCount: 6, reviewPolicy: 'REQUIRED', targetSha: 'a'.repeat(40) }
describe('版本审查流程汇总', () => {
  it('候选计划保留确认边界和条件复核', () => {
    const view = mount(WorkflowSnapshotSummary, { props: { content: planned } })
    expect(view.text()).toContain('候选计划已生成'); expect(view.text()).toContain('确认'); expect(view.text()).toContain('仅有候选问题'); expect(view.text()).not.toContain('完整报告已生成')
  })
  it('完整报告区分候选、独立支持和测试边界', () => {
    const view = mount(WorkflowSnapshotSummary, { props: { content: reported } })
    expect(view.text()).toContain('1 个候选问题'); expect(view.text()).toContain('1 个获独立支持'); expect(view.text()).toContain('未运行目标项目测试'); expect(view.text()).not.toContain('REQUIRED')
  })
  it('用户取消复核时说明候选仍未确认', () => {
    const view = mount(WorkflowSnapshotSummary, { props: { content: { ...reported, reviewPolicy: 'NONE', reviewedCount: 0, supportedCount: 0 } } })
    expect(view.text()).toContain('不要求独立复核'); expect(view.text()).toContain('仅作为候选保留')
  })
  it('无变化明确不调用分析模型', () => {
    const view = mount(WorkflowSnapshotSummary, { props: { content: { ...planned, unitCount: 0, excludedCount: 0, batchCount: 0 } } })
    expect(view.text()).toContain('无需模型分析')
  })
  it.each([{ ...planned, conditionalReviews: undefined }, { ...planned, excludedCount: 80 }, { ...reported, reviewedCount: 3 }, { ...reported, supportedCount: 2 }, { ...reported, targetSha: '<script>' }, { ...reported, draftCount: 1 }])('拒绝不一致的计数或来源 %#', content => {
    const view = mount(WorkflowSnapshotSummary, { props: { content } }); expect(view.get('[role="alert"]').text()).toContain('无法读取')
  })
  it('失败显示中文恢复说明', () => {
    const view = mount(WorkflowSnapshotSummary, { props: { content: { version: 1, type: 'SNAPSHOT_DOCUMENT', complete: false, code: 'WORKFLOW_SNAPSHOT_REPORT_INVALID' } } })
    expect(view.text()).toContain('检查完整范围'); expect(view.text()).not.toContain('WORKFLOW_')
  })
})
