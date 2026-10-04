import { api, ApiError, subscribeDesignerEvents } from '@/api/client'
import type { AnalysisReport, ArtifactKind, DesignerActivity, DesignerAppendResult, DesignerSession, DesignerTaskProfileUpdatePreview, LoopDraft, LoopSpec, LoopSpecAssessment, Project, StoryBindingCapability, StoryBindingConfiguration, TaskIntent } from '@/types/domain'
import { createW4Owner, idleCommand, type CommandState } from '@/pages/w4/shared/core'
import type { OperationInput, OperationIdentity, OperationOwner, ReadContext, RecoveryCapability } from '@/foundation/contracts/receipt'
import type { NavigationRequest } from '@/foundation/contracts/navigation'
import type { W2PageProps } from '@/pages/w2/shared/types'
import { userFacingError } from '@/utils/displayLabels'
import { blankSpec, fileMetadata, json, messageKey, promptKey, readText, shouldPoll, storeText, workspaceKey } from './model'

export interface ProfileDraft { intent: TaskIntent; artifact: ArtifactKind; largeTask: boolean; components: string[] }
export interface DesignerSnapshot {
  session?: DesignerSession; draft?: LoopDraft; editor: string; baseline?: number; conflict: boolean
  prompt: string; message: string; initialFiles: ReturnType<typeof fileMetadata>; files: ReturnType<typeof fileMetadata>
  projectId: string; projects: Project[]; profile?: ProfileDraft; profileEditing: boolean; routerOpen: boolean
  selectedPackage: string; report?: AnalysisReport; activity?: DesignerActivity; activityTokens: number | null; activityDelta: number; activityError: string; previews: Record<string, string>
  story: StoryBindingConfiguration; storyCapability?: StoryBindingCapability; storyChecking?: boolean; autoMode: boolean
  loading: boolean; error: string; notice: string; stream: 'idle' | 'connecting' | 'connected' | 'reconnecting'
  command: CommandState; assessment?: LoopSpecAssessment; acceptedTask: string; acceptedDesign?: { sessionId: string; draftId: string }; revision: number; now: number
}
export type DesignerOptions = { sessionId?: string; historyOnly?: boolean; session?: DesignerSession; navigation: W2PageProps['navigation']; focusComposer?: () => void }
const newId = () => typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `designer-${Date.now()}-${Math.random().toString(16).slice(2)}`
const none = { kind: 'NONE' } as const
export function createDesignerController(options: DesignerOptions) {
  let initialFiles: File[] = [], files: File[] = [], fileId = '', initialId = '', pollRelease = () => {}, streamRelease = () => {}, streamId = '', refreshing = false, refreshSequence = 0, readEpoch = 0, queued = false, failures = 0, autoTaskAttempted = '', clockRelease = () => {}, clockRunning = false
  let currentOperation: { recoverWrite(): Promise<void>; readOriginal(): Promise<void>; retryReadback(): Promise<void>; getSnapshot(): { phase: string; accepted: boolean; recovery: { kind: string } } } | undefined
  let readTaskPermit: { destination: string; confirmation: string } | undefined
  let designOperation: OperationOwner<Record<string, never>, DesignerSession> | undefined
  let taskOperation: OperationOwner<{ expectedVersion: number }, { taskId: string }> | undefined
  const initial = options.session
  const owner = createW4Owner<DesignerSnapshot>('designer', options.sessionId ?? initial?.id ?? 'initial', {
    session: initial, draft: initial?.draft, editor: initial?.draft ? json(initial.draft.spec) : '', baseline: initial?.draft?.version, conflict: false,
    prompt: readText(promptKey), message: readText(messageKey), initialFiles: [], files: [], projectId: initial?.projectId ?? '', projects: [], profile: initial ? profileOf(initial) : undefined, profileEditing: false, routerOpen: false,
    selectedPackage: '', activityTokens: null, activityDelta: 0, activityError: '', previews: {}, story: { enabled: false }, autoMode: false, loading: false, error: '', notice: '', stream: 'idle', command: { ...idleCommand }, acceptedTask: '', revision: 0, now: Date.now(),
  }, state => {
    if (state.acceptedDesign) return { kind: 'BLOCK', reason: '新设计会话已创建，原交接尚未完成，请打开已创建设计。', recoveryAction: '打开已创建设计' }
    if (state.acceptedTask) return { kind: 'BLOCK', reason: '已创建任务，原交接尚未完成，请打开已创建任务。', recoveryAction: '打开已创建任务' }
    if (state.prompt.trim() || state.message.trim() || state.initialFiles.length || state.files.length || state.draft && state.editor !== json(state.draft.spec) || state.profileEditing) return { kind: 'CONFIRM_DISCARD', description: '设计草稿或附件尚未提交，离开将丢失当前内存输入。', draftRevision: state.revision }
    return { kind: 'ALLOW' }
  })
  let activityDeltaRelease = () => {}
  const state = () => owner.base.getSnapshot()
  const patch = (changes: Partial<DesignerSnapshot>) => owner.patch(changes)
  const dirty = (changes: Partial<DesignerSnapshot>) => patch({ ...changes, revision: state().revision + 1 })
  const current = (context?: ReadContext) => owner.active() && owner.base.capture().isCurrent() && (!context || context.isCurrent())
  const requireCurrent = (context?: ReadContext) => { if (!current(context)) throw new Error('原设计页面已经离开，迟到结果不会写入新页面。') }
  function profileOf(session: DesignerSession): ProfileDraft { return { intent: session.taskProfile.intent, artifact: session.taskProfile.artifactKinds[0] ?? 'SOURCE_CODE', largeTask: session.taskProfile.largeTaskMode, components: [...(session.taskProfile.componentKeys ?? [])] } }
  function projectSession(session: DesignerSession) {
    if (!current()) return
    const old = state(); if (old.session && (session.discussionRevision < old.session.discussionRevision || session.taskProfile.version < old.session.taskProfile.version || (session.draft?.version ?? 0) < (old.draft?.version ?? 0) || (session.autoMode.version < old.session.autoMode.version) || session.workPackages?.some(pkg => pkg.designRevision < (old.session?.workPackages?.find(previous => previous.id === pkg.id)?.designRevision ?? 0)))) throw new Error('读取结果旧于当前冻结版本，已保留最新设计。'); const editing = !!old.draft && old.editor !== json(old.draft.spec)
    if (old.session && session.id !== old.session.id) throw new Error('读取结果与原设计会话不一致。')
    const changes: Partial<DesignerSnapshot> = { session: { ...session, messages: (() => { const messages = new Map(old.session?.messages.map(m => [m.id, m])); for (const message of session.messages) messages.set(message.id, message); return [...messages.values()] })() }, projectId: session.projectId, draft: session.draft ?? old.draft, loading: false, error: '' }
    if (!editing && session.draft && !old.conflict) { changes.editor = json(session.draft.spec); changes.baseline = session.draft.version }
    if (!old.profileEditing) changes.profile = profileOf(session)
    if (session.routerRun && ['PENDING', 'RUNNING'].includes(session.routerRun.state) || session.taskProfile.decisionState === 'NEEDS_CONFIRMATION' && !session.taskProfile.confirmationReady) changes.routerOpen = true
    if (session.taskId || session.autoMode.taskId) changes.acceptedTask = session.taskId || session.autoMode.taskId
    patch(changes)
    if (session.taskProfile.decisionState === 'ROUTING' && !clockRunning) { clockRunning = true; clockTick() } else if (session.taskProfile.decisionState !== 'ROUTING') { clockRelease(); clockRunning = false }
    if (session.draft) storeText(workspaceKey, JSON.stringify({ sessionId: session.id, draftId: session.draft.id }))
    if (session.id !== streamId) connect(session.id)
  }
  function clockTick() { if (!current() || state().session?.taskProfile.decisionState !== 'ROUTING') { clockRunning = false; return }; patch({ now: Date.now() }); clockRelease = owner.delay(clockTick, 1000) }
  function connect(id: string) {
    streamRelease(); streamId = id; patch({ stream: 'connecting' })
    const token = owner.ticket('stream')
    const stream = subscribeDesignerEvents(id, event => {
      if (!token.current() || event.sessionId !== id) return
      // Events are invalidation hints; only persisted REST messages enter the timeline.
      if (event.type === 'ERROR') patch({ error: userFacingError(event.detail || event.content, '设计会话读取失败。') })
      queued = true; void refresh()
    }, next => { if (token.current()) patch({ stream: next }) })
    streamRelease = owner.own(() => { stream.close(); if (streamId === id) streamId = '' })
  }
  function schedule() {
    pollRelease(); if (!current() || !shouldPoll(state().session)) return
    pollRelease = owner.delay(() => { void refresh() }, failures ? Math.min(12000, 1500 * 2 ** failures) : state().session?.taskProfile.decisionState === 'ROUTING' || state().session?.state === 'RUNNING' ? 1200 : 1500)
  }
  async function refresh() {
    if (!current()) return false
    if (refreshing) { queued = true; pollRelease(); pollRelease = owner.delay(() => { void refresh() }, 100); return false }
    const id = state().session?.id ?? options.sessionId
    if (!id) return false
    const token = owner.ticket('session'), sequence = ++refreshSequence, epoch = readEpoch; refreshing = true; queued = false; patch({ loading: true })
    try {
      const session = await api.getDesignerSession(id)
      if (!token.current() || epoch !== readEpoch) return false
      if (session.id !== id) throw new Error('会话读取身份不匹配。')
      if (session.archived && !state().session) { patch({ error: '该设计会话已归档，请从历史记录查看。', loading: false }); return false }
      projectSession(session); failures = 0
      const serverCompiler = session.compiler?.serverCompiled
      if ((session.state === 'RUNNING' && !serverCompiler && !session.pendingQuestions?.length) || session.taskProfile.decisionState === 'ROUTING') {
        try {
          const activity = await api.getDesignerActivity(id)
          if (!token.current() || epoch !== readEpoch) return false
          const previous = state().activityTokens, reported = activity.usage.totalTokens
          const total = reported == null ? previous : previous == null ? reported : Math.max(previous, reported)
          const delta = previous == null || total == null ? 0 : Math.max(0, total - previous)
          activityDeltaRelease()
          patch({ activity: { ...activity, parts: activity.parts.length ? [activity.parts.at(-1)!] : !activity.connected ? state().activity?.parts ?? [] : [] }, activityTokens: total, activityDelta: delta, activityError: '' })
          if (delta) activityDeltaRelease = owner.delay(() => { if (token.current() && epoch === readEpoch) patch({ activityDelta: 0 }) }, 850)
        } catch (cause) { if (!token.current() || epoch !== readEpoch) return false; patch({ activityError: userFacingError(cause, '当前角色活动暂时无法刷新') }) }
      } else { activityDeltaRelease(); patch({ activity: undefined, activityDelta: 0, activityError: '' }) }
      const taskId = session.taskId || session.autoMode.taskId
      const autoTaskStarted = session.autoMode.enabled || session.autoMode.state === 'COMPLETED' && session.autoMode.lastAction === 'TASK_START_REQUESTED'
      if (autoTaskStarted && taskId && taskId !== autoTaskAttempted && !hasUnsentDraft() && !['SENDING', 'UNKNOWN', 'ACCEPTED_READBACK'].includes(state().command.phase)) { autoTaskAttempted = taskId; void openKnownTask() }
      return true
    } catch (cause) { if (token.current() && epoch === readEpoch) { failures++; patch({ error: userFacingError(cause, '设计会话暂时无法读取，原恢复身份仍保留。'), loading: false }) } return false }
    finally { if (sequence !== refreshSequence) return; if (token.current() && epoch === readEpoch) { refreshing = false; patch({ loading: false }); if (queued) { queued = false; pollRelease = owner.delay(() => { void refresh() }, 100) } else schedule() } else refreshing = false }
  }
  async function refreshRequired(context: ReadContext, validate?: (session: DesignerSession) => void) { readEpoch++; owner.ticket('session'); requireCurrent(context); const id = state().session?.id; if (!id) throw new Error('原会话不存在。'); const session = await api.getDesignerSession(id); requireCurrent(context); if (session.id !== id) throw new Error('原会话身份不匹配。'); validate?.(session); projectSession(session); schedule() }
  function run<B, R>(label: string, input: OperationInput<B>, write: (identity: OperationIdentity<B>) => Promise<R>, read: (receipt: Readonly<R>, context: ReadContext) => Promise<void>, capability: RecoveryCapability<B, R> = none, handoffTarget?: (receipt: Readonly<R>) => string, definitive?: (cause: unknown) => boolean, definitiveOverridesDefault = false) {
    readEpoch++; refreshSequence++; refreshing = false; owner.ticket('session'); pollRelease(); const operation = owner.command({ label, input, write, read, capability, handoffTarget, changed: command => patch({ command }), isDefinitiveRejection: cause => definitiveOverridesDefault && definitive ? definitive(cause) : definitive?.(cause) || cause instanceof ApiError && [400, 401, 403, 422].includes(cause.status) })
    currentOperation = operation; return operation
  }
  async function invoke(action: () => Promise<unknown>) { try { await action(); if (current() && !['UNKNOWN', 'ACCEPTED_READBACK'].includes(state().command.phase)) patch({ error: '' }) } catch (cause) { if (current()) patch({ error: userFacingError(cause, '操作未结清，请保留原身份并恢复。') }) } }
  function captureConfirmation() { return JSON.stringify({ epoch: owner.base.identity.epoch, revision: state().revision, session: state().session?.id, discussion: state().session?.discussionRevision, draft: state().baseline, profile: state().session?.taskProfile.version, packages: state().session?.workPackages?.map(p => [p.id, p.designRevision, p.approvedDesignRevision]), router: state().session?.routerRun, state: state().session?.state, phase: state().session?.workflowPhase, task: state().session?.taskId || state().session?.autoMode.taskId, attachments: state().session?.messages.flatMap(message => message.attachments?.map(file => [file.id, file.sha256, file.state, file.scopeKey]) ?? []) }) }
  async function sessionCommand(label: string, suffix: string, body: Record<string, unknown>, write: () => Promise<unknown>, method: 'POST' | 'PUT' = 'POST') {
    const id = state().session?.id; if (!id) return
    await invoke(async () => { const operation = run(label, { endpoint: `/api/designer-sessions/${encodeURIComponent(id)}/${suffix}`, method, body }, async () => { await write(); return { sessionId: id } }, async (_receipt, context) => { await refreshRequired(context) }); await operation.execute() })
  }
  function syncFiles() { dirty({ initialFiles: fileMetadata(initialFiles), files: fileMetadata(files) }) }
  function stageFiles(incoming: readonly File[]) {
    if (incoming.some(file => file.size > 20 * 1024 * 1024)) { patch({ error: '单个附件不能超过 20 MiB。' }); return }
    const target = state().session ? files : initialFiles, next = [...target]
    for (const file of incoming) { const index = next.findIndex(item => item.name === file.name); if (index < 0) next.push(file); else next[index] = file }
    if (next.length > 10) { patch({ error: '每条消息最多携带 10 个附件。' }); return }
    if (state().session) { files = next; fileId ||= newId() } else { initialFiles = next; initialId ||= newId() }
    syncFiles()
  }
  async function initialSubmit() {
    if (state().command.phase === 'UNKNOWN' && state().command.recovery === 'RETRY_IDENTICAL' && currentOperation) { await invoke(() => currentOperation!.recoverWrite()); return }
    if (!owner.canStartWrite()) return
    const source = state(), content = source.prompt.trim(), capturedFiles = [...initialFiles], submissionId = initialId || newId()
    if (!content || !source.projectId) { patch({ error: '请先选择项目并填写设计目标。' }); return }
    if (source.story.enabled && (!source.story.systemCode?.trim() || !source.story.storyCode?.trim())) { patch({ error: '开启故事绑定后，请填写系统编号和故事编号。' }); return }
    const lease = owner.ticket('initial')
    await invoke(async () => {
      let draft = source.draft
      if (!draft) {
        const settings = await api.getSettings(); if (!lease.current()) return
        const spec = blankSpec(source.projectId, content, settings)
        const create = run('创建设计草稿', { endpoint: '/api/loop-drafts', method: 'POST', body: { spec } }, identity => api.createDraft(identity.body.spec), async (receipt, context) => { requireCurrent(context); if (!receipt.id?.trim() || receipt.spec.projectId !== source.projectId) throw new Error('草稿已接受，但原项目与回执身份尚未核对。'); draft = receipt as LoopDraft; patch({ draft: receipt as LoopDraft, editor: json(receipt.spec), baseline: receipt.version }) })
        await create.execute(); if (!lease.current() || !draft) return
      }
      const body = { submissionId, projectId: source.projectId, draftId: draft.id, content, autoModeEnabled: source.autoMode, storyBinding: source.story }
      const operation = run('创建设计会话', { endpoint: capturedFiles.length ? '/api/designer-sessions/context-turns' : '/api/designer-sessions', method: 'POST', body: capturedFiles.length ? body : { projectId: body.projectId, draftId: body.draftId, initialMessage: body.content, autoModeEnabled: body.autoModeEnabled, storyBinding: body.storyBinding }, requestKey: capturedFiles.length ? submissionId : undefined, files: capturedFiles }, identity => capturedFiles.length ? api.createDesignerContextTurn(body, [...identity.files]) : api.createDesignerSession(body.projectId, body.draftId, body.content, body.autoModeEnabled, body.storyBinding), async (receipt, context) => {
        requireCurrent(context); if (receipt.projectId !== source.projectId || receipt.draft?.id !== draft?.id || !receipt.id?.trim()) throw new Error('会话已接受，但回执不属于原项目与草稿；原文件身份仍保留。'); initialFiles = initialFiles.filter(file => !capturedFiles.includes(file)); files = [...files, ...initialFiles]; initialFiles = []; initialId = ''
        const later = state().prompt !== source.prompt ? state().prompt : ''
        patch({ session: undefined, prompt: '', message: later || state().message, initialFiles: fileMetadata(initialFiles), files: fileMetadata(files) }); storeText(promptKey, ''); storeText(messageKey, later || state().message)
        projectSession(receipt as DesignerSession)
      }, capturedFiles.length ? { kind: 'IDEMPOTENT_KEY' } : none, undefined, capturedFiles.length ? initialAttachmentRejectedBeforeCreate : undefined, !!capturedFiles.length)
      try { await operation.execute() }
      finally { if (operation.getSnapshot().phase === 'SETTLED' && !operation.getSnapshot().accepted) initialId = '' }
    })
  }
  async function send() {
    if (state().command.phase === 'UNKNOWN' && state().command.recovery === 'RETRY_IDENTICAL' && currentOperation) { await invoke(() => currentOperation!.recoverWrite()); return }
    if (!owner.canStartWrite()) return
    const source = state(), session = source.session; if (!session || !source.message.trim()) return
    const content = source.message.trim(), capturedFiles = [...files], submissionId = fileId || newId(), pkg = session.workPackages?.find(item => item.id === session.activeWorkPackageId)
    const body = { submissionId, content, scopeKey: session.discussionScope, workPackageId: session.activeWorkPackageId, expectedDiscussionRevision: session.discussionRevision, expectedDesignRevision: pkg?.designRevision ?? 0 }
    await invoke(async () => { const operation = run('发送设计消息', { endpoint: `/api/designer-sessions/${encodeURIComponent(session.id)}/${capturedFiles.length ? 'context-turns' : session.discussionScope === 'REQUIREMENT' ? 'requirement/messages' : pkg ? `work-packages/${pkg.id}/messages` : 'messages'}`, method: 'POST', body: capturedFiles.length ? body : session.discussionScope === 'REQUIREMENT' ? { content, expectedDiscussionRevision: session.discussionRevision } : pkg ? { content, expectedDiscussionRevision: session.discussionRevision, expectedDesignRevision: pkg.designRevision } : { content }, requestKey: capturedFiles.length ? submissionId : undefined, files: capturedFiles }, identity => capturedFiles.length ? api.sendDesignerContextTurn(session.id, body, [...identity.files]) : session.discussionScope === 'REQUIREMENT' ? api.sendRequirementMessage(session.id, content, session.discussionRevision) : pkg ? api.sendWorkPackageMessage(session.id, pkg.id, content, session.discussionRevision, pkg.designRevision) : api.sendDesignerMessage(session.id, content), async (receipt: Readonly<DesignerAppendResult>, context) => {
      requireCurrent(context); if (receipt.sessionId !== session.id) throw new Error('消息回执与原会话不匹配。')
      const byId = new Map(state().session?.messages.map(message => [message.id, message])); for (const message of receipt.persistedMessages) byId.set(message.id, message)
      files = files.filter(file => !capturedFiles.includes(file)); fileId = files.length ? newId() : ''
      const message = state().message === source.message ? '' : state().message
      patch({ message, files: fileMetadata(files), session: state().session ? { ...state().session!, state: receipt.state, messages: [...byId.values()] } : undefined, notice: receipt.notice ?? '' }); storeText(messageKey, message)
      await refreshRequired(context)
    }, capturedFiles.length ? { kind: 'IDEMPOTENT_KEY' } : none); await operation.execute() })
  }
  async function saveDraft() {
    const source = state(), draft = source.draft; if (!draft || draft.status === 'CONFIRMED' || source.conflict || draft.version === undefined || !owner.canStartWrite()) return false
    const lease = owner.ticket('save-validation')
    try {
      const spec = JSON.parse(source.editor) as LoopSpec, assessment = await api.validateDraft(spec)
      if (!lease.current()) return false
      patch({ assessment }); if (!assessment.valid) { patch({ error: assessment.errors.join('；') }); return false }
      const body = { spec, expectedVersion: source.baseline }
      const operation = run('保存执行规范', { endpoint: `/api/loop-drafts/${encodeURIComponent(draft.id)}`, method: 'PUT', body, versions: { draft: source.baseline ?? draft.version } }, identity => api.updateDraft(draft.id, identity.body.spec, identity.body.expectedVersion), async (receipt, context) => {
        requireCurrent(context)
        // Keep the exact submitted editor baseline until authoritative readback succeeds.
        if (receipt.id !== draft.id) throw new Error('保存回执与原草稿身份不匹配。')
        await refreshRequired(context, session => { if (session.draft?.id !== draft.id || (session.draft.version ?? -1) < (receipt.version ?? 0)) throw new Error('原草稿尚未读到已接受的保存版本。') }); requireCurrent(context)
        patch({ draft: receipt as LoopDraft, baseline: receipt.version, editor: state().editor === source.editor ? json(receipt.spec) : state().editor, conflict: false })
      }, none, undefined, cause => cause instanceof ApiError && cause.status === 409 && ['DRAFT_VERSION_CONFLICT', 'DESIGNER_DRAFT_CHANGED'].includes(cause.code ?? '')); await operation.execute(); return operation.getSnapshot().phase === 'SETTLED'
    } catch (cause) { if (current()) patch({ error: userFacingError(cause, '保存失败'), conflict: cause instanceof ApiError && cause.status === 409 || state().conflict }); return false }
  }
  async function reloadDraft() {
    const source = state(), id = source.draft?.id; if (!id || !owner.canStartWrite()) return
    const ticket = owner.ticket('draft-reload')
    await invoke(async () => { const draft = await api.getDraft(id); if (!ticket.current()) return; const session = source.session ? await api.getDesignerSession(source.session.id) : undefined; if (!ticket.current()) return; if (session && session.id !== source.session?.id || draft.id !== id) throw new Error('原草稿读取身份不匹配'); if (session) projectSession(session); patch({ draft, editor: json(draft.spec), baseline: draft.version, conflict: false }) })
  }
  async function confirm() {
    let source = state(); if (!source.draft || !source.session || !owner.canStartWrite()) return
    if (source.editor !== json(source.draft.spec) && !await saveDraft()) return
    source = state(); const draft = source.draft!
    if (draft.status === 'CONFIRMED' && (source.session?.taskId || source.session?.autoMode.taskId)) { patch({ acceptedTask: source.session.taskId || source.session.autoMode.taskId }); return openTask() }
    if (!source.session!.finalConfirmationEligible || source.conflict || draft.version === undefined) return
    const version = draft.version
    await invoke(async () => { taskOperation = run('确认设计并创建任务', { endpoint: `/api/loop-drafts/${encodeURIComponent(draft.id)}/confirm`, method: 'POST', body: { expectedVersion: version }, versions: { draft: version } }, identity => api.confirmDraft(draft.id, identity.body.expectedVersion), async (receipt, context) => {
      requireCurrent(context); if (!receipt.taskId?.trim()) throw new Error('已接受确认，但原任务回执缺少可核对身份。'); patch({ acceptedTask: receipt.taskId }); const confirmedDraft = await api.getDraft(draft.id); requireCurrent(context); if (confirmedDraft.id !== draft.id) throw new Error('确认后读取返回其他草稿。')
      const task = await api.getTaskOverview(receipt.taskId); requireCurrent(context); if (task.id !== receipt.taskId) throw new Error('确认后读取返回其他任务。'); await api.getTaskAudit(receipt.taskId); requireCurrent(context)
    }, none, receipt => `/tasks/${receipt.taskId}`); await taskOperation!.execute(); await openTask() })
  }
  async function openTask() {
    if (!current() || !state().acceptedTask || hasUnsentDraft()) return false
    if (!taskOperation) return openKnownTask()
    await invoke(async () => { if (taskOperation!.getSnapshot().phase === 'ACCEPTED_READBACK') await taskOperation!.retryReadback() })
    if (!current() || taskOperation.getSnapshot().phase !== 'SETTLED' || hasUnsentDraft()) return false
    try { const permit = taskOperation.prepareHandoff(); const successful = await options.navigation.goAccepted(permit.destination, permit); if (successful && current()) { patch({ acceptedTask: '', prompt: '', message: '', files: [], initialFiles: [] }); storeText(workspaceKey, ''); storeText(promptKey, ''); storeText(messageKey, '') } return successful }
    catch (cause) { if (current()) patch({ error: userFacingError(cause, '已创建任务，请从原交接入口再次打开。') }); return false }
  }
  function hasUnsentDraft() { const source = state(); return !!(source.prompt.trim() || source.message.trim() || source.initialFiles.length || source.files.length || source.profileEditing || source.draft && source.editor !== json(source.draft.spec)) }
  async function openKnownTask() {
    const source = state(), taskId = source.acceptedTask, session = source.session, ticket = owner.ticket('known-task'), confirmation = captureConfirmation()
    if (!session || !(session.taskId === taskId || session.autoMode.taskId === taskId) || !taskId || hasUnsentDraft() || ['SENDING', 'UNKNOWN', 'ACCEPTED_READBACK'].includes(source.command.phase)) return false
    try {
      const task = await api.getTaskOverview(taskId); if (!ticket.current() || confirmation !== captureConfirmation()) return false; if (task.id !== taskId) throw new Error('已确认任务的读取身份不匹配。')
      await api.getTaskAudit(taskId); if (!ticket.current() || confirmation !== captureConfirmation() || hasUnsentDraft()) return false
      readTaskPermit = { destination: `/tasks/${taskId}`, confirmation }
      const success = await options.navigation.go(readTaskPermit.destination)
      if (success && ticket.current()) { patch({ acceptedTask: '' }); storeText(workspaceKey, '') }
      return success
    } catch (cause) { if (ticket.current()) patch({ error: userFacingError(cause, '已创建任务，请重新读取原任务后打开。') }); return false }
    finally { readTaskPermit = undefined }
  }
  async function openDesign() {
    if (!current() || !designOperation || !state().acceptedDesign || hasUnsentDraft()) return false
    await invoke(async () => { if (designOperation!.getSnapshot().phase === 'ACCEPTED_READBACK') await designOperation!.retryReadback() })
    if (!current() || designOperation.getSnapshot().phase !== 'SETTLED') return false
    try { const next = state().acceptedDesign!, permit = designOperation.prepareHandoff(); const success = await options.navigation.goAccepted(permit.destination, permit); if (success) { if (current()) patch({ acceptedDesign: undefined }); storeText(workspaceKey, JSON.stringify(next)) } return success } catch (cause) { if (current()) patch({ error: userFacingError(cause, '新设计已创建，请从原交接入口再次打开。') }); return false }
  }
  function canLeave(request?: NavigationRequest) {
    if (!hasUnsentDraft() && state().acceptedDesign && request?.handoff && request.destination === `/designer?sessionId=${encodeURIComponent(state().acceptedDesign!.sessionId)}` && designOperation?.leaveRisk().permitsHandoff(request.handoff, request.destination)) return { kind: 'ALLOW' as const }
    if (!hasUnsentDraft() && readTaskPermit && request?.destination === readTaskPermit.destination && captureConfirmation() === readTaskPermit.confirmation && current()) return { kind: 'ALLOW' as const }
    if (!hasUnsentDraft() && state().acceptedTask && request?.handoff && request.destination === `/tasks/${state().acceptedTask}` && taskOperation?.leaveRisk().permitsHandoff(request.handoff, request.destination)) return { kind: 'ALLOW' as const }
    return owner.base.canLeave(request)
  }
  async function previewProfile() {
    const source = state(), session = source.session, profile = source.profile; if (!session || !profile || !owner.canStartWrite()) return undefined
    const ticket = owner.ticket('profile-preview'), revision = captureConfirmation()
    try { const result = await api.previewDesignerTaskProfileUpdate(session.id, profile.intent, profile.artifact, session.taskProfile.version, profile.intent === 'SOFTWARE_CHANGE' ? profile.largeTask : undefined, profile.components); if (!ticket.current() || revision !== captureConfirmation()) return undefined; return { preview: result, token: revision, sessionId: session.id, profile, expectedVersion: session.taskProfile.version } }
    catch (cause) { if (ticket.current()) { if (cause instanceof ApiError && cause.status === 409) { await refresh(); if (ticket.current()) patch({ notice: '任务设置已被更新，请按最新版本重新选择。' }) } else patch({ error: userFacingError(cause, '任务设置预览失败') }) } return undefined }
  }
  async function applyProfile(proposal: NonNullable<Awaited<ReturnType<typeof previewProfile>>>) {
    if (proposal.token !== captureConfirmation() || !current()) return
    await invoke(async () => { const body = { intent: proposal.profile.intent, primaryArtifactKind: proposal.profile.artifact, expectedVersion: proposal.expectedVersion, largeTaskMode: proposal.profile.intent === 'SOFTWARE_CHANGE' ? proposal.profile.largeTask : undefined, componentKeys: proposal.profile.components }
      const operation = run('修改任务设置', { endpoint: `/api/designer-sessions/${encodeURIComponent(proposal.sessionId)}/task-profile`, method: 'PUT', body }, identity => api.updateDesignerTaskProfile(proposal.sessionId, identity.body.intent, identity.body.primaryArtifactKind, identity.body.expectedVersion, identity.body.largeTaskMode, [...identity.body.componentKeys]), async (_receipt, context) => { await refreshRequired(context); requireCurrent(context); patch({ profileEditing: false, profile: state().session ? profileOf(state().session!) : undefined }) }, none, undefined, cause => cause instanceof ApiError && cause.status === 409 && cause.code === 'TASK_PROFILE_VERSION_CONFLICT'); await operation.execute() }).then(async () => { if (state().command.phase === 'SETTLED' && state().command.error) { await refresh(); if (current()) patch({ error: '', notice: '任务设置刚刚发生变化，已刷新最新结果。' }) } })
  }
  async function reset() {
    const session = state().session; if (!session || !owner.canStartWrite()) return false
    let stopped = false
    await invoke(async () => { const operation = run('停止并重新开始', { endpoint: `/api/designer-sessions/${encodeURIComponent(session.id)}/stop`, method: 'POST', body: {} }, () => api.stopDesignerSession(session.id), async (receipt, context) => { requireCurrent(context); if (!receipt.archived || receipt.failedSessions > 0) throw new Error('原会话尚未证明停止，请保留身份。'); stopped = true; await refreshRequired(context) }); await operation.execute() })
    if (!stopped || !current()) return false
    const success = await options.navigation.go(`/requirements/new?projectId=${encodeURIComponent(session.projectId)}`)
    if (success && current()) { storeText(workspaceKey, ''); storeText(promptKey, ''); storeText(messageKey, '') }
    return success
  }
  function detectStory(projectId = state().projectId) {
    const ticket = owner.ticket('story'); patch({ storyChecking: true, storyCapability: undefined, story: { ...state().story, enabled: false } })
    if (!projectId) { patch({ storyChecking: false }); return }
    void api.getStoryBindingCapability(projectId).then(storyCapability => { if (ticket.current()) patch({ storyCapability, storyChecking: false }) }).catch(cause => { if (ticket.current()) patch({ storyChecking: false, error: userFacingError(cause, '故事绑定能力暂时无法读取') }) })
  }
  owner.setStart(() => {
    refreshSequence++; refreshing = false; streamId = ''; queued = false; clockRunning = false
    const id = state().session?.id ?? options.sessionId
    if (id) { connect(id); void refresh() } else if (!options.historyOnly) {
      const ticket = owner.ticket('projects'); void api.getProjects().then(projects => { if (ticket.current()) { patch({ projects, projectId: state().projectId || projects[0]?.id || '' }); detectStory() } }).catch(cause => { if (ticket.current()) patch({ error: userFacingError(cause) }) })
    }
    if (options.focusComposer) { const lease = owner.ticket('focus'); const frame = requestAnimationFrame(() => { if (lease.current()) options.focusComposer?.() }); owner.own(() => cancelAnimationFrame(frame)) }
  })
  const base = { ...owner.base, canLeave }
  return { base, getSnapshot: state, subscribe: owner.base.subscribe, canLeave, attachView: owner.base.attachView,
    retire(forced = false) { const decision = owner.base.retire(forced); if (decision.kind === 'ALLOW') { pollRelease(); streamRelease() } return decision },
    refresh, send, initialSubmit, saveDraft, reloadDraft, confirm, openTask, openDesign, reset, previewProfile, applyProfile, captureConfirmation,
    confirmationCurrent: (token: string) => current() && token === captureConfirmation() && owner.canStartWrite(),
    setPrompt(prompt: string) { dirty({ prompt }); storeText(promptKey, prompt) }, setMessage(message: string) { dirty({ message }); storeText(messageKey, message) },
    detectStory, setProject(projectId: string) { dirty({ projectId }); detectStory(projectId) },
    setEditor(editor: string) { dirty({ editor, assessment: undefined }) }, setStory(story: StoryBindingConfiguration) { dirty({ story: { ...story, enabled: story.enabled && state().storyCapability?.available === true && !state().storyChecking } }) }, setInitialAuto(autoMode: boolean) { dirty({ autoMode }) },
    stageFiles, fileRefs: () => ({ initial: [...initialFiles], followup: [...files] }), removeFile(index: number, initial = false) { if (initial) { initialFiles.splice(index, 1); if (!initialFiles.length) initialId = '' } else { files.splice(index, 1); if (!files.length) fileId = '' } syncFiles() },
    selectPackage(selectedPackage: string) { patch({ selectedPackage }) }, editProfile(editing = true) { dirty({ profileEditing: editing, profile: state().session ? profileOf(state().session!) : undefined }) }, changeProfile(profile: ProfileDraft) { dirty({ profile }) }, setRouterOpen(routerOpen: boolean) { patch({ routerOpen }) },
    async recover() { if (!currentOperation || !current()) return; await invoke(async () => { const snapshot = currentOperation!.getSnapshot(); if (snapshot.accepted) await currentOperation!.retryReadback(); else if (snapshot.recovery.kind === 'RETRY_IDENTICAL') await currentOperation!.recoverWrite(); else if (snapshot.recovery.kind === 'READ_ORIGINAL') await currentOperation!.readOriginal() }) },
    async replyQuestion(questionId: string, answers: string[][]) { const session = state().session; if (!session) return; await sessionCommand('提交回答并继续', `questions/${questionId}/reply`, { answers }, () => api.replyDesignerQuestion(session.id, questionId, answers.map(answer => [...answer]))) },
    async action(action: 'confirmRequirement' | 'reopenRequirement' | 'approvePackage' | 'reopenPackage' | 'retryCompiler' | 'redesign' | 'retryDecomposition' | 'retryPackageCompiler' | 'redesignPackage' | 'confirmProfile' | 'rerouteProfile' | 'cancelRouter' | 'largeTask' | 'autoMode', packageId?: string) {
      const session = state().session; if (!session || !owner.canStartWrite() || action === 'confirmRequirement' && !session.taskProfile.confirmationReady) return
      if (['rerouteProfile', 'cancelRouter'].includes(action) && !session.routerRun) return
      const pkg = session.workPackages?.find(item => item.id === (packageId || session.activeWorkPackageId)), revision = session.discussionRevision, version = session.taskProfile.version
      const writes: Record<typeof action, { suffix: string; label: string; write: () => Promise<unknown> }> = {
        confirmRequirement: { suffix: 'requirement/confirm', label: '确认需求', write: () => api.confirmDesignerRequirement(session.id, revision) }, reopenRequirement: { suffix: 'requirement/reopen', label: '重新讨论需求', write: () => api.reopenDesignerRequirement(session.id, revision) },
        approvePackage: { suffix: `work-packages/${pkg?.id}/approve`, label: '接受当前设计', write: () => api.approveWorkPackage(session.id, pkg!.id, revision, pkg!.designRevision) }, reopenPackage: { suffix: `work-packages/${pkg?.id}/reopen`, label: '重新讨论工作包', write: () => api.reopenWorkPackage(session.id, pkg!.id, revision, pkg!.approvedDesignRevision!) },
        retryCompiler: { suffix: 'compiler/retry', label: '重新编译规范', write: () => api.retryDesignerCompiler(session.id) }, redesign: { suffix: 'redesign', label: '重新设计', write: () => api.requestDesignerRedesign(session.id) }, retryDecomposition: { suffix: 'decomposition/retry', label: '重试拆分', write: () => api.retryDesignerDecomposition(session.id) },
        retryPackageCompiler: { suffix: `work-packages/${pkg?.id}/compiler/retry`, label: '重新编译工作包', write: () => api.retryWorkPackageCompiler(session.id, pkg!.id) }, redesignPackage: { suffix: `work-packages/${pkg?.id}/redesign`, label: '重新设计工作包', write: () => api.redesignWorkPackage(session.id, pkg!.id) },
        confirmProfile: { suffix: 'task-profile/confirm', label: '确认任务设置', write: () => api.confirmDesignerTaskProfile(session.id, version) }, rerouteProfile: { suffix: 'task-profile/reroute', label: '重新判定', write: () => api.rerouteDesignerTaskProfile(session.id, session.routerRun!.id, version) }, cancelRouter: { suffix: 'task-profile/cancel', label: '取消 AI 判定', write: () => api.cancelDesignerTaskProfileRouting(session.id, session.routerRun!.id) },
        largeTask: { suffix: 'large-task-mode', label: '启用大型任务模式', write: () => api.enableDesignerLargeTaskMode(session.id, revision, version) }, autoMode: { suffix: 'auto-mode', label: '修改全自动模式', write: () => api.updateDesignerAutoMode(session.id, !session.autoMode.enabled, session.autoMode.version) },
      }
      if (['approvePackage', 'reopenPackage', 'retryPackageCompiler', 'redesignPackage'].includes(action) && !pkg || action === 'reopenPackage' && !pkg?.approvedDesignRevision) return
      const selected = writes[action]; const body = ['confirmRequirement', 'reopenRequirement'].includes(action) ? { expectedDiscussionRevision: revision } : ['approvePackage', 'reopenPackage'].includes(action) ? { expectedDiscussionRevision: revision, expectedDesignRevision: action === 'reopenPackage' ? pkg?.approvedDesignRevision : pkg?.designRevision } : action === 'confirmProfile' ? { expectedVersion: version } : action === 'rerouteProfile' ? { expectedRunId: session.routerRun?.id, expectedProfileVersion: version } : action === 'cancelRouter' ? { expectedRunId: session.routerRun?.id } : action === 'largeTask' ? { expectedDiscussionRevision: revision, expectedProfileVersion: version } : action === 'autoMode' ? { enabled: !session.autoMode.enabled, expectedVersion: session.autoMode.version } : {}; await sessionCommand(selected.label, selected.suffix, body, selected.write, action === 'autoMode' ? 'PUT' : 'POST')
    },
    async loadPreview(attachmentId: string) { const id = state().session?.id; if (!id) return; const ticket = owner.ticket(`attachment:${attachmentId}`); await invoke(async () => { const preview = await api.getDesignerAttachmentPreview(id, attachmentId); if (ticket.current()) patch({ previews: { ...state().previews, [attachmentId]: preview.text ?? '此资料不提供内联文本，请打开原文件。' } }) }) },
    async stopAttachment(attachmentId: string) { const id = state().session?.id; if (!id) return; const commandId = newId(); await invoke(async () => { const operation = run('停止资料未来使用', { endpoint: `/api/designer-sessions/${encodeURIComponent(id)}/attachments/${encodeURIComponent(attachmentId)}/stop-future-use`, method: 'POST', body: { commandId }, commandId }, identity => api.stopDesignerAttachment(id, attachmentId, identity.body.commandId), async (_receipt, context) => { await refreshRequired(context) }, { kind: 'IDEMPOTENT_KEY' }); await operation.execute() }) },
    async loadReport(reportId: string) { const id = state().session?.id; if (!id) return; const ticket = owner.ticket('report'); await invoke(async () => { const report = await api.getAnalysisReport(id, reportId); if (ticket.current()) { if (report.id !== reportId) throw new Error('报告读取与原报告身份不一致。'); patch({ report }) } }) },
    async convertReport() {
      const source = state(), session = source.session, report = source.report; if (!session || !report || !owner.canStartWrite()) return
      await invoke(async () => { designOperation = run('转换为可写设计', { endpoint: `/api/designer-sessions/${encodeURIComponent(session.id)}/reports/${encodeURIComponent(report.id)}/convert-to-design`, method: 'POST', body: {} }, () => api.convertAnalysisReportToDesign(session.id, report.id), async (receipt, context) => { requireCurrent(context); if (!receipt.id || !receipt.draft?.id || receipt.projectId !== session.projectId) throw new Error('转换已接受，但新设计回执身份尚未核对。'); patch({ acceptedDesign: { sessionId: receipt.id, draftId: receipt.draft.id } }) }, none, receipt => `/designer?sessionId=${encodeURIComponent(receipt.id)}`); await designOperation.execute(); await openDesign() })
    },
    async copyV2() { const source = state(), draft = source.draft; if (!draft || draft.spec.schemaVersion !== 'v1') return; await invoke(async () => { const copy = run('复制为新规范', { endpoint: `/api/loop-drafts/${encodeURIComponent(draft.id)}/copy-v2`, method: 'POST', body: {} }, () => api.copyDraftAsV2(draft.id), async (receipt, context) => { requireCurrent(context); patch({ draft: receipt as LoopDraft, editor: json(receipt.spec), baseline: receipt.version }) }); await copy.execute(); if (!current()) return; const next = state().draft!; const create = run('打开新版设计会话', { endpoint: '/api/designer-sessions', method: 'POST', body: { projectId: next.spec.projectId, draftId: next.id, autoModeEnabled: false } }, identity => api.createDesignerSession(identity.body.projectId, identity.body.draftId), async (receipt, context) => { requireCurrent(context); if (receipt.projectId !== next.spec.projectId || receipt.draft?.id !== next.id) throw new Error('新版会话回执与原升级草稿不一致。'); patch({ session: undefined }); projectSession(receipt as DesignerSession) }); await create.execute() }) },
    async assess() { const ticket = owner.ticket('assessment'), editor = state().editor; await invoke(async () => { const assessment = await api.validateDraft(JSON.parse(editor) as LoopSpec); if (ticket.current() && editor === state().editor) patch({ assessment }) }) },
  }
}
export type DesignerController = ReturnType<typeof createDesignerController>
export type ProfileProposal = { preview: DesignerTaskProfileUpdatePreview; token: string; sessionId: string; profile: ProfileDraft; expectedVersion: number }

/** prepare/inspect rejects these before sessions.create; generic 400 and session budget errors do not prove zero side effects. */
function initialAttachmentRejectedBeforeCreate(cause: unknown) {
  const prewriteCodes = new Set(['ATTACHMENT_FILE_COUNT_INVALID', 'ATTACHMENT_DUPLICATE_FILENAME', 'ATTACHMENT_EMPTY', 'ATTACHMENT_FILE_TOO_LARGE', 'ATTACHMENT_TYPE_UNSUPPORTED', 'ATTACHMENT_CONTEXT_TOO_LARGE', 'ATTACHMENT_PARSE_FAILED', 'ATTACHMENT_MAGIC_MISMATCH', 'ATTACHMENT_MACRO_FORBIDDEN', 'ATTACHMENT_FILENAME_INVALID', 'ATTACHMENT_UTF8_REQUIRED'])
  return cause instanceof ApiError && cause.status === 400 && !!cause.code && prewriteCodes.has(cause.code)
}
