import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkflowDocumentPlanReport from './WorkflowDocumentPlanReport.vue'
const report = { version: 1, type: 'DOCUMENT_REVIEW_PLAN', complete: true, sectionCount: 2, batchCount: 2, batches: [{ ordinal: 0, characters: 49000, sections: [{ fileId: 'DOC-1', section: 1 }] }, { ordinal: 1, characters: 10, sections: [{ fileId: 'DOC-1', section: 2 }] }] }
describe('原文分批候选', () => {
  it('完整展示超长单章及明确的人工确认步骤', () => {
    const view = mount(WorkflowDocumentPlanReport, { props: { content: report } })
    expect(view.text()).toContain('2 个原文章节，分为 2 批'); expect(view.text()).toContain('确认后再选择执行方式'); expect(view.text()).toContain('原文 1 · 第 2 章'); expect(view.text()).not.toContain('DOC-1')
  })
  it.each([null, {}, { ...report, sectionCount: 3 }, { ...report, batches: [report.batches[0], { ...report.batches[1], sections: report.batches[0]!.sections }] }, { ...report, batches: [report.batches[0], { ...report.batches[1], ordinal: 4 }] }])('缺失和重复章节不能显示成功', content => {
    const view = mount(WorkflowDocumentPlanReport, { props: { content } }); expect(view.text()).toContain('无法读取'); expect(view.text()).not.toContain('候选计划已生成')
  })
  it('容量错误提示保留资料并给出下一步', () => {
    const view = mount(WorkflowDocumentPlanReport, { props: { content: { version: 1, type: 'DOCUMENT_REVIEW_PLAN', complete: false, code: 'WORKFLOW_DOCUMENT_PLAN_LIMIT' } } }); expect(view.text()).toContain('未截断章节'); expect(view.text()).not.toContain('WORKFLOW_DOCUMENT_PLAN_LIMIT')
  })
})
