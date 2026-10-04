import { mount } from '@/pages/w6-tests/workflow/react-test-root'
import { describe, expect, it } from 'vitest'
import { WorkflowHistoryReport as WorkflowHistoryReport } from '@/pages/w5/workflow/reports'
const report = { version: 1, type: 'GIT_HISTORY', complete: true, branchId: 'remote:origin:refs/heads/main', commitSha: 'a'.repeat(40), projectPrefix: 'server', commitCount: 12, changeCount: 20, excludedCount: 1, startDate: '2026-09-01', endDate: '2026-09-11', timezone: 'Asia/Shanghai' }
describe('固定分支代码报告', () => {
  it('显示精确提交与文件限制，转义用户分支名称', () => {
    const view = mount(WorkflowHistoryReport, { props: { content: { ...report, branchId: 'local:refs/heads/<img src=x>' } } })
    expect(view.text()).toContain('Git 历史已固定'); expect(view.text()).toContain(report.commitSha)
    expect(view.text()).toContain('12 个提交 · 20 项文件变更 · 1 项排除计量'); expect(view.text()).toContain('本地 · <img src=x>'); expect(view.find('img').exists()).toBe(false)
  })
  it('失败报告只提供恢复办法，不把上一份摘要当作成功', () => {
    const view = mount(WorkflowHistoryReport, { props: { content: { ...report, complete: false, code: 'WORKFLOW_HISTORY_CAPTURE_FAILED' } } })
    expect(view.text()).toContain('采集未完成'); expect(view.text()).toContain('已定位的提交和日期范围保持不变')
    expect(view.text()).not.toContain('Git 历史已固定'); expect(view.text()).not.toContain(report.commitSha); expect(view.text()).not.toContain('WORKFLOW_HISTORY')
  })
  it('空范围是明确的完整记录，保留日期和时区', () => {
    const view = mount(WorkflowHistoryReport, { props: { content: { ...report, commitCount: 0, changeCount: 0, excludedCount: 0 } } })
    expect(view.text()).toContain('所选范围没有提交'); expect(view.text()).toContain('2026-09-01 至 2026-09-11'); expect(view.text()).toContain('北京时间')
  })
  it.each([null, {}, { ...report, version: 2 }, { ...report, commitSha: 'latest' }, { ...report, excludedCount: 21 }, { ...report, commitCount: 100001 }, { ...report, projectPrefix: null }])('不完整或矛盾的成功报告不能显示完成', content => {
    const view = mount(WorkflowHistoryReport, { props: { content } }); expect(view.text()).toContain('无法读取'); expect(view.text()).not.toContain('Git 历史已固定')
  })
})
