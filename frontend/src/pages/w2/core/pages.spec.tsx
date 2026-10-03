import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from '@/api/client'
import { semanticName } from '@/foundation/semanticRegistry'
import { skins } from '@/themes/registry'
import { CANVAS_RUNTIME_STORAGE } from '@/migration/canvasRuntime'
import { ProjectsPage, SettingsPage, TasksPage } from './index'
import { CoreContextPanel, CoreMarkdown } from './CoreUi'
import { coreFixture, coreFrame, projectFixture, setupCoreDom, taskFixtures } from './coreTestHelpers'
import { createCoreOwner, initialCoreState } from './owner'
import { useOwnerSnapshot } from '../shared'
import { defaultSettings } from './settingsController'
import { createProjectsController } from './projectsController'
import type { AppSettings, ProjectConventionDraft, GitCredentialView } from '@/types/domain'

const fixtures: ReturnType<typeof coreFixture>[] = []
const fixture = (...args: Parameters<typeof coreFixture>) => { const value = coreFixture(...args); fixtures.push(value); return value }
const currentSettings = (): AppSettings => ({ ...defaultSettings(), openCode: { ...defaultSettings().openCode, provider: 'opencode', model: 'model-a' } })
const credential: GitCredentialView = { mode: 'CUSTOM', serverUrl: 'https://gitlab.example', username: 'shared', kind: 'TOKEN', configured: true, source: 'GLOBAL', version: 2, updatedAt: null }
const button = (key: Parameters<typeof semanticName>[0], target?: string) => screen.getByRole('button', { name: semanticName(key, target) })
const selectTask = (name: string) => fireEvent.keyDown(screen.getByRole('row', { name: semanticName('selection.select', name) }), { key: 'Enter' })
const selectProject = (name = '项目 A') => fireEvent.click(button('selection.select', name))
beforeEach(() => { setupCoreDom(); localStorage.clear() })
afterEach(() => { cleanup(); fixtures.splice(0).forEach(value => value.dispose()); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers() })

