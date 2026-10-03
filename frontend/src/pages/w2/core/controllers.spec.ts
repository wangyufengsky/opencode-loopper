import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from '@/api/client'
import { createSettingsController, defaultSettings, settingsWritable } from './settingsController'
import { createCredentialsController } from './credentialsController'
import { createProjectsController, expectedDocumentPath } from './projectsController'
import { createTasksController, canArchiveTask, projectDemoTasks, readTaskFilters, taskDestination } from './tasksController'
import { coreFixture, deferred, projectFixture, taskFixtures } from './coreTestHelpers'
import type { AppSettings, GitCredentialView, ProjectAssistConfig, ProjectConventionDraft, SourceTemplateOverview, DocumentTemplateOverview } from '@/types/domain'
import * as resources from '@/foundation/contracts/resource'

afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers() })
const settingsFixture = (): AppSettings => ({ ...defaultSettings(), openCode: { ...defaultSettings().openCode, provider: 'opencode', model: 'model-a' } })
const models = [{ id: 'opencode/model-a', provider: 'opencode', model: 'model-a', label: 'model-a' }, { id: 'opencode/model-b', provider: 'opencode', model: 'model-b', label: 'model-b' }, { id: 'other/model-c', provider: 'other', model: 'model-c', label: 'model-c' }]
const credential: GitCredentialView = { mode: 'CUSTOM', serverUrl: 'https://gitlab.example', username: 'shared', kind: 'TOKEN', configured: true, source: 'GLOBAL', version: 2, updatedAt: null }
const assist: ProjectAssistConfig = { version: 3, credentialConfigured: false, config: { repository: 'group/repo', sources: [{ kind: 'LOG', root: '/service/logs', pattern: '*.log' }] } }
const draft = (state: ProjectConventionDraft['state']): ProjectConventionDraft => ({ id: 'draft', projectId: 'p', state, operation: 'UPDATE', readOnlyGeneration: true, updatedAt: 'now', content: '# rules' })
async function settingsOwner() {
  vi.spyOn(api, 'getSettings').mockResolvedValue(settingsFixture()); vi.spyOn(api, 'getSettingsModels').mockResolvedValue(models)
  const fixture = coreFixture(), owner = createSettingsController(fixture.port); owner.attachView(); await owner.initialize(); return { ...fixture, owner }
}

