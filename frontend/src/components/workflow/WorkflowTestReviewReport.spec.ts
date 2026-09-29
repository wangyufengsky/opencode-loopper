import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkflowTestReviewReport from './WorkflowTestReviewReport.vue'
import WorkflowNodeEditor from './WorkflowNodeEditor.vue'
import { newNode } from './graph'
const row = { key: 'sum', title: '正常求和', path: 'src/Calculator.java', assessment: 'COVERED', status: 'COVERED', reason: '断言覆盖期望结果', tests: [{ id: 'private-id', name: 'sum', status: 'PASSED', reportPath: 'report.xml' }], references: [{ path: 'src/test/java/AddedTest.java', startLine: 1, endLine: 1, quote: 'assertEquals(3, total(1, 2));' }] }
const result = { version: 1, type: 'SOURCE_TEST_REVIEW', verdict: 'PASS', nativePassed: true, inputUnchanged: true, reason: '逐场景独立核对', scenarios: [row] }
describe('场景覆盖复核报告', () => {
  it('区分评审意见与实际执行，隐藏内部记录标识', () => {
    const view = mount(WorkflowTestReviewReport, { props: { content: result } })
    expect(view.text()).toContain('复核通过'); expect(view.text()).toContain('评审认为已覆盖'); expect(view.text()).toContain('执行通过'); expect(view.text()).toContain('覆盖判断来自本节点的独立评审')
    expect(view.text()).not.toContain('private-id'); expect(view.get('details').text()).toContain('assertEquals')
  })
  it.each([['FAILED', '关联测试失败', 'FAILED'], ['NOT_EXECUTED', '关联测试未执行', 'SKIPPED'], ['MISSING', '缺少对应测试', 'PASSED'], ['INSUFFICIENT', '断言覆盖不足', 'PASSED'], ['EVIDENCE_INCOMPLETE', '固定版本证据不足', 'PASSED']])('真实展示 %s，不把流程结论扩大为测试通过', (status, label, nativeStatus) => {
    const view = mount(WorkflowTestReviewReport, { props: { content: { ...result, verdict: 'REVISE', nativePassed: nativeStatus !== 'FAILED', scenarios: [{ ...row, status, tests: [{ ...row.tests[0], status: nativeStatus }] }] } } })
    expect(view.text()).toContain('需要修订'); expect(view.text()).toContain(label); expect(view.text()).not.toContain('复核通过')
  })
  it.each([null, {}, { ...result, inputUnchanged: null }, { ...result, nativePassed: false }, { ...result, scenarios: [{ ...row, tests: [] }] }, { ...result, scenarios: [{ ...row, references: [] }] }, { ...result, scenarios: [{ ...row, tests: [{ ...row.tests[0], status: 'SKIPPED' }] }] }])('不完整或矛盾的通过记录不能显示成功', content => {
    const view = mount(WorkflowTestReviewReport, { props: { content } }); expect(view.get('[role="alert"]').text()).toContain('无法读取'); expect(view.text()).not.toContain('复核通过')
  })
  it('引用正文按文本展示，不执行 HTML', () => {
    const view = mount(WorkflowTestReviewReport, { props: { content: { ...result, scenarios: [{ ...row, references: [{ ...row.references[0], quote: '<img src=x onerror=alert(1)>' }] }] } } })
    expect(view.find('img').exists()).toBe(false); expect(view.text()).toContain('<img')
  })
  it('实际失败个案与整体通过矛盾时不显示虚假的原生通过', () => {
    const content = { ...result, verdict: 'REVISE', scenarios: [{ ...row, status: 'FAILED', tests: [{ ...row.tests[0], status: 'FAILED' }] }] }
    const view = mount(WorkflowTestReviewReport, { props: { content } }); expect(view.get('[role="alert"]').text()).toContain('无法读取'); expect(view.text()).not.toContain('原生测试：通过')
  })
  it('预设保持固定交付与业务结果，允许选择继续策略', () => {
    const node = { ...newNode('free.readonly'), moduleId: 'source.test-review', outputs: [{ name: 'review', title: '场景复核', kind: 'DECISION' as const, required: true }], outcomes: ['PASS', 'REVISE'] }
    const view = mount(WorkflowNodeEditor, { props: { node, graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } }, global: { stubs: { WorkflowRolePicker: true } } })
    expect(view.get('[aria-label="专业交付物"]').text()).toContain('场景复核'); expect(view.text()).not.toContain('添加交付物'); expect(view.text()).not.toContain('添加业务结果')
    expect(view.find('option[value="DELIVERABLES"]').exists()).toBe(true); expect(view.find('option[value="OUTCOME"]').exists()).toBe(true)
  })
})
