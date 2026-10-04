import { mount } from '@/pages/w6-tests/workflow/react-test-root'
import { describe, expect, it } from 'vitest'
import { WorkflowHistorySummary as WorkflowHistorySummary } from '@/pages/w5/workflow/reports'
const planned = { version: 1, type: 'HISTORY_PLAN', complete: true, commitCount: 2, unitCount: 20, batchCount: 2, contributorCount: 1 }
const reported = { version: 1, type: 'HISTORY_DOCUMENT', complete: true, sourceCount: 2, unitCount: 20, batchCount: 2, contributorCount: 1, fileCount: 4, assessedContributorCount: 1, reportKind: 'CONTRIBUTION_REPORT' }
describe('历史流程汇总', () => {
  it('候选生成仍显示确认入口语义，不暗示已经执行', () => {
    const view = mount(WorkflowHistorySummary, { props: { content: planned } })
    expect(view.text()).toContain('候选计划已生成'); expect(view.text()).toContain('确认'); expect(view.text()).toContain('2 个审查批次')
    expect(view.text()).not.toContain('完整报告已生成')
  })
  it('报告呈现完整覆盖和历史分析局限，不把评分当成模型结论', () => {
    const view = mount(WorkflowHistorySummary, { props: { content: reported } })
    expect(view.text()).toContain('完整报告已生成'); expect(view.text()).toContain('程序计分'); expect(view.text()).toContain('未运行测试')
    expect(view.text()).not.toContain('CONTRIBUTION_REPORT')
  })
  it('空范围可完成且清楚说明没有模型分析', () => {
    const view = mount(WorkflowHistorySummary, { props: { content: { ...reported, sourceCount: 0, unitCount: 0, batchCount: 0, contributorCount: 0, assessedContributorCount: 0, fileCount: 3 } } })
    expect(view.text()).toContain('无提交'); expect(view.text()).toContain('无需模型分析')
  })
  it.each([{ ...reported, fileCount: 10001 }, { ...reported, assessedContributorCount: 2 }, { ...planned, commitCount: 0 }, { ...planned, batchCount: -1 }])('拒绝不一致的摘要 %#', content => {
    const view = mount(WorkflowHistorySummary, { props: { content } }); expect(view.get('[role="alert"]').text()).toContain('无法读取')
  })
  it('失败保留中文恢复说明', () => {
    const view = mount(WorkflowHistorySummary, { props: { content: { version: 1, type: 'HISTORY_DOCUMENT', complete: false, code: 'WORKFLOW_HISTORY_REPORT_INVALID' } } })
    expect(view.text()).toContain('报告未生成'); expect(view.text()).toContain('检查输入'); expect(view.text()).not.toContain('WORKFLOW_')
  })
})