describe('W2 Settings operation owner', () => {
  it('keeps all settings and provider-qualified model choices, with no writes during initialization', async () => {
    const update = vi.spyOn(api, 'updateSettings'), { owner } = await settingsOwner()
    expect(update).not.toHaveBeenCalled(); owner.change('openCode', 'provider', 'other')
    expect(owner.getSnapshot().settings.openCode).toMatchObject({ provider: 'other', model: 'model-c' })
    owner.change('openCode', 'model', 'model-a'); expect(await owner.save()).toBe(false)
    expect(owner.getSnapshot()).toMatchObject({ section: 'models', modelError: '请先刷新并选择一个可用模型。' })
    expect(update).not.toHaveBeenCalled(); owner.retire(true)
  })
  it('rejects every mutation/late confirmation after actual owner retirement', async () => {
    const fixture = coreFixture(), project = createProjectsController(fixture.port), settings = await settingsOwner(), credentials = createCredentialsController(undefined, false)
    vi.spyOn(api, 'gitCredentials').mockResolvedValue(credential); await credentials.initialize()
    project.open('register'); project.changeRegister('name', 'New'); project.changeRegister('rootPath', '/new')
    const create = vi.spyOn(api, 'createProject'), update = vi.spyOn(api, 'updateSettings'), saveCredentials = vi.spyOn(api, 'saveGitCredentials'), remove = vi.spyOn(api, 'cancelProjectManagement')
    project.retire(true); settings.owner.retire(true); credentials.retire(true)
    await project.register(); await project.unmanage(projectFixture); await settings.owner.save(); await credentials.save()
    expect(create).not.toHaveBeenCalled(); expect(update).not.toHaveBeenCalled(); expect(saveCredentials).not.toHaveBeenCalled(); expect(remove).not.toHaveBeenCalled()
  })
  it('preserves edits between sections and reveals invalid absolute-root or blank-CLI input', async () => {
    const { owner } = await settingsOwner()
    owner.change('runtime', 'allowedRoot', 'relative/path'); owner.setSection('models'); await owner.save()
    expect(owner.getSnapshot()).toMatchObject({ section: 'runtime', fieldError: '允许项目根必须是绝对路径。' })
    expect(owner.getSnapshot().settings.runtime.allowedRoot).toBe('relative/path')
    owner.change('runtime', 'allowedRoot', '\\\\server\\share'); owner.change('openCode', 'cliPath', ''); await owner.save()
    expect(owner.getSnapshot().section).toBe('models'); expect(owner.getSnapshot().modelError).toContain('不能为空'); owner.retire(true)
  })
  it('writes the selected model, concurrency, advanced fields and comma-separated hosts once', async () => {
    const { owner, port } = await settingsOwner(), update = vi.spyOn(api, 'updateSettings').mockImplementation(async input => input)
    owner.change('openCode', 'model', 'model-b'); owner.change('limits', 'templateAnalysisConcurrency', 7)
    owner.change('runtime', 'abortCleanupAttempts', 5); owner.change('retryWait', 'verificationMaxSeconds', 80); owner.setHosts(' a.example, , b.example ')
    expect(await owner.save()).toBe(true)
    expect(update).toHaveBeenCalledTimes(1); expect(update).toHaveBeenCalledWith(expect.objectContaining({ openCode: expect.objectContaining({ model: 'model-b' }), limits: expect.objectContaining({ templateAnalysisConcurrency: 7 }),
      runtime: expect.objectContaining({ abortCleanupAttempts: 5 }), publication: expect.objectContaining({ httpWebHosts: ['a.example', 'b.example'] }) }))
    expect(port.refreshRuntime).toHaveBeenCalledTimes(1); expect(owner.canLeave().kind).toBe('ALLOW'); owner.retire(true)
  })
  it('preserves no-key UNKNOWN body and only performs GET recovery, refusing new writes or edits', async () => {
    const { owner } = await settingsOwner(), write = vi.spyOn(api, 'updateSettings').mockRejectedValue(new Error('响应丢失'))
    owner.change('limits', 'maxTaskAttempts', 17); await owner.save()
    const original = owner.operationIdentity(); expect(original).toMatchObject({ endpoint: '/settings', method: 'PUT' }); expect(original?.requestKey).toBeUndefined()
    expect(owner.canLeave().kind).toBe('BLOCK'); owner.change('limits', 'maxTaskAttempts', 18); await owner.save()
    expect(owner.operationIdentity()).toBe(original); expect(write).toHaveBeenCalledTimes(1)
    vi.mocked(api.getSettings).mockResolvedValue(settingsFixture()); await owner.recover(); expect(owner.canLeave().kind).toBe('BLOCK')
    const accepted = settingsFixture(); accepted.limits.maxTaskAttempts = 17; vi.mocked(api.getSettings).mockResolvedValue(accepted)
    await owner.recover(); expect(write).toHaveBeenCalledTimes(1); expect(owner.getSnapshot().mutation.phase).toBe('SETTLED'); expect(owner.canLeave().kind).toBe('ALLOW'); owner.retire(true)
  })
  it('never repeats accepted PUT when refreshRuntime read fails', async () => {
    const { owner, port } = await settingsOwner(), write = vi.spyOn(api, 'updateSettings').mockImplementation(async input => input)
    vi.mocked(port.refreshRuntime).mockRejectedValueOnce(new Error('运行状态读取失败'))
    owner.change('limits', 'maxStageAttempts', 4); await owner.save()
    expect(owner.getSnapshot().mutation.phase).toBe('ACCEPTED_READBACK'); expect(owner.canLeave().kind).toBe('BLOCK')
    await owner.save(); await owner.recover(); expect(write).toHaveBeenCalledTimes(1); expect(port.refreshRuntime).toHaveBeenCalledTimes(2)
    expect(owner.canLeave().kind).toBe('ALLOW'); owner.retire(true)
  })
  it('isolates late model results after CLI changes and late settings after forced retirement', async () => {
    const { owner } = await settingsOwner(), next = deferred<typeof models>()
    vi.mocked(api.getSettingsModels).mockReturnValueOnce(next.promise); const refresh = owner.refreshModels()
    owner.change('openCode', 'cliPath', 'other-cli'); next.resolve(models); await refresh
    expect(owner.getSnapshot().models).toEqual([])
    const old = createSettingsController(coreFixture().port), read = deferred<AppSettings>()
    vi.mocked(api.getSettings).mockReturnValueOnce(read.promise); const initial = old.initialize(), frozen = old.getSnapshot()
    old.retire(true); read.resolve(settingsFixture()); await initial; expect(old.getSnapshot()).toBe(frozen); owner.retire(true)
  })
  it('advances draft revision when a deferred catalog fallback changes the default model', async () => {
    const { owner } = await settingsOwner(), pending = deferred<typeof models>()
    vi.mocked(api.getSettingsModels).mockReturnValueOnce(pending.promise); owner.change('runtime', 'allowedRoot', '/draft'); const original = owner.canLeave()
    expect(original.kind).toBe('CONFIRM_DISCARD'); const read = owner.refreshModels(); pending.resolve([models[2]!]); await read
    const latest = owner.canLeave(); expect(latest.kind).toBe('CONFIRM_DISCARD')
    if (original.kind === 'CONFIRM_DISCARD' && latest.kind === 'CONFIRM_DISCARD') expect(latest.draftRevision).toBeGreaterThan(original.draftRevision)
    expect(owner.getSnapshot().settings.openCode).toMatchObject({ provider: 'other', model: 'model-c' }); owner.retire(true)
  })
  it('toggles demo explicitly through the sole legacy owner, without settings PUT', async () => {
    const { owner, port } = await settingsOwner(), write = vi.spyOn(api, 'updateSettings')
    await owner.toggleDemo(); expect(port.getSnapshot().usingDemo).toBe(true); await owner.toggleDemo()
    expect(port.deactivateDemo).toHaveBeenCalledTimes(1); expect(write).not.toHaveBeenCalled(); owner.retire(true)
  })
})

