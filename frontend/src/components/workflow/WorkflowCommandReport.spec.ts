import { describe, expect, it } from 'vitest'
import { mount } from '@/pages/w6-tests/workflow/react-test-root'
import { WorkflowCommandReport as WorkflowCommandReport } from '@/pages/w5/workflow/reports'
const report = (overrides = {}) => ({ version: 1, type: 'COMMAND', valid: true, passed: true, exitCode: 0, timedOut: false, cancelled: false, outputTruncated: false, output: '实际检查输出', errorCode: '', reportExcerpt: false, ...overrides })
describe('command report presentation', () => {
  it('distinguishes a valid failed check from an incomplete execution and escapes output', () => {
    const wrapper = mount(WorkflowCommandReport, { props: { content: report({ passed: false, exitCode: 3, output: '<img src=x onerror=alert(1)>' }) } })
    expect(wrapper.text()).toContain('检查未通过'); expect(wrapper.text()).toContain('进程退出码：3'); expect(wrapper.get('.w3-code-text').text()).toContain('<img'); expect(wrapper.find('img').exists()).toBe(false); wrapper.unmount()
  })
  it('does not display a passed claim when the execution was incomplete', () => {
    const wrapper = mount(WorkflowCommandReport, { props: { content: report({ valid: false, passed: true, timedOut: true, outputTruncated: true, reportExcerpt: true, errorCode: 'COMMAND_OUTPUT_INCOMPLETE' }) } })
    expect(wrapper.text()).toContain('检查未完成'); expect(wrapper.text()).not.toContain('检查通过'); expect(wrapper.text()).toContain('超过执行时限'); expect(wrapper.text()).toContain('输出摘要'); expect(wrapper.text()).not.toContain('COMMAND_OUTPUT_INCOMPLETE'); wrapper.unmount()
  })
  it.each([null, report({ version: 2 }), report({ valid: 'yes' }), report({ exitCode: '0' })])('rejects unsupported report shapes', content => {
    const wrapper = mount(WorkflowCommandReport, { props: { content } }); expect(wrapper.get('[role=alert]').text()).toContain('无法读取'); wrapper.unmount()
  })
})
