import { pptApi } from '@/api/ppt'
import { ApiError } from '@/api/client'
import { createSnapshotController } from '@/foundation/contracts/controller'
import { createOperationOwner, type OperationOwner, type ReadContext, type OriginalLookup } from '@/foundation/contracts/receipt'
import type { LeaveDecision, ReceiptPhase } from '@/foundation/contracts/types'
import { userFacingError } from '@/utils/displayLabels'
import { emptyPptPlan, type PptAgentStatus, type PptAsset, type PptCapabilities, type PptDeck, type PptDocument, type PptEditablePlan, type PptGeneration, type PptIssue, type PptJob, type PptKnowledge, type PptMessage, type PptOperation, type PptPlan, type PptQuestion, type PptRevision, type PptScope, type PptSource, type PptSourceContent } from '@/types/ppt'

export type StudioCommand = 'operations' | 'plan' | 'action' | 'message' | 'reply' | 'job' | 'retry-job' | 'generate' | 'confirm-generation' | 'resume' | 'stop' | 'upload'
export interface StudioPending {
  kind: StudioCommand; key: string; revision: number; payload: Record<string, unknown>
  phase: ReceiptPhase; acceptedRevision?: number; file?: { name: string; size: number; sha256: string; kind: 'sources' | 'assets' }
}
export interface StudioDraft {
  key: string; kind: 'element' | 'slide' | 'plan'; id: string; slideId: string
  value: Record<string, unknown>; baseline: string; revision: number; dirty: boolean; valid: boolean; scheduled: boolean
}
export interface StudioSnapshot {
  document: PptDocument | null; deck: PptDeck | null; plan: PptEditablePlan; sources: PptSource[]; assets: PptAsset[]
  jobs: PptJob[]; revisions: PptRevision[]; messages: PptMessage[]; agent: PptAgentStatus | null; generation: PptGeneration | null
  capabilities: PptCapabilities | null; issues: PptIssue[]; checkedRevision: number | null; loading: boolean; busy: boolean
  error: string; disconnected: boolean; pending: StudioPending | null; drafts: Record<string, StudioDraft>
  chat: string; answers: Record<string, string>; messageCursor: string; revisionCursor: string
  historical: { revision: number; deck: PptDeck } | null; knowledge: PptKnowledge | null; sourceContent: PptSourceContent | null
}
type Receipt = Awaited<ReturnType<typeof pptApi.operations>> | PptDocument | PptGeneration | PptMessage | PptJob | PptAgentStatus | PptSource | { revision: number; plan: PptPlan }
type StudioApi = typeof pptApi
type StoragePort = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const activeStates = ['PREPARED', 'PENDING', 'QUEUED', 'RUNNING']
const terminal = ['IDLE', 'COMPLETED', 'STOPPED', 'FAILED']
export const studioActive = (s: Readonly<StudioSnapshot>) => !!s.generation && !terminal.includes(s.generation.state) || !!s.agent && !terminal.includes(s.agent.state)
export const studioJobsActive = (s: Readonly<StudioSnapshot>) => s.jobs.some(job => activeStates.includes(job.state))
export const studioReady = (s: Readonly<StudioSnapshot>) => ['REVIEW', 'EXPORTED'].includes(s.document?.phase ?? '') || s.generation?.state === 'COMPLETED' || s.jobs.some(job => job.kind === 'EXPORT' && job.state === 'COMPLETED')
export const studioEditable = (s: Readonly<StudioSnapshot>) => !!s.document && !s.document.archived && !s.busy && !s.pending && !s.historical && !studioActive(s)
export const studioDirty = (s: Readonly<StudioSnapshot>) => Object.values(s.drafts).some(draft => draft.dirty) || !!s.chat.trim() || Object.values(s.answers).some(value => !!value.trim())
export function normalizePlan(plan: PptPlan): PptEditablePlan {
  return { ...emptyPptPlan(), ...copy(plan), brief: { ...emptyPptPlan().brief, ...plan.brief },
    narrative: { story: '', chapters: [], ...plan.narrative },
    visualRules: { style: '', fontFamily: 'Noto Sans CJK SC', accentColor: '2563EB', density: '适中', aspectRatio: '16:9', ...plan.visualRules },
    assets: { requirements: '', chartGuidance: '', imageGuidance: '', ...plan.assets },
    delivery: { fileName: '', targetSoftware: 'WPS / PowerPoint', includeNotes: true, ...plan.delivery } }
}
function initial(): StudioSnapshot {
  return { document: null, deck: null, plan: normalizePlan(emptyPptPlan()), sources: [], assets: [], jobs: [], revisions: [], messages: [], agent: null, generation: null, capabilities: null, issues: [], checkedRevision: null, loading: true, busy: false, error: '', disconnected: false, pending: null, drafts: {}, chat: '', answers: {}, messageCursor: '', revisionCursor: '', historical: null, knowledge: null, sourceContent: null }
}
function mergeMessages(before: readonly PptMessage[], incoming: readonly PptMessage[]) {
  const rows = new Map(before.map(row => [row.id, row]))
  for (const row of incoming) if (!rows.has(row.id) || rows.get(row.id)!.version <= row.version) rows.set(row.id, row)
  return [...rows.values()].sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id))
}
function pendingValid(value: unknown): value is StudioPending {
  if (!value || typeof value !== 'object') return false
  const row = value as Record<string, unknown>
  return ['operations', 'plan', 'action', 'message', 'reply', 'job', 'retry-job', 'generate', 'confirm-generation', 'resume', 'stop', 'upload'].includes(String(row.kind)) && typeof row.key === 'string' && Number.isInteger(row.revision) && !!row.payload && typeof row.payload === 'object'
}

