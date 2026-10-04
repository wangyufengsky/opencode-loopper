import type { Page, Route } from '@playwright/test'
import { template } from '../../src/components/workflow/workflowTestFixtures'
import { requirement } from '../../src/components/workflow/workflowRunTestFixtures'
export const project = { id: 'w2-project', name: '桌面财务项目 · 模拟', rootPath: '/fixture/finance', version: 3, description: '本地模拟运输数据，未连接后端或模型', status: 'READY', updatedAt: '2026-10-03T00:00:00Z', taskCount: 1, openDesignerSessionCount: 1 }
export const conversation = { id: 'w2-conversation', projectId: project.id, title: '数据库索引讨论 · 模拟', model: 'fixture/model', state: 'IDLE', createdAt: '2026-10-03T00:00:00Z', updatedAt: '2026-10-03T00:00:00Z', version: 3, sources: [], options: { contractVersion: 1, archivedAt: null, lastActivityAt: '2026-10-03T00:00:00Z', version: 3 } }
export const database = { id: 'w2-database', name: '报表数据库 · 模拟', config: { type: 'MYSQL', jdbcUrl: 'jdbc:mysql://fixture/report', host: 'fixture', port: 3306, database: 'report', username: 'reader', driverFile: 'mysql.jar', driverClass: 'com.mysql.cj.jdbc.Driver', driverProfile: 'mysql', schemas: ['report'], parameters: {}, timeoutSeconds: 10, maxRows: 200 }, passwordConfigured: true, enabled: true, archived: false, projectIds: [project.id], version: 4, createdAt: '2026-10-03' }
export const document = { id: 'w2-ppt', title: '季度分析演示 · 模拟', projectId: null, model: 'fixture/model', phase: 'BRIEFING', revision: 0, version: 0, archived: false, createdAt: '2026-10-03T00:00:00Z', updatedAt: '2026-10-03T00:00:00Z' }
const usage = { inputTokens: null, outputTokens: null, totalTokens: null, unknownUsageCount: 1, costByCurrency: { USD: '1', CNY: '2' } }
const slot = 'PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY'
export const role = { roleId: 'builtin.package-designer', displayName: '工作包设计师 · 模拟', description: '整理工作包设计', origin: 'BUILTIN', latestRevisionId: 'revision-2', latestRevisionNumber: 2, activeSlots: [slot], groupKey: 'package-designer', groupLabel: '工作包设计师' }
const task = { id: 'w2-task', title: '报表核对任务 · 模拟', goal: '核对报表', goalPreview: '核对报表', projectId: project.id, projectName: project.name, status: 'SUCCEEDED', executionMode: 'WORKTREE', version: 3, archived: false, attemptCount: 1, maxAttempts: 3, hasDesignHistory: true, branch: 'main', createdAt: '2026-10-03T00:00:00Z', updatedAt: '2026-10-03T00:00:00Z' }
export async function productFixture(page: Page, write?: (route: Route) => Promise<void>, options: { nativeTaskStreams?: boolean } = {}) {
  const errors: string[] = [], unexpected: string[] = [], requests: { method: string; path: string; body: string | null }[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(options => {
    const streams = { opened: [] as string[], closed: [] as string[], instances: [] as {id:number;url:string;closed:boolean;closeCalls:number}[] }; (window as unknown as { __w2Streams: typeof streams }).__w2Streams = streams
    class SimulatedStream extends EventTarget { onopen: ((event: Event) => void) | null = null; onmessage = null; onerror = null; readyState = 1; row:{id:number;url:string;closed:boolean;closeCalls:number}; constructor(readonly url: string) { super(); this.row={id:streams.instances.length+1,url,closed:false,closeCalls:0};streams.instances.push(this.row);streams.opened.push(url); queueMicrotask(() => {if(this.readyState!==2)this.onopen?.(new Event('open'))}) } close() { this.row.closeCalls++;if(!this.row.closed){this.row.closed=true;this.readyState = 2; streams.closed.push(this.url)} } }
    const Native = window.EventSource
    class RecordedTaskStream extends Native {
      constructor(url: string | URL, config?: EventSourceInit) { super(url, config); streams.opened.push(String(url)) }
      close() { if (this.readyState !== Native.CLOSED) streams.closed.push(this.url.replace(location.origin, '')); super.close() }
    }
    // Test transport option only: the real browser owns Task reconnect/cursor.
    // Existing W2/W3 fixtures retain their original simulated streams unchanged.
    window.EventSource = (options.nativeTaskStreams ? class {
      constructor(url: string | URL, config?: EventSourceInit) { return /\/api\/tasks\/[^/]+\/events(?:\?|$)/.test(String(url)) ? new RecordedTaskStream(url, config) : new SimulatedStream(String(url)) }
    } : SimulatedStream) as unknown as typeof EventSource
  }, options)
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (!['127.0.0.1', 'localhost'].includes(url.hostname)) { unexpected.push(`external ${url.origin}`); return route.abort('blockedbyclient') }
    if (!url.pathname.startsWith('/api/')) return route.continue()
    const path = url.pathname, method = route.request().method(); requests.push({ method, path, body: route.request().postData() })
    if (method !== 'GET') { if (write) return write(route); unexpected.push(`${method} ${path}`); return route.fulfill({ status: 501, json: { message: '未授权的模拟写入' } }) }
    const pageOf = (items: unknown[]) => ({ items, facets: {}, nextCursor: null })
    const payloads: Record<string, unknown> = {
      '/api/projects/summaries': [project], '/api/projects': [project], '/api/story-accounting': [],
      '/api/tasks/summaries': pageOf([task]), '/api/settings': { runtime: { serverPort: 8080, openBrowser: true, allowedRoot: '', monitorDelaySeconds: 2, designerMonitorDelayMillis: 750, abortCleanupAttempts: 3 }, openCode: { cliPath: 'opencode', mode: 'managed', baseUrl: 'http://127.0.0.1:4096', provider: '', model: '', connectTimeoutSeconds: 5, requestTimeoutSeconds: 30, startupTimeoutSeconds: 15 }, limits: { templateAnalysisConcurrency: 4, timeoutEnabled: false, maxStageAttempts: 3, maxTaskAttempts: 12, sessionErrorLimit: 3, maxDurationMinutes: 120, attemptTimeoutMinutes: 30, verifierTimeoutMinutes: 10, designerTimeoutMinutes: 30 }, retryWait: { rateLimitBaseSeconds: 60, rateLimitMaxSeconds: 300, sessionBaseSeconds: 10, sessionMaxSeconds: 60, verificationBaseSeconds: 5, verificationMaxSeconds: 30 }, publication: { httpWebHosts: ['gitlab.spdb.com'], gitlabHost: 'gitlab.spdb.com', gitlabApiBaseUrl: 'http://gitlab.spdb.com/api/v4', connectTimeoutSeconds: 3, requestTimeoutSeconds: 10 }, appliedLiveFields: [], restartRequiredFields: [] }, '/api/settings/models': [],
      '/api/runtime/opencode': { status: 'ONLINE', managed: true, generation: 'generation-7', version: 'fixture-cli', loopperVersion: 'service-fixture', model: 'fixture/model', checkedAt: '2026-10-03T00:00:00Z', endpoint: 'http://127.0.0.1:4096', internalMcp: { status: 'CONNECTED', configured: true } },
      '/api/knowledge/conversations': pageOf([conversation]), '/api/ppt/documents': pageOf([document]), '/api/ppt/projects': pageOf([project]),
      '/api/database-connections': pageOf([database]), '/api/database-connections/types': [{ id: 'mysql', type: 'MYSQL', label: 'MySQL', defaultPort: 3306, driverClass: 'com.mysql.cj.jdbc.Driver', binaries: [{ filename: 'mysql.jar', sha256: 'a'.repeat(64) }] }], '/api/database-connections/drivers': [],
      '/api/runtime/tools': { servers: [{ id: 'fixture-tools', name: '只读搜索工具 · 模拟', type: 'local', status: 'connected' }], complete: true, checkedAt: '2026-10-03T00:00:00Z' },
      '/api/runtime/tools/skills': { skills: [{ name: 'code-review', description: '本地审查技能 · 模拟', location: '/fixture/SKILL.md' }], checkedAt: '2026-10-03', complete: true },
      '/api/runtime/tools/skills/document': { name: 'code-review', description: '本地审查技能 · 模拟', location: '/fixture/SKILL.md', content: '# 本地审查说明\n仅模拟展示，不调用模型。' },
      '/api/runtime/tool-policies': { tools: [{ name: 'find', description: '只读检索', configurable: true, writes: false, globalEnabled: true, projectOverride: 'INHERIT', enabled: true, source: 'GLOBAL', globalVersion: 2, projectVersion: -1 }], complete: true, detail: '' },
      '/api/insights/page': { tasks: [{ taskId: task.id, title: task.title, state: task.status, durationMs: 2000, retryCount: 0, usage, quality: { state: 'PASS', deterministicPassed: true, verificationCount: 1, verificationPassedCount: 1, humanApproved: true, requirementJudgePassed: false, riskJudgePassed: false } }], usage, generatedAt: task.updatedAt, nextCursor: null },
      '/api/workflows/templates': pageOf([template({ id: 'w2-flow', title: '核对交付流程 · 模拟' })]), '/api/workflows/requirements': pageOf([requirement({ title: '待规划需求 · 模拟' })]),
      '/api/template-tasks/projects': pageOf([project]), '/api/template-tasks/catalog': { templates: [], dimensions: [] },
      '/api/designer-sessions/history-page': pageOf([{ id: 'w2-design', projectId: project.id, projectName: project.name, goal: '报表设计方案 · 模拟', state: 'WAITING_INPUT', workflowPhase: 'DISCUSSING_REQUIREMENT', draftId: 'w2-draft', draftStatus: 'DRAFT', resumable: true, stopRetryAvailable: false, archived: false, createdAt: task.createdAt, updatedAt: task.updatedAt }]),
      '/api/roles': pageOf([role]), '/api/role-bindings': [{ slot, profile: slot, activeRoleId: role.roleId, activeRevisionId: 'revision-2', bindingVersion: 3, label: '工作包设计 · 候选', purpose: '制作工作包设计候选' }],
    }
    if (Object.hasOwn(payloads, path)) return route.fulfill({ json: payloads[path] })
    if (path === `/api/roles/${role.roleId}`) return route.fulfill({ json: role })
    if (path === `/api/roles/${role.roleId}/revisions/revision-2`) return route.fulfill({ json: { roleId: role.roleId, revisionId: 'revision-2', revisionNumber: 2, contentSha256: 'a'.repeat(64), publishedAt: task.createdAt, manifest: { roleId: role.roleId, allowedSlots: [slot], runtimePolicy: 'WORKFLOW_ADAPTER' }, promptFragments: { 'machine-role.package-designer': '请核对当前工作包设计。' }, promptVariables: [], permissionMode: 'BASELINE', modelPolicy: 'INHERIT_WORKFLOW', nativeTools: ['read'], mcpTools: [], requiredMcpTools: [] } })
    if (path.endsWith('/revisions')) return route.fulfill({ json: pageOf([]) })
    unexpected.push(`${method} ${path}`); return route.fulfill({ status: 500, json: { message: '此入口缺少准确运输夹具' } })
  })
  return { requests, errors, unexpected }
}