describe('W2 Git account independent owner', () => {
  it('loads no secret, preserves an existing secret when empty, and retains the real version', async () => {
    vi.spyOn(api, 'gitCredentials').mockResolvedValue(credential)
    const save = vi.spyOn(api, 'saveGitCredentials').mockResolvedValue({ ...credential, version: 3 }), owner = createCredentialsController(undefined, false)
    await owner.initialize(); expect(owner.getSnapshot().secret).toBe(''); await owner.save()
    expect(save).toHaveBeenCalledWith(undefined, expect.objectContaining({ mode: 'CUSTOM', secret: undefined, version: 2 }))
    expect(owner.getSnapshot().message).toContain('已保存'); owner.retire(true)
  })
  it('supports inheritance/independent accounts and clears the secret only after accepted save', async () => {
    vi.spyOn(api, 'gitCredentials').mockResolvedValue({ ...credential, mode: 'INHERIT', version: 0 })
    const save = vi.spyOn(api, 'saveGitCredentials').mockResolvedValue({ ...credential, source: 'PROJECT', username: 'own', version: 1 }), owner = createCredentialsController('p', false)
    await owner.initialize(); expect(owner.getSnapshot().mode).toBe('INHERIT')
    owner.change('mode', 'CUSTOM'); owner.change('username', 'own'); owner.change('secret', 'synthetic-test-token'); await owner.save()
    expect(save).toHaveBeenCalledWith('p', expect.objectContaining({ mode: 'CUSTOM', username: 'own', secret: 'synthetic-test-token' })); expect(owner.getSnapshot().secret).toBe(''); owner.retire(true)
  })
  it('verifies an unsaved draft without saving and invalidates stale success/error on edit', async () => {
    vi.spyOn(api, 'gitCredentials').mockResolvedValue(credential); const pending = deferred<{ success: boolean; message: string }>()
    const test = vi.spyOn(api, 'testGitCredentials').mockReturnValueOnce(pending.promise), save = vi.spyOn(api, 'saveGitCredentials'), owner = createCredentialsController(undefined, false)
    await owner.initialize(); owner.change('secret', 'draft-fixture'); owner.change('repositoryUrl', 'https://gitlab.example/g/a.git')
    const testing = owner.test(); owner.change('username', 'changed'); pending.resolve({ success: true, message: '旧连接成功' }); await testing
    expect(owner.getSnapshot().probe).toBeUndefined(); expect(save).not.toHaveBeenCalled(); expect(test).toHaveBeenCalledWith(undefined, expect.objectContaining({ secret: 'draft-fixture', repositoryUrl: 'https://gitlab.example/g/a.git' }))
    vi.mocked(test).mockResolvedValue({ success: false, message: 'Git 认证失败，请检查账号' }); await owner.test()
    expect(owner.getSnapshot().probe).toEqual({ success: false, message: 'Git 认证失败，请检查账号' }); owner.retire(true)
  })
  it('cannot pretend GET metadata proves a secret-changing UNKNOWN write accepted', async () => {
    const read = vi.spyOn(api, 'gitCredentials').mockResolvedValue(credential), write = vi.spyOn(api, 'saveGitCredentials').mockRejectedValue(new Error('响应未知'))
    const owner = createCredentialsController('p', false); await owner.initialize(); owner.change('secret', 'private-fixture'); await owner.save()
    const identity = owner.operationIdentity(); expect(identity?.body).toMatchObject({ secret: 'private-fixture', version: 2 })
    await owner.save(); await owner.recover(); expect(write).toHaveBeenCalledTimes(1); expect(read).toHaveBeenCalledTimes(1)
    expect(owner.canLeave().kind).toBe('BLOCK'); owner.retire(true); expect(owner.getSnapshot().secret).toBe(''); expect(owner.operationIdentity()).toBe(identity)
  })
  it('keeps drafts after definitive version conflict and has no writes/probes in demo', async () => {
    vi.spyOn(api, 'gitCredentials').mockResolvedValue(credential); const write = vi.spyOn(api, 'saveGitCredentials').mockRejectedValue(new ApiError('配置已变化，请刷新后重试', 409)), test = vi.spyOn(api, 'testGitCredentials')
    const owner = createCredentialsController(undefined, false); await owner.initialize(); owner.change('username', 'draft'); await owner.save()
    expect(owner.getSnapshot().username).toBe('draft'); expect(owner.canLeave().kind).toBe('CONFIRM_DISCARD')
    const demo = createCredentialsController(undefined, true); await demo.initialize(); await demo.save(); await demo.test(); expect(write).toHaveBeenCalledTimes(1); expect(test).not.toHaveBeenCalled(); demo.retire(true); owner.retire(true)
  })
})