describe('actual React Task list with the existing owner projection', () => {
  it('preserves project/time/search/group/archive URL behavior and keyboard-selected contextual actions', async () => {
    const f = fixture({ usingDemo: true }), view = render(coreFrame(<TasksPage {...f.props} />))
    await waitFor(() => expect(screen.getAllByRole('link').filter(link => link.classList.contains('task-link')).map(link => link.textContent)).toEqual(['A 的新任务', 'B 的任务', 'A 的旧任务']))
    fireEvent.change(screen.getByLabelText('按项目筛选任务'), { target: { value: 'p' } }); expect(screen.getAllByRole('link').filter(link => link.classList.contains('task-link'))).toHaveLength(2)
    fireEvent.change(screen.getByLabelText('按项目筛选任务'), { target: { value: 'ALL' } }); fireEvent.change(screen.getByLabelText('按更新时间排序'), { target: { value: 'OLDEST' } })
    expect(screen.getAllByRole('link').filter(link => link.classList.contains('task-link')).map(link => link.textContent)).toEqual(['A 的旧任务', 'B 的任务', 'A 的新任务'])
    fireEvent.change(screen.getByLabelText('搜索任务'), { target: { value: 'B 的任务' } }); expect(screen.getAllByRole('link').filter(link => link.classList.contains('task-link'))).toHaveLength(1)
    expect(f.props.navigation.go).toHaveBeenLastCalledWith({ path: '/tasks', query: { order: 'oldest', q: 'B 的任务' } }, true)
    fireEvent.change(screen.getByLabelText('搜索任务'), { target: { value: '' } }); fireEvent.click(screen.getByLabelText('按项目分组'))
    expect(view.container.querySelectorAll('.task-group-header')).toHaveLength(2)
    selectTask('A 的新任务'); fireEvent.click(button('task.archive', 'A 的新任务'))
    await waitFor(() => expect(f.port.setTaskArchived).toHaveBeenCalledWith('new-a', true))
    fireEvent.change(screen.getByLabelText('选择归档范围'), { target: { value: 'ARCHIVED' } }); expect(view.container.querySelectorAll('.task-link')).toHaveLength(1)
    expect(f.props.navigation.go).toHaveBeenLastCalledWith({ path: '/tasks', query: { order: 'oldest', archive: 'archived', group: 'project' } }, true)
  })
  it('routes template query to the only server owner and never adds a task watcher', async () => {
    const f = fixture(); f.props.route = { ...f.props.route, query: { type: 'template' } }
    const view = render(coreFrame(<StrictMode><TasksPage {...f.props} /></StrictMode>))
    await waitFor(() => expect(f.port.loadTaskSummaries).toHaveBeenCalledWith(expect.objectContaining({ taskType: 'TEMPLATE' }), false))
    expect(f.port.loadProjects).toHaveBeenCalledTimes(1)
    fireEvent.change(screen.getByLabelText('按任务类型筛选'), { target: { value: 'STANDARD' } }); expect(f.props.navigation.go).toHaveBeenLastCalledWith({ path: '/tasks', query: { type: 'standard' } }, true)
    view.unmount(); f.dispose(); expect(f.port.invalidateTaskSummaries).toHaveBeenCalled(); expect(Object.keys(f.port)).not.toContain('watchTask')
  })
  it('preserves linked report/disposition and exact progress/history destinations', async () => {
    const linked = { ...taskFixtures[0]!, id: 'document', status: 'AWAITING_DECISION' as const, documentRunId: 'document', documentState: 'COMPLETED' as const, linkedTaskId: 'linked', sourceTemplateId: 'REQUIREMENT_DEVELOPMENT' }
    const f = fixture({ taskItems: [linked] }); render(coreFrame(<TasksPage {...f.props} />))
    await waitFor(() => expect(f.port.loadTaskSummaries).toHaveBeenCalled())
    selectTask(linked.title); const panel = screen.getByRole('complementary')
    expect(within(panel).queryByRole('button', { name: semanticName('task.archive', linked.title) })).toBeNull()
    expect(panel.textContent).toContain('等待处置'); fireEvent.click(button('task.openDesign', linked.title))
    expect(f.props.navigation.go).toHaveBeenCalledWith('/tasks/linked/design')
    expect(screen.getByRole('link', { name: '查看进度' }).getAttribute('href')).toBe('/template-tasks/document-runs/document')
    act(() => f.publish({ taskItems: [{ ...linked, status: 'COMPLETED' }] })); expect(button('task.archive', linked.title)).toBeTruthy()
  })
  it('permanently deletes only an archived ordinary Task after default-Stay confirmation', async () => {
    const archived = { ...taskFixtures[0]!, archived: true }, f = fixture({ usingDemo: true, tasks: [archived], taskItems: [archived] }); f.props.route = { ...f.props.route, query: { archive: 'archived' } }
    render(coreFrame(<TasksPage {...f.props} />)); selectTask(archived.title); fireEvent.click(button('task.delete', archived.title))
    const dialog = await screen.findByRole('dialog'); expect(dialog.textContent).toContain('项目文件、Git 分支与 worktree 不会删除'); expect(f.port.deleteArchivedTask).not.toHaveBeenCalled()
    const confirm = within(dialog).getByRole('button', { name: semanticName('task.delete', archived.title) }); fireEvent.click(confirm)
    await waitFor(() => expect(f.port.deleteArchivedTask).toHaveBeenCalledWith(archived.id)); expect(screen.queryByRole('link', { name: archived.title })).toBeNull()
  })
  it('shows the persisted retry countdown and project onboarding without writing', async () => {
    const retry = { ...taskFixtures[0]!, id: 'retry', title: '等待重试任务', status: 'RETRY_WAIT' as const, retryDueAt: new Date(Date.now() + 30_000).toISOString() }
    const f = fixture({ usingDemo: true, tasks: [retry], taskItems: [retry] }), view = render(coreFrame(<TasksPage {...f.props} />))
    expect(view.container.textContent).toMatch(/(?:29|30)s/); expect(f.port.setTaskArchived).not.toHaveBeenCalled(); view.unmount(); f.dispose()
    const empty = fixture({ projects: [], tasks: [], taskItems: [] }); render(coreFrame(<TasksPage {...empty.props} />))
    await screen.findByText('先登记一个项目'); fireEvent.click(screen.getAllByRole('button', { name: semanticName('project.register') })[0]!)
    expect(empty.props.navigation.go).toHaveBeenCalledWith('/projects')
  })
  it('keeps UNKNOWN visible through skin updates and denies close/navigation without repeating write', async () => {
    const f = fixture({ usingDemo: true }); vi.mocked(f.port.setTaskArchived).mockRejectedValue(new Error('任务回执未知'))
    const view = render(coreFrame(<TasksPage {...f.props} />)); selectTask('A 的新任务'); fireEvent.click(button('task.archive', 'A 的新任务'))
    await screen.findByText(/原操作身份与输入已保留/); expect([...f.guards][0]!().kind).toBe('BLOCK')
    view.rerender(coreFrame(<TasksPage {...f.props} skin={skins[1]!} />, skins[1]!)); fireEvent.keyDown(screen.getByRole('complementary'), { key: 'Escape' })
    expect(screen.getByRole('complementary')).toBeTruthy(); expect((button('task.archive', 'A 的新任务') as HTMLButtonElement).disabled).toBe(true)
    expect(f.port.setTaskArchived).toHaveBeenCalledTimes(1); vi.spyOn(api, 'getTask').mockResolvedValue({ ...taskFixtures[0]!, archived: true })
    fireEvent.click(button('ui.retry')); await waitFor(() => expect([...f.guards][0]!().kind).toBe('ALLOW')); expect(f.port.setTaskArchived).toHaveBeenCalledTimes(1)
  })
})

