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

    expect(wrapper.text()).toContain('实际执行验收')
    expect(wrapper.text()).toContain('只有 Git 差异检查，无法证明 Designer 描述的功能验收')
    expect(wrapper.text()).toContain('命令验证')
    expect(wrapper.text()).toContain('java -cp target/classes PiCrossCheck')
    expect(wrapper.text()).toContain('CROSS-CHECK PASS')
  })
})