describe('W2 Project contexts, versioned settings and read-only AI design', () => {
  it('fills the chosen root/name, preserves manual input on cancel, and registers without a new writer', async () => {
    const { port } = coreFixture(), owner = createProjectsController(port); owner.attachView(); owner.open('register')
    const pick = vi.spyOn(api, 'pickProjectDirectory').mockResolvedValueOnce({ selected: true, path: '/tmp/project', name: 'Example' }).mockResolvedValueOnce({ selected: false })
    await owner.pick('rootPath'); expect(owner.getSnapshot().register).toMatchObject({ name: 'Example', rootPath: '/tmp/project' })
    owner.changeRegister('rootPath', '/tmp/manual'); await owner.pick('rootPath'); expect(owner.getSnapshot().register.rootPath).toBe('/tmp/manual')
    const create = vi.spyOn(api, 'createProject').mockResolvedValue({ ...projectFixture, id: 'created', rootPath: '/tmp/manual', name: 'Example' })
    await owner.register(); expect(create).toHaveBeenCalledTimes(1); expect(port.addProject).toHaveBeenCalledTimes(1); expect(pick).toHaveBeenCalledTimes(2); owner.retire(true)
  })
  it('rejects relative roots and ignores picker completion after context retirement', async () => {
    const owner = createProjectsController(coreFixture().port); owner.open('register'); owner.changeRegister('name', 'Example'); owner.changeRegister('rootPath', 'relative')
    const write = vi.spyOn(api, 'createProject'); expect(await owner.register()).toBe(false); expect(write).not.toHaveBeenCalled()
    const next = deferred<{ selected: boolean; path: string }>(); vi.spyOn(api, 'pickProjectDirectory').mockReturnValue(next.promise)
    const picking = owner.pick('rootPath'); const frozen = owner.getSnapshot(); owner.retire(true); next.resolve({ selected: true, path: '/old' }); await picking
    expect(owner.getSnapshot()).toBe(frozen)
  })
  it('saves document paths with the loaded version and preserves conflicts/unknown operation identity', async () => {
    const { port } = coreFixture(), owner = createProjectsController(port); owner.open('document', projectFixture); owner.changeDocument('reports')
    const write = vi.spyOn(api, 'updateProjectDocumentPath').mockRejectedValueOnce(new ApiError('项目设置已更新，请刷新后重试', 409)).mockResolvedValueOnce({ ...projectFixture, documentPath: '/project/reports', version: 5 })
    await owner.saveDocument(); expect(owner.getSnapshot().documentPath).toBe('reports'); expect(owner.getSnapshot().context).toBe('document')
    await owner.saveDocument(); expect(write).toHaveBeenCalledWith('p', 'reports', 4); expect(port.replaceProject).toHaveBeenCalledWith(expect.objectContaining({ documentPath: '/project/reports', version: 5 })); owner.retire(true)
  })
  it('blocks versionless document writes, and keeps original body while UNKNOWN only reads existing summary', async () => {
    const owner = createProjectsController(coreFixture().port), write = vi.spyOn(api, 'updateProjectDocumentPath').mockRejectedValue(new Error('未知'))
    owner.open('document', { ...projectFixture, version: undefined }); await owner.saveDocument(); expect(write).not.toHaveBeenCalled(); owner.close(); owner.open('document', projectFixture)
    owner.changeDocument('new-docs'); await owner.saveDocument(); const original = owner.operationIdentity(); owner.changeDocument('replace'); await owner.saveDocument()
    vi.spyOn(api, 'getProjects').mockResolvedValue([{ ...projectFixture, version: 5, documentPath: '/project/new-docs' }]); await owner.recover()
    expect(write).toHaveBeenCalledTimes(1); expect(owner.operationIdentity()).toBe(original); expect(owner.canLeave().kind).toBe('ALLOW'); owner.retire(true)
  })
  it('preserves assist version/source scope, discovers via GET, rejects dirty check and keeps conflict drafts', async () => {
    vi.spyOn(api, 'projectAssistConfig').mockResolvedValue(assist); const save = vi.spyOn(api, 'saveProjectAssistConfig').mockRejectedValueOnce(new ApiError('配置已变化，请刷新后重试', 409)).mockResolvedValueOnce({ ...assist, version: 4 })
    const check = vi.spyOn(api, 'checkProjectGitLab').mockResolvedValue(assist); vi.spyOn(api, 'discoverProjectGitLab').mockResolvedValue({ repository: 'new/repo' })
    const owner = createProjectsController(coreFixture().port); owner.open('assist', projectFixture); await vi.waitFor(() => expect(owner.getSnapshot().assist).toEqual(assist))
    await owner.assistAction('discover'); await owner.assistAction('check'); expect(check).not.toHaveBeenCalled()
    await owner.assistAction('save'); expect(owner.getSnapshot().repository).toBe('new/repo'); expect(owner.getSnapshot().context).toBe('assist')
    expect(save).toHaveBeenCalledWith('p', 3, 'new/repo', assist.config.sources); await owner.assistAction('save'); expect(owner.getSnapshot().assist?.version).toBe(4); owner.retire(true)
  })
  it('does not start AI on read; generation/stop/apply are separate explicit commands', async () => {
    vi.spyOn(api, 'getCurrentProjectConvention').mockResolvedValue({ projectId: 'p', exists: true, loopperManaged: true, content: '# existing' })
    const generate = vi.spyOn(api, 'generateProjectConvention').mockResolvedValue(draft('READY')), apply = vi.spyOn(api, 'applyProjectConvention').mockResolvedValue(draft('APPLIED'))
    const owner = createProjectsController(coreFixture().port); owner.attachView(); owner.open('convention', projectFixture)
    await vi.waitFor(() => expect(owner.getSnapshot().convention?.content).toBe('# existing')); expect(generate).not.toHaveBeenCalled()
    await owner.conventionAction('generate'); expect(apply).not.toHaveBeenCalled(); expect(owner.getSnapshot().draft?.content).toBe('# rules')
    await owner.conventionAction('apply'); expect(apply).toHaveBeenCalledWith('p', 'draft'); expect(owner.getSnapshot().convention?.content).toBe('# rules'); owner.retire(true)
  })
  it('streams only current activity and cannot close RUNNING/STOPPING before explicit stop proof', async () => {
    vi.useFakeTimers(); vi.spyOn(api, 'getCurrentProjectConvention').mockResolvedValue({ projectId: 'p', exists: false, loopperManaged: false, content: '' })
    vi.spyOn(api, 'generateProjectConvention').mockResolvedValue(draft('RUNNING')); const read = vi.spyOn(api, 'getProjectConventionDraft').mockResolvedValue(draft('RUNNING'))
    vi.spyOn(api, 'getProjectConventionActivity').mockResolvedValue({ actor: 'PROJECT_CONVENTION', remoteState: 'RUNNING', connected: true, observedAt: 'now',
      parts: [{ id: 'part', type: 'THINKING', label: '核对构建', content: '核对 Maven 模块', status: 'RUNNING' }], usage: { totalTokens: 321, unknownUsageCount: 0, observedAt: 'now' } })
    const stop = vi.spyOn(api, 'cancelProjectConvention').mockResolvedValueOnce(draft('STOPPING')), owner = createProjectsController(coreFixture().port)
    const release = owner.attachView(); owner.open('convention', projectFixture); await Promise.resolve(); await Promise.resolve(); await owner.conventionAction('generate')
    await vi.advanceTimersByTimeAsync(1000); expect(owner.getSnapshot().activity?.usage.totalTokens).toBe(321); owner.close(); expect(owner.getSnapshot().context).toBe('convention'); expect(owner.canLeave().kind).toBe('BLOCK')
    await owner.conventionAction('stop'); expect(stop).toHaveBeenCalledWith('p', 'draft'); expect(owner.getSnapshot().draft?.state).toBe('STOPPING')
    read.mockResolvedValue(draft('CANCELLED')); await vi.advanceTimersByTimeAsync(1000); expect(owner.canLeave().kind).toBe('ALLOW'); release(); owner.retire(true); expect(vi.getTimerCount()).toBe(0)
  })
  it('never rearms its poll after root retirement while a draft/activity GET is pending', async () => {
    vi.useFakeTimers(); vi.spyOn(api, 'getCurrentProjectConvention').mockResolvedValue({ projectId: 'p', exists: false, loopperManaged: false, content: '' }); vi.spyOn(api, 'generateProjectConvention').mockResolvedValue(draft('RUNNING'))
    const pending = deferred<ProjectConventionDraft>(); const read = vi.spyOn(api, 'getProjectConventionDraft').mockReturnValue(pending.promise), activity = vi.spyOn(api, 'getProjectConventionActivity')
    const owner = createProjectsController(coreFixture().port); const release = owner.attachView(); owner.open('convention', projectFixture); await Promise.resolve(); await Promise.resolve(); await owner.conventionAction('generate')
    await vi.advanceTimersByTimeAsync(1000); expect(read).toHaveBeenCalledTimes(1); const frozen = owner.getSnapshot(); release(); owner.retire(true); pending.resolve(draft('RUNNING')); await Promise.resolve(); await Promise.resolve()
    expect(activity).not.toHaveBeenCalled(); expect(owner.getSnapshot()).toBe(frozen); expect(vi.getTimerCount()).toBe(0)
  })
  it('does not regenerate an UNKNOWN no-key AI request and cannot hide its identity', async () => {
    vi.spyOn(api, 'getCurrentProjectConvention').mockResolvedValue({ projectId: 'p', exists: false, loopperManaged: false, content: '' }); const generate = vi.spyOn(api, 'generateProjectConvention').mockRejectedValue(new Error('生成回执丢失'))
    const owner = createProjectsController(coreFixture().port); owner.open('convention', projectFixture); await vi.waitFor(() => expect(owner.getSnapshot().loading).toBe(false))
    await owner.conventionAction('generate'); const identity = owner.operationIdentity(); expect(identity).toMatchObject({ endpoint: '/projects/p/agents-md', method: 'POST', body: undefined })
    await owner.conventionAction('generate'); await owner.recover(); owner.close(); expect(owner.open('details', { ...projectFixture, id: 'other' })).toBe(false)
    expect(generate).toHaveBeenCalledTimes(1); expect(owner.operationIdentity()).toBe(identity); expect(owner.canLeave().kind).toBe('BLOCK'); owner.retire(true)
  })
  it('isolates late assist/context responses and does not erase history when canceling management', async () => {
    const next = deferred<ProjectAssistConfig>(); vi.spyOn(api, 'projectAssistConfig').mockReturnValueOnce(next.promise).mockResolvedValueOnce({ ...assist, config: { repository: 'new/project', sources: [] } })
    const first = createProjectsController(coreFixture().port); first.open('assist', projectFixture); const snapshot = first.getSnapshot(); first.retire(true)
    const fixture = coreFixture(), second = createProjectsController(fixture.port); second.open('assist', { ...projectFixture, id: 'other' }); await vi.waitFor(() => expect(second.getSnapshot().repository).toBe('new/project'))
    next.resolve(assist); await Promise.resolve(); expect(first.getSnapshot()).toBe(snapshot); expect(second.getSnapshot().repository).toBe('new/project')
    second.close(); vi.spyOn(api, 'cancelProjectManagement').mockResolvedValue(undefined); await second.unmanage(projectFixture)
    expect(fixture.port.removeProject).toHaveBeenCalledWith('p'); expect(second.getSnapshot().message).toContain('历史任务、设计对话、LoopSpec 与执行证据均已保留'); second.retire(true)
  })
  it('retires the real credentials child even when an owned parent resource disposer throws', async () => {
    const original = resources.createResourceScope
    vi.spyOn(resources, 'createResourceScope').mockImplementation(identity => {
      const scope = original(identity); scope.own(() => { throw new Error('owned cleanup failed') }); return scope
    })
    vi.spyOn(api, 'projectAssistConfig').mockResolvedValue(assist); vi.spyOn(api, 'gitCredentials').mockResolvedValue(credential)
    const save = vi.spyOn(api, 'saveGitCredentials'), owner = createProjectsController(coreFixture().port); owner.attachView(); owner.open('assist', projectFixture)
    const child = owner.credentials()!; await child.initialize(); const childToken = child.capture(); child.change('secret', 'retained-private-fixture')
    expect(() => owner.retire(true)).toThrow('资源清理')
    expect(childToken.isCurrent()).toBe(false); expect(child.getSnapshot().secret).toBe(''); await child.save(); expect(save).not.toHaveBeenCalled()
    expect(owner.capture().isCurrent()).toBe(false)
  })
})

