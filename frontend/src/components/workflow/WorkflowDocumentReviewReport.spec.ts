import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import WorkflowDocumentReviewReport from './WorkflowDocumentReviewReport.vue'
const source = { fileId: 'DOC-1', section: 1 }
const entry = { title: '金额校验', statement: '金额为正数', sources: [source], issues: [], assessment: { requirementKey: 'RQ-1', conclusion: 'SATISFIED', rationale: '正数条件存在', checkedPaths: ['code.java'], evidence: [{ path: 'code.java', blobSha: 'private-hash', startLine: 1, endLine: 1, quote: 'amount > 0' }], missingEntryEvidence: null, testSourceCoverage: '暂无测试源码', limitations: ['没有执行代码'] } }
const candidate = { snapshotSha: 'private-commit', entries: [entry], findings: [], skippedSections: [], limitations: ['外部实现未知'] }
const opinion = { snapshotSha: 'private-commit', approved: false, reviewedRequirementKeys: ['RQ-1'], reviewedFindingKeys: [], checkedSections: [source], corrections: [{ requirementKey: 'RQ-1', findingKey: null, source, detail: '补充入口调用证据' }] }
describe('原文代码评审报告', () => {
  it('显示业务结论和原文位置，明确静态评审未运行测试', () => {
    const view = mount(WorkflowDocumentReviewReport, { props: { content: candidate } })
    expect(view.text()).toContain('符合需求'); expect(view.text()).toContain('静态代码评审 · 未运行测试'); expect(view.text()).toContain('文档 1 · 第 1 章')
    expect(view.get('pre').text()).toBe('amount > 0'); expect(view.text()).not.toContain('private-'); expect(view.text()).not.toContain('RQ-1'); expect(view.text()).not.toContain('DOC-1')
  })
  it('问题明细区分缺陷和验证缺口，并保留影响程度', () => {
    const finding = { key: 'private-finding', kind: 'VALIDATION_GAP', severity: 'HIGH', title: '缺少边界验证', trigger: '金额溢出', impact: '错误金额可能通过', recommendation: '补充边界测试', evidence: [] }
    const view = mount(WorkflowDocumentReviewReport, { props: { content: { ...candidate, findings: [finding] } } })
    expect(view.text()).toContain('验证缺口 · 高影响'); expect(view.text()).toContain('补充边界测试'); expect(view.text()).not.toContain('private-finding')
  })
  it('有效返修不呈现为通过，修正位置和意见可查', () => {
    const view = mount(WorkflowDocumentReviewReport, { props: { review: true, content: opinion } })
    expect(view.text()).toContain('需要返修'); expect(view.text()).toContain('补充入口调用证据'); expect(view.text()).not.toContain('复核通过')
  })
  it.each([null, {}, { ...opinion, approved: true }, { ...opinion, checkedSections: [] }, { ...opinion, corrections: [{ detail: '没有来源' }] }])('格式缺失或通过意见矛盾时拒绝显示成功', content => {
    const view = mount(WorkflowDocumentReviewReport, { props: { review: true, content } }); expect(view.get('[role=alert]').text()).toContain('无法读取'); expect(view.text()).not.toContain('复核通过')
  })
  it('有业务歧义时不能呈现确定性结论', () => {
    const view = mount(WorkflowDocumentReviewReport, { props: { content: { ...candidate, entries: [{ ...entry, issues: ['单位未知'] }] } } })
    expect(view.get('[role=alert]').text()).toContain('无法读取'); expect(view.text()).not.toContain('符合需求')
  })
  it('原文和代码引用仅作为文本，保留未适用章节理由', () => {
    const view = mount(WorkflowDocumentReviewReport, { props: { content: { ...candidate, entries: [{ ...entry, statement: '<img src=x onerror=alert(1)>' }], skippedSections: [{ source: { fileId: 'DOC-2', section: 3 }, reason: '目录章节' }] } } })
    expect(view.find('img').exists()).toBe(false); expect(view.text()).toContain('文档 2 · 第 3 章：目录章节')
  })
})
