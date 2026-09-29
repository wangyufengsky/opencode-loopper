import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkflowTestSummaryReport from './WorkflowTestSummaryReport.vue'
import WorkflowNodeEditor from './WorkflowNodeEditor.vue'
import { readFileSync } from 'node:fs'
import type { WorkflowNode } from '@/types/domain'
const report = {
  version: 1, type: 'SOURCE_TEST_SUMMARY', complete: true, passed: true, testPassed: true, reviewSatisfied: true,
  sourceCount: 2, coveredSourceCount: 2, moduleCount: 1, batchCount: 1, reviewedBatchCount: 1, reviewPolicy: 'DUAL',
  writerAttempt: 'private-writer-id',
  modules: [{ root: '.', passed: true, counts: { total: 3, passed: 2, failed: 0, skipped: 1 } }],
  batches: [{ moduleRoot: '.', scenarioCount: 2, reviewSatisfied: true, reviews: [{ attempt: 'private-review-1' }, { attempt: 'private-review-2' }] }],
}
describe('单测汇总', () => {
  it('展示最终代码、完整范围、实际执行和显式双复核', () => {
    const view = mount(WorkflowTestSummaryReport, { props: { content: report } })
    expect(view.text()).toContain('单测汇总通过'); expect(view.text()).toContain('源码覆盖 2 / 2'); expect(view.text()).toContain('同一份最终代码')
    expect(view.text()).toContain('实际执行 2 项'); expect(view.text()).toContain('跳过 1 项'); expect(view.text()).toContain('每批两位独立角色')
    expect(view.text()).not.toContain('private-'); expect(view.text()).not.toContain('SOURCE_TEST_SUMMARY')
  })
  it('允许用户明确取消复核且不伪造复核记录', () => {
    const view = mount(WorkflowTestSummaryReport, { props: { content: { ...report, reviewPolicy: 'NONE', reviewedBatchCount: 0, batches: [{ ...report.batches[0], reviews: [] }] } } })
    expect(view.text()).toContain('单测汇总通过'); expect(view.text()).toContain('按自定义策略不要求复核'); expect(view.text()).toContain('0 份意见'); expect(view.text()).not.toContain('复核通过')
  })
  it('缺失范围或复核时保留未通过结果', () => {
    const view = mount(WorkflowTestSummaryReport, { props: { content: { ...report, complete: false, coveredSourceCount: 1, passed: false, reviewSatisfied: false, batches: [{ ...report.batches[0], reviewSatisfied: false }] } } })
    expect(view.text()).toContain('单测汇总未通过'); expect(view.text()).toContain('源码覆盖 1 / 2'); expect(view.text()).toContain('复核条件未满足')
  })
  it('自定义流程中同一批跨多个模块的汇总仍可查看', () => {
    const modules = ['one', 'two'].map(root => ({ ...report.modules[0], root }))
    const batches = modules.flatMap(module => Array.from({ length: 31 }, () => ({ moduleRoot: module.root, scenarioCount: 1, reviewSatisfied: true, reviews: [] })))
    const view = mount(WorkflowTestSummaryReport, { props: { content: { ...report, moduleCount: 2, modules, batchCount: batches.length, batches, reviewPolicy: 'NONE', reviewedBatchCount: 0 } } })
    expect(view.text()).toContain('单测汇总通过'); expect(view.text()).toContain('查看 62 批场景'); expect(view.text()).not.toContain('无法读取')
  })
  it.each([null, {}, { ...report, coveredSourceCount: 3 }, { ...report, batchCount: 2 }, { ...report, modules: [] }, { ...report, testPassed: false }, { ...report, reviewPolicy: 'unknown' }])('损坏或互相矛盾的报告不显示成功', content => {
    const view = mount(WorkflowTestSummaryReport, { props: { content } }); expect(view.text()).toContain('无法读取'); expect(view.text()).not.toContain('单测汇总通过')
  })
  it('汇总策略可编辑并保留其他参数', async () => {
    const presets = JSON.parse(readFileSync('../src/main/resources/workflows/node-presets.json', 'utf8'))
    const node: WorkflowNode = presets.presets.find((p: { id: string }) => p.id === 'source.test-summary').node
    const view = mount(WorkflowNodeEditor, { props: { node, graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } } })
    await view.get('select[aria-label="单测独立复核策略"]').setValue('NONE')
    const changed = view.emitted('change')![0]![0] as WorkflowNode
    expect(changed.parameters).toEqual({ ...node.parameters, reviewPolicy: 'NONE' }); expect(changed.completion).toEqual(node.completion)
    expect(view.text()).toContain('全部模块测试须绑定同一份最终代码')
  })
})
