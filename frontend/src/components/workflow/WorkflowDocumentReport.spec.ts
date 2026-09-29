import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkflowDocumentReport from './WorkflowDocumentReport.vue'
const report = { version: 1, type: 'DESIGN_DOCUMENT', complete: true, sourceCount: 2, draftCount: 2, reviewedCount: 2, reviseCount: 0, fileCount: 4, reviewPolicy: 'REQUIRED' }
describe('文档汇总报告', () => {
  it('区分文档生成与独立复核情况', () => {
    const view = mount(WorkflowDocumentReport, { props: { content: { ...report, reviewPolicy: 'NONE', reviewedCount: 0, reviseCount: 1 } } })
    expect(view.text()).toContain('文档已生成'); expect(view.text()).toContain('通过 0 / 2'); expect(view.text()).toContain('1 份要求返修'); expect(view.text()).toContain('未要求全部复核通过')
  })
  it('覆盖失败有具体恢复说明且没有下载成功的提示', () => {
    const view = mount(WorkflowDocumentReport, { props: { content: { version: 1, type: 'DESIGN_DOCUMENT', complete: false, code: 'SOURCE_COVERAGE_INCOMPLETE' } } })
    expect(view.text()).toContain('文档未生成'); expect(view.text()).toContain('覆盖'); expect(view.text()).not.toContain('SOURCE_COVERAGE_INCOMPLETE')
  })
  it('默认策略通过时展示实际覆盖与文件数', () => {
    const view = mount(WorkflowDocumentReport, { props: { content: report } }); expect(view.text()).toContain('生成 4 个 Markdown 文件'); expect(view.text()).toContain('通过 2 / 2')
  })
  it.each([null, {}, { ...report, reviewedCount: 1 }, { ...report, sourceCount: 0 }, { ...report, reviseCount: 1 }, { ...report, fileCount: '4' }])('损坏或矛盾状态不能显示成功', content => {
    const view = mount(WorkflowDocumentReport, { props: { content } }); expect(view.text()).toContain('无法读取'); expect(view.text()).not.toContain('文档已生成')
  })
})
it('静态评审报告保留没有复核与没有可评审需求的事实', () => {
  const view = mount(WorkflowDocumentReport, { props: { content: { ...report, type: 'ASSESSMENT_DOCUMENT', crossBatchReviewedCount: 0, reviewPolicy: 'NONE', reviewedCount: 0, requirementCount: 0, findingCount: 0, allRequirementsSatisfied: false, testExecution: 'NOT_RUN_STATIC_REVIEW', fileCount: 2 } } })
  expect(view.text()).toContain('2 个原文章节'); expect(view.text()).toContain('不能据此认定全部满足'); expect(view.text()).toContain('本次未运行'); expect(view.text()).toContain('通过 0 / 2')
})
it('拒绝虚构测试通过或空集全部满足的报告', () => {
  for (const patch of [{ testExecution: 'PASSED' }, { requirementCount: 0, allRequirementsSatisfied: true }]) {
    const view = mount(WorkflowDocumentReport, { props: { content: { ...report, type: 'ASSESSMENT_DOCUMENT', crossBatchReviewedCount: 2, requirementCount: 1, findingCount: 0, allRequirementsSatisfied: true, testExecution: 'NOT_RUN_STATIC_REVIEW', ...patch } } })
    expect(view.text()).toContain('无法读取'); expect(view.text()).not.toContain('文档已生成')
  }
})
it('自定义局部复核不会显示为跨批次核对完成', () => {
  const view = mount(WorkflowDocumentReport, { props: { content: { ...report, type: 'ASSESSMENT_DOCUMENT', reviewPolicy: 'NONE', requirementCount: 2, findingCount: 0, allRequirementsSatisfied: true, testExecution: 'NOT_RUN_STATIC_REVIEW', crossBatchReviewedCount: 0 } } })
  expect(view.text()).toContain('通过 2 / 2'); expect(view.text()).toContain('跨批次核对尚未完整')
})
