import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkflowTestScopeReport from './WorkflowTestScopeReport.vue'
import WorkflowNodeEditor from './WorkflowNodeEditor.vue'
import { newNode } from './graph'
const result = { version: 1, type: 'SOURCE_TEST_SCOPE', passed: true, testsExecuted: false, message: '文件范围已核验' }
describe('单测写入范围报告', () => {
  it('明确区分范围通过与实际测试结果', () => {
    const view = mount(WorkflowTestScopeReport, { props: { content: result } })
    expect(view.text()).toContain('范围检查通过'); expect(view.text()).toContain('实际测试结果请查看后续验证节点'); expect(view.text()).not.toContain('测试通过')
  })
  it('失败保留具体原因和下一步，不渲染不可信 HTML', () => {
    const view = mount(WorkflowTestScopeReport, { props: { content: { ...result, passed: false, message: '<img src=x> tests/test_old.py：已有测试被删除' } } })
    expect(view.text()).toContain('范围检查未通过'); expect(view.text()).toContain('已有测试被删除'); expect(view.text()).toContain('失败成果不能直接用于后续执行'); expect(view.find('img').exists()).toBe(false)
  })
  it.each([null, {}, { ...result, type: 'wrong' }, { ...result, passed: 'true' }, { ...result, testsExecuted: true }, { ...result, message: '' }])('无效报告不显示通过', content => {
    const view = mount(WorkflowTestScopeReport, { props: { content } }); expect(view.get('[role="alert"]').text()).toContain('无法读取'); expect(view.text()).not.toContain('检查通过')
  })
  it('专用编写保留固定交付、使用设计范围且不能配置业务结果绕过检查', () => {
    const node = { ...newNode('free.write'), moduleId: 'source.test-write', outputs: [{ name: 'code', title: '固定代码', kind: 'CODE' as const, required: true }, { name: 'scope', title: '修改范围', kind: 'JSON' as const, required: true }, { name: 'summary', title: '工作说明', kind: 'TEXT' as const, required: true }] }
    const view = mount(WorkflowNodeEditor, { props: { node, graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } }, global: { stubs: { WorkflowRolePicker: true } } })
    expect(view.get('[aria-label="专业交付物"]').text()).toContain('修改范围'); expect(view.text()).toContain('使用所绑定场景设计的源码范围')
    expect(view.find('option[value="OUTCOME"]').exists()).toBe(false); expect(view.text()).not.toContain('添加业务结果'); expect(view.text()).not.toContain('添加交付物')
    expect(view.find('textarea[placeholder="每行一个项目内路径；留空使用全部适用目标"]').exists()).toBe(false)
  })
})
