import { api } from '@/api/client'
import type { TaskSessionActivity, TaskSessionSummary, TaskSessionRoleSummary, TaskSessionPendingQuestion } from '@/types/domain'
import type { OperationOwner } from '@/foundation/contracts/receipt'
import type { TaskParentPort } from '../shared/types'
import { createW4Owner, dirtyDecision, idleCommand, pendingCommand, recoverOperation, type CommandState } from '../shared/core'
import { userFacingError } from '@/utils/displayLabels'

export interface SessionMonitorState {
  sessions: TaskSessionSummary[]; selected: string; activity?: TaskSessionActivity; loading: boolean; error: string
  answers: Record<string, string[][]>; custom: Record<string, string[]>; draftQuestions: Record<string, TaskSessionPendingQuestion>; dirty: boolean; draftRevision: number
  command: CommandState; confirming?: string; role?: TaskSessionRoleSummary; roleOpened: boolean; roleLoading: boolean; roleError: string
  switching?: { key: string; revision: number }
  totalTokens: number | null; tokenDelta: number; expanded: string[]; follow: boolean; todoExpanded: boolean
}
export const sessionIsActive = (value?: TaskSessionActivity) => !!value && ['creating', 'running', 'busy', 'retry'].includes((value.remoteState || value.session.state).toLowerCase())
export function createSessionMonitorController(taskId: string, parent?: TaskParentPort) {
  const env = createW4Owner<SessionMonitorState>('task-sessions', taskId, { sessions: [], selected: '', loading: false, error: '', answers: {}, custom: {}, draftQuestions: {}, dirty: false, draftRevision: 0, command: idleCommand,
    roleOpened: false, roleLoading: false, roleError: '', totalTokens: null, tokenDelta: 0, expanded: [], follow: true, todoExpanded: false }, s => dirtyDecision(s.dirty, s.draftRevision))
  const { base, patch, ticket } = env
  env.setWriteGate(() => parent?.canStartWrite(base) ?? true)
  let releasePoll = () => {}, releaseDelta = () => {}
  const locked = () => pendingCommand(base.getSnapshot().command)
  function applyActivity(value: TaskSessionActivity, key: string) {
    if (!value?.session || value.session.key !== key || !Array.isArray(value.pendingQuestions)) throw new Error('活动结果不属于原会话，请重新读取。')
    const old = base.getSnapshot(), total = value.usage?.totalTokens
    let delta = 0, baseline = old.totalTokens
    if (typeof total === 'number' && Number.isFinite(total) && total >= 0 && (baseline === null || total > baseline)) { delta = baseline === null ? 0 : total - baseline; baseline = total }
    patch({ activity: value, totalTokens: baseline, ...(delta ? { tokenDelta: delta } : {}) })
    if (delta) { releaseDelta(); releaseDelta = env.delay(() => patch({ tokenDelta: 0 }), 850) }
  }
  function schedule() {
    releasePoll(); releasePoll = env.delay(() => { void load() }, sessionIsActive(base.getSnapshot().activity) ? 1200 : 3000)
  }
  async function load() {
    releasePoll(); const current = ticket('sessions'); patch({ loading: true, error: '' })
    try {
      const sessions = await api.getTaskSessions(taskId)
      if (!current.current()) return
      const old = base.getSnapshot()
      // A missing session cannot erase original question input or an uncertain command.
      const selected = old.selected && (sessions.some(row => row.key === old.selected) || old.dirty || locked()) ? old.selected : sessions[0]?.key ?? ''
      patch({ sessions, selected })
      if (selected) {
        const value = await api.getTaskSessionActivity(taskId, selected)
        if (current.current() && base.getSnapshot().selected === selected) applyActivity(value, selected)
      } else patch({ activity: undefined })
    } catch (cause) { if (current.current()) patch({ error: userFacingError(cause, '会话读取失败，请重试。') }) }
    finally { if (current.current()) { patch({ loading: false }); schedule() } }
  }
  async function select(key: string, discardRevision?: number) {
    const s = base.getSnapshot()
    if (locked() || !s.sessions.some(row => row.key === key)) return false
    if (s.dirty && discardRevision !== s.draftRevision) return false
    ticket('sessions'); ticket('role'); releaseDelta()
    patch({ selected: key, activity: undefined, answers: {}, custom: {}, draftQuestions: {}, dirty: false, draftRevision: s.draftRevision + 1, role: undefined, roleOpened: false, roleError: '', totalTokens: null, tokenDelta: 0, expanded: [], switching: undefined })
    await load(); return true
  }
  function question(id: string) { const s = base.getSnapshot(); return s.draftQuestions[id] ?? s.activity?.pendingQuestions.find(row => row.id === id) }
  function questionCurrent(id: string) { const original = question(id), current = base.getSnapshot().activity?.pendingQuestions.find(row => row.id === id); return !!original && JSON.stringify(original) === JSON.stringify(current) }
  function answerValues(value: TaskSessionPendingQuestion): string[][] {
    const s = base.getSnapshot()
    return value.questions.map((prompt, index) => {
      const chosen = s.answers[value.id]?.[index] ?? [], custom = s.custom[value.id]?.[index]?.trim()
      return prompt.custom && custom ? prompt.multiple ? [...chosen, custom] : [custom] : chosen
    })
  }
  let operation: OperationOwner<{ answers?: string[][] }, { question: string; session: string }> | undefined
  async function submit(questionId: string, reject = false) {
    const s = base.getSnapshot(), row = question(questionId)
    if (!row || !questionCurrent(questionId) || !s.selected || locked() || !env.canStartWrite()) return
    const answers = answerValues(row)
    if (!reject && answers.some(value => !value.length)) return
    const session = s.selected, revision = s.draftRevision
    operation = env.command({ label: reject ? '拒绝问题' : '提交回答', input: { endpoint: `/tasks/${taskId}/sessions/${session}/questions/${questionId}/${reject ? 'reject' : 'reply'}`, method: 'POST', body: reject ? {} : { answers } },
      capability: { kind: 'READ_ORIGINAL', readOriginal: async () => {
        const value = await api.getTaskSessionActivity(taskId, session)
        if (base.capture().isCurrent()) applyActivity(value, session)
        // This API has no request lookup/resolved-action receipt. Absence alone does not prove our answer was accepted.
        return { kind: 'UNCONFIRMED' }
      } }, write: async identity => {
        if (reject) await api.rejectTaskSessionQuestion(taskId, session, questionId)
        else await api.replyTaskSessionQuestion(taskId, session, questionId, identity.body.answers!.map(items => [...items]))
        return { question: questionId, session }
      }, read: async (receipt, context) => {
        if (receipt.question !== questionId || receipt.session !== session) throw new Error('回答回执不属于原问题。')
        const value = await api.getTaskSessionActivity(taskId, session)
        if (value.session.key !== session || value.pendingQuestions.some(item => item.id === questionId)) throw new Error('回答已接受，正在等待原问题结果，请继续读取。')
        context.apply(() => { applyActivity(value, session); if (base.getSnapshot().draftRevision === revision) {
          const old = base.getSnapshot(), answers = { ...old.answers }, custom = { ...old.custom }, draftQuestions = { ...old.draftQuestions }
          delete answers[questionId]; delete custom[questionId]; delete draftQuestions[questionId]
          patch({ dirty: !!Object.keys(draftQuestions).length, answers, custom, draftQuestions, confirming: undefined })
        } })
      }, changed: command => patch({ command }) })
    try { await operation.execute() } catch { }
  }
  env.setStart(() => { void load() })
  return Object.assign(base, { load, select, locked, submit,
    requestSelect(key: string) { const s = base.getSnapshot(); if (locked()) return; if (s.dirty) patch({ switching: { key, revision: s.draftRevision } }); else void select(key) },
    confirmSelect() { const next = base.getSnapshot().switching; if (next) void select(next.key, next.revision) },
    cancelSelect() { patch({ switching: undefined }) },
    canSubmit(id: string) { const row = question(id); return !!row && questionCurrent(id) && !locked() && env.canStartWrite() && answerValues(row).every(items => items.length > 0) },
    questionCurrent,
    choose(id: string, index: number, value: string) {
      const row = question(id), s = base.getSnapshot(); if (!row || locked()) return
      const answers = (s.answers[id] ?? row.questions.map(() => [])).map(items => [...items]), selected = answers[index] ?? []
      answers[index] = row.questions[index]?.multiple ? selected.includes(value) ? selected.filter(item => item !== value) : [...selected, value] : [value]
      const custom = [...(s.custom[id] ?? [])]; if (!row.questions[index]?.multiple) custom[index] = ''
      patch({ answers: { ...s.answers, [id]: answers }, custom: { ...s.custom, [id]: custom }, draftQuestions: { ...s.draftQuestions, [id]: row }, dirty: true, draftRevision: s.draftRevision + 1 })
    },
    customAnswer(id: string, index: number, value: string) {
      if (!question(id) || locked()) return; const s = base.getSnapshot(), values = [...(s.custom[id] ?? [])]; values[index] = value
      patch({ custom: { ...s.custom, [id]: values }, draftQuestions: { ...s.draftQuestions, [id]: question(id)! }, dirty: true, draftRevision: s.draftRevision + 1 })
    },
    discard(revision: number) { const s = base.getSnapshot(); if (locked() || revision !== s.draftRevision) return false; patch({ dirty: false, answers: {}, custom: {}, draftQuestions: {}, draftRevision: s.draftRevision + 1 }); return true },
    requestReject(id: string) { if (question(id) && !locked() && env.canStartWrite()) patch({ confirming: id }) },
    cancelReject() { patch({ confirming: undefined }) },
    async recover() { try { await recoverOperation(operation) } catch { } },
    async toggleRole() {
      const s = base.getSnapshot(), opened = !s.roleOpened; patch({ roleOpened: opened }); if (!opened || !s.selected || s.role) return
      const current = ticket('role'), key = s.selected; patch({ roleLoading: true, roleError: '' })
      try { const role = await api.getTaskSessionRole(taskId, key); if (current.current() && base.getSnapshot().selected === key) patch({ role }) }
      catch (cause) { if (current.current()) patch({ roleError: userFacingError(cause, '角色权限读取失败，请重试。') }) }
      finally { if (current.current()) patch({ roleLoading: false }) }
    },
    expand(id: string) { const s = base.getSnapshot(); patch({ expanded: s.expanded.includes(id) ? s.expanded.filter(row => row !== id) : [...s.expanded, id] }) },
    setFollow(value: boolean) { patch({ follow: value }) },
    toggleTodo() { patch({ todoExpanded: !base.getSnapshot().todoExpanded }) },
  })
}
export type SessionMonitorController = ReturnType<typeof createSessionMonitorController>
