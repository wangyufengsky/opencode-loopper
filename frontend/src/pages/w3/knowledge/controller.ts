import { api, ApiError } from '@/api/client'
import { knowledgeApi } from '@/api/knowledge'
import { createSnapshotController } from '@/foundation/contracts/controller'
import { createOperationOwner, type OperationInput, type OperationOwner, type OriginalLookup, type AcceptedHandoff } from '@/foundation/contracts/receipt'
import type { ResourceScope } from '@/foundation/contracts/types'
import type { NavigationRequest } from '@/foundation/contracts/navigation'
import { initialCoreState, rejected, type CoreState } from '@/pages/w2/core/owner'
import { userFacingError } from '@/utils/displayLabels'
import { knowledgeFileTarget } from '@/utils/knowledgeLinks'
import type { AvailableModel, KnowledgeCitation, KnowledgeContent, KnowledgeConversation, KnowledgeMessage, KnowledgeQuestion, KnowledgeRange, KnowledgeSource, Project } from '@/types/domain'

export interface QuestionDraft { choices: string[][]; custom: string[] }
export interface KnowledgeState extends CoreState {
  routeKey: string; project: string; projects: Project[]; conversation: KnowledgeConversation | null; messages: KnowledgeMessage[]
  sources: KnowledgeSource[]; selected: string[]; sourceCursor: string | null; nextCursor: string | null; text: string
  loading: boolean; disconnected: boolean; models: AvailableModel[]; model: string; defaultModel: string; mode: string
  settingsError: string; catalogError: string; settingsLoading: boolean; catalogLoading: boolean
  citation?: { citation: KnowledgeCitation; body: KnowledgeContent }; filePreview?: KnowledgeContent; focusRange?: KnowledgeRange
  fileTarget?: { path: string; start: number; end: number }; referenceList: KnowledgeCitation[]; evidenceLoading: boolean; evidenceError: string
  questionDrafts: Record<string, QuestionDraft>
}
const combine = (rows: KnowledgeMessage[]) => [...new Map(rows.map(row => [row.id, row])).values()].sort((a, b) => a.ordinal - b.ordinal)
const maximum = (before: number | null, current: number | null) => before == null ? current : current == null ? before : Math.max(before, current)
function defaultStorage(): Storage | undefined { try { return sessionStorage } catch { return undefined } }
let epoch = 0
/** No Pinia delegate or competing subscription. Reads project only to this retained page owner. */
export function createKnowledgeController(options: { routeKey?: string; conversationId?: string; storage?: Storage | null } = {}) {
  const storage = options.storage === null ? undefined : options.storage ?? defaultStorage()
  const storageGet = (key: string) => { try { return storage?.getItem(key) ?? '' } catch { return '' } }
  const storageSet = (key: string, value: string) => { try { storage?.setItem(key, value) } catch { /* In-memory identity remains authoritative for this instance. */ } }
  const storageRemove = (key: string) => { try { storage?.removeItem(key) } catch { /* Never forget the in-memory identity due to optional storage failure. */ } }
  let conversationId = options.conversationId ?? '', freshId = crypto.randomUUID(), chosen = false, catalogLoaded = false
  let operation: OperationOwner<unknown, unknown> | undefined, operationLabel = '', operationIdempotent = false
  let resources: ResourceScope | undefined, subscription: EventSource | undefined, streamId = '', refreshTimer: ReturnType<typeof setTimeout> | undefined
  let generation = 0, sourceGeneration = 0, evidenceGeneration = 0, settingsGeneration = 0
  let updatePage: { after: number; cursor: string } | undefined, refreshing: Promise<boolean> | undefined, refreshAgain = false
  let initializing: Promise<void> | undefined, pendingCatalog: Promise<void> | undefined
  let newConversationPermit: (() => AcceptedHandoff) | undefined
  let releaseStream: (() => void) | undefined
  let restoredReply: { id: string; question: KnowledgeQuestion; body: { idempotencyKey: string; version: number; answers: string[][] } } | undefined
  const routeKey = options.routeKey ?? '/knowledge'
  const initial: KnowledgeState = { ...initialCoreState(), routeKey, project: '', projects: [], conversation: null, messages: [], sources: [], selected: [], sourceCursor: null, nextCursor: null,
    text: storageGet(`knowledge.draft.${routeKey}`), loading: false, disconnected: false, models: [], model: '', defaultModel: '', mode: '',
    settingsError: '', catalogError: '', settingsLoading: false, catalogLoading: false, referenceList: [], evidenceLoading: false, evidenceError: '', questionDrafts: {}, dirty: !!storageGet(`knowledge.draft.${routeKey}`) }
  const owner = createSnapshotController({ identity: { domain: 'knowledge', id: conversationId || freshId, epoch: ++epoch }, initial,
    canLeave: value => value.messages.some(message => message.questions?.some(q => ['PREPARED', 'SENDING', 'UNKNOWN'].includes(q.state)))
      ? { kind: 'BLOCK', reason: '回答投递结果尚未确认，请读取原会话核对，保留回答身份', recoveryAction: '核对原操作结果' }
      : value.dirty ? { kind: 'CONFIRM_DISCARD', description: '仍有未发送问题或回答，是否放弃后离开？', draftRevision: value.draftRevision } : { kind: 'ALLOW' },
    attachReads: current => {
      resources = current
      current.own(() => { clearTimeout(refreshTimer); refreshTimer = undefined })
      current.own(() => { if (resources === current) resources = undefined; closeStream() })
      const timer = setInterval(() => { const s = owner.getSnapshot(); if (current.capture().isCurrent() && s.conversation && (s.conversation.state !== 'IDLE' || s.disconnected)) void refresh() }, 5000)
      current.own(() => clearInterval(timer))
      if (owner.getSnapshot().conversation) ensureStream()
      void initialize()
    },
  })
  const state = owner.getSnapshot
  const set = (patch: Partial<KnowledgeState>) => owner.project({ ...state(), ...patch })
  const active = () => !!state().conversation && state().conversation!.state !== 'IDLE'
  const locked = () => !!restoredMessage || !!restoredReply || !!operation && !['IDLE', 'SETTLED'].includes(operation.getSnapshot().phase)
  function closeStream() { try { releaseStream?.() } finally { releaseStream = undefined; subscription = undefined; streamId = '' } }
  function ensureStream() {
    const id = state().conversation?.id
    if (!resources?.capture().isCurrent() || !id || subscription && streamId === id) return
    closeStream(); const current = resources, stream = knowledgeApi.events(id); subscription = stream; streamId = id
    const currentStream = () => current.capture().isCurrent() && subscription === stream && state().conversation?.id === id
    stream.onmessage = () => { if (!currentStream()) return; set({ disconnected: false }); if (refreshTimer !== undefined) return; refreshTimer = setTimeout(() => { refreshTimer = undefined; if (currentStream()) void refresh() }, 180) }
    stream.onopen = () => { if (currentStream()) { set({ disconnected: false }); void refresh() } }
    stream.onerror = () => { if (currentStream()) set({ disconnected: true }) }
    let released = false
    const release = () => { if (released) return; released = true; stream.onmessage = null; stream.onopen = null; stream.onerror = null; try { stream.close() } finally { if (subscription === stream) { subscription = undefined; streamId = ''; releaseStream = undefined } } }
    releaseStream = release; current.own(release)
  }
  function error(failure: unknown, fallback = '加载失败，请重新读取核对状态') { set({ error: userFacingError(failure, fallback) }) }
  function publishOperation() {
    const current = operation?.getSnapshot(); if (!current) return
    set({ mutation: { phase: current.phase, label: operationLabel, busy: current.busy, canRead: current.recovery.kind === 'READ_ORIGINAL', canRetry: operationIdempotent && current.phase === 'UNKNOWN' },
      ...(current.error ? { error: userFacingError(current.error, '操作结果尚未确认，请保留原输入并恢复原操作') } : {}) })
  }
  async function command<B, R>(input: OperationInput<B>, settings: { label: string; write: (identity: { body: Readonly<B>; files: readonly File[] }) => Promise<R>;
    read: (receipt: Readonly<NoInfer<R>>, apply: (project: () => void) => boolean) => Promise<void>; lookup?: () => Promise<OriginalLookup<NoInfer<R>>>; idempotent?: boolean; handoff?: (receipt: Readonly<NoInfer<R>>) => string }) {
    if (locked() || !owner.capture().isCurrent()) return false
    const next = createOperationOwner({ owner: owner.identity, label: settings.label, input,
      capability: settings.idempotent ? { kind: 'IDEMPOTENT_KEY', readOriginal: settings.lookup } : settings.lookup ? { kind: 'READ_ORIGINAL', readOriginal: settings.lookup } : { kind: 'NONE' },
      write: identity => settings.write(identity), read: (receipt, context) => settings.read(receipt, context.apply), isDefinitiveRejection: rejected, handoffTarget: settings.handoff })
    if (!owner.ownOperation(next)) { next.retire(true); return false }
    operation = next as unknown as OperationOwner<unknown, unknown>; operationLabel = settings.label; operationIdempotent = settings.idempotent ?? false
    if (settings.handoff) newConversationPermit = next.prepareHandoff
    next.subscribe(publishOperation); set({ error: '' }); publishOperation()
    try { await next.execute(); return true } catch { return false } finally { publishOperation() }
  }
  async function loadSettings() {
    const token = owner.capture(), ticket = ++settingsGeneration; set({ settingsLoading: true })
    try {
      const result = await api.getSettings()
      if (!token.isCurrent() || ticket !== settingsGeneration) return
      const provider = result.openCode.provider.trim(), name = result.openCode.model.trim(), defaultModel = provider && name ? `${provider}/${name}` : ''
      set({ defaultModel, ...(!chosen ? { model: defaultModel } : {}), mode: result.openCode.mode, settingsError: '' })
    } catch { if (token.isCurrent() && ticket === settingsGeneration) set({ settingsError: '无法读取全局模型配置，请重新读取。' }) }
    finally { if (token.isCurrent() && ticket === settingsGeneration) set({ settingsLoading: false }) }
  }
  function loadCatalog(force = false): Promise<void> {
    if (pendingCatalog) return pendingCatalog
    if (catalogLoaded && !force || state().conversation) return Promise.resolve()
    const token = owner.capture(); set({ catalogLoading: true, catalogError: '' })
    pendingCatalog = api.getSettingsModels().then(models => { if (token.isCurrent()) { catalogLoaded = true; set({ models }) } }).catch(() => {
      if (token.isCurrent()) set({ catalogError: '暂时无法读取其他模型，仍可使用全局默认模型。' })
    }).finally(() => { pendingCatalog = undefined; if (token.isCurrent()) set({ catalogLoading: false }) })
    return pendingCatalog
  }
  async function loadSources(more = false) {
    const project = state().conversation?.projectId || state().project, ticket = ++sourceGeneration, token = owner.capture()
    if (!project) return
    try {
      const page = await knowledgeApi.sources(project, more ? state().sourceCursor || '' : '')
      if (!token.isCurrent() || ticket !== sourceGeneration || project !== (state().conversation?.projectId || state().project)) return
      const sources = more ? [...state().sources, ...page.items] : page.items
      set({ sources, sourceCursor: page.nextCursor ?? null, ...(!state().conversation ? { selected: state().selected.filter(id => sources.some(s => s.id === id && s.state === 'READY')) } : {}) })
    } catch (failure) { if (token.isCurrent() && ticket === sourceGeneration) error(failure) }
  }
  async function changeProject(project: string) {
    if (locked() || state().conversation) return
    ++sourceGeneration; set({ project, sources: [], selected: [], sourceCursor: null, draftRevision: state().draftRevision + 1 })
    await loadSources(); if (owner.capture().isCurrent() && project === state().project) set({ selected: state().sources.filter(s => s.state === 'READY').map(s => s.id) })
  }
  async function restoreMessage(id: string) {
    const saved = storageGet(`loopper.knowledge.pending.${id}`)
    if (!saved || locked()) return
    try {
      const pending = JSON.parse(saved) as { key?: string; text?: string }
      if (typeof pending.key !== 'string' || typeof pending.text !== 'string') return
      restoredMessage = { id, key: pending.key, text: pending.text }
      set({ text: pending.text, dirty: true, mutation: { phase: 'UNKNOWN', label: '发送问题', busy: false, canRead: true, canRetry: true } })
      const token = owner.capture(), result = await knowledgeApi.receipt(id, pending.key)
      if (!token.isCurrent() || id !== state().conversation?.id) return
      if (result.accepted) { restoredMessage = undefined; storageRemove(`loopper.knowledge.pending.${id}`); storageRemove(`knowledge.draft.${state().routeKey}`);set({ text: '', dirty: false, mutation: initialCoreState().mutation }); return }
      set({ text: pending.text, dirty: true })
      // Reconstructed UNKNOWN identity has no automatic POST. Retry is an explicit user intent.
      restoredMessage = { id, key: pending.key, text: pending.text }
      set({ mutation: { phase: 'UNKNOWN', label: '发送问题', busy: false, canRead: true, canRetry: true } })
    } catch (failure) { error(failure, '上次消息状态读取失败，请保留原问题重新核对') }
  }
  let restoredMessage: { id: string; key: string; text: string } | undefined
  async function load(id: string, fullPath = id ? `/knowledge/${id}` : '/knowledge') {
    if (locked() || restoredMessage) return false
    storageSet(`knowledge.draft.${state().routeKey}`, state().text)
    const ticket = ++generation, token = owner.capture(); ++sourceGeneration; ++evidenceGeneration; closeStream(); updatePage = undefined; refreshing = undefined; refreshAgain = false
    clearTimeout(refreshTimer); refreshTimer = undefined; conversationId = id
    set({ routeKey: fullPath, conversation: null, messages: [], citation: undefined, filePreview: undefined, fileTarget: undefined, referenceList: [], evidenceError: '', evidenceLoading: false,
      text: storageGet(`knowledge.draft.${fullPath}`), nextCursor: null, loading: !!id, disconnected: false, error: '', questionDrafts: {} })
    if (!id) { freshId = crypto.randomUUID(); set({ dirty: !!state().text }); await changeProject(state().project || state().projects[0]?.id || ''); return true }
    try {
      const [summary, page] = await Promise.all([knowledgeApi.get(id), knowledgeApi.messages(id)])
      if (!token.isCurrent() || ticket !== generation) return false
      if (summary.id !== id) throw new Error('会话读取结果不匹配，请重新读取原会话')
      set({ conversation: summary, project: summary.projectId, messages: combine(page.items), nextCursor: page.nextCursor ?? null }); ensureStream(); restoreQuestionDrafts()
      await Promise.all([loadSources(), restoreMessage(id)]); return true
    } catch (failure) { if (token.isCurrent() && ticket === generation) error(failure); return false }
    finally { if (token.isCurrent() && ticket === generation) set({ loading: false }) }
  }
  function refresh(): Promise<boolean> {
    if (!state().conversation) return Promise.resolve(false)
    if (refreshing) { refreshAgain = true; return refreshing }
    const id = state().conversation!.id, ticket = generation, token = owner.capture()
    const after = updatePage?.after ?? Math.max(0, (state().messages.at(-1)?.ordinal ?? 1) - 1), cursor = updatePage?.cursor ?? ''
    const current = Promise.all([knowledgeApi.get(id), knowledgeApi.updates(id, after, cursor)]).then(([summary, page]) => {
      if (!token.isCurrent() || ticket !== generation || state().conversation?.id !== id) return false
      if (summary.id !== id) throw new Error('会话读取结果不匹配，请保留原会话')
      const previous = state().conversation!.usage
      if (summary.usage && previous) summary.usage = { inputTokens: maximum(previous.inputTokens, summary.usage.inputTokens), outputTokens: maximum(previous.outputTokens, summary.usage.outputTokens) }
      else if (previous) summary.usage = previous
      set({ conversation: summary, messages: combine([...state().messages, ...page.items]), error: '' }); updatePage = page.nextCursor ? { after, cursor: page.nextCursor } : undefined
      if (updatePage) refreshAgain = true
      restoreQuestionDrafts(); return true
    }).catch(failure => { if (token.isCurrent() && ticket === generation) error(failure); return false }).finally(() => {
      if (refreshing === current) refreshing = undefined
      if (token.isCurrent() && ticket === generation && refreshAgain) { refreshAgain = false; void refresh() }
    })
    refreshing = current; return current
  }
  async function requireRefresh() { if (!await refresh()) throw new Error('写入已接受，但会话读取失败；请只重新读取原结果') }
  async function more() {
    if (!state().conversation || !state().nextCursor || state().loading) return
    const token = owner.capture(), id = state().conversation!.id, ticket = generation, cursor = state().nextCursor!
    set({ loading: true })
    try { const page = await knowledgeApi.messages(id, cursor); if (token.isCurrent() && ticket === generation) set({ messages: combine([...page.items, ...state().messages]), nextCursor: page.nextCursor ?? null }) }
    catch (failure) { if (token.isCurrent() && ticket === generation) error(failure) }
    finally { if (token.isCurrent() && ticket === generation) set({ loading: false }) }
  }
  function editText(text: string) { if (locked() || restoredMessage) return false; storageSet(`knowledge.draft.${state().routeKey}`, text); return set({ text, dirty: !!text || Object.values(state().questionDrafts).some(d => d.choices.some(a => a.length) || d.custom.some(Boolean)), draftRevision: state().draftRevision + 1 }) }
  function canSend() { const s = state(); return !!s.text.trim() && !!s.project && !locked() && !restoredMessage && !active() && s.mode === 'managed'
    && (!!s.conversation || !!s.selected.length && !!s.model && !s.settingsError && (s.model === s.defaultModel || s.models.some(m => m.id === s.model))) }
  async function send() {
    if (!canSend()) return false
    const token = owner.capture(), text = state().text.trim()
    if (!state().conversation) {
      const body = { id: freshId, projectId: state().project, model: state().model, sourceIds: [...state().selected], title: text.slice(0, 80) }
      const created = await command({ endpoint: '/knowledge/conversations', method: 'POST', body }, { label: '创建知识对话', write: identity => knowledgeApi.create(identity.body),
        lookup: async () => { try { const receipt = await knowledgeApi.get(body.id); if (receipt.id !== body.id || receipt.projectId !== body.projectId || receipt.model !== body.model) throw new Error('原对话身份不匹配'); return { kind: 'ACCEPTED', receipt } } catch (failure) { if (failure instanceof ApiError && failure.status === 404) return { kind: 'UNCONFIRMED' }; throw failure } },
        read: async (receipt, apply) => { if (receipt.id !== body.id || receipt.projectId !== body.projectId || receipt.model !== body.model) throw new Error('写入已接受，但原对话回执身份不匹配；不能继续发送问题，请核对原结果'); const summary = await knowledgeApi.get(body.id); if (summary.id !== body.id || summary.projectId !== body.projectId || summary.model !== body.model) throw new Error('原对话身份不匹配'); apply(() => { set({ conversation: summary, project: summary.projectId }); ensureStream() }) }, handoff: receipt => `/knowledge/${receipt.id}` })
      if (!created || !token.isCurrent()) return false
    }
    return sendMessage(state().conversation!.id, crypto.randomUUID(), text)
  }
  async function sendMessage(id: string, key: string, text: string) {
    const body = { idempotencyKey: key, text }, storageKey = `loopper.knowledge.pending.${id}`
    storageSet(storageKey, JSON.stringify({ key, text }))
    return command({ endpoint: `/knowledge/conversations/${encodeURIComponent(id)}/messages`, method: 'POST', requestKey: key, body }, {
      label: '发送问题', idempotent: true, write: async identity => { await knowledgeApi.send(id, identity.body.idempotencyKey, identity.body.text); return { id, key } },
      lookup: async () => (await knowledgeApi.receipt(id, key)).accepted ? { kind: 'ACCEPTED', receipt: { id, key } } : { kind: 'UNCONFIRMED' },
      read: async (_receipt, apply) => { await requireRefresh(); apply(() => { storageRemove(storageKey); restoredMessage = undefined; storageRemove(`knowledge.draft.${state().routeKey}`); set({ text: '', dirty: false, error: '' }) }) },
    })
  }
  async function stop() {
    const conversation = state().conversation; if (!conversation || locked() || !active() || conversation.state === 'STOPPING') return false
    return command({ endpoint: `/knowledge/conversations/${conversation.id}/stop`, method: 'POST', body: {} }, { label: '停止生成',
      write: () => knowledgeApi.stop(conversation.id), lookup: async () => { const receipt = await knowledgeApi.get(conversation.id); return receipt.id === conversation.id && receipt.state === 'IDLE' ? { kind: 'ACCEPTED', receipt } : { kind: 'UNCONFIRMED' } },
      read: async (receipt, apply) => { if (!('id' in receipt) || receipt.id !== conversation.id) throw new Error('写入已接受，但停止回执身份不匹配；请只核对原会话'); apply(() => set({ conversation: receipt as KnowledgeConversation })); await requireRefresh() },
    })
  }
  async function recover() {
    if (!owner.capture().isCurrent()) return
    if (restoredReply) {
      const value = restoredReply, token = owner.capture()
      try { const page = await knowledgeApi.messages(value.id); if (!token.isCurrent()) return; const found = page.items.flatMap(message => message.questions ?? []).find(q => q.id === value.question.id); if (found?.state === 'ANSWERED' && JSON.stringify(found.answers) === JSON.stringify(value.body.answers)) { await requireRefresh(); if (!token.isCurrent()) return; restoredReply = undefined; storageRemove(`loopper.knowledge.reply.${value.id}.${value.question.id}`); storageRemove(`loopper.knowledge.reply.${value.id}.${value.question.id}.draft`); set({ mutation: initialCoreState().mutation, error: '' }) } } catch (failure) { if (token.isCurrent()) error(failure) }
      return
    }
    if (restoredMessage) {
      const value = restoredMessage, token = owner.capture()
      try { const found = await knowledgeApi.receipt(value.id, value.key); if (!token.isCurrent()) return; if (found.accepted) { await requireRefresh(); restoredMessage = undefined; storageRemove(`loopper.knowledge.pending.${value.id}`); set({ text: '', dirty: false, mutation: initialCoreState().mutation, error: '' }) } }
      catch (failure) { if (token.isCurrent()) error(failure) }
      return
    }
    if (!operation || operation.getSnapshot().busy) return
    try { if (operation.getSnapshot().accepted) await operation.retryReadback(); else await operation.readOriginal() } catch { /* Exact original request remains owned. */ }
    publishOperation()
  }
  async function retryOriginal() {
    if (!owner.capture().isCurrent()) return
    if (restoredReply) { const value = restoredReply; restoredReply = undefined; await performReply(value.id, value.question, value.body); return }
    if (restoredMessage) { const value = restoredMessage; restoredMessage = undefined; await sendMessage(value.id, value.key, value.text); return }
    if (!operation || operation.getSnapshot().busy || !operationIdempotent) return
    try { await operation.recoverWrite() } catch { /* Original body/version/File stay captured. */ }
    publishOperation()
  }
  function restoreQuestionDrafts() {
    const id = state().conversation?.id; if (!id) return
    const drafts = { ...state().questionDrafts }
    for (const question of state().messages.flatMap(message => message.questions ?? [])) {
      if (question.state === 'ANSWERED' || question.state === 'CLOSED') { delete drafts[question.id]; if (restoredReply?.question.id === question.id && JSON.stringify(question.answers) === JSON.stringify(restoredReply.body.answers)) { restoredReply = undefined; storageRemove(`loopper.knowledge.reply.${id}.${question.id}`) }; continue }
      if (question.state !== 'PENDING') continue
      let draft: QuestionDraft = { choices: question.questions.map(() => []), custom: question.questions.map(() => '') }
      try {
        const prepared = JSON.parse(storageGet(`loopper.knowledge.reply.${id}.${question.id}`) || 'null') as { idempotencyKey?: string; version?: number; answers?: string[][] } | null
        const stored = JSON.parse(storageGet(`loopper.knowledge.reply.${id}.${question.id}.draft`) || 'null') as QuestionDraft | null
        if (prepared?.idempotencyKey && typeof prepared.version === 'number' && prepared.answers && (!operation || operation.getSnapshot().phase === 'SETTLED')) { restoredReply = { id, question, body: { idempotencyKey: prepared.idempotencyKey, version: prepared.version, answers: prepared.answers } }; set({ mutation: { phase: 'UNKNOWN', label: '回答助手提问', busy: false, canRead: true, canRetry: true } }) }
        if (prepared?.answers) draft = { choices: question.questions.map((q, i) => (prepared.answers![i] ?? []).filter(value => q.options.some(option => option.label === value))), custom: question.questions.map((q, i) => (prepared.answers![i] ?? []).filter(value => !q.options.some(option => option.label === value)).join('；')) }
        else if (drafts[question.id]) draft = drafts[question.id]!
        else if (stored?.choices && stored.custom) draft = stored
      } catch { /* A malformed optional draft cannot change a server question. */ }
      drafts[question.id] = draft
    }
    set({ questionDrafts: drafts, dirty: !!state().text || Object.values(drafts).some(d => d.choices.some(a => a.length) || d.custom.some(Boolean)) })
  }
  function editQuestion(question: KnowledgeQuestion, draft: QuestionDraft) {
    if (locked() || question.state !== 'PENDING') return
    storageSet(`loopper.knowledge.reply.${state().conversation!.id}.${question.id}.draft`, JSON.stringify(draft))
    set({ questionDrafts: { ...state().questionDrafts, [question.id]: draft }, dirty: true, draftRevision: state().draftRevision + 1 })
  }
  async function reply(question: KnowledgeQuestion) {
    const id = state().conversation?.id, draft = state().questionDrafts[question.id]
    if (!id || !draft || locked() || question.state !== 'PENDING') return false
    const answers = question.questions.map((q, i) => draft.custom[i]?.trim() ? q.multiple ? [...draft.choices[i]!, draft.custom[i]!.trim()] : [draft.custom[i]!.trim()] : draft.choices[i]!)
    if (answers.some(values => !values.length)) return false
    const storageKey = `loopper.knowledge.reply.${id}.${question.id}`
    let body = { idempotencyKey: crypto.randomUUID(), version: question.version, answers }
    try { const prepared = JSON.parse(storageGet(storageKey) || 'null'); if (prepared?.idempotencyKey && Array.isArray(prepared.answers)) { if (JSON.stringify(prepared.answers) !== JSON.stringify(answers)) throw new Error('上一条回答结果尚未核对，请先恢复原回答'); body = prepared } }
    catch (failure) { if (failure instanceof Error && failure.message.includes('上一条')) { error(failure); return false } }
    storageSet(storageKey, JSON.stringify(body))
    return performReply(id, question, body)
  }
  async function performReply(id: string, question: KnowledgeQuestion, body: { idempotencyKey: string; version: number; answers: string[][] }) {
    const storageKey = `loopper.knowledge.reply.${id}.${question.id}`
    return command({ endpoint: `/knowledge/conversations/${id}/questions/${question.id}/reply`, method: 'POST', requestKey: body.idempotencyKey, versions: { version: body.version }, body }, {
      label: '回答助手提问', idempotent: true, write: identity => knowledgeApi.reply(id, question.id, identity.body),
      lookup: async () => { const page = await knowledgeApi.messages(id); const found = page.items.flatMap(message => message.questions ?? []).find(q => q.id === question.id); return found && found.state === 'ANSWERED' && Number.isInteger(found.version) && found.version > body.version && JSON.stringify(found.answers) === JSON.stringify(body.answers) ? { kind: 'ACCEPTED', receipt: found } : { kind: 'UNCONFIRMED' } },
      read: async (receipt, apply) => {
        if (receipt.id !== question.id || !Number.isInteger(receipt.version) || receipt.version <= body.version || JSON.stringify(receipt.answers) !== JSON.stringify(body.answers)) throw new Error('回答已接受，但读取回执未确认原问题，请只核对原回答')
        // A successful reply normally returns PREPARED. Delivery is authoritative
        // only when the original conversation's existing REST projection catches up.
        await requireRefresh()
        const confirmed = state().messages.flatMap(message => message.questions ?? []).find(q => q.id === question.id)
        if (!confirmed || confirmed.state !== 'ANSWERED' || !Number.isInteger(confirmed.version) || confirmed.version < receipt.version || JSON.stringify(confirmed.answers) !== JSON.stringify(body.answers)) throw new Error('回答已接受，投递结果仍待核对；请只读取原会话，不会重复提交')
        apply(() => {
          storageRemove(storageKey); storageRemove(`${storageKey}.draft`)
          const drafts = { ...state().questionDrafts }; delete drafts[question.id]
          set({ messages: state().messages.map(message => ({ ...message, ...(message.questions ? { questions: message.questions.map(q => q.id === question.id ? confirmed : q) } : {}) })), questionDrafts: drafts,
            dirty: !!state().text || Object.values(drafts).some(d => d.choices.some(a => a.length) || d.custom.some(Boolean)), error: '' })
        })
      },
    })
  }
  async function sourceMutation(action: 'directory' | 'upload' | 'refresh' | 'remove', input: string | KnowledgeSource | readonly File[]) {
    if (state().conversation || locked()) return false
    const project = state().project, source = typeof input === 'object' && !Array.isArray(input) ? input as KnowledgeSource : undefined
    const body: { id?: string; version?: number; path?: string } = source ? { id: source.id, version: source.version } : { path: typeof input === 'string' ? input.trim() : '' }
    const files = Array.isArray(input) ? input as readonly File[] : []
    return command({ endpoint: `/projects/${encodeURIComponent(project)}/knowledge-sources${source ? `/${encodeURIComponent(source.id)}${action === 'refresh' ? '/refresh' : ''}` : action === 'upload' ? '/uploads' : '/directories'}`, method: action === 'remove' ? 'DELETE' : 'POST', body, files, ...(source ? { versions: { version: source.version } } : {}) }, {
      label: action === 'remove' ? '移除资料绑定' : action === 'upload' ? '上传文档' : action === 'refresh' ? '刷新资料' : '登记目录',
      write: async identity => { if (action === 'directory') return knowledgeApi.directory(project, identity.body.path!); if (action === 'upload') return knowledgeApi.upload(project, [...identity.files]); if (action === 'refresh') return knowledgeApi.refresh(project, source!.id, source!.version); await knowledgeApi.remove(project, source!.id, source!.version); return { removed: true } },
      read: async (receipt, apply) => { const page = await knowledgeApi.sources(project); apply(() => { set({ sources: page.items, sourceCursor: page.nextCursor ?? null, selected: state().selected.filter(id => page.items.some(s => s.id === id && s.state === 'READY')), error: Array.isArray(receipt) ? receipt.filter(s => s.state !== 'READY').map(s => `${s.name}：${s.detail}`).join('；') : '' }) }) },
    })
  }
  async function openCitation(target: string, refs?: KnowledgeCitation[]) {
    if (!state().conversation) return
    const [id, location] = target.split('#'); if (!id) return
    const range = location?.match(/^([LR])(\d+)-[LR](\d+)$/), ticket = ++evidenceGeneration, token = owner.capture(), conv = state().conversation!.id
    set({ citation: undefined, filePreview: undefined, fileTarget: undefined, evidenceLoading: true, evidenceError: '', focusRange: range ? { unit: range[1] as 'L' | 'R', first: Number(range[2]), last: Number(range[3]) } : undefined, ...(refs ? { referenceList: refs } : {}) })
    try { const result = await knowledgeApi.citation(conv, id); if (token.isCurrent() && ticket === evidenceGeneration && conv === state().conversation?.id) set({ citation: result }) }
    catch (failure) { if (token.isCurrent() && ticket === evidenceGeneration) set({ evidenceError: userFacingError(failure, '引用读取失败，请重新选择原引用') }) }
    finally { if (token.isCurrent() && ticket === evidenceGeneration) set({ evidenceLoading: false }) }
  }
  async function openFile(target: string, section = 0) {
    if (!state().conversation) return
    const ticket = ++evidenceGeneration, token = owner.capture(), conv = state().conversation!.id
    set({ citation: undefined, filePreview: undefined, referenceList: [], focusRange: undefined, evidenceLoading: true, evidenceError: '' })
    try { const file = knowledgeFileTarget(target); set({ fileTarget: file }); const result = await knowledgeApi.file(conv, file.path, file.start, file.end, section); if (token.isCurrent() && ticket === evidenceGeneration) set({ filePreview: result, focusRange: file.end ? { unit: 'L', first: file.start, last: file.end } : undefined }) }
    catch (failure) { if (token.isCurrent() && ticket === evidenceGeneration) set({ evidenceError: userFacingError(failure, '文件读取失败，请从资料来源中查找') }) }
    finally { if (token.isCurrent() && ticket === evidenceGeneration) set({ evidenceLoading: false }) }
  }
  async function initialize() {
    if (initializing) return initializing
    const token = owner.capture()
    initializing = (async () => {
      void loadSettings().then(() => { if (token.isCurrent() && !conversationId) void loadCatalog() })
      const loadingConversation = conversationId ? load(conversationId, state().routeKey) : Promise.resolve(true)
      try { const projects = await api.getProjects(); if (!token.isCurrent()) return; set({ projects }); if (!conversationId) await changeProject(projects[0]?.id ?? '') }
      catch (failure) { if (token.isCurrent()) error(failure) }
      await loadingConversation
    })()
    return initializing
  }
  return { ...owner, getSnapshot: state, initialize, load, refresh, more, loadSources, changeProject, loadSettings, loadCatalog, canSend, send, stop, recover, retryOriginal, reply, editQuestion, sourceMutation, openCitation, openFile, locked, active, editText,
    canLeave(request?: NavigationRequest) { if (restoredReply) return { kind: 'BLOCK' as const, reason: '上次回答结果尚未确认，请核对或恢复原回答', recoveryAction: '恢复原操作' }; if (restoredMessage) return { kind: 'BLOCK' as const, reason: '上次问题投递结果尚未确认，请保留原问题并核对原结果', recoveryAction: '恢复原操作' }; return owner.canLeave(request) },
    selectModel(model: string) { if (locked() || state().conversation) return; chosen = model !== state().defaultModel; set({ model, draftRevision: state().draftRevision + 1 }) },
    selectSources(selected: string[]) { if (!locked() && !state().conversation) set({ selected, draftRevision: state().draftRevision + 1 }) },
    closeEvidence() { ++evidenceGeneration; set({ evidenceLoading: false }) },
    pauseEvidence() { ++evidenceGeneration },
    originalReply: () => restoredReply?.body,
    prepareConversationHandoff: () => newConversationPermit?.(),
    originalIdentity: () => operation?.identity,
    async reload() { await loadSettings(); if (!state().conversation) void loadCatalog(true); if (state().conversation) await refresh(); await loadSources() },
  }
}
export type KnowledgeController = ReturnType<typeof createKnowledgeController>
