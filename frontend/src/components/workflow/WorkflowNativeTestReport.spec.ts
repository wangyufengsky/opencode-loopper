import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkflowNativeTestReport from './WorkflowNativeTestReport.vue'
import WorkflowNodeEditor from './WorkflowNodeEditor.vue'
import { newNode } from './graph'
const result = { version: 1, type: 'SOURCE_TEST_RUN', valid: true, passed: true, moduleRoot: '.', framework: 'junit', counts: { total: 3, passed: 2, failed: 0, skipped: 1 }, exitCode: 0, command: ['mvn', 'test'], files: [{ path: 'target/surefire-reports/TEST-Calculator.xml', sha256: 'private-hash' }], fileCount: 1, message: '已读取原生报告', scenarioCoverageVerified: false, producerAttempt: 'private-attempt' }
const finalCode = { ...result, version: 2, inputUnchanged: true, writerLineage: ['private-attempt', 'private-first'], batches: [
  { inputName: 'design', designAttempt: 'private-design1', designSha256: 'a'.repeat(64), writerAttempt: 'private-first', scenarioCount: 1 },
  { inputName: 'design_second', designAttempt: 'private-design2', designSha256: 'b'.repeat(64), writerAttempt: 'private-attempt', scenarioCount: 1 },
] }
describe('原生单测报告', () => {
  it('多批场景按不同设计保留，显示同版回归而不泄露生产身份', () => {
    const view = mount(WorkflowNativeTestReport, { props: { content: finalCode } })
    expect(view.text()).toContain('同一份固定代码上回归 2 批场景'); expect(view.text()).toContain('原生测试通过'); expect(view.text()).not.toContain('private-')
  })
  it.each([{ ...finalCode, batches: [] }, { ...finalCode, batches: [finalCode.batches[0], finalCode.batches[0]] },
    { ...finalCode, writerLineage: ['private-first'] }, { ...finalCode, writerLineage: ['private-attempt'] },
    ...[0, 65].map(scenarioCount => ({ ...finalCode, batches: [{ ...finalCode.batches[0], scenarioCount }] }))])('缺少批次或继承关系不能显示同版回归', content => {
    const view = mount(WorkflowNativeTestReport, { props: { content } }); expect(view.get('[role="alert"]').text()).toContain('无法读取')
  })
  it('显示实际执行与跳过数量，区分场景覆盖，不泄露内部标识', async () => {
    const view = mount(WorkflowNativeTestReport, { props: { content: result } })
    expect(view.text()).toContain('原生测试通过'); expect(view.text()).toContain('实际执行 2 项：通过 2，失败 0，另有 1 项跳过')
    expect(view.text()).toContain('测试数量不表示每个设计场景都已覆盖'); expect(view.text()).not.toContain('private-')
  })
  it('业务允许继续也显示真实失败，零测试不能显示通过', () => {
    const failed = mount(WorkflowNativeTestReport, { props: { content: { ...result, passed: false, exitCode: 1, counts: { total: 2, passed: 1, failed: 1, skipped: 0 } } } })
    expect(failed.text()).toContain('原生测试未通过')
    const zero = mount(WorkflowNativeTestReport, { props: { content: { ...result, counts: { total: 0, passed: 0, failed: 0, skipped: 0 } } } })
    expect(zero.text()).toContain('测试证据不完整'); expect(zero.text()).not.toContain('原生测试通过')
    expect(zero.text()).not.toContain('实际执行 0'); expect(zero.text()).toContain('未取得完整的测试执行证据')
  })
  it('固定输入变化时即使进程和测试都通过也不能显示节点通过', () => {
    const view = mount(WorkflowNativeTestReport, { props: { content: { ...result, inputUnchanged: false, message: '执行期间固定配置发生变化。' } } })
    expect(view.text()).toContain('测试证据不完整'); expect(view.text()).toContain('固定配置发生变化'); expect(view.text()).not.toContain('原生测试通过')
  })
  it('新的输入校验明确展示，历史报告不会补造校验事实', () => {
    const checked = mount(WorkflowNativeTestReport, { props: { content: { ...result, inputUnchanged: true } } })
    expect(checked.text()).toContain('已核对固定源码、测试和配置')
    const old = mount(WorkflowNativeTestReport, { props: { content: result } })
    expect(old.text()).toContain('原生测试通过'); expect(old.text()).not.toContain('已核对固定源码')
  })
  it.each([null, {}, { ...result, scenarioCoverageVerified: true }, { ...result, counts: { total: 4, passed: 2, failed: 0, skipped: 1 } }, { ...result, valid: 'true' }])('无效报告不显示通过', content => {
    const view = mount(WorkflowNativeTestReport, { props: { content } }); expect(view.get('[role="alert"]').text()).toContain('无法读取')
  })
  it('报告正文保持纯文本', () => {
    const view = mount(WorkflowNativeTestReport, { props: { content: { ...result, message: '<img src=x onerror=alert(1)>', moduleRoot: '<b>module</b>' } } })
    expect(view.text()).toContain('<img'); expect(view.find('img').exists()).toBe(false); expect(view.find('b').exists()).toBe(false)
  })
  it('模块与时限可以修改，命令仍来自固定配置', async () => {
    const node = { ...newNode('free.readonly'), kind: 'SYSTEM' as const, moduleId: 'system.source.test-run', roleId: null, roleRevisionId: null, parameters: { testModuleRoot: '.', testTimeoutSeconds: '600' } }
    const view = mount(WorkflowNodeEditor, { props: { node, graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } }, global: { stubs: { WorkflowRolePicker: true } } })
    await view.get('input[aria-label="测试模块路径"]').setValue('backend'); expect((view.emitted('change')![0]![0] as typeof node).parameters.testModuleRoot).toBe('backend')
    expect(view.text()).toContain('命令从固定测试配置生成'); expect(view.find('textarea[aria-label="命令参数"]').exists()).toBe(false)
    await view.setProps({ disabled: true }); expect((view.get('fieldset[aria-label="原生单测设置"]').element as HTMLFieldSetElement).disabled).toBe(true)
  })
})