/** This page owns commands, drafts and the single live subscription. React only projects DTOs. */
export function createPptStudioController(id: string, options: { api?: StudioApi; storage?: StoragePort; autosaveMs?: number } = {}) {
  const api = options.api ?? pptApi
  let storage: StoragePort | undefined = options.storage
  if (!storage) try { storage = sessionStorage } catch { /* An open owner still retains input when optional storage is unavailable. */ }
  const storageRead = (key: string) => { try { return storage?.getItem(key) ?? '' } catch { return '' } }
  const storageWrite = (key: string, value: unknown) => { try { value === null ? storage?.removeItem(key) : storage?.setItem(key, typeof value === 'string' ? value : JSON.stringify(value)) } catch { /* Never unlock or discard volatile state. */ } }
  const pendingKey = `loopper.ppt.pending.${id}`
  const first = initial()
  try { const value: unknown = JSON.parse(storageRead(pendingKey) || 'null'); if (pendingValid(value)) first.pending = { ...value, phase: value.phase === 'ACCEPTED_READBACK' ? value.phase : 'UNKNOWN' } } catch { /* Corrupt metadata is not authority to issue a request. */ }
  first.chat = storageRead(`loopper.ppt.chat.${id}`)
  try { const value: unknown = JSON.parse(storageRead(`loopper.ppt.answers.${id}`) || '{}'); if (value && typeof value === 'object') first.answers = Object.fromEntries(Object.entries(value).filter((entry): entry is [string, string] => typeof entry[1] === 'string')) } catch { /* Optional draft. */ }
  if (first.pending) first.error = '检测到原操作结果待核对，请恢复原操作；本地文件不会跨刷新自动恢复。'
  let retired = false, started: Promise<void> | null = null, refreshPromise: Promise<boolean> | null = null
  let queued = false, queuedAll = false, operation: OperationOwner<unknown, Receipt> | null = null, detachOperation: (() => void) | undefined
  let originalFile: File | undefined, fileSequence = 0, readSequence = 0
  const timers = new Map<string, ReturnType<typeof setTimeout>>()
  const attempted = new Map<string, string>()
  function leave(s: Readonly<StudioSnapshot>): LeaveDecision {
    if (s.busy || s.pending) return { kind: 'BLOCK', reason: s.pending?.phase === 'ACCEPTED_READBACK' ? '原写入已接受，请先读取原结果；不会重复提交。' : '原操作尚未确认，请保留身份、文件与草稿并核对原操作。', recoveryAction: '恢复原操作' }
    return studioDirty(s) ? { kind: 'CONFIRM_DISCARD', description: '仍有未保存修改或未发送内容，是否保留浏览器草稿后离开？未持久化的文件不能跨刷新恢复。', draftRevision: draftRevision } : { kind: 'ALLOW' }
  }
  let draftRevision = 0
  const controller = createSnapshotController<StudioSnapshot>({ identity: { domain: 'ppt-studio', id, epoch: 0 }, initial: first, canLeave: leave,
    attachReads(resources) {
      const events = api.events(id)
      resources.own(() => { events.onmessage = null; events.onopen = null; events.onerror = null; events.close() })
      events.onmessage = event => {
        if (!resources.isActive()) return
        patch({ disconnected: false })
        let activity = false
        try { activity = JSON.parse(event.data).type === 'agent' } catch { /* Unknown event requests a complete projection. */ }
        void refresh(activity ? 'activity' : 'all')
      }
      events.onopen = () => { if (resources.isActive()) { patch({ disconnected: false }); void refresh() } }
      events.onerror = () => { if (resources.isActive()) patch({ disconnected: true }) }
      const poll = setInterval(() => { const s = snapshot(); if (resources.isActive() && (studioActive(s) || studioJobsActive(s) || s.disconnected)) void refresh('activity') }, 4000)
      resources.own(() => clearInterval(poll))
      resources.own(() => { for (const timer of timers.values()) clearTimeout(timer); timers.clear(); if (!retired) patch({ drafts: Object.fromEntries(Object.entries(snapshot().drafts).map(([key, draft]) => [key, { ...draft, scheduled: false }])) }) })
    } })
  const snapshot = controller.getSnapshot
  function patch(values: Partial<StudioSnapshot>) { if (!retired) controller.project({ ...snapshot(), ...values }) }
  function persistPending(value: StudioPending | null) { storageWrite(pendingKey, value) }
  function saveChat() { storageWrite(`loopper.ppt.chat.${id}`, snapshot().chat); storageWrite(`loopper.ppt.answers.${id}`, snapshot().answers) }
  function projectDrafts(values: Partial<StudioSnapshot>) {
    const s = { ...snapshot(), ...values }, drafts = { ...s.drafts }
    for (const [key, draft] of Object.entries(drafts)) {
      const latest = draft.kind === 'plan' ? s.plan : draft.kind === 'slide' ? s.deck?.slides.find(slide => slide.id === draft.id) : s.deck?.slides.find(slide => slide.id === draft.slideId)?.elements.find(element => element.id === draft.id)
      if (!latest) continue
      const value = draft.kind === 'slide' ? { title: 'title' in latest ? latest.title : '', section: 'section' in latest ? latest.section : '', notes: 'notes' in latest ? latest.notes : '' } : latest
      if (!draft.dirty || JSON.stringify(value) === JSON.stringify(draft.value)) {
        drafts[key] = { ...draft, value: copy(value) as Record<string, unknown>, baseline: JSON.stringify(value), revision: s.document?.revision ?? draft.revision, dirty: false, scheduled: false }
        storageWrite(key, null)
      }
    }
    patch({ ...values, drafts })
  }
  async function readSnapshot(mode: 'all' | 'activity'): Promise<boolean> {
    const token = controller.capture()
    const summary = await api.get(id)
    if (!token.isCurrent() || summary.id !== id) return false
    const before = snapshot().document
    if (before && (summary.revision < before.revision || summary.version < before.version)) return false
    const same = snapshot().document?.revision === summary.revision && !!snapshot().deck
    if (mode === 'activity' && same) {
      const [messages, agent, generation] = await Promise.all([api.messages(id), api.agent(id), api.generation(id)])
      if (!token.isCurrent()) return false
      if (generation && generation.revision !== summary.revision) { queued = true; queuedAll = true; return false }
      patch({ document: summary, messages: mergeMessages(snapshot().messages, messages.items), messageCursor: messages.nextCursor ?? '', agent, generation }); return true
    }
    const [deck, plan, sources, jobs, revisions, messages, agent, generation] = await Promise.all([api.deck(id, summary.revision), api.plan(id), api.sources(id), api.jobs(id), api.revisions(id), api.messages(id), api.agent(id), api.generation(id)])
    if (!token.isCurrent()) return false
    if (plan.revision !== summary.revision || generation && generation.revision !== summary.revision) { queued = true; queuedAll = true; return false }
    projectDrafts({ document: summary, deck, plan: normalizePlan(plan.plan), sources: sources.sources, assets: sources.assets, jobs, revisions: revisions.items, revisionCursor: revisions.nextCursor ?? '', messages: mergeMessages(snapshot().messages, messages.items), messageCursor: messages.nextCursor ?? '', agent, generation,
      ...(same ? {} : { issues: [], checkedRevision: null }) }); return true
  }
  function refresh(mode: 'all' | 'activity' = 'all'): Promise<boolean> {
    if (retired) return Promise.resolve(false)
    if (refreshPromise) { queued = true; queuedAll ||= mode === 'all'; return refreshPromise }
    refreshPromise = (async () => {
      let result = false, current = mode, attempts = 0
      do {
        queued = false; queuedAll = false
        try { result = await readSnapshot(current); if (!retired && !snapshot().pending) patch({ error: '' }) }
        catch (failure) { if (!retired) patch({ error: userFacingError(failure, '作品暂时无法读取，请重试；草稿与原操作仍保留。') }); return false }
        current = queuedAll ? 'all' : 'activity'
        attempts++
      } while (queued && !retired && attempts < 3)
      if (queued && !retired) { patch({ error: '作品版本仍在变化，本轮读取已暂停；保留当前草稿，稍后轮询或显式重新读取。' }); return false }
      return result
    })().finally(() => { refreshPromise = null })
    return refreshPromise
  }
  async function start() {
    if (started) return started
    started = (async () => { const token = controller.capture(); await Promise.all([refresh(), api.capabilities().then(capabilities => { if (token.isCurrent()) patch({ capabilities }) }).catch(failure => { if (token.isCurrent()) patch({ error: userFacingError(failure, '制作能力暂时无法读取，请重试。') }) })]); if (token.isCurrent()) patch({ loading: false }) })()
    return started
  }
  function wire(request: StudioPending) {
    const p = request.payload
    switch (request.kind) {
      case 'operations': return api.operations(id, request.revision, p.operations as PptOperation[], request.key)
      case 'plan': return api.savePlan(id, request.revision, p.plan as PptPlan, request.key)
      case 'action': return api.action(id, String(p.action), request.revision, request.key, p.extra as Record<string, unknown>)
      case 'message': return api.send(id, { idempotencyKey: request.key, expectedRevision: request.revision, text: String(p.text), scope: p.scope as PptScope })
      case 'reply': return api.reply(id, String(p.questionId), { idempotencyKey: request.key, expectedRevision: request.revision, version: Number(p.version), answer: String(p.answer), ...(typeof p.confirmed === 'boolean' ? { confirmed: p.confirmed } : {}) })
      case 'job': return api.createJob(id, p.kind as 'PREVIEW' | 'EXPORT', request.revision, request.key, p.slideId ? String(p.slideId) : undefined)
      case 'retry-job': return api.retryJob(id, String(p.jobId))
      case 'generate': return api.generate(id, request.revision, String(p.prompt), request.key)
      case 'confirm-generation': return api.confirmGeneration(id, request.revision, request.key)
      case 'resume': return api.resume(id, request.revision, request.key, p.adjustment ? String(p.adjustment) : undefined)
      case 'stop': return api.stop(id)
      case 'upload': if (originalFile) return api.upload(id, originalFile, request.file!.kind, request.key); throw new Error('文件不在内存中，请重新选择并核对原文件字节。')
    }
  }
  function endpoint(request: StudioPending) {
    const base = `/ppt/documents/${encodeURIComponent(id)}`
    return base + ({ operations: '/operations', plan: '/plan', action: `/actions/${encodeURIComponent(String(request.payload.action))}`, message: '/messages', reply: `/questions/${encodeURIComponent(String(request.payload.questionId))}/reply`, job: '/jobs', 'retry-job': `/jobs/${encodeURIComponent(String(request.payload.jobId))}/retry`, generate: '/generate', 'confirm-generation': '/generate/confirm', resume: '/generate/resume', stop: '/stop', upload: `/${request.file?.kind ?? 'sources'}` }[request.kind])
  }
  function body(request: StudioPending): Record<string, unknown> {
    const p = request.payload
    if (request.kind === 'stop' || request.kind === 'retry-job') return {}
    if (request.kind === 'upload') return { idempotencyKey: request.key, file: request.file }
    if (request.kind === 'job') return { kind: p.kind, revision: request.revision, idempotencyKey: request.key, ...(p.slideId ? { slideId: p.slideId } : {}) }
    if (request.kind === 'action') return { expectedRevision: request.revision, idempotencyKey: request.key, ...p.extra as Record<string, unknown> }
    const { questionId: _question, ...fields } = p
    return { ...fields, expectedRevision: request.revision, idempotencyKey: request.key }
  }
  async function lookup(request: StudioPending): Promise<OriginalLookup<Receipt>> {
    if (request.kind === 'message') {
      const page = await api.messages(id)
      const message = page.items.find(item => item.documentId === id && item.idempotencyKey === request.key && item.expectedRevision === request.revision && item.text === request.payload.text && JSON.stringify(item.scope) === JSON.stringify(request.payload.scope))
      return message ? { kind: 'ACCEPTED', receipt: message } : { kind: 'UNCONFIRMED' }
    }
    if (request.kind === 'upload' && request.file?.sha256) {
      const values = await api.sources(id)
      const source = values[request.file.kind].find(value => value.sha256 === request.file?.sha256 && value.bytes === request.file.size && value.name === request.file.name && value.state === 'READY')
      return source ? { kind: 'ACCEPTED', receipt: source } : { kind: 'UNCONFIRMED' }
    }
    if (request.kind === 'retry-job') {
      const job = await api.job(id, String(request.payload.jobId))
      return job.documentId === id && job.id === request.payload.jobId && activeStates.concat('COMPLETED').includes(job.state) ? { kind: 'ACCEPTED', receipt: job } : { kind: 'UNCONFIRMED' }
    }
    if (request.kind === 'stop') {
      const agent = await api.agent(id)
      return !!request.payload.runId && agent.runId === request.payload.runId && ['STOPPING', 'STOPPED', 'COMPLETED', 'FAILED'].includes(agent.state) ? { kind: 'ACCEPTED', receipt: agent } : { kind: 'UNCONFIRMED' }
    }
    return { kind: 'UNCONFIRMED' }
  }
  function acknowledged(request: StudioPending) {
    if (request.kind === 'message' && snapshot().chat.trim() === request.payload.text || request.kind === 'resume' && snapshot().chat.trim() === request.payload.adjustment) patch({ chat: '' })
    if (request.kind === 'reply') { const answers = { ...snapshot().answers }; if (answers[String(request.payload.questionId)]?.trim() === request.payload.answer) delete answers[String(request.payload.questionId)]; patch({ answers }) }
    saveChat()
  }
  function rememberAcceptedRevision(request: StudioPending, receipt?: Readonly<Receipt>) {
    if (request.kind !== 'operations' && request.kind !== 'plan') return
    if (receipt) {
      if (!('revision' in receipt) || !Number.isSafeInteger(receipt.revision) || receipt.revision <= request.revision) throw new Error('原写入已接受，但回执版本尚无法核对；请保留原身份。')
      request.acceptedRevision = receipt.revision
      persistPending({ ...request, phase: 'ACCEPTED_READBACK' })
    }
    const minimum = request.acceptedRevision ?? request.revision + 1
    if (!Number.isSafeInteger(minimum) || minimum <= request.revision || (snapshot().document?.revision ?? -1) < minimum) throw new Error('原写入已接受，读取仍是旧作品版本；请只读恢复原结果。')
  }
  async function execute(request: StudioPending): Promise<boolean> {
    if (retired || snapshot().busy) return false
    request = copy(request)
    detachOperation?.()
    const keyless = request.kind === 'stop' || request.kind === 'retry-job'
    const readOriginal = ['message', 'upload', 'stop', 'retry-job'].includes(request.kind) ? () => lookup(request) : undefined
    const next = createOperationOwner<Record<string, unknown>, Receipt>({ owner: controller.identity, label: 'PPT 原操作',
      input: { endpoint: endpoint(request), method: 'POST', body: body(request), ...(keyless ? {} : { requestKey: request.key }), versions: { revision: request.revision }, ...(originalFile && request.kind === 'upload' ? { files: [originalFile] } : {}) },
      capability: keyless ? { kind: 'READ_ORIGINAL', readOriginal: () => lookup(request) } : { kind: 'IDEMPOTENT_KEY', ...(readOriginal ? { readOriginal } : {}) },
      write: () => wire(request),
      read: async (receipt, context: ReadContext) => {
        if ((request.kind === 'operations' || request.kind === 'plan') && 'revision' in receipt && Number.isSafeInteger(receipt.revision) && receipt.revision > request.revision) { request.acceptedRevision = receipt.revision; persistPending({ ...request, phase: 'ACCEPTED_READBACK' }) }
        if (!await refresh() || !context.isCurrent()) throw new Error('原写入已接受，但作品读取尚未完成；请只读恢复原结果。')
        rememberAcceptedRevision(request, receipt)
        if (request.kind === 'upload' && !snapshot()[request.file!.kind].some(source => source.name === request.file!.name && source.bytes === request.file!.size && source.sha256 === request.file!.sha256 && source.state === 'READY')) throw new Error('原文件上传已接受，解析尚未就绪；请只读取原资料状态。')
        context.apply(() => acknowledged(request))
      },
      isDefinitiveRejection: failure => failure instanceof ApiError && failure.status >= 400 && failure.status < 500 && ![408, 429].includes(failure.status),
    })
    operation = next as OperationOwner<unknown, Receipt>; controller.ownOperation(next)
    const publish = () => {
      if (retired || operation !== next as unknown) return
      const current = next.getSnapshot(), pending = current.phase === 'SETTLED' ? null : { ...request, phase: current.phase }
      persistPending(pending)
      patch({ pending, busy: current.busy, error: current.error ? userFacingError(current.error, current.accepted ? '原写入已接受，请只读取原结果。' : '操作结果待核对，请恢复原操作。') : '' })
    }
    detachOperation = next.subscribe(publish)
    // Retain identity before entering the asynchronous writer, including storage failures.
    persistPending({ ...request, phase: 'SENDING' }); patch({ pending: { ...request, phase: 'SENDING' }, busy: true, error: '' })
    try { await next.execute(); return !retired && next.getSnapshot().phase === 'SETTLED' }
    catch (failure) { if (!retired && failure instanceof ApiError && failure.status === 409) await refresh(); return false }
    finally { publish() }
  }
  function command(kind: StudioCommand, payload: Record<string, unknown>, revision = snapshot().document?.revision ?? -1) {
    if (retired || !snapshot().document || snapshot().busy || snapshot().pending) return Promise.resolve(false)
    if (['operations', 'plan', 'generate', 'confirm-generation', 'resume'].includes(kind) && snapshot().document?.archived) return Promise.resolve(false)
    if ((kind === 'operations' || kind === 'plan') && (studioActive(snapshot()) || snapshot().historical)) return Promise.resolve(false)
    if (kind === 'plan' && !['BRIEFING', 'DIRECTION', 'DESIGN'].includes(snapshot().document?.phase ?? '')) return Promise.resolve(false)
    return execute({ kind, key: crypto.randomUUID(), revision, payload: copy(payload), phase: 'IDLE' })
  }
  async function recover(retry = false): Promise<boolean> {
    if (retired || snapshot().busy || !snapshot().pending) return false
    const request = snapshot().pending!
    if (operation) {
      try { const current = operation.getSnapshot(); if (current.accepted) await operation.retryReadback(); else if (retry) await operation.recoverWrite(); else if (current.recovery.kind === 'READ_ORIGINAL') await operation.readOriginal(); else await operation.recoverWrite(); return !retired && operation.getSnapshot().phase === 'SETTLED' } catch { return false }
    }
    // Cross-refresh receipt metadata never invokes a POST from mounting or an effect.
    if (request.phase === 'ACCEPTED_READBACK') {
      patch({ busy: true }); const token = controller.capture()
      try { if (!await refresh() || !token.isCurrent()) return false; rememberAcceptedRevision(request); if (request.kind === 'upload' && !snapshot()[request.file!.kind].some(source => source.name === request.file!.name && source.bytes === request.file!.size && source.sha256 === request.file!.sha256 && source.state === 'READY')) { patch({ error: '原文件解析尚未就绪，请保留原身份并只读取原资料状态。' }); return false }; acknowledged(request); persistPending(null); patch({ pending: null, error: '' }); return true }
      catch (failure) { if (token.isCurrent()) patch({ error: userFacingError(failure, '原写入已接受，请只读取原结果。') }); return false }
      finally { if (token.isCurrent()) patch({ busy: false }) }
    }
    if (!retry && (request.kind === 'stop' || request.kind === 'retry-job' || request.kind === 'message' || request.kind === 'upload')) {
      const token = controller.capture(); patch({ busy: true })
      try { const result = await lookup(request); if (!token.isCurrent()) return false; if (result.kind === 'ACCEPTED') { persistPending({ ...request, phase: 'ACCEPTED_READBACK' }); patch({ pending: { ...request, phase: 'ACCEPTED_READBACK' } }); if (!await refresh() || !token.isCurrent()) return false; acknowledged(request); persistPending(null); patch({ pending: null, error: '' }); return true } }
      catch (failure) { if (token.isCurrent()) patch({ error: userFacingError(failure, '尚未核对原操作，请保留原身份。') }) }
      finally { if (token.isCurrent()) patch({ busy: false }) }
      patch({ error: '读取尚不能证明原结果，请保留原身份。仅有幂等 key 的命令可显式重试原写入。' }); return false
    }
    if (request.kind === 'stop' || request.kind === 'retry-job') return false
    if (request.kind === 'upload' && !originalFile) { patch({ error: '请重新选择原文件并核对 SHA-256；旧条目没有摘要时不能证明字节相同。' }); return false }
    return execute(request)
  }
  function draftKey(kind: StudioDraft['kind'], draftId: string) { return kind === 'plan' ? `loopper.ppt.plan.${id}` : `loopper.ppt.${kind === 'element' ? 'element' : 'slideDraft'}.${id}.${draftId}` }
  function openDraft(kind: StudioDraft['kind'], draftId: string, slideId = '') {
    const key = draftKey(kind, draftId), s = snapshot()
    if (s.drafts[key]) return key
    const source = kind === 'plan' ? s.plan : kind === 'slide' ? s.deck?.slides.find(slide => slide.id === draftId) : s.deck?.slides.find(slide => slide.id === slideId)?.elements.find(element => element.id === draftId)
    if (!source) return ''
    const value = kind === 'slide' ? { title: 'title' in source ? source.title : '', section: 'section' in source ? source.section : '', notes: 'notes' in source ? source.notes : '' } : source
    let draft: StudioDraft = { key, kind, id: draftId, slideId, value: copy(value) as Record<string, unknown>, baseline: JSON.stringify(value), revision: s.document?.revision ?? -1, dirty: false, valid: true, scheduled: false }
    try { const saved = JSON.parse(storageRead(key) || 'null') as { draft?: Record<string, unknown>; baseline?: string; revision?: number } | null; if (saved?.draft && typeof saved.baseline === 'string' && Number.isInteger(saved.revision) && (kind !== 'element' || saved.draft.id === draftId)) draft = { ...draft, value: saved.draft, baseline: saved.baseline, revision: saved.revision!, dirty: JSON.stringify(saved.draft) !== saved.baseline } } catch { /* Optional draft; no automatic save on restoration. */ }
    patch({ drafts: { ...s.drafts, [key]: draft } }); return key
  }
  function writeDraft(draft: StudioDraft) { storageWrite(draft.key, draft.dirty ? { draft: draft.value, baseline: draft.baseline, revision: draft.revision } : null) }
  function changeDraft(key: string, value: Record<string, unknown>, valid = true) {
    const draft = snapshot().drafts[key]
    if (!draft || !studioEditable(snapshot())) return
    const slide = snapshot().deck?.slides.find(item => item.id === (draft.kind === 'slide' ? draft.id : draft.slideId))
    if (draft.kind !== 'plan' && (slide?.locked || draft.kind === 'element' && slide?.elements.find(item => item.id === draft.id)?.locked)) return
    if (draft.kind === 'plan' && !['BRIEFING', 'DIRECTION', 'DESIGN'].includes(snapshot().document?.phase ?? '')) return
    const next = { ...draft, value: copy(value), valid, dirty: JSON.stringify(value) !== draft.baseline, scheduled: false }
    draftRevision++; writeDraft(next)
    const previous = timers.get(key); if (previous) clearTimeout(previous); timers.delete(key)
    patch({ drafts: { ...snapshot().drafts, [key]: next } })
    const identity = `${next.revision}:${JSON.stringify(next.value)}`
    if (next.dirty && valid && next.revision === snapshot().document?.revision && attempted.get(key) !== identity) {
      const timer = setTimeout(() => { timers.delete(key); if (retired) return; patch({ drafts: { ...snapshot().drafts, [key]: { ...snapshot().drafts[key]!, scheduled: false } } }); void saveDraft(key, false) }, options.autosaveMs ?? 900)
      timers.set(key, timer); patch({ drafts: { ...snapshot().drafts, [key]: { ...next, scheduled: true } } })
    }
  }
  async function saveDraft(key: string, explicit = true) {
    const draft = snapshot().drafts[key], s = snapshot()
    if (!draft?.dirty || !draft.valid || !studioEditable(s) || draft.revision !== s.document?.revision) return false
    const slide = s.deck?.slides.find(item => item.id === (draft.kind === 'slide' ? draft.id : draft.slideId))
    if (draft.kind !== 'plan' && (slide?.locked || draft.kind === 'element' && slide?.elements.find(item => item.id === draft.id)?.locked)) return false
    const timer = timers.get(key); if (timer) clearTimeout(timer); timers.delete(key)
    const identity = `${draft.revision}:${JSON.stringify(draft.value)}`
    if (!explicit && attempted.get(key) === identity) return false
    attempted.set(key, identity)
    if (draft.kind === 'plan') return command('plan', { plan: draft.value }, draft.revision)
    const { id: _id, type: _type, ...values } = draft.value
    const accepted = await command('operations', { operations: [{ op: draft.kind === 'element' ? 'update_element' : 'update_slide', slideId: draft.kind === 'element' ? draft.slideId : draft.id, ...(draft.kind === 'element' ? { elementId: draft.id } : {}), patch: values }] }, draft.revision)
    if (accepted && draft.kind === 'element') await command('job', { kind: 'PREVIEW', slideId: draft.slideId })
    return accepted
  }
  function reloadDraft(key: string) { const drafts = { ...snapshot().drafts }, draft = drafts[key]; if (!draft) return; const timer = timers.get(key); if (timer) clearTimeout(timer); timers.delete(key); delete drafts[key]; storageWrite(key, null); draftRevision++; patch({ drafts }); openDraft(draft.kind, draft.id, draft.slideId) }
  function pauseDraft(key: string) { const timer = timers.get(key); if (timer) clearTimeout(timer); timers.delete(key); const draft = snapshot().drafts[key]; if (draft?.scheduled) patch({ drafts: { ...snapshot().drafts, [key]: { ...draft, scheduled: false } } }) }
  async function upload(file: File, kind: 'sources' | 'assets') {
    if (retired || snapshot().busy || snapshot().pending || studioActive(snapshot()) || snapshot().document?.archived) return false
    if (file.size > 20 * 1024 * 1024 || kind === 'sources' && (snapshot().sources.length >= 10 || snapshot().sources.reduce((sum, source) => sum + source.bytes, file.size) > 50 * 1024 * 1024)) { patch({ error: '资料最多 10 份，每份 20 MiB，总计 50 MiB。' }); return false }
    const ticket = ++fileSequence, token = controller.capture()
    patch({ busy: true })
    try {
      const bytes = await file.arrayBuffer(), digest = await crypto.subtle.digest('SHA-256', bytes)
      if (!token.isCurrent() || ticket !== fileSequence) return false
      const sha256 = [...new Uint8Array(digest)].map(value => value.toString(16).padStart(2, '0')).join('')
      originalFile = file; patch({ busy: false })
      const keyName = `loopper.ppt.upload.${id}.${kind}.${file.name}.${sha256}`, key = storageRead(keyName) || crypto.randomUUID(); storageWrite(keyName, key)
      const accepted = await execute({ kind: 'upload', revision: snapshot().document?.revision ?? -1, key, payload: {}, file: { name: file.name, size: file.size, sha256, kind }, phase: 'IDLE' })
      if (accepted) { storageWrite(keyName, null); originalFile = undefined }
      return accepted
    } catch (failure) { if (token.isCurrent()) patch({ busy: false, error: userFacingError(failure, '文件摘要无法核对，请保留原文件后重试。') }); return false }
  }
  async function reselectFile(file: File) {
    const request = snapshot().pending, token = controller.capture(), ticket = ++fileSequence
    if (request?.kind !== 'upload' || !request.file?.sha256 || snapshot().busy) return false
    const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
    if (!token.isCurrent() || ticket !== fileSequence) return false
    const hash = [...new Uint8Array(digest)].map(value => value.toString(16).padStart(2, '0')).join('')
    if (request.file.name !== file.name || request.file.size !== file.size || request.file.sha256 !== hash) { patch({ error: '文件字节或名称与原操作不一致；未追加上传，请重新选择原文件。' }); return false }
    originalFile = file; patch({ error: '原文件字节已核对，可显式恢复原操作。' }); return true
  }
  async function readExtra<T>(read: () => Promise<T>, apply: (value: T) => void) {
    const token = controller.capture(), ticket = ++readSequence
    try { const value = await read(); if (!token.isCurrent() || ticket !== readSequence) return false; apply(value); return true } catch (failure) { if (token.isCurrent() && ticket === readSequence) patch({ error: userFacingError(failure, '内容暂时无法读取，请重试。') }); return false }
  }
  const owner = {
    identity: controller.identity, getSnapshot: snapshot, subscribe: controller.subscribe, attachView: controller.attachView, canLeave: controller.canLeave,
    start, refresh, recover, retryOriginal: () => recover(true),
    retire(forced = true) { if (retired) return { kind: 'ALLOW' as const }; if (!forced) { const decision = controller.canLeave(); if (decision.kind !== 'ALLOW') return decision }; retired = true; fileSequence++; readSequence++; detachOperation?.(); for (const timer of timers.values()) clearTimeout(timer); timers.clear(); return controller.retire(forced) },
    setChat(value: string) { if (retired || snapshot().pending) return; draftRevision++; patch({ chat: value }); saveChat() },
    setAnswer(questionId: string, value: string) { if (retired || snapshot().pending) return; draftRevision++; patch({ answers: { ...snapshot().answers, [questionId]: value } }); saveChat() },
    operations: (operations: PptOperation[], revision?: number) => command('operations', { operations }, revision),
    action: (action: string, extra: Record<string, unknown> = {}) => command('action', { action, extra }),
    generate: (prompt: string) => command('generate', { prompt }), confirmRequirements: () => command('confirm-generation', {}),
    resume: (adjustment?: string) => command('resume', adjustment ? { adjustment } : {}),
    send: (text: string, scope: PptScope) => command('message', { text: text.trim(), scope }),
    reply: (question: PptQuestion, answer: string, confirmed?: boolean) => command('reply', { questionId: question.id, version: question.version, answer, ...(confirmed === undefined ? {} : { confirmed }) }),
    createJob: (kind: 'PREVIEW' | 'EXPORT', slideId?: string) => command('job', { kind, ...(slideId ? { slideId } : {}) }),
    retryJob: (jobId: string) => command('retry-job', { jobId }), stop: () => command('stop', { runId: snapshot().agent?.runId ?? '' }),
    upload, reselectFile, openDraft, changeDraft, saveDraft, reloadDraft, pauseDraft,
    check: () => { const revision = snapshot().document?.revision; return readExtra(() => api.checks(id, revision), value => { if (snapshot().document?.revision === revision) patch({ issues: value.issues, checkedRevision: revision ?? null }) }) },
    inspectRevision: (revision: number) => readExtra(() => api.deck(id, revision), deck => patch({ historical: { revision, deck } })),
    returnCurrent: () => patch({ historical: null }),
    readKnowledge: () => readExtra(() => api.knowledge(id), knowledge => patch({ knowledge })),
    readSource: (sourceId: string) => readExtra(() => api.source(id, sourceId), sourceContent => patch({ sourceContent })),
    closeSource: () => patch({ sourceContent: null }),
    more: (kind: 'messages' | 'revisions') => kind === 'messages' ? readExtra(() => api.messages(id, snapshot().messageCursor), page => patch({ messages: mergeMessages(snapshot().messages, page.items), messageCursor: page.nextCursor ?? '' })) : readExtra(() => api.revisions(id, snapshot().revisionCursor), page => patch({ revisions: [...new Map([...snapshot().revisions, ...page.items].map(row => [row.revision, row])).values()], revisionCursor: page.nextCursor ?? '' })),
    clearError: () => patch({ error: '' }),
  }
  return owner
}
export type PptStudioController = ReturnType<typeof createPptStudioController>
