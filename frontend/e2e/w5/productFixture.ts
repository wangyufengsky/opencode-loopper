import type { Page, Route } from '@playwright/test'
import { productFixture, project } from '../w2/productFixture'
import { template, preset, commandPreset } from '../../src/components/workflow/workflowTestFixtures'
import { requirement, execution, candidate, attempt } from '../../src/components/workflow/workflowRunTestFixtures'
export { project }
export const flow = template({ id: 'w5-flow', title: '交付工作流 · 模拟', description: '实际 React 生产画布，模拟数据' })
flow.graph.nodes.push({ ...flow.graph.nodes[0]!, id: 'delivery', title: '交付复核', task: '核对原始交付' })
flow.graph.edges.push({ id: 'review-delivery', from: 'review', to: 'delivery', outcome: null })
flow.layout.positions = { review: { x: 0, y: 0 }, delivery: { x: 360, y: 0 } }
export const plan = requirement({ id: 'w5-req', projectId: project.id, title: '需求计划 · 模拟', graph: flow.graph, layout: flow.layout })
export const spec = { schemaVersion: 'v2', projectId: project.id, goal: '设计报表交付 · 模拟', context: '仅模拟数据，无真实模型调用', stages: [{ objective: '核对交付依据', implementationKind: 'NON_JAVA', allowedPaths: [], forbiddenPaths: [], deliverables: ['证据报告'], acceptanceCriteria: [], verifiers: [] }], limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: 'PT2H', attemptTimeout: 'PT30M' } }
export const draft = { id: 'w5-draft', version: 4, status: 'DRAFT_READY', updatedAt: '2026-10-03', spec }
export const session = { id: 'w5-session', projectId: project.id, projectName: project.name, state: 'REVIEWING', workflowPhase: 'DISCUSSING_REQUIREMENT', activeActor: 'SYSTEM', accessMode: 'READ_ONLY', readOnly: true, discussionScope: 'REQUIREMENT', discussionRevision: 1, finalConfirmationEligible: false, autoMode: { enabled: false, state: 'DISABLED', version: 0 }, questionInteraction: { mode: 'NONE', awaitingAnswer: false }, taskProfile: { id: 'w5-profile', state: 'PROVISIONAL', decisionState: 'CONFIRMED', confirmationReady: true, intent: 'SOFTWARE_CHANGE', workflowTemplate: 'FULL_PACKAGE_DESIGN', mutationMode: 'WRITE_CODE', artifactKinds: ['SOURCE_CODE'], technologies: [], testPolicy: 'REQUIRED', executionStrategy: 'OPEN_CODE_IMPLEMENTATION', rolePackId: 'fixture', rolePackVersion: 'fixture', confidence: 100, evidence: [], resolutionSource: 'USER_CONFIRMED', decisionRequired: false, largeTaskMode: false, version: 7 }, availableProfileOverrides: ['SOFTWARE_CHANGE', 'DOCUMENT_AUTHORING'], availableArtifactOverrides: ['SOURCE_CODE', 'MARKDOWN'], reports: [], messages: [{ id: 'message-original', role: 'ASSISTANT', actor: 'REQUIREMENT_ASSISTANT', scopeKey: 'REQUIREMENT', content: '请先确认报表范围，再进入设计。\n\n这是模拟会话，未连接真实模型。', createdAt: '2026-10-03T00:00:00Z' }], draft }
export async function w5ProductFixture(page: Page, options: { write?: (route: Route) => Promise<void>; native?: boolean; sessionId?: string; base?: Awaited<ReturnType<typeof productFixture>> } = {}) {
  if (options.native) await page.addInitScript(() => { (window as any).__w5NativeStream = window.EventSource })
  const base = options.base ?? await productFixture(page)
  if (options.native) await page.addInitScript(() => {
    const Native = (window as any).__w5NativeStream as typeof EventSource, Fallback = window.EventSource
    class Recorded extends Native {
      constructor(url: string | URL, config?: EventSourceInit) { super(url, config); (window as any).__w2Streams.opened.push(String(url)) }
      close() { if (this.readyState !== Native.CLOSED) (window as any).__w2Streams.closed.push(this.url.replace(location.origin, '')); super.close() }
    }
    window.EventSource = class { constructor(url: string | URL, config?: EventSourceInit) { return /\/api\/designer-sessions\/[^/]+\/events(?:\?|$)/.test(String(url)) ? new Recorded(url, config) : new Fallback(url, config) } } as unknown as typeof EventSource
  })
  const requests: { method: string; path: string; body: string | null }[] = [], unexpected: string[] = []
  const payloads: Record<string, unknown> = {
    '/api/workflows/templates': {items:[flow],facets:{},nextCursor:null},
    '/api/workflows/requirements': {items:[plan],facets:{},nextCursor:null},
    [`/api/projects/${project.id}`]: project,
    [`/api/template-tasks/projects/${project.id}`]: project,
    '/api/workflows/templates/builtin.workflow.development': flow,
    '/api/workflows/templates/w5-flow': flow,
    '/api/workflows/node-presets': { items: [preset(), commandPreset()], nextCursor: null },
    '/api/workflows/node-presets/analysis.read/versions/1': preset(),
    '/api/workflows/node-presets/verification.command/versions/1': commandPreset(),
    '/api/workflows/requirements/w5-req': plan,
    '/api/workflows/requirements/w5-req/execution': { ...execution(), execution: { ...execution().execution, id: plan.id }, control: { ...execution().control, id: plan.id } },
    '/api/workflows/requirements/w5-req/candidates': { items: [{ ...candidate(), sourceState: 'PLANNING', createdAt: '2026-10-03' }], nextCursor: null },
    '/api/workflows/requirements/w5-req/candidates/candidate': candidate(),
    '/api/workflows/requirements/w5-req/finish': { requirementId: plan.id, version: plan.version, state: plan.state, intent: null, pending: { attempts: 0, resources: 0 } },
    '/api/workflows/requirements/w5-req/publication': null,
    '/api/workflows/requirements/w5-req/publication/sources': { items: [], nextCursor: null },
    '/api/workflows/requirements/w5-req/push': null,
    '/api/workflows/requirements/w5-req/push/remotes': ['origin'],
    '/api/workflows/requirements/w5-req/writeback': null,
    '/api/workflows/requirements/w5-req/documents': { items: [], nextCursor: null },
    '/api/workflows/requirements/w5-req/nodes/review/attempts': { items: [attempt()], nextCursor: null },
    '/api/workflows/requirements/w5-req/nodes/review/attempts/run': attempt(),
    '/api/workflows/requirements/w5-req/nodes/review/attempts/run/definition': flow.graph.nodes[0],
    '/api/workflows/requirements/w5-req/nodes/review/attempts/run/inputs': { items: [], nextCursor: null },
    '/api/workflows/requirements/w5-req/nodes/review/attempts/run/result': { result: null, notices: [] },
    '/api/workflows/requirements/w5-req/nodes/review/attempts/run/activity': { items: [], nextCursor: null },
    '/api/designer-sessions/w5-session': session,
    '/api/designer-sessions/w5-session/messages': session.messages,
    '/api/designer-sessions/w5-session/activity': { actor: 'SYSTEM', remoteState: 'IDLE', connected: true, observedAt: '2026-10-03', parts: [], usage: { totalTokens: null, unknownUsageCount: 1 } },
    '/api/loop-drafts/w5-draft': draft,
  }
  if (options.sessionId) {
    for (const suffix of ['', '/messages', '/activity']) {
      const original = `/api/designer-sessions/${session.id}${suffix}`
      payloads[`/api/designer-sessions/${options.sessionId}${suffix}`] = suffix ? payloads[original] : { ...session, id: options.sessionId }
    }
  }
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname, method = route.request().method()
    if (!path.startsWith('/api/workflows/') && !path.startsWith('/api/designer-sessions/w5-') && !path.startsWith('/api/loop-drafts/') && path !== `/api/projects/${project.id}` && path !== `/api/template-tasks/projects/${project.id}`) return route.fallback()
    requests.push({ path, method, body: route.request().postData() })
    if (options.native && /\/events$/.test(path)) return route.continue()
    if (method !== 'GET') { if (options.write) return options.write(route); unexpected.push(`${method} ${path}`); return route.fulfill({ status: 501, json: { message: '未授权模拟写入' } }) }
    if (Object.hasOwn(payloads, path)) return route.fulfill({ json: payloads[path] })
    unexpected.push(`${method} ${path}`); return route.fulfill({ status: 501, json: { message: 'W5 缺少准确运输夹具' } })
  })
  return { base, requests, unexpected, set(path: string, value: unknown) { payloads[path] = value } }
}
