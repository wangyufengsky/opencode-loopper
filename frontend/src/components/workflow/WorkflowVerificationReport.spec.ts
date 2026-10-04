import { describe, expect, it } from 'vitest'
import { mount } from '@/pages/w6-tests/workflow/react-test-root'
import { WorkflowVerificationReport as WorkflowVerificationReport } from '@/pages/w5/workflow/reports'

describe('fixed delivery check report', () => {
  it('shows each actual result and never converts an unknown result into a pass', () => {
    const wrapper = mount(WorkflowVerificationReport, { props: { content: { version: 1, passed: true, checks: [
      { title: '内容检查', path: 'config.json', state: 'PASS' },
      { title: '旧文件清理', path: 'old.json', state: 'FAIL' },
      { state: 'future-code', detail: '无法读取固定文件' },
    ] } } })
    const rows = wrapper.findAll('article')
    expect(rows[0]!.text()).toContain('通过'); expect(rows[1]!.text()).toContain('未通过'); expect(rows[2]!.text()).toContain('无法检查')
    expect(wrapper.text()).not.toContain('future-code'); expect(rows[2]!.text()).toContain('无法读取固定文件'); wrapper.unmount()
  })
  it.each([null, { version: 2, checks: [] }, { version: 1, checks: [] }])('shows a readable error for missing or unsupported evidence', content => {
    const wrapper = mount(WorkflowVerificationReport, { props: { content } })
    expect(wrapper.get('[role=alert]').text()).toContain('无法读取'); expect(wrapper.find('article').exists()).toBe(false); wrapper.unmount()
  })
})
