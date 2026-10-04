import type { Page } from '@playwright/test'
import type { DocumentTemplateOverview, SourceTemplateOverview, DocumentReportSummary, SourceArtifact, KnowledgeConversation, KnowledgeMessage, KnowledgeContent, KnowledgeCitation, DocumentRequirementSummary, DocumentRequirementDetail } from '../../src/types/domain'
import { catalogFixture } from '../../src/pages/w3/templates/catalog/fixtures'
import { productFixture, project } from '../w2/productFixture'
import { pptProductFixture } from './pptFixture'

const when = '2026-10-03T00:00:00Z', hash = 'c'.repeat(64)
export const reportMarkdown = '# 本地模拟报告\n\n生产 React 富文档渲染，未调用真实后端或模型。\n\n```mermaid\nflowchart LR\n  A[读取冻结原文] --> B[核对业务条目]\n  B --> C[记录证据]\n```\n\n[详细条目](details.md)'
export const documentRun: DocumentTemplateOverview = { id: 'w3-document', projectId: project.id, templateId: 'REQUIREMENT_CODE_REVIEW', templateVersion: '1', title: '需求静态评审 · 本地模拟', state: 'COMPLETED', waitingReasonCode: null, waitingMessage: null, designerId: null, taskId: null, requirementRevision: 1, version: 3, createdAt: when, updatedAt: when, canCancel: false, canResume: false, archived: false, uploadReady: true, snapshotSha: hash,
  files: [{ id: 'doc-file', filename: '需求.md', format: 'MARKDOWN', sizeBytes: 42, sha256: hash, representationSha256: hash, parserVersion: 'fixture', sectionCount: 1, limitations: [] }], progress: { attempts: 2, validated: 2, active: 0, stopped: 0, requirements: 1, reports: 1, revision: 1 } }
