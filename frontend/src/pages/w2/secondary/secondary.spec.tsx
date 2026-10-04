import { StrictMode, type ReactNode } from 'react'
import { cleanup, fireEvent, render, screen, waitFor, within, act } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from '@/api/client'
import { knowledgeApi } from '@/api/knowledge'
import { pptApi } from '@/api/ppt'
import { FoundationProvider } from '@/foundation/provider'
import { skins } from '@/themes/registry'
import { semanticName } from '@/foundation/semanticRegistry'
import type { W2PageProps, W2LeaveGuard, TaskPortSnapshot } from '../shared/types'
import type { DatabaseConnection, DatabaseTypeProfile, KnowledgeConversation, Project, TaskInsight, RuntimeInfo, McpToolPolicy } from '@/types/domain'
import { HomePage, RuntimePage, InsightsPage, KnowledgeHistoryPage, DatabasePage, ToolsPage, PptListPage } from './index'
const project: Project = { id: 'p-1', name: '财务系统', rootPath: '/repos/finance', status: 'READY', updatedAt: '', taskCount: 2, openDesignerSessionCount: 0 }
const conversation = { id: 'k-1', projectId: 'p-1', title: '索引方案讨论', state: 'IDLE', model: 'local/read', createdAt: '2026-10-03T10:00:00Z', sources: [], version: 3, updatedAt: '2026-10-03T10:00:00Z', options: { contractVersion: 1, archivedAt: null, lastActivityAt: '2026-10-03T10:00:00Z', version: 3 } } satisfies KnowledgeConversation
const profile: DatabaseTypeProfile = { id: 'mysql', type: 'MYSQL', label: 'MySQL', driverClass: 'driver', defaultPort: 3306, binaries: [{ filename: 'mysql.jar', sha256: 'known' }] }
const connection: DatabaseConnection = { id: 'db-1', name: '财务库', config: { type: 'MYSQL', jdbcUrl: 'jdbc:mysql://server/data', host: 'server', port: 3306, database: 'data', username: 'reader', driverProfile: 'mysql', driverFile: '', driverClass: '', schemas: ['data'], parameters: {}, timeoutSeconds: 10, maxRows: 200 }, passwordConfigured: true, enabled: true, archived: false, projectIds: ['p-1'], version: 4, createdAt: '' }
const usage = { inputTokens: null, outputTokens: null, totalTokens: null, unknownUsageCount: 2, costByCurrency: { USD: '1', CNY: '2' } } satisfies TaskInsight['usage']
const insight = { taskId: 't-1', title: '核对报表', state: 'SUCCEEDED', durationMs: 2000, retryCount: 1, usage, quality: { state: 'REVIEW_REQUIRED', deterministicPassed: true, verificationCount: 1, verificationPassedCount: 1, humanApproved: true, requirementJudgePassed: false, riskJudgePassed: false } } satisfies TaskInsight
const policy: McpToolPolicy = { name: 'find', description: '<script>untrusted()</script>Find code', configurable: true, writes: false, globalEnabled: true, projectOverride: 'INHERIT', enabled: true, source: 'GLOBAL', globalVersion: 5, projectVersion: -1 }
const deferred = <T,>() => { let resolve!: (value: T) => void, reject!: (value: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { resolve, reject, promise } }
function props(path = '/') {
  const guards = new Set<W2LeaveGuard>(), dispose = new Map<object, () => void>(), listeners = new Set<() => void>()
  let snapshot: TaskPortSnapshot = { usingDemo: false, projects: [project], tasks: [], taskItems: [], taskFacets: {}, loading: false }
  const task = { getSnapshot: () => snapshot, subscribe: (cb: () => void) => { listeners.add(cb); return () => listeners.delete(cb) }, refreshRuntime: vi.fn(async () => snapshot.runtime), startRuntime: vi.fn(async () => snapshot.runtime), restartRuntime: vi.fn(async () => snapshot.runtime), loadProjects: vi.fn(async () => [project]), loadTaskSummaries: vi.fn(), invalidateTaskSummaries: vi.fn(), setTaskArchived: vi.fn(), deleteArchivedTask: vi.fn(), activateDemo: vi.fn(), deactivateDemo: vi.fn(), replaceProject: vi.fn(), addProject: vi.fn(), removeProject: vi.fn() }
  const value: W2PageProps = { route: { path, fullPath: path, query: {}, params: {} }, navigation: { go: vi.fn(async () => true), goAccepted: vi.fn(async () => true), back: vi.fn(), guardChanged: vi.fn(), registerGuard: cb => { guards.add(cb); return () => { guards.delete(cb) } } }, legacy: { task }, skin: skins[0]!, setSkin: vi.fn(), lifecycle: { retain: (owner, release) => dispose.set(owner, release) } }
  return { value, task, policy: () => [...guards][0]?.(), retire: () => { dispose.forEach(fn => fn()); dispose.clear() }, publish: (next: Partial<TaskPortSnapshot>) => { snapshot = { ...snapshot, ...next }; listeners.forEach(cb => cb()) } }
}
const frame = (child: ReactNode) => <FoundationProvider skin={skins[0]!} reducedMotion>{child}</FoundationProvider>
const click = (key: Parameters<typeof semanticName>[0], target?: string) => fireEvent.click(screen.getByRole('button', { name: semanticName(key, target) }))
beforeEach(() => {
  vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
  const getStyle = window.getComputedStyle.bind(window); vi.spyOn(window, 'getComputedStyle').mockImplementation(element => getStyle(element))
  sessionStorage.clear()
  vi.spyOn(api, 'getProjects').mockResolvedValue([project]); vi.spyOn(knowledgeApi, 'history').mockResolvedValue({ items: [conversation], facets: {} })
  vi.spyOn(api, 'getDatabaseConnections').mockResolvedValue({ items: [connection], facets: {} }); vi.spyOn(api, 'getDatabaseTypes').mockResolvedValue([profile]); vi.spyOn(api, 'getDatabaseDrivers').mockResolvedValue([])
  vi.spyOn(api, 'getInsightsPage').mockResolvedValue({ tasks: [insight], usage, generatedAt: '2026-10-03T10:00:00Z', nextCursor: undefined })
  vi.spyOn(api, 'getMcpServers').mockResolvedValue({ servers: [{ id: 'search', name: 'Code search', status: 'connected', type: 'local' }], complete: true, checkedAt: '' })
  vi.spyOn(api, 'getMcpToolPolicies').mockResolvedValue({ tools: [policy], complete: true, detail: '' }); vi.spyOn(api, 'getSkills').mockResolvedValue({ skills: [{ name: 'review', description: '审查代码', location: '/skills/review/SKILL.md' }], checkedAt: '', complete: true })
  vi.spyOn(pptApi, 'list').mockResolvedValue({ items: [], facets: {} })
})
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
describe('W2 secondary pages render their real React paths', () => {
  it('Home reads actual projects/history, filters locally and exposes original navigation', async () => {
    const f = props(); render(frame(<HomePage {...f.value} />)); await screen.findByRole('button', { name: /财务系统/ }); expect(screen.queryByRole('complementary')).toBeNull()
    fireEvent.change(screen.getByLabelText('搜索项目与最近对话'), { target: { value: '索引' } }); expect(screen.queryByRole('button', { name: /财务系统/ })).toBeNull(); expect(screen.getByRole('button', { name: '索引方案讨论' })).toBeTruthy(); expect(api.getProjects).toHaveBeenCalledTimes(1)
    click('workflow.newRequirement'); expect(f.value.navigation.go).toHaveBeenCalledWith('/requirements/new')
  })
  it('Home selection is presentation only and Escape restores its caller', async () => {
    const f = props(), view = render(frame(<HomePage {...f.value} />)), item = await screen.findByRole('button', { name: /财务系统/ }); fireEvent.click(item)
    const panel = screen.getByRole('complementary'); expect(f.value.navigation.go).not.toHaveBeenCalled(); fireEvent.keyDown(panel, { key: 'Escape' }); expect(screen.queryByRole('complementary')).toBeNull(); expect(document.activeElement).toBe(item); view.unmount()
  })
  it('Home keeps native modifier links and uses its owner only for ordinary SPA clicks', async () => {
    const f = props(); render(frame(<HomePage {...f.value} />)); await screen.findByRole('button', { name: /财务系统/ })
    const link = screen.getByRole('link', { name: '任务' })
    for (const modifier of [{ ctrlKey: true }, { metaKey: true }, { shiftKey: true }, { altKey: true }, { button: 1 }]) {
      const event = new MouseEvent('click', { bubbles: true, cancelable: true, ...modifier }); fireEvent(link, event)
      expect(event.defaultPrevented).toBe(false); expect(f.value.navigation.go).not.toHaveBeenCalled()
    }
    const event = new MouseEvent('click', { bubbles: true, cancelable: true }); fireEvent(link, event)
    expect(event.defaultPrevented).toBe(true); expect(f.value.navigation.go).toHaveBeenCalledWith('/tasks')
  })
  it('Runtime distinguishes service version, process generation and internal MCP without capability cards', () => {
    const f = props('/runtime'); f.publish({ runtime: { status: 'ONLINE', version: 'cli1', loopperVersion: 'service2', managed: true, generation: '8', model: 'local/model', checkedAt: '', internalMcp: { status: 'CONNECTED', configured: true } } as RuntimeInfo })
    const view = render(frame(<RuntimePage {...f.value} />)); expect(view.container.querySelector('.loopper-version')?.textContent).toContain('service2'); expect(view.container.querySelector('.managed-generation')?.textContent).toBe('8'); expect(screen.getByText(/OpenCode cli1/)).toBeTruthy(); expect(screen.queryByText('执行授权')).toBeNull()
  })
  it('Runtime UNKNOWN explicit reads do not guess that ONLINE proves the original restart', async () => {
    const f = props('/runtime'); f.publish({ runtime: { status: 'ONLINE', managed: true, checkedAt: '' } as RuntimeInfo }); f.task.restartRuntime.mockRejectedValueOnce(new Error('timeout'))
    render(frame(<RuntimePage {...f.value} />)); click('runtime.restart'); await waitFor(() => expect(f.policy()?.kind).toBe('BLOCK')); click('receipt.readOriginal'); await waitFor(() => expect(f.task.refreshRuntime).toHaveBeenCalledTimes(2)); expect(f.task.restartRuntime).toHaveBeenCalledTimes(1); expect(f.policy()?.kind).toBe('BLOCK')
  })
  it('Insights preserves applied filters through cursor paging and resets cursor for a new search', async () => {
    vi.mocked(api.getInsightsPage).mockResolvedValueOnce({ tasks: [insight], usage, generatedAt: '2026-10-03T10:00:00Z', nextCursor: 'next' })
    const f = props('/insights'); render(frame(<InsightsPage {...f.value} />)); await screen.findByRole('button', { name: /核对报表/ }); click('ui.loadMore'); await waitFor(() => expect(api.getInsightsPage).toHaveBeenLastCalledWith({ archive: 'ACTIVE', cursor: 'next' }))
    fireEvent.change(screen.getByLabelText('质量筛选'), { target: { value: 'PASS' } }); fireEvent.submit(screen.getByRole('form', { name: '洞察筛选' })); await waitFor(() => expect(api.getInsightsPage).toHaveBeenLastCalledWith(expect.objectContaining({ quality: 'PASS', cursor: undefined })))
  })
  it('Insights keeps unknown tokens/currencies separate and real human approval and deep link', async () => {
    const f = props('/insights'); render(frame(<InsightsPage {...f.value} />)); fireEvent.click(await screen.findByRole('button', { name: /核对报表/ })); expect(screen.getByText('USD 1 · CNY 2')).toBeTruthy(); expect(screen.getByText(/人工认定通过/)).toBeTruthy(); expect(screen.getByRole('link', { name: /查看任务评审/ }).getAttribute('href')).toBe('/tasks/t-1#judge-review')
  })
  it('Knowledge archive keeps accepted/read-failed state and recovers with no extra POST', async () => {
    const f = props('/knowledge/history'); const write = vi.spyOn(knowledgeApi, 'archive').mockResolvedValue({ ...conversation, options: { contractVersion: 1, lastActivityAt: '2026-10-03T10:00:00Z', version: 4, archivedAt: 'now' } }); render(frame(<KnowledgeHistoryPage {...f.value} />)); fireEvent.click(await screen.findByRole('button', { name: /索引方案讨论/ })); vi.mocked(knowledgeApi.history).mockRejectedValueOnce(new Error('read failed')); click('knowledge.archive')
    await waitFor(() => expect(f.policy()?.kind).toBe('BLOCK')); expect(screen.getByRole('complementary')).toBeTruthy(); click('receipt.readOriginal'); await waitFor(() => expect(f.policy()?.kind).toBe('ALLOW')); expect(write).toHaveBeenCalledTimes(1)
  })
  it('Knowledge UNKNOWN archive verifies the original ID/version/state with an explicit GET', async () => {
    const f = props('/knowledge/history'), write = vi.spyOn(knowledgeApi, 'archive').mockRejectedValue(new Error('timeout')), read = vi.spyOn(knowledgeApi, 'get').mockResolvedValue({ ...conversation, options: { contractVersion: 1, lastActivityAt: '2026-10-03T10:00:00Z', version: 4, archivedAt: 'now' } })
    render(frame(<KnowledgeHistoryPage {...f.value} />)); fireEvent.click(await screen.findByRole('button', { name: /索引方案讨论/ })); click('knowledge.archive'); await waitFor(() => expect(f.policy()?.kind).toBe('BLOCK')); click('receipt.readOriginal'); await waitFor(() => expect(f.policy()?.kind).toBe('ALLOW')); expect(read).toHaveBeenCalledWith('k-1'); expect(write).toHaveBeenCalledTimes(1)
  })
  it('Knowledge selected context uses refreshed archive/version and disappears when its row leaves the result', async () => {
    const f = props('/knowledge/history'); f.value.route = { ...f.value.route, fullPath: '/knowledge/history?archive=all', query: { archive: 'all' } }
    const archived = { ...conversation, options: { ...conversation.options, archivedAt: '2026-10-03T11:00:00Z', version: 4 } }
    const write = vi.spyOn(knowledgeApi, 'archive').mockResolvedValue(archived)
    render(frame(<KnowledgeHistoryPage {...f.value} />)); fireEvent.click(await screen.findByRole('button', { name: /索引方案讨论/ }))
    vi.mocked(knowledgeApi.history).mockResolvedValueOnce({ items: [archived], facets: {} }); click('knowledge.archive')
    await screen.findByRole('button', { name: '恢复对话' }); await waitFor(() => expect(f.policy()?.kind).toBe('ALLOW'))
    expect(write).toHaveBeenCalledWith('k-1', true, 3)
    vi.mocked(knowledgeApi.history).mockResolvedValueOnce({ items: [], facets: {} }); click('knowledge.restoreArchive')
    await waitFor(() => expect(screen.queryByRole('complementary')).toBeNull()); expect(write).toHaveBeenLastCalledWith('k-1', false, 4)
  })
  it('Knowledge props query changes restore the actual scope without an initial implicit replace', async () => {
    const f = props('/knowledge/history'); const view = render(frame(<StrictMode><KnowledgeHistoryPage {...f.value} /></StrictMode>)); await screen.findByRole('button', { name: /索引方案讨论/ }); expect(f.value.navigation.go).not.toHaveBeenCalled()
    view.rerender(frame(<StrictMode><KnowledgeHistoryPage {...f.value} route={{ ...f.value.route, fullPath: '/knowledge/history?query=恢复', query: { query: '恢复' } }} /></StrictMode>))
    await waitFor(() => expect((screen.getByLabelText('搜索历史对话') as HTMLInputElement).value).toBe('恢复')); await waitFor(() => expect(knowledgeApi.history).toHaveBeenLastCalledWith('', '', expect.objectContaining({ query: '恢复' })))
    view.rerender(frame(<StrictMode><KnowledgeHistoryPage {...f.value} /></StrictMode>)); await waitFor(() => expect((screen.getByLabelText('搜索历史对话') as HTMLInputElement).value).toBe('')); expect(f.value.navigation.go).not.toHaveBeenCalled()
  })
  it('Tools keeps Skills lazy, escapes descriptions and searches read tools', async () => {
    const f = props('/tools'); render(frame(<ToolsPage {...f.value} />)); const item = await screen.findByRole('button', { name: /Code search/ }); expect(api.getSkills).not.toHaveBeenCalled(); fireEvent.click(item); await screen.findByText(policy.description!); expect(document.querySelector('script')).toBeNull(); fireEvent.change(screen.getByLabelText('搜索 MCP 和已读取工具'), { target: { value: 'find' } }); expect(within(screen.getByRole('list', { name: 'MCP 服务列表' })).getAllByRole('button', { name: /Code search/ })).toHaveLength(1)
    fireEvent.click(screen.getByRole('tab', { name: '技能（Skill）' })); await screen.findByRole('button', { name: /review/ }); expect(api.getSkills).toHaveBeenCalledWith(''); expect(screen.queryByText(policy.description!)).toBeNull()
  })
  it('Skills sanitize Markdown and preserve raw lines while rejecting a late old document', async () => {
    const stale = deferred<Awaited<ReturnType<typeof api.getSkillDocument>>>(); vi.spyOn(api, 'getSkillDocument').mockImplementationOnce(() => stale.promise).mockResolvedValue({ name: 'review', description: '', location: '', content: '# 使用说明\n<script>unsafe()</script>\n[跳转](javascript:alert(1))' })
    const f = props('/tools'); render(frame(<ToolsPage {...f.value} />)); fireEvent.click(screen.getByRole('tab', { name: '技能（Skill）' })); fireEvent.click(await screen.findByRole('button', { name: /review/ })); fireEvent.click(screen.getByRole('tab', { name: '工具' })); await act(async () => stale.resolve({ name: 'old', description: '', location: '', content: 'OLD' })); expect(screen.queryByText('OLD')).toBeNull()
    fireEvent.click(screen.getByRole('tab', { name: '技能（Skill）' })); fireEvent.click(await screen.findByRole('button', { name: /review/ })); await screen.findByRole('heading', { name: '使用说明' }); expect(document.querySelector('script')).toBeNull(); expect(document.querySelector('a[href^="javascript:"]')).toBeNull(); click('tools.rawSkill'); expect(screen.getByLabelText('Markdown 源文').textContent).toContain('1  # 使用说明')
  })
  it('Tools never exposes a required or incomplete tool permission switch', async () => {
    vi.mocked(api.getMcpToolPolicies).mockResolvedValue({ tools: [{ ...policy, configurable: false, source: 'SYSTEM' }], complete: true, detail: '' }); const f = props('/tools'); render(frame(<ToolsPage {...f.value} />)); fireEvent.click(await screen.findByRole('button', { name: /Code search/ })); await screen.findByText('系统必需，不可关闭'); expect(screen.queryByRole('checkbox', { name: 'find 工具策略' })).toBeNull()
  })
  it('Tools accepts then retries only failed readback without another versioned PUT', async () => {
    const f = props('/tools'), write = vi.spyOn(api, 'updateMcpToolPolicy').mockResolvedValue(undefined); render(frame(<ToolsPage {...f.value} />)); fireEvent.click(await screen.findByRole('button', { name: /Code search/ })); await screen.findByRole('checkbox', { name: 'find 工具策略' }); vi.mocked(api.getMcpToolPolicies).mockRejectedValueOnce(new Error('read failed')); fireEvent.click(screen.getByRole('checkbox', { name: 'find 工具策略' })); await waitFor(() => expect(f.policy()?.kind).toBe('BLOCK')); click('receipt.readOriginal'); await waitFor(() => expect(f.policy()?.kind).toBe('ALLOW')); expect(write).toHaveBeenCalledExactlyOnceWith({ projectId: '', serverId: 'search', toolName: 'find', enabled: 0, version: 5 })
  })
  it('Database draft checks do not save and editing invalidates a late probe', async () => {
    const f = props('/databases'), check = deferred<Awaited<ReturnType<typeof api.testDatabaseDraft>>>(), test = vi.spyOn(api, 'testDatabaseDraft').mockReturnValue(check.promise), save = vi.spyOn(api, 'saveDatabaseConnection')
    render(frame(<DatabasePage {...f.value} />)); fireEvent.click(await screen.findByRole('button', { name: /财务库/ })); click('database.edit'); click('database.test'); await waitFor(() => expect(test).toHaveBeenCalledTimes(1)); fireEvent.change(screen.getByLabelText('连接名称'), { target: { value: '已修改' } }); await act(async () => check.resolve({ connected: true, sessionReadOnly: true, compatibilityVerified: false, serverProduct: 'stale-result', serverVersion: '', driverVersion: '', driverSha256: '', detail: '' })); expect(screen.queryByText(/stale-result/)).toBeNull(); expect(save).not.toHaveBeenCalled(); expect(f.policy()?.kind).toBe('CONFIRM_DISCARD')
  })
  it('Database restores each versioned connection probe and keeps another connection error scoped', async () => {
    const other = { ...connection, id: 'db-2', name: '归档测试库', archived: true, enabled: false }
    vi.mocked(api.getDatabaseConnections).mockResolvedValue({items:[connection,other],facets:{}})
    const result = {connected:true,sessionReadOnly:false,readOnlyEnforced:true,compatibilityVerified:false,serverProduct:'SQL Server',serverVersion:'2022',driverVersion:'',driverSha256:'',detail:'原连接检查结果'}
    const probe=vi.spyOn(api,'testDatabaseConnection').mockResolvedValueOnce(result).mockRejectedValueOnce(new Error('另一连接检查失败'))
    const f=props('/databases');render(frame(<DatabasePage {...f.value}/>))
    fireEvent.click(await screen.findByRole('button',{name:/财务库/}));click('database.test');await screen.findByText('原连接检查结果')
    fireEvent.click(screen.getByRole('button',{name:/归档测试库/}));click('database.test');await screen.findByRole('alert')
    fireEvent.click(screen.getByRole('button',{name:/财务库/}));expect(screen.getByText('原连接检查结果')).toBeTruthy();expect(screen.queryByRole('alert')).toBeNull()
    fireEvent.click(screen.getByRole('button',{name:/归档测试库/}));expect(screen.getByRole('alert').textContent).toContain('另一连接检查失败');expect(screen.queryByText('原连接检查结果')).toBeNull()
    expect(probe.mock.calls).toEqual([['db-1'],['db-2']])
    vi.mocked(api.getDatabaseConnections).mockResolvedValue({items:[{...connection,version:5},other],facets:{}});click('ui.refresh')
    await waitFor(()=>expect(api.getDatabaseConnections).toHaveBeenCalledTimes(2));fireEvent.click(screen.getByRole('button',{name:/财务库/}));expect(screen.queryByText('原连接检查结果')).toBeNull();expect(probe).toHaveBeenCalledTimes(2)
  })
  it('Database accepted but failed list read retains password/draft and recovery does not PUT twice', async () => {
    const f = props('/databases'), write = vi.spyOn(api, 'saveDatabaseConnection').mockResolvedValue({ ...connection, version: 5 }); render(frame(<DatabasePage {...f.value} />)); fireEvent.click(await screen.findByRole('button', { name: /财务库/ })); click('database.edit'); fireEvent.change(screen.getByLabelText('新密码'), { target: { value: ' secret ' } }); vi.mocked(api.getDatabaseConnections).mockRejectedValueOnce(new Error('list failed')); click('ui.save')
    await waitFor(() => expect(f.policy()?.kind).toBe('BLOCK')); expect((screen.getByLabelText('新密码') as HTMLInputElement).value).toBe(' secret '); expect(screen.getByRole('complementary')).toBeTruthy(); click('receipt.readOriginal'); await waitFor(() => expect(f.policy()?.kind).toBe('ALLOW')); expect(write).toHaveBeenCalledTimes(1); expect(write.mock.calls[0]![1].password).toBe(' secret '); expect(screen.queryByLabelText('新密码')).toBeNull()
  })
  it('Database 409 retains dirty edit values and the original version', async () => {
    const f = props('/databases'), write = vi.spyOn(api, 'saveDatabaseConnection').mockRejectedValue(new ApiError('连接已经变化', 409)); render(frame(<DatabasePage {...f.value} />)); fireEvent.click(await screen.findByRole('button', { name: /财务库/ })); click('database.edit'); fireEvent.change(screen.getByLabelText('连接名称'), { target: { value: '本地名称' } }); click('ui.save'); await waitFor(() => expect(write).toHaveBeenCalledTimes(1)); await waitFor(() => expect(f.policy()?.kind).toBe('CONFIRM_DISCARD')); expect((screen.getByLabelText('连接名称') as HTMLInputElement).value).toBe('本地名称'); expect(write.mock.calls[0]![1].version).toBe(4)
  })
  it('PPT input creates no command while typing and its dirty guard retains the exact text', async () => {
    const f = props('/ppt'), create = vi.spyOn(pptApi, 'create'), send = vi.spyOn(pptApi, 'send'); render(frame(<PptListPage {...f.value} />)); await waitFor(() => expect(screen.queryByText('正在读取…')).toBeNull()); fireEvent.change(screen.getByLabelText('你想制作什么 PPT'), { target: { value: '先讨论，暂不制作' } }); expect(f.policy()?.kind).toBe('CONFIRM_DISCARD'); expect(create).not.toHaveBeenCalled(); expect(send).not.toHaveBeenCalled()
  })
  it('StrictMode setup adds only read subscriptions and no implicit writes', async () => {
    const f = props('/databases'), write = vi.spyOn(api, 'saveDatabaseConnection'); render(frame(<StrictMode><DatabasePage {...f.value} /></StrictMode>)); await screen.findByRole('button', { name: /财务库/ }); expect(write).not.toHaveBeenCalled(); expect(screen.queryByRole('complementary')).toBeNull(); expect(f.policy()?.kind).toBe('ALLOW')
  })
})
