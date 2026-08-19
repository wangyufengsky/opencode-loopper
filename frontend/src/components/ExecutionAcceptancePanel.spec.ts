import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ExecutionAcceptancePanel from './ExecutionAcceptancePanel.vue'

describe('ExecutionAcceptancePanel', () => {
  it('shows the machine checks and warns when Designer supplied only GIT_DIFF', () => {
    const source = JSON.stringify({ stages: [
      { objective: '弱验收', verifiers: [{ type: 'GIT_DIFF', requireChanges: true }] },
      { objective: '真实验收', verifiers: [
        { type: 'PROCESS', command: ['java', '-cp', 'target/classes', 'PiCrossCheck'], outputContains: 'CROSS-CHECK PASS' },
        { type: 'GIT_DIFF', requireChanges: true },
      ] },
    ] })

    const wrapper = mount(ExecutionAcceptancePanel, { props: { source } })

    expect(wrapper.text()).toContain('验收计划')
    expect(wrapper.text()).toContain('机器执行验收')
    expect(wrapper.text()).toContain('缺少功能验收，暂时无法确认')
    expect(wrapper.text()).toContain('命令验证')
    expect(wrapper.text()).toContain('java -cp target/classes PiCrossCheck')
    expect(wrapper.text()).toContain('CROSS-CHECK PASS')
  })

  it('shows Judge criteria as planned review rather than executed coverage', () => {
    const source = JSON.stringify({ stages: [{
      objective: 'Java behavior',
      acceptanceCriteria: [{ id: 'AC-1', description: 'works', verificationMode: 'BOTH', judgeRubric: '评审边界行为' }],
      verifiers: [{ type: 'PROCESS', command: ['mvn', '-Dtest=FooTest', 'test'], processPurpose: 'TEST' }],
    }] })

    const wrapper = mount(ExecutionAcceptancePanel, { props: { source } })

    expect(wrapper.text()).toContain('最终 AI 评审')
    expect(wrapper.text()).toContain('机器 + AI')
    expect(wrapper.text()).toContain('评审边界行为')
    expect(wrapper.text()).not.toContain('AI 已通过')
  })

  it('labels legacy FILE_EXISTS checks as non-blocking', () => {
    const source = JSON.stringify({ stages: [{
      objective: '旧规范',
      verifiers: [
        { type: 'PROCESS', command: ['java', 'SelfCheck'], outputContains: 'PASS' },
        { type: 'FILE_EXISTS', path: 'target/model-output.txt' },
      ],
    }] })

    const wrapper = mount(ExecutionAcceptancePanel, { props: { source } })

    expect(wrapper.text()).toContain('兼容检查（不阻断）')
    expect(wrapper.text()).toContain('仅记录：target/model-output.txt')
  })
})