export const sourceRun: SourceTemplateOverview = { id: 'w3-source', projectId: project.id, templateId: 'DETAILED_DESIGN_WRITING', templateVersion: '1', title: '详细设计文档 · 本地模拟', state: 'COMPLETED', version: 3, createdAt: when, updatedAt: when, archived: false, sourcePath: 'src/main', testOutputPath: null, documentPath: '/fixture/design', requirements: '保存公开接口与业务依据', snapshot: { manifestSha256: hash, fileCount: 1, targetCount: 1, ready: true }, designerId: null, taskId: null, taskState: null, waitingReasonCode: null, waitingMessage: null, coverage: [{ status: 'COMPLETED', count: 1 }], progress: [], canResume: false, canArchive: true, testProfile: null }
export const citation: KnowledgeCitation = { id: 'w3-citation', kind: 'CODE', name: '接口实现片段 · 本地模拟', location: 'src/main/Finance.java:1–3', sha256: hash, createdAt: when }
export const conversation: KnowledgeConversation = { id: 'w3-knowledge', projectId: project.id, title: '财务流程问答 · 本地模拟', model: 'fixture/model', state: 'IDLE', sources: [{ id: 'w3-code', name: '项目代码 · 本地模拟', kind: 'CODE', state: 'READY', detail: '本地运输夹具', version: 2 }], createdAt: when, updatedAt: when, version: 3, options: { contractVersion: 1, archivedAt: null, lastActivityAt: when, version: 3 } }
export const knowledgeMessage: KnowledgeMessage = { id: 'w3-knowledge-message', ordinal: 1, state: 'COMPLETED', userText: '当前核算流程是什么？', answer: reportMarkdown, thinking: '只读核对冻结的模拟代码。', detail: '', inputTokens: 20, outputTokens: 30, createdAt: when, citations: [citation], calls: [] }
export const evidenceBody: KnowledgeContent = { kind: 'CODE', sourceId: 'w3-code', path: 'src/main/Finance.java', name: 'Finance.java', location: citation.location, sha256: hash, text: 'class Finance {\n  int amount() { return 105; }\n}', startLine: 1, endLine: 3, limitations: [] }
export const requirement: DocumentRequirementSummary = { requirementKey: 'r1', ordinal: 0, title: '保留财务核算依据', groupName: '财务', kind: 'FUNCTIONAL', issueCount: 0, conclusion: 'SATISFIED' }
export const requirementDetail: DocumentRequirementDetail = { requirement: { key: 'r1', title: requirement.title, group: '财务', kind: 'FUNCTIONAL', statement: '每笔核算保留原文和执行依据。', sources: [{ fileId: 'doc-file', section: 0, quote: '保留每笔核算依据' }], acceptance: ['可以从记录打开原文依据。'], issues: [] }, assessment: { requirementKey: 'r1', conclusion: 'SATISFIED', rationale: '模拟源码保留原记录。', evidence: [{ path: 'Finance.java', blobSha: hash, startLine: 1, endLine: 3, quote: evidenceBody.text! }], checkedPaths: ['Finance.java'], missingEntryEvidence: null, testSourceCoverage: '仅静态检查；本次未执行测试', limitations: [] } }
const documentReport: DocumentReportSummary = { id: 'w3-report', name: 'summary.md', kind: 'MARKDOWN', sha256: hash, bytes: 300, createdAt: when }
const sourceReport: SourceArtifact = { id: 'w3-artifact', name: 'overview.md', kind: 'MARKDOWN', sha256: hash, sizeBytes: 300 }
export const historicalTemplate = { id: 'w3-old-template', name: '历史冻结模板 · 本地模拟', description: '保留原合同，仅只读查看', state: 'ARCHIVED', updatedAt: when, version: 3, versions: [{ id: 'w3-old-version', templateId: 'w3-old-template', versionNumber: 2, spec: { contractVersion: 1, title: '原始冻结合同', stages: [] }, specSha256: hash, immutable: true, autoStartApproved: false, createdAt: when }] }
export const historicalRule = { id: 'w3-old-rule', name: '历史 Git 检测 · 本地模拟', projectId: project.id, templateVersionId: 'w3-old-version', triggerType: 'GIT_HEAD_CHANGED', triggerConfig: { branch: 'main' }, state: 'ENABLED', approvalMode: 'REVIEW_REQUIRED', version: 3, updatedAt: when, health: { status: 'FAILED', lastCheckedAt: when, consecutiveFailures: 2, errorMessage: '模拟 Git 读取中断，请核对记录。' } }
export const historicalRun = { id: 'w3-old-run', ruleId: historicalRule.id, triggerType: 'MANUAL', state: 'REVIEW_REQUIRED', draftId: 'w3-old-draft', taskId: null, evidence: { original: 'frozen' }, detectedAt: when, startedAt: null, endedAt: null }
export const latestExport = ' { "contractVersion": 1, "title": "原始冻结合同", "stages": [] }\n'
export const workspaceExport = '{"formatVersion":1,"templates":[],"rules":[]}\n'

