import { mount } from '@/pages/w6-tests/workflow/react-test-root'
import { describe, expect, it } from 'vitest'
import { WorkflowReviewSourceReport as WorkflowReviewSourceReport } from '@/pages/w5/workflow/reports'
const report = { version: 1, type: 'REVIEW_SOURCE', complete: true, branchId: 'local:refs/heads/main', mode: 'DATE_INCREMENTAL',
  startDate: '2026-09-11', endDate: '2026-09-12', timezone: 'Asia/Shanghai', sourceSha: 'a'.repeat(40), baselineSha: 'b'.repeat(40), targetSha: 'c'.repeat(40),
  projectPrefix: 'server/', capturedAt: '2026-09-29T00:00:00Z', noChanges: false, nonMonotonic: false, unitCount: 8, excludedCount: 1 }
describe('版本审查资料报告', () => {
  it('区分来源、基线和目标，不把采集说成审查通过', () => {
    const view = mount(WorkflowReviewSourceReport, { props: { content: { ...report, branchId: 'local:refs/heads/<img src=x>', nonMonotonic: true } } })
    expect(view.text()).toContain('审查资料已固定'); expect(view.text()).toContain('基线版本'); expect(view.text()).toContain(report.targetSha)
    expect(view.text()).toContain('8 个代码单元 · 1 项未纳入正文'); expect(view.text()).toContain('尚未生成审查结论'); expect(view.text()).toContain('提交时间存在倒序')
    expect(view.text()).toContain('本地 · <img src=x>'); expect(view.find('img').exists()).toBe(false)
  })
  it('全面模式不展示日期，增量同树保留明确的无变化结果', () => {
    const full = mount(WorkflowReviewSourceReport, { props: { content: { ...report, mode: 'FULL', startDate: null, endDate: null, baselineSha: null } } })
    expect(full.text()).toContain('全面审查'); expect(full.text()).not.toContain('日期范围'); expect(full.text()).not.toContain('基线版本')
    const unchanged = mount(WorkflowReviewSourceReport, { props: { content: { ...report, noChanges: true, unitCount: 0, excludedCount: 0 } } })
    expect(unchanged.text()).toContain('代码树相同'); expect(unchanged.text()).toContain('2026-09-11 至 2026-09-12')
  })
  it('失败结果显示恢复办法，隐藏遗留成功字段', () => {
    const view = mount(WorkflowReviewSourceReport, { props: { content: { ...report, complete: false, code: 'WORKFLOW_REVIEW_CAPTURE_FAILED' } } })
    expect(view.text()).toContain('采集未完成'); expect(view.text()).toContain('原节点'); expect(view.text()).not.toContain(report.targetSha)
    expect(view.text()).not.toContain('WORKFLOW_REVIEW'); expect(view.text()).not.toContain('审查资料已固定')
  })
  it.each([null, {}, { ...report, mode: 'LATEST' }, { ...report, targetSha: 'HEAD' }, { ...report, noChanges: true }, { ...report, excludedCount: 9 },
    { ...report, mode: 'FULL' }, { ...report, baselineSha: null }, { ...report, capturedAt: 'unknown' }])('拒绝矛盾或缺失的报告', content => {
    const view = mount(WorkflowReviewSourceReport, { props: { content } }); expect(view.text()).toContain('无法读取'); expect(view.text()).not.toContain('审查资料已固定')
  })
})
