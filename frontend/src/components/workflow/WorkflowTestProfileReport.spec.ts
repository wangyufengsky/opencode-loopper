import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkflowTestProfileReport from './WorkflowTestProfileReport.vue'
import WorkflowNodeEditor from './WorkflowNodeEditor.vue'
import type { WorkflowNode } from '@/types/workflow'
const module = { root: '.', framework: 'junit', sourcePaths: ['src/main/java/Main.java'], testRoots: ['src/test/java'], fixtureRoots: ['src/test/resources'], command: ['mvn', '-f', 'a module/pom.xml', 'test'] }
const profile = { version: 1, type: 'SOURCE_TEST_PROFILE', sourceAttemptId: 'private-attempt', source: { snapshotId: 'private-source' }, profile: { manifestSha256: 'a'.repeat(64), modules: [module] } }
describe('固定测试配置', () => {
  it('显示原生命令的参数边界和范围，不把识别当作测试通过', () => {
    const view = mount(WorkflowTestProfileReport, { props: { content: profile } })
    expect(view.text()).toContain('测试尚未执行'); expect(view.text()).toContain('JUnit'); expect(view.text()).toContain('src/test/resources')
    expect(view.findAll('.workflow-test-command code').map(el => el.text())).toEqual(module.command)
    expect(view.text()).not.toContain('测试通过'); expect(view.text()).not.toContain('private-attempt'); expect(view.text()).not.toContain('private-source')
  })
  it('失败保留明确的文件原因并转义不可信文本', () => {
    const view = mount(WorkflowTestProfileReport, { props: { content: { version: 1, type: 'SOURCE_TEST_PROFILE', complete: false, message: 'pom.xml 缺少配置 <img src=x>' } } })
    expect(view.text()).toContain('配置未确定'); expect(view.text()).toContain('pom.xml'); expect(view.find('img').exists()).toBe(false)
  })
  it.each([null, {}, { ...profile, profile: { ...profile.profile, modules: [] } }, { ...profile, profile: { ...profile.profile, modules: [{ ...module, framework: 'toString' }] } }, { ...profile, profile: { ...profile.profile, modules: [{ ...module, command: [] }] } }, { ...profile, profile: { ...profile.profile, modules: [module, module] } }, { version: 1, type: 'SOURCE_TEST_PROFILE', complete: true, sourceCount: 0, moduleCount: 1 }])('损坏配置不会显示有效识别', content => {
    const view = mount(WorkflowTestProfileReport, { props: { content } }); expect(view.text()).toContain('无法读取'); expect(view.text()).not.toContain('配置已识别')
  })
  it('缩小输出目录时保留节点其他参数，并尊重编辑锁', async () => {
    const node: WorkflowNode = { id: 'profile', title: '识别测试配置', kind: 'SYSTEM', moduleId: 'system.source.test-profile', moduleVersion: 1, roleId: null, task: '识别', inputs: [], outputs: [], outcomes: [], completion: { kind: 'DELIVERABLES', criterion: '', expectedOutcome: null }, maxRetries: 0, pauseAfter: false, parameters: { keep: 'original' } }
    const view = mount(WorkflowNodeEditor, { props: { node, graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } } })
    const input = view.get('input[placeholder="留空使用项目原生测试目录"]'); await input.setValue('src/test/java/unit')
    expect((view.emitted('change')?.at(-1)?.[0] as WorkflowNode).parameters).toEqual({ keep: 'original', testOutputPath: 'src/test/java/unit' })
    expect(view.text()).toContain('实际测试由后续节点执行'); await view.setProps({ disabled: true }); expect(input.element.closest('fieldset')?.disabled).toBe(true)
  })
})