/** Transport-only mocks at the actual production API, with all unexpected requests rejected. */
export async function w3ProductFixture(page: Page, withPpt = false, sharedBase?: Awaited<ReturnType<typeof productFixture>>) {
  const ppt = withPpt ? await pptProductFixture(page,{base:sharedBase}) : undefined, base = sharedBase ?? ppt?.base ?? await productFixture(page)
  const requests: { method: string; path: string; query: string; body: string | null }[] = [], unexpected: string[] = []
  const pageOf = (items: unknown[]) => ({ items, facets: {}, nextCursor: null })
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname, method = request.method()
    const doc = `/api/template-tasks/document-runs/${documentRun.id}`, source = `/api/template-tasks/source-runs/${sourceRun.id}`, conv = `/api/knowledge/conversations/${conversation.id}`
    if (!(path.startsWith(doc) || path.startsWith(source) || path.startsWith(conv) || path.startsWith('/api/automations/') || path === '/api/template-tasks/catalog' || path.startsWith('/api/template-tasks/projects/') || path.includes('/knowledge-sources') || path === '/api/interactions')) return route.fallback()
    requests.push({ method, path, query: url.search, body: request.postData() })
    if (method !== 'GET') { unexpected.push(`${method} ${path}`); return route.fulfill({ status: 501, json: { detail: '该用例不授权写入' } }) }
    if (path === '/api/automations/templates/export') return route.fulfill({ body: workspaceExport, contentType: 'application/json' })
    if (path === `/api/automations/templates/${historicalTemplate.id}/export`) return route.fulfill({ body: latestExport, contentType: 'application/json' })
    const payloads: Record<string, unknown> = {
      '/api/interactions': [], '/api/template-tasks/catalog': catalogFixture,
      [`/api/template-tasks/projects/${project.id}`]: { ...project, documentPath: '/fixture/docs' },
      [`/api/template-tasks/projects/${project.id}/branches`]: { page: pageOf([{ id: 'local:main', label: 'main', name: 'main', local: true, branch: 'main' }]), defaultBranch: { id: 'local:main', label: 'main', name: 'main', local: true, branch: 'main' }, remoteAvailable: false, remoteProblems: [] },
      [doc]: documentRun, [doc + '/requirements']: { items: [requirement], revision: 1, nextOffset: null }, [doc + '/requirements/r1']: requirementDetail,
      [doc + '/documents/doc-file/sections']: { items: [{ fileId: 'doc-file', ordinal: 0, title: '核算要求', characters: 20, sha256: hash }], nextOffset: null }, [doc + '/documents/doc-file/sections/0']: { fileId: 'doc-file', ordinal: 0, title: '核算要求', content: '# 核算要求\n保留每笔核算依据。', sha256: hash },
      [doc + '/reports']: pageOf([documentReport]), [doc + '/reports/content']: { ...documentReport, content: reportMarkdown },
      [source]: sourceRun, [source + '/batches']: pageOf([]), [source + '/coverage']: pageOf([{ runId: sourceRun.id, ordinal: 0, path: 'Finance.java', target: 1, sizeBytes: 32, sha256: hash, exclusion: null, status: 'COMPLETED', resultJson: '{"documents":["核算流程"]}' }]), [source + '/coverage/item']: { runId: sourceRun.id, ordinal: 0, path: 'Finance.java', target: 1, sizeBytes: 32, sha256: hash, exclusion: null, status: 'COMPLETED', resultJson: '{"documents":["核算流程"]}' },
      [source + '/artifacts']: pageOf([sourceReport]), [source + '/artifacts/content']: { ...sourceReport, content: reportMarkdown },
      [conv]: conversation, [conv + '/messages']: pageOf([knowledgeMessage]), [conv + '/messages/updates']: pageOf([knowledgeMessage]), [conv + '/citations/' + citation.id]: { citation, body: evidenceBody }, [conv + '/file']: evidenceBody,
      [`/api/projects/${project.id}/knowledge-sources`]: pageOf(conversation.sources),
      '/api/automations/workspace': { templates: [historicalTemplate], rules: [historicalRule], runs: [historicalRun], serverTime: when }, '/api/automations/templates': [historicalTemplate], [`/api/automations/templates/${historicalTemplate.id}`]: historicalTemplate, [`/api/automations/templates/${historicalTemplate.id}/versions`]: historicalTemplate.versions,
      '/api/automations/rules': [historicalRule], [`/api/automations/rules/${historicalRule.id}/runs`]: [historicalRun], '/api/automations/runs': { runs: [historicalRun], serverTime: when },
    }
    if (Object.hasOwn(payloads, path)) return route.fulfill({ json: payloads[path] })
    unexpected.push(`${method} ${path}${url.search}`); return route.fulfill({ status: 500, json: { detail: 'W3 缺少准确运输夹具' } })
  })
  return { requests, unexpected, errors: base.errors, base, ppt }
}
