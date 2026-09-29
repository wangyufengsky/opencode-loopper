import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkflowReviewReport from './WorkflowReviewReport.vue'
const opinion = (perspective = 'REQUIREMENT', verdict = 'PASS') => ({ perspective, verdict, reason: '已检查固定代码', attempt: 'internal-attempt', basis: 'internal-basis' })
const report = () => ({ version: 1, type: 'DUAL_REVIEW', passed: true, verificationPassed: true, reviews: [opinion(), opinion('RISK')] })
describe('评审报告', () => {
  it('显示两个独立视角和程序证据，不暴露内部身份', () => {
    const view = mount(WorkflowReviewReport, { props: { content: report() } })
    expect(view.text()).toContain('验收通过'); expect(view.text()).toContain('程序验证：通过')
    expect(view.text()).toContain('需求评审'); expect(view.text()).toContain('风险评审')
    expect(view.text()).not.toContain('internal-')
  })
  it.each([
    { ...report(), verificationPassed: false },
    { ...report(), reviews: [opinion(), opinion('RISK', 'BLOCKED')] },
    { ...report(), reviews: [opinion(), opinion()] },
    { ...report(), version: 2 },
  ])('矛盾或不完整报告不显示通过', content => {
    const view = mount(WorkflowReviewReport, { props: { content } })
    expect(view.text()).toContain('无法识别'); expect(view.text()).not.toContain('验收通过')
  })
  it('保留阻断原因，并把不可信文本作为文本显示', () => {
    const content = { ...opinion('RISK', 'BLOCKED'), version: 1, type: 'REVIEW', reason: '<img src=x onerror=alert(1)>\n异常路径未覆盖' }
    const view = mount(WorkflowReviewReport, { props: { content } })
    expect(view.text()).toContain('评审未通过'); expect(view.text()).toContain('异常路径未覆盖'); expect(view.find('img').exists()).toBe(false)
  })
})