describe('W2 task summary projection and explicit mutations', () => {
  const source: SourceTemplateOverview = { id: 'source', projectId: 'p', templateId: 'SOURCE', templateVersion: '1', title: '源任务', state: 'COMPLETED', version: 9,
    createdAt: 'now', updatedAt: 'now', archived: false, sourcePath: 'src', testOutputPath: null, documentPath: null, requirements: '', snapshot: null,
    designerId: null, taskId: null, taskState: null, waitingReasonCode: null, waitingMessage: null, coverage: [], progress: [], canResume: false, canArchive: true, testProfile: null }
  const document: DocumentTemplateOverview = { id: 'doc', projectId: 'p', templateId: 'DOC', templateVersion: '1', title: '文档任务', state: 'COMPLETED', version: 11,
    createdAt: 'now', updatedAt: 'now', archived: false, designerId: null, taskId: null, waitingReasonCode: null, waitingMessage: null, requirementRevision: 1,
    canCancel: false, canResume: false, uploadReady: true, snapshotSha: null, progress: { attempts: 0, validated: 0, active: 0, stopped: 0, requirements: 0, reports: 0, revision: 1 }, files: [] }
  it.each(['source', 'document'] as const)('uses %s actual archive endpoint/version/key and never repeats accepted POST after read failure', async kind => {
    const fixture = coreFixture(), owner = createTasksController(fixture.port, fixture.props.navigation, fixture.props.route); owner.attachView(); await owner.initialize()
    const sourceRead = vi.spyOn(api, 'sourceTemplate').mockResolvedValue(source), documentRead = vi.spyOn(api, 'documentTemplate').mockResolvedValue(document)
    const sourceWrite = vi.spyOn(api, 'sourceTemplateCommand').mockResolvedValue({ ...source, archived: true }), documentWrite = vi.spyOn(api, 'documentTemplateCommand').mockResolvedValue({ ...document, archived: true })
    vi.mocked(fixture.port.loadTaskSummaries).mockRejectedValueOnce(new Error('summary读失败'))
    const row = { ...taskFixtures[0]!, ...(kind === 'source' ? { sourceRunId: 'source', sourceState: 'COMPLETED' as const } : { documentRunId: 'doc', documentState: 'COMPLETED' as const }) }
    await owner.archive(row); const identity = owner.operationIdentity()
    expect(identity?.endpoint).toBe(`/template-tasks/${kind === 'source' ? 'source-runs/source/controls/archive' : 'document-runs/doc/archive'}`)
    expect(identity?.body).toMatchObject({ expectedVersion: kind === 'source' ? 9 : 11, requestKey: expect.any(String) }); expect(identity?.requestKey).toBe((identity?.body as { requestKey: string }).requestKey)
    expect(owner.getSnapshot().mutation.phase).toBe('ACCEPTED_READBACK'); await owner.archive(row); await owner.recover()
    expect(kind === 'source' ? sourceWrite : documentWrite).toHaveBeenCalledTimes(1); expect(kind === 'source' ? documentWrite : sourceWrite).not.toHaveBeenCalled()
    expect(kind === 'source' ? sourceRead : documentRead).toHaveBeenCalledTimes(1); expect(fixture.port.setTaskArchived).not.toHaveBeenCalled(); expect(owner.canLeave().kind).toBe('ALLOW'); owner.retire(true)
  })
  it('never sends another template command during GET recovery and retries only the original version/key on explicit intent', async () => {
    const fixture = coreFixture({ usingDemo: true }), owner = createTasksController(fixture.port, fixture.props.navigation, fixture.props.route)
    vi.spyOn(api, 'sourceTemplate').mockResolvedValue(source)
    const write = vi.spyOn(api, 'sourceTemplateCommand').mockRejectedValueOnce(new Error('响应未知')).mockResolvedValueOnce({ ...source, archived: true })
    await owner.archive({ ...taskFixtures[0]!, sourceRunId: source.id, sourceState: 'COMPLETED' })
    const original = owner.operationIdentity(), firstBody = write.mock.calls[0]![2]
    await owner.recover(); expect(write).toHaveBeenCalledTimes(1); expect(owner.getSnapshot().mutation.phase).toBe('UNKNOWN')
    await owner.retryOriginal(); expect(write).toHaveBeenCalledTimes(2); expect(write.mock.calls[1]![2]).toBe(firstBody)
    expect(owner.operationIdentity()).toBe(original); expect(owner.canLeave().kind).toBe('ALLOW'); owner.retire(true)
  })
  it('retains all URL/deep-link filters and sends the real template/status group query to the only owner', async () => {
    const fixture = coreFixture(), owner = createTasksController(fixture.port, fixture.props.navigation, { ...fixture.props.route, query: { type: ['template'], status: 'ACTIVE', q: ' test ', group: 'project', archive: 'archived', order: 'oldest', project: 'p' } })
    const release = owner.attachView(); await owner.initialize(); expect(fixture.port.loadTaskSummaries).toHaveBeenCalledWith(expect.objectContaining({ taskType: 'TEMPLATE', statusGroup: 'PROCESSING', archive: 'ARCHIVED', order: 'oldest', q: 'test', projectId: 'p' }), false)
    owner.change({ type: 'STANDARD' }); expect(fixture.props.navigation.go).toHaveBeenCalledWith({ path: '/tasks', query: { status: 'ACTIVE', type: 'standard', project: 'p', order: 'oldest', archive: 'archived', q: 'test', group: 'project' } }, true)
    release(); owner.retire(true)
  })
  it('sorts/filters demo tasks locally and maps genuine source/document destinations and disposition', () => {
    expect(projectDemoTasks(taskFixtures, readTaskFilters({})).map(task => task.id)).toEqual(['new-a', 'middle-b', 'old-a'])
    expect(projectDemoTasks(taskFixtures, readTaskFilters({ project: 'p', order: 'oldest', q: 'A old' })).map(task => task.id)).toEqual(['old-a'])
    const document = { ...taskFixtures[0]!, documentRunId: 'doc', documentState: 'COMPLETED' as const, linkedTaskId: 'linked', status: 'AWAITING_DECISION' as const }
    expect(canArchiveTask(document)).toBe(false); expect(canArchiveTask({ ...document, status: 'COMPLETED' })).toBe(true)
    expect(taskDestination(document)).toBe('/template-tasks/document-runs/doc'); expect(taskDestination({ ...document, sourceRunId: 'source' })).toBe('/template-tasks/source-runs/source')
  })
  it('cleans the display clock and query debounce across replayed view leases, and starts no task stream', async () => {
    vi.useFakeTimers(); const fixture = coreFixture(), owner = createTasksController(fixture.port, fixture.props.navigation, fixture.props.route)
    const first = owner.attachView(); first(); const second = owner.attachView(); await owner.initialize(); owner.change({ search: 'later' })
    expect(vi.getTimerCount()).toBe(2); const count = vi.mocked(fixture.port.loadTaskSummaries).mock.calls.length; second(); owner.retire(true)
    await vi.advanceTimersByTimeAsync(5000); expect(vi.getTimerCount()).toBe(0); expect(fixture.port.loadTaskSummaries).toHaveBeenCalledTimes(count)
    expect(Object.keys(fixture.port)).not.toContain('watchTask')
  })
  it('does not repeat an accepted archive when summary refresh fails', async () => {
    const fixture = coreFixture({ usingDemo: true }), owner = createTasksController(fixture.port, fixture.props.navigation, fixture.props.route); owner.attachView(); await owner.initialize()
    fixture.publish({ usingDemo: false }); vi.mocked(fixture.port.loadTaskSummaries).mockRejectedValueOnce(new Error('读取失败'))
    await owner.archive(taskFixtures[0]!); expect(owner.getSnapshot().mutation.phase).toBe('ACCEPTED_READBACK'); await owner.archive(taskFixtures[0]!); await owner.recover()
    expect(fixture.port.setTaskArchived).toHaveBeenCalledTimes(1); expect(owner.canLeave().kind).toBe('ALLOW'); owner.retire(true)
  })
  it('preserves standard archive UNKNOWN and only reads the original task, with explicit deletion guards', async () => {
    const fixture = coreFixture({ usingDemo: true }), owner = createTasksController(fixture.port, fixture.props.navigation, fixture.props.route)
    vi.mocked(fixture.port.setTaskArchived).mockRejectedValue(new Error('未知')); await owner.archive(taskFixtures[0]!); const identity = owner.operationIdentity()
    await owner.archive(taskFixtures[0]!); expect(fixture.port.setTaskArchived).toHaveBeenCalledTimes(1); expect(owner.canLeave().kind).toBe('BLOCK')
    vi.spyOn(api, 'getTask').mockResolvedValue({ ...taskFixtures[0]!, archived: true }); await owner.recover(); expect(owner.operationIdentity()).toBe(identity)
    await owner.remove(taskFixtures[0]!); expect(fixture.port.deleteArchivedTask).not.toHaveBeenCalled()
    await owner.remove({ ...taskFixtures[0]!, archived: true, sourceRunId: 'source' }); expect(fixture.port.deleteArchivedTask).not.toHaveBeenCalled()
    await owner.remove({ ...taskFixtures[0]!, archived: true }); expect(fixture.port.deleteArchivedTask).toHaveBeenCalledTimes(1); owner.retire(true)
  })
})

// The DTO comparison ignores server-only response metadata, never credentials or File bytes.
it('uses all writable Settings fields when verifying GET recovery', () => {
  const a = settingsFixture(), b = settingsFixture(); b.updatedAt = 'later'; expect(settingsWritable(a)).toEqual(settingsWritable(b))
  b.retryWait.sessionMaxSeconds++; expect(settingsWritable(a)).not.toEqual(settingsWritable(b))
})
it('matches canonical document defaults only for safely resolvable path forms', () => {
  expect(expectedDocumentPath('/project', ' reports/./docs ')).toBe('/project/reports/docs')
  expect(expectedDocumentPath('/project', '../private')).toBeUndefined()
  expect(expectedDocumentPath('/project', '')).toBe('')
  expect(expectedDocumentPath('C:\\project', 'reports')).toBeUndefined()
})