describe('actual React Projects and all reachable advanced contexts', () => {
  it('reads persisted stack/mode/counts, reveals the six original actions only on selection and retains deep links', async () => {
    const f = fixture({ projects: [{ ...projectFixture, rootPath: '/tmp/git/module', repositoryRoot: '/tmp/git', branch: 'main', executionMode: 'WORKTREE', description: 'Isolated changes' }] })
    const stack = vi.spyOn(api, 'getProjectStackProfile'), generate = vi.spyOn(api, 'generateProjectConvention')
    render(coreFrame(<ProjectsPage {...f.props} />)); expect(screen.queryByRole('button', { name: semanticName('project.unmanage', projectFixture.name) })).toBeNull()
    selectProject(); expect(screen.getByRole('complementary').textContent).toContain('Git 仓库：/tmp/git'); expect(screen.getByRole('complementary').textContent).toContain('Isolated changes')
    expect(screen.getByRole('complementary').textContent).toContain('Java · 2 个组件'); expect(screen.getByRole('complementary').textContent).toContain('2 个任务 · 1 个待继续设计')
    fireEvent.click(button('designer.continue', projectFixture.name)); expect(f.props.navigation.go).toHaveBeenCalledWith({ path: '/designs', query: { projectId: 'p' } })
    expect(screen.getByRole('link', { name: '模板任务' }).getAttribute('href')).toBe('/template-tasks?projectId=p')
    expect(stack).not.toHaveBeenCalled(); expect(generate).not.toHaveBeenCalled()
  })
  it('native directory selection fills root/name and validates before registration', async () => {
    const f = fixture(), pick = vi.spyOn(api, 'pickProjectDirectory').mockResolvedValue({ selected: true, path: '/tmp/example-project', name: 'example-project' })
    const write = vi.spyOn(api, 'createProject').mockResolvedValue({ ...projectFixture, id: 'registered', rootPath: '/tmp/example-project', name: 'example-project' })
    render(coreFrame(<ProjectsPage {...f.props} />)); fireEvent.click(button('project.register')); fireEvent.click(button('project.chooseDirectory', '项目根路径'))
    await waitFor(() => expect((screen.getByLabelText('项目根路径') as HTMLInputElement).value).toBe('/tmp/example-project'))
    expect((screen.getByLabelText('项目名称') as HTMLInputElement).value).toBe('example-project'); expect(write).not.toHaveBeenCalled()
    fireEvent.click(within(screen.getByRole('complementary')).getByRole('button', { name: semanticName('project.register') }))
    await waitFor(() => expect(write).toHaveBeenCalledTimes(1)); expect(pick).toHaveBeenCalledTimes(1); expect(f.port.addProject).toHaveBeenCalled()
  })
  it('keeps manual input on picker cancel/failure and confirms dirty panel close with focus return', async () => {
    const f = fixture(); vi.spyOn(api, 'pickProjectDirectory').mockResolvedValueOnce({ selected: false }).mockRejectedValueOnce(new Error('无法打开系统选择器'))
    render(coreFrame(<ProjectsPage {...f.props} />)); const origin = button('project.register'); origin.focus(); fireEvent.click(origin)
    fireEvent.change(screen.getByLabelText('项目根路径'), { target: { value: '/tmp/keep-this-path' } }); fireEvent.click(button('project.chooseDirectory', '项目根路径'))
    await waitFor(() => expect((screen.getByLabelText('项目根路径') as HTMLInputElement).disabled).toBe(false)); expect((screen.getByLabelText('项目根路径') as HTMLInputElement).value).toBe('/tmp/keep-this-path')
    fireEvent.click(button('project.chooseDirectory', '项目根路径')); await screen.findByText('无法打开系统选择器')
    fireEvent.keyDown(screen.getByRole('complementary'), { key: 'Escape' }); const dialog = await screen.findByRole('dialog'); expect((screen.getByLabelText('项目根路径') as HTMLInputElement).value).toBe('/tmp/keep-this-path')
    fireEvent.click(within(dialog).getByRole('button', { name: semanticName('ui.discardChanges') })); await waitFor(() => expect(screen.queryByRole('complementary')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(origin))
  })
  it('blocks an old project-switch confirmation after its actual retained owner receives a newer draft', async () => {
    const f = fixture(), create = vi.spyOn(api, 'createProject')
    render(coreFrame(<ProjectsPage {...f.props} />)); fireEvent.click(button('project.register'))
    fireEvent.change(screen.getByLabelText('项目名称'), { target: { value: 'first draft' } })
    selectProject(); const dialog = await screen.findByRole('dialog')
    const owner = [...f.retained.keys()][0] as ReturnType<typeof createProjectsController>
    expect(owner.getSnapshot().context).toBe('register')
    act(() => { owner.changeRegister('name', 'new draft') })
    const confirm = within(dialog).getByRole('button', { name: semanticName('ui.discardChanges') })
    expect((confirm as HTMLButtonElement).disabled).toBe(true); fireEvent.click(confirm)
    expect(owner.getSnapshot().context).toBe('register'); expect(owner.getSnapshot().register.name).toBe('new draft')
    expect(owner.getSnapshot().selected).toBeUndefined(); expect(create).not.toHaveBeenCalled()
  })
  it('saves document picker result/version and preserves conflict drafts in the actual context', async () => {
    const f = fixture(); vi.spyOn(api, 'pickProjectDirectory').mockResolvedValue({ selected: true, path: '/tmp/selected reports' })
    const save = vi.spyOn(api, 'updateProjectDocumentPath').mockRejectedValueOnce(new ApiError('项目设置已更新，请刷新后重试', 409)).mockResolvedValueOnce({ ...projectFixture, documentPath: '/tmp/selected reports', version: 5 })
    render(coreFrame(<ProjectsPage {...f.props} />)); selectProject(); fireEvent.click(button('project.editDocumentPath', projectFixture.name)); fireEvent.click(button('project.chooseDirectory', '项目文档路径'))
    await waitFor(() => expect((screen.getByRole('textbox', { name: '项目文档路径' }) as HTMLInputElement).value).toBe('/tmp/selected reports'))
    fireEvent.click(button('ui.save', '文档路径')); await screen.findByText('项目设置已更新，请刷新后重试'); expect(screen.getByRole('complementary')).toBeTruthy()
    fireEvent.click(button('ui.save', '文档路径')); await waitFor(() => expect(f.port.replaceProject).toHaveBeenCalledWith(expect.objectContaining({ version: 5, documentPath: '/tmp/selected reports' })))
    expect(save).toHaveBeenCalledWith('p', '/tmp/selected reports', 4)
  })
  it('shows Git/GitLab credentials, source kind/path/pattern and explicit versioned assist save', async () => {
    const f = fixture(); vi.spyOn(api, 'gitCredentials').mockResolvedValue(credential)
    const saved = { version: 3, credentialConfigured: false, config: { repository: 'group/repo', sources: [{ kind: 'LOG' as const, root: '/service/logs', pattern: '*.log' }] } }
    vi.spyOn(api, 'projectAssistConfig').mockResolvedValue(saved); const save = vi.spyOn(api, 'saveProjectAssistConfig').mockResolvedValue({ ...saved, version: 4 })
    const credentialSave = vi.spyOn(api, 'saveGitCredentials'); render(coreFrame(<ProjectsPage {...f.props} />)); selectProject(); fireEvent.click(button('project.configureAssist', projectFixture.name))
    await waitFor(() => expect((screen.getByLabelText('Git 服务器地址') as HTMLInputElement).value).toBe(credential.serverUrl))
    fireEvent.click(screen.getByRole('tab', { name: 'GitLab 与证据' })); expect(screen.getByRole('complementary').textContent).toContain('未配置，请设置环境变量')
    expect((screen.getByLabelText('允许读取的外部日志目录') as HTMLInputElement).value).toBe('/service/logs')
    fireEvent.click(button('ui.save', 'GitLab 与证据配置')); await waitFor(() => expect(save).toHaveBeenCalledWith('p', 3, 'group/repo', saved.config.sources)); expect(credentialSave).not.toHaveBeenCalled()
  })
  it('shows current AGENTS without AI, previews normalized READY output and writes only explicitly', async () => {
    const f = fixture(); vi.spyOn(api, 'getCurrentProjectConvention').mockResolvedValue({ projectId: 'p', exists: true, loopperManaged: true, content: '# Existing rules' })
    const value: ProjectConventionDraft = { id: 'd', projectId: 'p', state: 'READY', operation: 'UPDATE', readOnlyGeneration: true, content: '# Project rules', normalizationNotice: 'AI 输出已自动规范化：WRAPPER_TOLERATED', updatedAt: 'now' }
    const generate = vi.spyOn(api, 'generateProjectConvention').mockResolvedValue(value), apply = vi.spyOn(api, 'applyProjectConvention').mockResolvedValue({ ...value, state: 'APPLIED' })
    render(coreFrame(<ProjectsPage {...f.props} />)); selectProject(); fireEvent.click(button('project.readConvention', projectFixture.name))
    await screen.findByLabelText('当前 AGENTS.md 项目公约'); expect(generate).not.toHaveBeenCalled(); fireEvent.click(button('project.generateConvention'))
    await screen.findByLabelText('AGENTS.md 完整预览'); expect(screen.getByRole('complementary').textContent).toContain('已兼容常见外层格式'); expect(screen.getByRole('complementary').textContent).not.toContain('WRAPPER_TOLERATED'); expect(apply).not.toHaveBeenCalled()
    fireEvent.click(button('project.applyConvention')); await waitFor(() => expect(apply).toHaveBeenCalledWith('p', 'd'))
  })
  it('requires explicit confirmation before canceling management and preserves history/files message', async () => {
    const f = fixture(), cancel = vi.spyOn(api, 'cancelProjectManagement').mockResolvedValue(undefined); render(coreFrame(<ProjectsPage {...f.props} />)); selectProject(); fireEvent.click(button('project.unmanage', projectFixture.name))
    const dialog = await screen.findByRole('dialog'); expect(dialog.textContent).toContain('项目目录、历史任务、设计对话、LoopSpec 与执行证据都不会删除'); expect(cancel).not.toHaveBeenCalled()
    fireEvent.click(within(dialog).getByRole('button', { name: semanticName('project.unmanage') })); await waitFor(() => expect(cancel).toHaveBeenCalledWith('p'))
    expect(f.port.getSnapshot().projects).toHaveLength(0)
  })
  it('blocks UNKNOWN generation close, keeps the request across theme changes and never auto-regenerates', async () => {
    const f = fixture(), generate = vi.spyOn(api, 'generateProjectConvention').mockRejectedValue(new Error('生成回执未知')); vi.spyOn(api, 'getCurrentProjectConvention').mockResolvedValue({ projectId: 'p', exists: false, loopperManaged: false, content: '' })
    const view = render(coreFrame(<StrictMode><ProjectsPage {...f.props} /></StrictMode>)); selectProject(); fireEvent.click(button('project.readConvention', projectFixture.name)); await screen.findByText('暂无项目公约')
    fireEvent.click(button('project.generateConvention')); await screen.findByText(/原操作身份与输入已保留/)
    view.rerender(coreFrame(<StrictMode><ProjectsPage {...f.props} skin={skins[2]!} /></StrictMode>, skins[2]!)); fireEvent.keyDown(screen.getByRole('complementary'), { key: 'Escape' })
    expect(screen.getByRole('complementary')).toBeTruthy(); expect([...f.guards][0]!().kind).toBe('BLOCK'); expect(generate).toHaveBeenCalledTimes(1)
  })
})

describe('actual React Settings sections/defaults/independent credentials', () => {
  async function settingsRender() {
    vi.spyOn(api, 'getSettings').mockResolvedValue(currentSettings()); vi.spyOn(api, 'getSettingsModels').mockResolvedValue([
      { id: 'opencode/model-a', provider: 'opencode', model: 'model-a', label: 'model-a' }, { id: 'opencode/big-pickle', provider: 'opencode', model: 'big-pickle', label: 'big-pickle' },
      { id: 'other/model-c', provider: 'other', model: 'model-c', label: 'model-c' },
    ])
    const f = fixture(), view = render(coreFrame(<SettingsPage {...f.props} />)); await waitFor(() => expect((screen.getByLabelText('允许项目根（立即生效）') as HTMLInputElement).disabled).toBe(false)); return { ...f, view }
  }
  it('retains section edits, reveals validation blockers and keeps roles outside the save form', async () => {
    const { view } = await settingsRender(), save = vi.spyOn(api, 'updateSettings')
    fireEvent.change(screen.getByLabelText('允许项目根（立即生效）'), { target: { value: 'relative/path' } }); fireEvent.click(screen.getByRole('button', { name: '模型服务' })); fireEvent.click(button('settings.save'))
    await screen.findByText('允许项目根必须是绝对路径。'); expect(view.container.querySelector('#settings-runtime')?.hasAttribute('hidden')).toBe(false); expect((screen.getByLabelText('允许项目根（立即生效）') as HTMLInputElement).value).toBe('relative/path')
    fireEvent.change(screen.getByLabelText('允许项目根（立即生效）'), { target: { value: '/workspace' } }); fireEvent.click(screen.getByRole('button', { name: '模型服务' })); fireEvent.change(screen.getByLabelText('命令行路径（下次会话生效）'), { target: { value: '' } }); fireEvent.click(button('settings.save'))
    await screen.findByText('OpenCode CLI 路径不能为空。'); expect(save).not.toHaveBeenCalled(); expect(view.container.querySelector('a[href="/settings/roles"]')).toBeNull()
  })
  it('preserves all limit controls with conditional duration policy and explicit catalog-qualified model saving', async () => {
    const f = await settingsRender(), save = vi.spyOn(api, 'updateSettings').mockImplementation(async body => body)
    fireEvent.click(screen.getByRole('button', { name: '模型服务' })); fireEvent.change(screen.getByLabelText('模型'), { target: { value: 'big-pickle' } })
    fireEvent.click(screen.getByRole('button', { name: '执行限制' })); expect(screen.queryByLabelText('尝试超时（分钟）')).toBeNull()
    fireEvent.click(screen.getByRole('switch', { name: '启用业务超时限制' })); expect(screen.getByLabelText('尝试超时（分钟）')).toBeTruthy(); expect(screen.getByLabelText('验证超时（分钟）')).toBeTruthy(); expect(screen.getByLabelText('设计超时（分钟）')).toBeTruthy()
    expect(f.view.container.querySelector('.limits-grid')?.querySelectorAll('.ant-form-item')).toHaveLength(8)
    fireEvent.change(screen.getByLabelText('模板分析并发数'), { target: { value: '7' } }); fireEvent.click(button('settings.save'))
    await waitFor(() => expect(save).toHaveBeenCalledWith(expect.objectContaining({ openCode: expect.objectContaining({ provider: 'opencode', model: 'big-pickle' }), limits: expect.objectContaining({ templateAnalysisConcurrency: 7, timeoutEnabled: true }) })))
    expect(f.port.refreshRuntime).toHaveBeenCalledTimes(1)
  })
  it('makes every advanced runtime/model/retry/publication field reachable without automatic save', async () => {
    const { view } = await settingsRender(), save = vi.spyOn(api, 'updateSettings')
    fireEvent.click(within(view.container.querySelector('#settings-runtime') as HTMLElement).getByRole('button', { name: '展开：高级操作' }))
    expect(screen.getByLabelText('中止清理尝试次数')).toBeTruthy(); fireEvent.click(screen.getByRole('button', { name: '模型服务' })); expect(screen.getByLabelText('启动超时（秒）')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '重试等待' })); for (const label of ['限流基础等待（秒）', '限流最大等待（秒）', '会话异常基础等待（秒）', '会话异常最大等待（秒）', '验证失败基础等待（秒）', '验证失败最大等待（秒）']) expect(screen.getByLabelText(label)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '发布网络' })); for (const label of ['允许 HTTP 的 Git Host', 'GitLab Host', 'GitLab API 地址', '发布连接超时（秒）', '发布请求超时（秒）']) expect(screen.getByLabelText(label)).toBeTruthy()
    expect(save).not.toHaveBeenCalled()
  })
  it('independently saves/tests Git accounts without settings PUT or leaking stored secrets', async () => {
    await settingsRender(); vi.spyOn(api, 'gitCredentials').mockResolvedValue(credential); const settingsSave = vi.spyOn(api, 'updateSettings'), save = vi.spyOn(api, 'saveGitCredentials').mockResolvedValue({ ...credential, version: 3 }), test = vi.spyOn(api, 'testGitCredentials').mockResolvedValue({ success: false, message: 'Git 认证失败' })
    fireEvent.click(screen.getByRole('button', { name: 'Git 账号' })); await screen.findByLabelText('Git 密码或令牌')
    await waitFor(() => expect((screen.getByLabelText('Git 密码或令牌') as HTMLInputElement).disabled).toBe(false)); expect((screen.getByLabelText('Git 密码或令牌') as HTMLInputElement).value).toBe('')
    fireEvent.change(screen.getByLabelText('Git 密码或令牌'), { target: { value: 'draft-fixture' } }); fireEvent.click(button('settings.testCredentials')); await screen.findByText('Git 认证失败'); expect(save).not.toHaveBeenCalled()
    fireEvent.click(button('settings.saveCredentials')); await waitFor(() => expect(save).toHaveBeenCalledWith(undefined, expect.objectContaining({ secret: 'draft-fixture', version: 2 })))
    expect((screen.getByLabelText('Git 密码或令牌') as HTMLInputElement).value).toBe(''); expect(test).toHaveBeenCalledTimes(1); expect(settingsSave).not.toHaveBeenCalled()
  })
  it('preserves no-key UNKNOWN through preference/theme updates and accepted readback retries without another PUT', async () => {
    const f = await settingsRender(), write = vi.spyOn(api, 'updateSettings').mockRejectedValueOnce(new Error('设置响应丢失'))
    fireEvent.change(screen.getByLabelText('允许项目根（立即生效）'), { target: { value: '/workspace' } }); fireEvent.click(button('settings.save')); await screen.findByText(/原操作身份与输入已保留/)
    expect([...f.guards][0]!().kind).toBe('BLOCK'); fireEvent.click(screen.getByRole('button', { name: '画布显示' })); fireEvent.change(screen.getByLabelText('流程创作与需求画布'), { target: { value: 'vue' } })
    expect(JSON.parse(localStorage.getItem(CANVAS_RUNTIME_STORAGE)!)).toMatchObject({ workflow: 'vue' }); f.view.rerender(coreFrame(<SettingsPage {...f.props} skin={skins[1]!} />, skins[1]!)); expect([...f.guards][0]!().kind).toBe('BLOCK'); expect(write).toHaveBeenCalledTimes(1)
    const accepted = currentSettings(); accepted.runtime.allowedRoot = '/workspace'; vi.mocked(api.getSettings).mockResolvedValue(accepted); vi.mocked(f.port.refreshRuntime).mockRejectedValueOnce(new Error('运行状态读取失败'))
    fireEvent.click(button('ui.retry')); await screen.findByText('运行状态读取失败'); expect([...f.guards][0]!().kind).toBe('BLOCK')
    fireEvent.click(button('ui.retry')); await waitFor(() => expect([...f.guards][0]!().kind).toBe('ALLOW')); expect(write).toHaveBeenCalledTimes(1)
  })
  it('toggles demo via the same owner and reports definitive save failure while retaining editable draft', async () => {
    const f = await settingsRender(), save = vi.spyOn(api, 'updateSettings').mockRejectedValue(new ApiError('配置文件写入失败', 422))
    fireEvent.change(screen.getByLabelText('允许项目根（立即生效）'), { target: { value: '/workspace' } }); fireEvent.click(button('settings.save')); await screen.findByText('配置文件写入失败')
    expect((screen.getByLabelText('允许项目根（立即生效）') as HTMLInputElement).disabled).toBe(false); expect((button('settings.save') as HTMLButtonElement).disabled).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: '开发辅助' })); fireEvent.click(button('settings.toggleDemo', '启用演示数据')); await screen.findByText('当前来源：演示数据')
    fireEvent.click(button('settings.toggleDemo', '退出演示数据')); await screen.findByText('当前来源：实时数据'); expect(f.port.deactivateDemo).toHaveBeenCalledTimes(1); expect(save).toHaveBeenCalledTimes(1)
  })
})

