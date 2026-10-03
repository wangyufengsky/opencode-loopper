import { api } from '@/api/client'
import type { AssistEvidenceSource, Project, ProjectAssistConfig, ProjectConventionActivity, ProjectConventionDraft, ProjectConventionSnapshot } from '@/types/domain'
import type { ResourceScope } from '@/foundation/contracts/types'
import { OwnedResourceCleanupError } from '@/foundation/contracts/resource'
import { userFacingError } from '@/utils/displayLabels'
import { createCredentialsController } from './credentialsController'
import { createCoreOwner, initialCoreState, sameDto, type CoreState } from './owner'

export interface ProjectsPort {
  getSnapshot(): { usingDemo: boolean; projects: readonly Project[] }
  loadProjects(refresh?: boolean): Promise<unknown>
  addProject(project: Project): void
  replaceProject(project: Project): void
  removeProject(id: string): void
}
export type ProjectContext = 'details' | 'register' | 'document' | 'assist' | 'convention'
export interface ProjectState extends CoreState {
  selected?: Project
  context?: ProjectContext
  register: { name: string; rootPath: string; documentPath: string; description: string }
  documentPath: string
  picking: boolean
  loading: boolean
  assistTab: 'account' | 'gitlab'
  assist?: ProjectAssistConfig
  repository: string
  sources: AssistEvidenceSource[]
  assistDirty: boolean
  convention?: ProjectConventionSnapshot
  draft?: ProjectConventionDraft
  activity?: ProjectConventionActivity
  activityError: string
}
export function stackLabel(project: Project) {
  if (project.stackProfileState === 'FAILED') return '分析失败'
  if (project.stackProfileState === 'PARTIAL') return '画像不完整'
  if (!project.stackProfileState || project.stackProfileState === 'UNANALYZED') return '待分析'
  const families = project.stackTechnologyFamilies ?? []
  return families.length > 1 ? '混合栈' : ({ java: 'Java', node: 'Node', python: 'Python', other: '其他技术栈' }[families[0] ?? ''] ?? '未识别技术栈')
}
export const conventionRunning = (draft?: Readonly<ProjectConventionDraft>) => draft?.state === 'RUNNING' || draft?.state === 'STOPPING' || draft?.state === 'APPLYING'
/** Root is already canonical server data. Unsafe/unknown path forms remain unconfirmed. */
export function expectedDocumentPath(root: string, input: string): string | undefined {
  const path = input.trim()
  if (!path) return ''
  if (!root.startsWith('/') || /[\u0000-\u001f\u007f\\]/.test(path) || path.split('/').includes('..')) return undefined
  const absolute = path.startsWith('/') ? path : `${root}/${path}`
  return '/' + absolute.split('/').filter(part => part && part !== '.').join('/')
}
export function createProjectsController(port: ProjectsPort, client = api) {
  let lease: ResourceScope | undefined, poll: ReturnType<typeof setTimeout> | undefined, generation = 0
  let credentialOwner: ReturnType<typeof createCredentialsController> | undefined
  function clearPoll() { if (poll !== undefined) clearTimeout(poll); poll = undefined }
  const owner = createCoreOwner<ProjectState>('projects', { ...initialCoreState(), register: { name: '', rootPath: '', documentPath: '', description: '' },
    documentPath: '', picking: false, loading: false, assistTab: 'account', repository: '', sources: [], assistDirty: false, activityError: '' }, {
    attachReads: resources => {
      lease = resources
      resources.own(() => { clearPoll(); if (lease === resources) lease = undefined })
      schedulePoll()
    },
    extraLeave: state => {
      const child = credentialOwner?.canLeave()
      if (child?.kind === 'BLOCK') return child
      if (conventionRunning(state.draft)) return { kind: 'BLOCK', reason: '项目公约仍在生成、停止确认或写入确认中，请先处理原操作。', recoveryAction: '停止生成或核对写入结果' }
      if (state.picking || state.loading) return { kind: 'BLOCK', reason: '正在读取项目设置或选择目录，请先等待。', recoveryAction: '等待读取完成' }
      if (child?.kind === 'CONFIRM_DISCARD') return { ...child, draftRevision: child.draftRevision + state.draftRevision }
      return undefined
    },
  })
  function valid(token: ReturnType<typeof owner.capture>, current: number) { return token.isCurrent() && current === generation }
  async function readContext(context: ProjectContext, project: Project, current: number) {
    const token = owner.capture(); owner.set({ loading: true })
    try {
      if (context === 'assist') {
        const assist = port.getSnapshot().usingDemo ? { version: -1, credentialConfigured: false, config: { sources: [] } } : await client.projectAssistConfig(project.id)
        if (valid(token, current)) owner.set({ assist, repository: assist.config.repository ?? '', sources: assist.config.sources.map(item => ({ ...item })), assistDirty: false })
      } else if (context === 'convention') {
        const convention = port.getSnapshot().usingDemo ? { projectId: project.id, exists: false, loopperManaged: false, content: '' } : await client.getCurrentProjectConvention(project.id)
        if (valid(token, current)) owner.set({ convention })
      }
    } catch (error) { if (valid(token, current)) owner.set({ error: userFacingError(error, '项目设置读取失败，请重新读取') }) }
    finally { if (valid(token, current)) owner.set({ loading: false }) }
  }
  function open(context: ProjectContext, project?: Project): boolean {
    if (owner.canLeave().kind !== 'ALLOW') return false
    generation++; clearPoll(); credentialOwner?.retire(true); credentialOwner = undefined
    owner.set({ selected: project, context, dirty: false, error: '', message: '', picking: false, loading: false,
      register: { name: '', rootPath: '', documentPath: '', description: '' }, documentPath: project?.documentPath ?? '',
      assist: undefined, repository: '', sources: [], assistTab: 'account', assistDirty: false,
      convention: undefined, draft: undefined, activity: undefined, activityError: '' })
    if (context === 'assist' && project) {
      credentialOwner = createCredentialsController(project.id, () => port.getSnapshot().usingDemo, client)
      credentialOwner.subscribe(() => owner.set({ draftRevision: owner.getSnapshot().draftRevision + 1 }))
    }
    if (project && ['assist', 'convention'].includes(context)) void readContext(context, project, generation)
    return true
  }
  function discardContext() { if (!owner.locked() && owner.canLeave().kind !== 'BLOCK') { credentialOwner?.clearDraft(); owner.clearDraft(); generation++; clearPoll(); credentialOwner?.retire(true); credentialOwner = undefined; owner.set({ context: undefined, selected: undefined }) } }
  function close() { if (owner.canLeave().kind === 'ALLOW') { generation++; clearPoll(); credentialOwner?.retire(true); credentialOwner = undefined; owner.set({ context: undefined, selected: undefined }) } }
  async function pick(field: 'rootPath' | 'registerDocument' | 'documentPath' | number) {
    if (owner.locked() || owner.getSnapshot().picking || owner.getSnapshot().loading) return
    const current = generation, token = owner.capture(), original = owner.getSnapshot()
    owner.set({ picking: true, error: '' })
    try {
      if (port.getSnapshot().usingDemo && field !== 'rootPath') return
      const value = port.getSnapshot().usingDemo ? { selected: true, path: port.getSnapshot().projects[0]?.rootPath ?? '/tmp/example-project', name: port.getSnapshot().projects[0]?.name ?? 'project' } : await client.pickProjectDirectory()
      if (!valid(token, current) || !value.selected || !value.path) return
      const state = owner.getSnapshot()
      if (field === 'rootPath' && state.register.rootPath === original.register.rootPath) owner.edit({ register: { ...state.register, rootPath: value.path, name: state.register.name.trim() ? state.register.name : value.name ?? '' } })
      if (field === 'registerDocument' && state.register.documentPath === original.register.documentPath) owner.edit({ register: { ...state.register, documentPath: value.path } })
      if (field === 'documentPath' && state.documentPath === original.documentPath) owner.edit({ documentPath: value.path })
      if (typeof field === 'number' && state.sources[field]?.root === original.sources[field]?.root) owner.edit({ sources: state.sources.map((source, i) => i === field ? { ...source, root: value.path! } : source), assistDirty: true })
    } catch (error) { if (valid(token, current)) owner.set({ error: userFacingError(error, '无法打开文件夹选择器，请手动填写路径') }) }
    finally { if (valid(token, current)) owner.set({ picking: false }) }
  }
  async function register() {
    const state = owner.getSnapshot()
    if (owner.locked() || state.picking) return false
    if (!state.register.name.trim()) { owner.set({ error: '请输入项目名称。' }); return false }
    if (!/^(\/|[A-Za-z]:[\\/])/.test(state.register.rootPath.trim())) { owner.set({ error: '请输入绝对项目根路径；相对路径不允许登记。' }); return false }
    const body = { ...state.register }, previousIds = new Set(port.getSnapshot().projects.map(project => project.id))
    return owner.mutate({ endpoint: '/projects', method: 'POST', body }, { label: '登记项目',
      write: original => port.getSnapshot().usingDemo ? Promise.resolve({ id: `demo-${crypto.randomUUID()}`, ...original, status: 'NEEDS_GIT' as const,
        executionMode: 'DIRECT' as const, updatedAt: new Date().toISOString(), taskCount: 0, openDesignerSessionCount: 0,
        stackProfileState: 'UNANALYZED' as const, stackTechnologyFamilies: [], stackComponentCount: 0 }) : client.createProject(original),
      lookup: async original => {
        const projects = await client.getProjects()
        const matches = projects.filter(project => !previousIds.has(project.id) && project.name === original.name.trim() && project.rootPath === original.rootPath.trim()
          && (project.documentPath ?? '') === expectedDocumentPath(project.rootPath, original.documentPath) && (project.description ?? '') === original.description.trim())
        return matches.length === 1 ? { kind: 'ACCEPTED', receipt: matches[0]! } : { kind: 'UNCONFIRMED' }
      },
      read: (project, apply) => { apply(() => { port.addProject(project as Project); owner.set({ dirty: false, context: undefined, message: '项目已登记' }) }) },
    })
  }
  async function saveDocument() {
    const state = owner.getSnapshot(), project = state.selected
    if (!project || state.picking || owner.locked()) return false
    if (!port.getSnapshot().usingDemo && project.version === undefined) { owner.set({ error: '项目设置已更新，请刷新项目列表后重试' }); return false }
    const body = { documentPath: state.documentPath, version: project.version ?? 0 }
    return owner.mutate({ endpoint: `/projects/${encodeURIComponent(project.id)}/document-path`, method: 'PUT', body, versions: { version: body.version } }, {
      label: '保存文档路径', write: original => port.getSnapshot().usingDemo ? Promise.resolve({ ...project, documentPath: original.documentPath.trim() || undefined }) : client.updateProjectDocumentPath(project.id, original.documentPath, original.version),
      lookup: async original => {
        const found = (await client.getProjects()).find(item => item.id === project.id)
        const expected = expectedDocumentPath(project.rootPath, original.documentPath)
        return found && expected !== undefined && (found.version ?? -1) > original.version && (found.documentPath ?? '') === expected
          ? { kind: 'ACCEPTED', receipt: found } : { kind: 'UNCONFIRMED' }
      },
      read: (value, apply) => { apply(() => { port.replaceProject(value as Project); owner.set({ selected: value as Project, dirty: false, context: undefined, message: '文档路径已保存' }) }) },
    })
  }
  async function assistAction(kind: 'save' | 'check' | 'discover') {
    const state = owner.getSnapshot(), project = state.selected, assist = state.assist
    if (!project || !assist || state.loading || state.picking || owner.locked()) return
    if (credentialOwner?.canLeave().kind === 'BLOCK') return
    if (port.getSnapshot().usingDemo) { owner.set({ error: '请在实际项目中配置 GitLab 与证据来源' }); return }
    if (kind === 'discover') {
      const token = owner.capture(), current = generation; owner.set({ loading: true, error: '' })
      try { const result = await client.discoverProjectGitLab(project.id); if (valid(token, current)) owner.edit({ repository: result.repository, assistDirty: true }) }
      catch (error) { if (valid(token, current)) owner.set({ error: userFacingError(error, '无法识别 origin 仓库') }) }
      finally { if (valid(token, current)) owner.set({ loading: false }) }
      return
    }
    if (kind === 'check' && (state.assistDirty || !assist.config.repository)) return
    const body = kind === 'save' ? { version: assist.version, repository: state.repository, sources: state.sources.map(source => ({ ...source })) } : { version: assist.version }
    await owner.mutate({ endpoint: `/projects/${encodeURIComponent(project.id)}/assist-config${kind === 'check' ? '/check' : ''}`, method: kind === 'save' ? 'PUT' : 'POST', body,
      versions: { version: assist.version } }, { label: kind === 'save' ? '保存 GitLab 与证据配置' : '检查已保存绑定',
      write: original => kind === 'save' ? client.saveProjectAssistConfig(project.id, original.version, original.repository!, original.sources!) : client.checkProjectGitLab(project.id, original.version),
      lookup: async original => {
        const found = await client.projectAssistConfig(project.id)
        const matches = found.version > original.version && (kind === 'save'
          ? found.config.repository === original.repository && sameDto(found.config.sources, original.sources)
          : !!found.config.projectId && found.config.repository === assist.config.repository)
        return matches ? { kind: 'ACCEPTED', receipt: found } : { kind: 'UNCONFIRMED' }
      },
      read: (value, apply) => { apply(() => owner.set({ assist: value as ProjectAssistConfig, repository: value.config.repository ?? '', sources: value.config.sources.map(source => ({ ...source })), assistDirty: false, dirty: false, message: '配置已保存并核对' })) },
    })
  }
  function schedulePoll() {
    clearPoll()
    const state = owner.getSnapshot(), resources = lease, project = state.selected, draft = state.draft, current = generation
    if (!resources?.isActive() || state.context !== 'convention' || !project || !draft || !conventionRunning(draft)) return
    poll = setTimeout(async () => {
      poll = undefined
      try {
        const next = await client.getProjectConventionDraft(project.id, draft.id)
        if (!resources.isActive() || !valid(owner.capture(), current)) return
        owner.set({ draft: next })
        if (next.state === 'RUNNING' || next.state === 'STOPPING') {
          try {
            const activity = await client.getProjectConventionActivity(project.id, draft.id)
            if (!resources.isActive() || current !== generation) return
            const previousParts = owner.getSnapshot().activity?.parts, previous = previousParts?.[previousParts.length - 1]
            owner.set({ activity: { ...activity, parts: activity.parts.length ? [activity.parts[activity.parts.length - 1]!] : !activity.connected && previous ? [previous] : [] }, activityError: '' })
          } catch (error) { if (resources.isActive() && current === generation) owner.set({ activityError: userFacingError(error, 'AI 活动暂时无法刷新') }) }
        }
        if (resources.isActive() && current === generation) schedulePoll()
      } catch (error) { if (resources.isActive() && current === generation) owner.set({ error: userFacingError(error, '无法获取项目公约生成状态') }) }
    }, 1000)
  }
  async function conventionAction(kind: 'generate' | 'stop' | 'apply') {
    const state = owner.getSnapshot(), project = state.selected, draft = state.draft
    if (!project || owner.locked() || state.loading) return
    if (port.getSnapshot().usingDemo) { owner.set({ error: '演示模式不会调用 AI 或写入项目文件' }); return }
    if (kind === 'generate' && (conventionRunning(draft) || !state.convention)) return
    if (kind === 'stop' && (!draft || !['RUNNING', 'STOPPING'].includes(draft.state))) return
    if (kind === 'apply' && draft?.state !== 'READY') return
    clearPoll()
    const endpoint = `/projects/${encodeURIComponent(project.id)}/agents-md${kind === 'generate' ? '' : `/${encodeURIComponent(draft!.id)}`}`
    await owner.mutate({ endpoint, method: kind === 'generate' ? 'POST' : kind === 'stop' ? 'DELETE' : 'PUT', body: undefined }, {
      label: kind === 'generate' ? '生成项目公约' : kind === 'stop' ? '停止生成' : '确认写入 AGENTS.md',
      write: () => kind === 'generate' ? client.generateProjectConvention(project.id) : kind === 'stop'
        ? client.cancelProjectConvention(project.id, draft!.id) : client.applyProjectConvention(project.id, draft!.id),
      // POST returns the only draft identity. Losing it cannot be repaired by inventing a by-request endpoint.
      lookup: kind === 'generate' ? undefined : async () => {
        const found = await client.getProjectConventionDraft(project.id, draft!.id)
        const confirmed = kind === 'apply' ? found.state === 'APPLIED' : ['STOPPING', 'CANCELLED'].includes(found.state)
        return confirmed ? { kind: 'ACCEPTED', receipt: found } : { kind: 'UNCONFIRMED' }
      },
      read: (value, apply) => { apply(() => {
        owner.set({ draft: value as ProjectConventionDraft, error: '', activity: kind === 'generate' ? undefined : owner.getSnapshot().activity,
          ...(value.state === 'APPLIED' ? { convention: { projectId: project.id, exists: true, loopperManaged: true, content: value.content ?? draft?.content ?? '' }, message: `${value.operation === 'CREATE' ? '已创建' : '已更新'} AGENTS.md` } : {}) })
        schedulePoll()
      }) },
    })
  }
  async function unmanage(project: Project) {
    if (owner.canLeave().kind === 'BLOCK') return false
    return owner.mutate({ endpoint: `/projects/${encodeURIComponent(project.id)}`, method: 'DELETE', body: undefined }, {
      label: '取消项目管理', write: () => port.getSnapshot().usingDemo ? Promise.resolve() : client.cancelProjectManagement(project.id),
      lookup: async () => !(await client.getProjects()).some(item => item.id === project.id) ? { kind: 'ACCEPTED', receipt: undefined } : { kind: 'UNCONFIRMED' },
      read: (_, apply) => { apply(() => { port.removeProject(project.id); owner.set({ context: undefined, selected: undefined, dirty: false, message: '已取消管理；项目目录、历史任务、设计对话、LoopSpec 与执行证据均已保留' }) }) },
    })
  }
  return { ...owner, open, close, discardContext, pick, register, saveDocument, assistAction, conventionAction, unmanage,
    credentials: () => credentialOwner,
    retryContext: () => { const state = owner.getSnapshot(); if (!owner.locked() && state.selected && state.context) return readContext(state.context, state.selected, generation) },
    changeRegister: (field: keyof ProjectState['register'], value: string) => owner.edit({ register: { ...owner.getSnapshot().register, [field]: value } }),
    changeDocument: (documentPath: string) => owner.edit({ documentPath }),
    changeRepository: (repository: string) => owner.edit({ repository, assistDirty: true }),
    changeSource: (index: number, patch: Partial<AssistEvidenceSource>) => owner.edit({ sources: owner.getSnapshot().sources.map((source, i) => i === index ? { ...source, ...patch, ...(patch.kind ? { root: '' } : {}) } : source), assistDirty: true }),
    addSource: () => { if (owner.getSnapshot().sources.length < 16) owner.edit({ sources: [...owner.getSnapshot().sources, { kind: 'LOG', root: '', pattern: 'logs/*.log' }], assistDirty: true }) },
    removeSource: (index: number) => owner.edit({ sources: owner.getSnapshot().sources.filter((_, i) => i !== index), assistDirty: true }),
    retire(forced = false) {
      const decision = owner.canLeave()
      if (!forced && decision.kind !== 'ALLOW') return decision
      generation++
      const failures: unknown[] = []
      try { owner.retire(forced) } catch (error) { failures.push(error) }
      try { clearPoll() } catch (error) { failures.push(error) }
      try { credentialOwner?.retire(forced) } catch (error) { failures.push(error) }
      if (failures.length) throw new OwnedResourceCleanupError(failures.flatMap(error => error instanceof OwnedResourceCleanupError ? error.failures : [error]))
      return { kind: 'ALLOW' as const }
    },
  }
}
