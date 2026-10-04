import { mount } from '@/pages/w6-tests/workflow/react-test-root'
import { describe, expect, it } from 'vitest'
import { WorkflowSourcePlanReport as WorkflowSourcePlanReport } from '@/pages/w5/workflow/reports'
const report = { version: 1, type: 'SOURCE_DESIGN_PLAN', complete: true, sourceCount: 2, batchCount: 2, batches: [{ ordinal: 0, title: 'src', paths: ['src/Main.java'] }, { ordinal: 1, title: 'src/other', paths: ['src/other/Part.java'] }] }
describe('源码分批报告', () => {
  it.each(['SOURCE_DESIGN_PLAN', 'SOURCE_TEST_PLAN'])('显示完整范围且不把候选生成当作应用或执行成功 %s', type => {
    const view = mount(WorkflowSourcePlanReport, { props: { content: { ...report, type } } })
    expect(view.text()).toContain('2 个源码文件，分为 2 批'); expect(view.text()).toContain('查看变更与确认状态'); expect(view.text()).not.toContain('计划已应用'); expect(view.findAll('details')).toHaveLength(2)
  })
  it('容量超限明确保留原来源并要求调整范围', () => {
    const view = mount(WorkflowSourcePlanReport, { props: { content: { version: 1, type: 'SOURCE_DESIGN_PLAN', complete: false, code: 'WORKFLOW_SOURCE_PLAN_LIMIT' } } })
    expect(view.text()).toContain('分批计划未生成'); expect(view.text()).toContain('子目录'); expect(view.text()).not.toContain('WORKFLOW_SOURCE_PLAN_LIMIT')
  })
  it.each([null, {}, { ...report, sourceCount: 3 }, { ...report, batchCount: 1 }, { ...report, batches: [report.batches[0], { ...report.batches[1], paths: ['src/Main.java'] }] }, { ...report, batches: [report.batches[0], { ...report.batches[1], ordinal: 9 }] }])('损坏或遗漏的覆盖不能显示成功', content => {
    const view = mount(WorkflowSourcePlanReport, { props: { content } }); expect(view.text()).toContain('无法读取'); expect(view.text()).not.toContain('候选计划已生成')
  })
})
