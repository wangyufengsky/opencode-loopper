import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkflowTestDesignReport from './WorkflowTestDesignReport.vue'
import WorkflowNodeEditor from './WorkflowNodeEditor.vue'
import type { WorkflowNode } from '@/types/workflow'
const scenario = { key: 'private-scenario', path: 'src/calculator.py', category: 'NORMAL', title: '整数求和', steps: ['调用 total(1, 2)'], expected: '返回 3', references: [{ path: 'src/calculator.py', startLine: 1, endLine: 1, quote: 'def total(a, b):' }] }
const result = { version: 1, type: 'SOURCE_TEST_DESIGN', sourceAttemptId: 'private-source', profileAttemptId: 'private-profile', design: { title: '加法测试设计', summary: '覆盖正常和边界', scenarios: [scenario], limitations: ['需人工确认边界值'] } }
describe('单测场景交付', () => {
  it('把步骤、可观察期望和依据呈现为设计，不冒充执行证据', () => {
    const view = mount(WorkflowTestDesignReport, { props: { content: result } })
    expect(view.text()).toContain('1 个测试场景 · 尚未执行测试'); expect(view.text()).toContain('调用 total(1, 2)'); expect(view.text()).toContain('返回 3')
    expect(view.text()).toContain('正常'); expect(view.text()).toContain('第 1–1 行'); expect(view.text()).not.toContain('private-'); expect(view.text()).not.toContain('NORMAL'); expect(view.text()).not.toContain('测试通过')
    expect(view.find('details').attributes('open')).toBeUndefined()
  })
  it('对步骤、期望和引用中的 HTML 原样转义', () => {
    const view = mount(WorkflowTestDesignReport, { props: { content: { ...result, design: { ...result.design, scenarios: [{ ...scenario, steps: ['<img src=x>'], expected: '<script>alert(1)</script>' }] } } } })
    expect(view.find('img').exists()).toBe(false); expect(view.find('script').exists()).toBe(false); expect(view.text()).toContain('<img src=x>')
  })
  it.each([null, {}, { ...result, type: 'wrong' }, { ...result, design: { ...result.design, scenarios: [] } }, { ...result, design: { ...result.design, scenarios: [scenario, scenario] } }, { ...result, design: { ...result.design, scenarios: [{ ...scenario, category: 'toString' }] } }, { ...result, design: { ...result.design, scenarios: [{ ...scenario, steps: [] }] } }, { ...result, design: { ...result.design, scenarios: [{ ...scenario, references: [{ ...scenario.references[0], path: 'another.py' }] }] } }])('无效交付显示明确错误', content => {
    const view = mount(WorkflowTestDesignReport, { props: { content } }); expect(view.get('[role="alert"]').text()).toContain('无法读取'); expect(view.find('h3').exists()).toBe(false)
  })
  it('允许设置本批源码并保留配置，完成方式不显示业务结果', async () => {
    const node: WorkflowNode = { id: 'design', title: '场景', kind: 'WORK', moduleId: 'source.test-design', moduleVersion: 1, roleId: 'designer', task: '设计', inputs: [], outputs: [], outcomes: [], completion: { kind: 'DELIVERABLES', criterion: '完整覆盖', expectedOutcome: null }, maxRetries: 0, pauseAfter: true, parameters: { keep: 'original' } }
    const view = mount(WorkflowNodeEditor, { props: { node, graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } }, global: { stubs: { WorkflowRolePicker: true } } })
    await view.get('textarea[placeholder="每行一个项目内路径；留空使用全部适用目标"]').setValue('src/a.py\nsrc/b.py')
    expect((view.emitted('change')?.at(-1)?.[0] as WorkflowNode).parameters).toEqual({ keep: 'original', targetPaths: '["src/a.py","src/b.py"]' })
    expect(view.find('option[value="OUTCOME"]').exists()).toBe(false); await view.setProps({ disabled: true }); expect(view.get('textarea[placeholder="每行一个项目内路径；留空使用全部适用目标"]').element.closest('fieldset')?.disabled).toBe(true)
  })
})