it('keeps actual React Markdown safe and retains thinking disclosure without Vue', () => {
  const view = render(coreFrame(<CoreMarkdown content={'<think>逐项核对</think>\n\n# 活动\n\n<script>alert(1)</script>\n\n[资料](https://example.test)'} skin={skins[0]!} />))
  expect(view.container.querySelector('script')).toBeNull(); expect(screen.getByText('思考过程')).toBeTruthy()
  expect(screen.getByRole('link', { name: '资料' }).getAttribute('rel')).toBe('noopener noreferrer')
})
it('blocks an old dirty-close confirmation when the actual owner publishes a newer draft revision', async () => {
  const owner = createCoreOwner('context-review', { ...initialCoreState(), title: 'original' }), discard = vi.fn()
  owner.edit({ title: 'first draft' })
  function Context() {
    useOwnerSnapshot(owner)
    return <CoreContextPanel open title="草稿" readPolicy={owner.canLeave} discard={discard} onClose={() => undefined}><p>当前草稿</p></CoreContextPanel>
  }
  render(coreFrame(<Context />)); fireEvent.keyDown(screen.getByRole('complementary'), { key: 'Escape' }); const dialog = await screen.findByRole('dialog')
  act(() => { owner.edit({ title: 'new draft' }) })
  const confirm = within(dialog).getByRole('button', { name: semanticName('ui.discardChanges') }); expect((confirm as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(confirm); expect(discard).not.toHaveBeenCalled(); expect(owner.getSnapshot().title).toBe('new draft'); owner.retire(true)
})
