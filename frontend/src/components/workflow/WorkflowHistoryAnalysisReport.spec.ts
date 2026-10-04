import { mount } from '@/pages/w6-tests/workflow/react-test-root'
import { describe, expect, it } from 'vitest'
import { WorkflowHistoryAnalysisReport as WorkflowHistoryAnalysisReport } from '@/pages/w5/workflow/reports'
const source = { version: 1, type: 'GIT_HISTORY', snapshotId: 'internal-snapshot', sha256: 'a'.repeat(64) }
const review = { version: 1, type: 'HISTORY_REVIEW', source, batchOrdinal: 0, batchCount: 2,
  locations: [{ unitId: 'internal-unit', commitSha: 'b'.repeat(40), path: 'src/Example.java' }],
  reviews: [{ unitId: 'internal-unit', summary: '已检查变更', limitations: ['敏感正文未读取'], findings: [{ severity: 'HIGH', side: 'BEFORE', line: 9, title: '<img src=x>', detail: '异常分支未处理', recommendation: '补充处理' }] }] }
const dimension = { level: 2, reason: '实现并覆盖相关场景', evidenceIds: ['owned'] }
const contribution = { version: 1, type: 'HISTORY_CONTRIBUTION', source, reviewAttempts: ['internal-review'], person: { author: { name: '开发者', identity: 'mail', email: 'mail', robot: false }, commits: ['sha'], evidenceIds: ['owned'], rawLines: 10.5, effectiveLines: 5.5 }, assessment: { identity: 'mail', summary: '本人实现', value: dimension, difficulty: dimension, quality: dimension, maintenance: dimension } }
describe('历史审查与贡献交付', () => {
  it('显示文件、准确位置与局限，转义输入且不输出内部身份', () => {
    const view = mount(WorkflowHistoryAnalysisReport, { props: { content: review } })
    expect(view.text()).toContain('第 1 / 2 批'); expect(view.text()).toContain('src/Example.java'); expect(view.text()).toContain('变更前第 9 行'); expect(view.text()).toContain('敏感正文未读取')
    expect(view.text()).not.toContain('internal-'); expect(view.find('img').exists()).toBe(false); expect(view.text()).toContain('<img src=x>')
  })
  it('贡献只显示四维等级与固定计量，机器人不显示排名', () => {
    const view = mount(WorkflowHistoryAnalysisReport, { props: { content: { ...contribution, person: { ...contribution.person, author: { ...contribution.person.author, robot: true } } } } })
    expect(view.text()).toContain('质量与验证证据 · 2 / 4'); expect(view.text()).toContain('机器人，报告不参与排名'); expect(view.text()).toContain('有效 5.5 行'); expect(view.text()).not.toContain('internal-')
  })
  it.each([null, {}, { ...review, version: 2 }, { ...review, batchOrdinal: 2 }, { ...review, locations: [] }, { ...review, reviews: [review.reviews[0], review.reviews[0]] },
    { ...contribution, assessment: { ...contribution.assessment, identity: 'other' } }, { ...contribution, assessment: { ...contribution.assessment, quality: { ...dimension, evidenceIds: ['foreign'] } } }])('拒绝矛盾或无来源的数据', content => {
    const view = mount(WorkflowHistoryAnalysisReport, { props: { content } }); expect(view.get('[role=alert]').text()).toContain('无法读取')
  })
})
