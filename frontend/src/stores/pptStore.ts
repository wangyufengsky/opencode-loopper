import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { ApiError } from '@/api/client'
import { pptApi as api } from '@/api/ppt'
import { emptyPptPlan, type PptDocument, type PptDeck, type PptPlan, type PptSource, type PptJob, type PptRevision, type PptMessage, type PptAgentStatus, type PptCapabilities, type PptIssue, type PptScope, type PptOperation, type PptQuestion } from '@/types/domain'

type Pending = { kind: 'operations' | 'plan' | 'action' | 'message' | 'reply' | 'job' | 'retry-job'; key: string; revision: number; payload: Record<string, unknown> }
const explain = (value: unknown) => value instanceof Error && /[\u4e00-\u9fff]/.test(value.message) ? value.message : '暂时无法完成操作，请重新核对状态后重试'
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
export const usePptStore = defineStore('ppt', () => {
  const document = ref<PptDocument | null>(null), deck = ref<PptDeck | null>(null), plan = ref<PptPlan>(emptyPptPlan())
  const sources = ref<PptSource[]>([]), assets = ref<PptSource[]>([]), jobs = ref<PptJob[]>([]), revisions = ref<PptRevision[]>([])
  const messages = ref<PptMessage[]>([]), agent = ref<PptAgentStatus | null>(null), capabilities = ref<PptCapabilities | null>(null), issues = ref<PptIssue[]>([])
  const loading = ref(false), busy = ref(false), error = ref(''), disconnected = ref(false), pending = ref<Pending | null>(null)
  const messageCursor = ref<string | null>(null), revisionCursor = ref<string | null>(null)
  const checkedRevision = ref<number | null>(null)
  let epoch = 0, owner = '', subscription: EventSource | undefined, refreshing: Promise<void> | null = null, queued = false
  const active = computed(() => !!agent.value && !['IDLE', 'COMPLETED', 'STOPPED', 'FAILED'].includes(agent.value.state))
  const jobsActive = computed(() => jobs.value.some(job => ['PREPARED', 'PENDING', 'QUEUED', 'RUNNING'].includes(job.state)))
  const editable = computed(() => !!document.value && !document.value.archived && !busy.value)
  const storageKey = (id: string) => `loopper.ppt.pending.${id}`
  function persist(id: string, value: Pending | null) { try { if (value) sessionStorage.setItem(storageKey(id), JSON.stringify(value)); else sessionStorage.removeItem(storageKey(id)) } catch { /* The live request still retains its identity. */ } }
  function clearAcceptedDraft(id: string, request: Pending) { if (request.kind !== 'message') return; try { const key = `loopper.ppt.chat.${id}`; if (sessionStorage.getItem(key)?.trim() === String(request.payload.text)) sessionStorage.removeItem(key) } catch { /* The server receipt remains authoritative. */ } }
  function close() { subscription?.close(); subscription = undefined }
  function reset() { ++epoch; close(); owner = ''; document.value = null; deck.value = null; plan.value = emptyPptPlan(); sources.value = []; assets.value = []; jobs.value = []; revisions.value = []; messages.value = []; agent.value = null; issues.value = []; pending.value = null; error.value = ''; disconnected.value = false; loading.value = false; busy.value = false; messageCursor.value = null; revisionCursor.value = null }
  function mergeMessages(rows: PptMessage[]) { return [...new Map(rows.map(row => [row.id, row])).values()].sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id)) }
  function refresh(): Promise<void> {
    if (!owner) return Promise.resolve()
    if (refreshing) { queued = true; return refreshing }
    // All callers await the queued snapshot too: follow-up jobs must freeze the
    // revision just written, even if an SSE refresh was already in flight.
    refreshing = (async () => { do { queued = false; await readSnapshot() } while (queued && owner) })().finally(() => { refreshing = null })
    return refreshing
  }
  async function readSnapshot() {
    const id = owner, ticket = epoch
    try {
      const summary = await api.get(id)
      const [scene, design, resources, runs, history, chat, status] = await Promise.all([api.deck(id, summary.revision), api.plan(id), api.sources(id), api.jobs(id), api.revisions(id), api.messages(id), api.agent(id)])
      if (ticket !== epoch || id !== owner) return
      if (design.revision !== summary.revision) { queued = true; return }
      if (document.value?.revision !== summary.revision) { issues.value = []; checkedRevision.value = null }
      document.value = summary; deck.value = scene; plan.value = design.plan || emptyPptPlan(); sources.value = resources.sources; assets.value = resources.assets; jobs.value = runs
      revisions.value = [...new Map([...revisions.value, ...history.items].map(row => [row.revision, row])).values()].sort((a, b) => b.revision - a.revision)
      if (revisions.value.length <= history.items.length) revisionCursor.value = history.nextCursor ?? null
      messages.value = mergeMessages([...messages.value, ...chat.items]); if (messages.value.length <= chat.items.length) messageCursor.value = chat.nextCursor ?? null; agent.value = status
      if (pending.value?.kind === 'message' && messages.value.some(message => message.idempotencyKey === pending.value?.key)) { clearAcceptedDraft(id, pending.value); pending.value = null; persist(id, null) }
    } catch (failure) { if (ticket === epoch) error.value = explain(failure) }
  }
  async function load(id: string) {
    if (owner !== id) { reset(); owner = id; try { pending.value = JSON.parse(sessionStorage.getItem(storageKey(id)) || 'null') as Pending | null } catch { pending.value = null } }
    const ticket = epoch; loading.value = true; error.value = ''
    await Promise.all([refresh(), api.capabilities().then(value => { if (ticket === epoch) capabilities.value = value }).catch(failure => { if (ticket === epoch) error.value = explain(failure) })])
    if (ticket !== epoch) return
    loading.value = false; close(); subscription = api.events(id)
    subscription.onmessage = () => { if (owner === id) { disconnected.value = false; void refresh() } }
    subscription.onopen = () => { if (owner === id) { disconnected.value = false; void refresh() } }
    subscription.onerror = () => { if (owner === id) disconnected.value = true }
  }
  async function execute(request: Pending) {
    if (!document.value || busy.value) return false
    const id = owner, ticket = epoch; busy.value = true; error.value = ''; pending.value = request; persist(id, request)
    try {
      const p = request.payload, revision = request.revision, key = request.key
      if (request.kind === 'operations') await api.operations(id, revision, p.operations as PptOperation[], key)
      else if (request.kind === 'plan') await api.savePlan(id, revision, p.plan as PptPlan, key)
      else if (request.kind === 'action') await api.action(id, String(p.action), revision, key, p.extra as Record<string, unknown>)
      else if (request.kind === 'message') await api.send(id, { expectedRevision: revision, idempotencyKey: key, text: String(p.text), scope: p.scope as PptScope })
      else if (request.kind === 'reply') await api.reply(id, String(p.questionId), { expectedRevision: revision, idempotencyKey: key, version: Number(p.version), answer: String(p.answer) })
      else if (request.kind === 'retry-job') await api.retryJob(id, String(p.jobId))
      else await api.createJob(id, p.kind as 'PREVIEW' | 'EXPORT', revision, key, p.slideId as string | undefined)
      clearAcceptedDraft(id, request); persist(id, null)
      if (ticket !== epoch) return false
      pending.value = null; await refresh(); return ticket === epoch && !error.value
    } catch (failure) {
      if (ticket === epoch) {
        error.value = explain(failure)
        if (failure instanceof ApiError && failure.status >= 400 && failure.status < 500) { pending.value = null; persist(id, null); if (failure.status === 409) await refresh() }
      }
      return false
    } finally { if (ticket === epoch) busy.value = false }
  }
  function mutate(kind: Pending['kind'], payload: Record<string, unknown>, expectedRevision?: number) {
    if (!document.value) return Promise.resolve(false)
    if (pending.value) { error.value = '上一项操作结果尚未核对，请先重试原操作'; return Promise.resolve(false) }
    return execute({ kind, key: crypto.randomUUID(), revision: expectedRevision ?? document.value.revision, payload: clone(payload) })
  }
  const operations = (values: PptOperation[], expectedRevision?: number) => mutate('operations', { operations: values }, expectedRevision)
  const savePlan = (value: PptPlan, expectedRevision?: number) => mutate('plan', { plan: value }, expectedRevision)
  const action = (value: string, extra: Record<string, unknown> = {}) => mutate('action', { action: value, extra })
  const send = (text: string, scope: PptScope) => mutate('message', { text, scope })
  const reply = (question: PptQuestion, answer: string) => mutate('reply', { questionId: question.id, version: question.version, answer })
  const createJob = (kind: 'PREVIEW' | 'EXPORT', slideId?: string) => mutate('job', { kind, slideId })
  const retryJob = (jobId: string) => mutate('retry-job', { jobId })
  const retryPending = () => pending.value ? execute(pending.value) : refresh()
  async function stop() { if (!owner || busy.value) return; const id = owner, ticket = epoch; busy.value = true; try { const value = await api.stop(id); if (ticket === epoch) { agent.value = value; await refresh() } } catch (failure) { if (ticket === epoch) error.value = explain(failure) } finally { if (ticket === epoch) busy.value = false } }
  async function check() { if (!document.value) return; const id = owner, revision = document.value.revision, ticket = epoch; try { const result = await api.checks(id, revision); if (ticket === epoch && revision === document.value?.revision) { issues.value = result.issues; checkedRevision.value = revision } } catch (failure) { if (ticket === epoch) error.value = explain(failure) } }
  async function more(kind: 'messages' | 'revisions') { const cursor = kind === 'messages' ? messageCursor.value : revisionCursor.value; if (!owner || !cursor) return; const ticket = epoch; try { if (kind === 'messages') { const page = await api.messages(owner, cursor); if (ticket === epoch) { messages.value = mergeMessages([...page.items, ...messages.value]); messageCursor.value = page.nextCursor ?? null } } else { const page = await api.revisions(owner, cursor); if (ticket === epoch) { revisions.value.push(...page.items); revisionCursor.value = page.nextCursor ?? null } } } catch (failure) { if (ticket === epoch) error.value = explain(failure) } }
  return { document, deck, plan, sources, assets, jobs, revisions, messages, agent, capabilities, issues, checkedRevision, loading, busy, error, disconnected, pending, active, jobsActive, editable, messageCursor, revisionCursor, close, reset, load, refresh, operations, savePlan, action, send, reply, createJob, retryJob, retryPending, stop, check, more }
})
