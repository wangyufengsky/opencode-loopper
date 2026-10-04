import { mount } from '@/pages/w6-tests/workflow/react-test-root'
import { describe, expect, it } from 'vitest'
import { WorkflowSnapshotReport as WorkflowSnapshotReport } from '@/pages/w5/workflow/reports'
import { snapshotAnalysis, snapshotReview } from './snapshotTestFixtures'
describe('版本代码审查交付', () => {
  it('以业务名称展示批次、问题、引用及覆盖，不显示内部身份', async () => {
    const view = mount(WorkflowSnapshotReport, { props: { content: snapshotAnalysis } })
    expect(view.text()).toContain('第 2 / 3 批版本分析'); expect(view.text()).toContain('空输入未被处理'); expect(view.text()).toContain('第 24–25 行'); expect(view.text()).toContain('归因未确定'); expect(view.text()).not.toContain('private-')
    expect(view.find('.w3-code-text').text()).toContain('validate(input)'); expect(view.text()).toContain('未运行的测试不能视为通过')
  })
  it('没有候选问题也不宣称无缺陷或已经复核', () => {
    const content = { ...snapshotAnalysis, claims: { ...snapshotAnalysis.claims, findings: [] } }
    const view = mount(WorkflowSnapshotReport, { props: { content } }); expect(view.text()).toContain('未进行问题独立复核'); expect(view.text()).toContain('不代表代码没有缺陷')
  })
  it('复用明确显示来源而不伪装本次模型执行', () => {
    const reuse = { sourceRequirementId: 'private-source', sourceAttemptId: 'private-attempt', sourceTitle: '上周固定版本审查', sourceNodeTitle: '第一批分析', sourceDeliverySha256: 'a'.repeat(64) }
    const content = { ...snapshotAnalysis, reuse, claims: { ...snapshotAnalysis.claims, findings: [], limitations: [], coverage: snapshotAnalysis.claims.coverage.map(c => ({ ...c, evidence: [], limitations: [] })) } }
    const view = mount(WorkflowSnapshotReport, { props: { content } })
    expect(view.text()).toContain('本次没有创建模型会话'); expect(view.get('a').text()).toBe('上周固定版本审查'); expect(view.get('a').attributes('href')).toBe('/requirements/private-source'); expect(view.text()).not.toContain('private-')
    const invalid = mount(WorkflowSnapshotReport, { props: { content: { ...snapshotAnalysis, reuse } } }); expect(invalid.get('[role="alert"]').text()).toContain('无法读取')
  })
  it('复核显示逐项判定及其自身证据', () => {
    const view = mount(WorkflowSnapshotReport, { props: { content: snapshotReview } }); expect(view.text()).toContain('证据支持'); expect(view.text()).toContain('独立读取'); expect(view.text()).not.toContain('private-')
  })
  it.each([
    null, { ...snapshotAnalysis, version: 9 }, { ...snapshotAnalysis, batchOrdinal: 3 },
    { ...snapshotAnalysis, locations: [] }, { ...snapshotAnalysis, claims: { ...snapshotAnalysis.claims, coverage: [] } },
    { ...snapshotAnalysis, claims: { ...snapshotAnalysis.claims, findings: [{ ...snapshotAnalysis.claims.findings[0], evidence: [] }] } },
    { ...snapshotReview, claims: { ...snapshotReview.claims, decisions: [] } },
    { ...snapshotReview, claims: { ...snapshotReview.claims, decisions: [{ ...snapshotReview.claims.decisions[0], verdict: 'MADE_UP' }] } },
  ])('损坏或矛盾的结果不显示成功结论', content => {
    const view = mount(WorkflowSnapshotReport, { props: { content } }); expect(view.get('[role="alert"]').text()).toContain('无法读取'); expect(view.text()).not.toContain('证据支持')
  })
})
