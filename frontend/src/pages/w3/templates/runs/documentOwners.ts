import { api } from '@/api/client'
import type { DocumentTemplateOverview, DocumentSectionPage, DocumentSection, DocumentClarificationRequest, DocumentSupplementOptions, DocumentSupplementRequest } from '@/types/domain'
import type { OperationOwner } from '@/foundation/contracts/receipt'
import { userFacingError } from '@/utils/displayLabels'
import { dirtyDecision, idleCommand, pendingCommand, recoverOperation, runOwner, type CommandState } from './core'

function validateReceipt(receipt: Readonly<DocumentTemplateOverview>, id: string, version: number) {
  if (receipt.id !== id || !Number.isSafeInteger(receipt.version) || receipt.version < version) throw new Error('原操作回执不属于当前需求运行或版本无效，请核对原结果。')
}

export function createDocumentSourcesOwner(run: DocumentTemplateOverview) {
  const core = runOwner('document-sources', `${run.id}:${run.sourceRevision ?? 0}`, { pages: {} as Record<string, DocumentSectionPage>, bodies: {} as Record<string, DocumentSection>, busy: {} as Record<string, boolean>, error: '' })
  const { base, patch, ticket } = core
  async function sections(fileId: string, next = false) {
    const state = base.getSnapshot(), previous = state.pages[fileId], key = `index:${fileId}`
    if (state.busy[key] || previous && !next || next && previous?.nextOffset == null) return
    const request = ticket(key); patch({ busy: { ...state.busy, [key]: true }, error: '' })
    try { const page = await api.documentSections(run.id, fileId, next ? previous!.nextOffset! : 0)
      if (request.current()) patch({ pages: { ...base.getSnapshot().pages, [fileId]: { items: [...(next ? previous!.items : []), ...page.items], nextOffset: page.nextOffset } } })
    } catch (failure) { if (request.current()) patch({ error: userFacingError(failure, '原文目录读取失败，请重试') }) }
    finally { if (request.current()) patch({ busy: { ...base.getSnapshot().busy, [key]: false } }) }
  }
  async function body(fileId: string, ordinal: number) {
    const key = `${fileId}:${ordinal}`, state = base.getSnapshot(), file = run.files.find(item => item.id === fileId)
    if (!file || state.busy[key] || state.bodies[key]) return
    const request = ticket(key); patch({ busy: { ...state.busy, [key]: true }, error: '' })
    try { const value = await api.documentSection(run.id, fileId, ordinal, file.sha256)
      if (request.current()) patch({ bodies: { ...base.getSnapshot().bodies, [key]: value } })
    } catch (failure) { if (request.current()) patch({ error: userFacingError(failure, '原文读取失败，请重试') }) }
    finally { if (request.current()) patch({ busy: { ...base.getSnapshot().busy, [key]: false } }) }
  }
  return { ...base, sections, body }
}

export interface ClarificationState { answer: string; loading: boolean; error: string; draftRevision: number; command: CommandState }
export function createClarificationOwner(initialRun: DocumentTemplateOverview, requirementKey: string, updated: (run: DocumentTemplateOverview) => void) {
  let run = initialRun
  const core = runOwner<ClarificationState>('document-clarification', `${run.id}:${run.requirementRevision}:${requirementKey}`, { answer: '', loading: false, error: '', draftRevision: 0, command: idleCommand }, state => dirtyDecision(!!state.answer.trim(), state.draftRevision))
  const { base, patch } = core
  let operation: OperationOwner<DocumentClarificationRequest, DocumentTemplateOverview> | undefined
  async function submit() {
    const state = base.getSnapshot()
    if (pendingCommand(state.command) || !state.answer.trim() || !base.canStartWrite()) return
    const body: DocumentClarificationRequest = { requestKey: crypto.randomUUID(), expectedVersion: run.version, requirementRevision: run.requirementRevision, answers: [{ requirementKey, answer: state.answer.trim() }] }
    operation = core.command({ label: '业务回答', input: { endpoint: `/template-tasks/document-runs/${run.id}/clarifications`, method: 'POST', body, requestKey: body.requestKey }, capability: { kind: 'IDEMPOTENT_KEY' },
      write: identity => api.answerDocumentRequirements(initialRun.id, identity.body),
      read: async (receipt, context) => { validateReceipt(receipt, initialRun.id, body.expectedVersion); context.apply(() => { if (base.capture().isCurrent()) { patch({ answer: '', draftRevision: base.getSnapshot().draftRevision + 1 }); updated(receipt as DocumentTemplateOverview) } }) },
      changed: command => patch({ command, loading: command.busy, error: command.error }),
    })
    await operation.execute().catch(() => {})
  }
  return { ...base, submit,
    changeAnswer(answer: string) { if (!pendingCommand(base.getSnapshot().command)) patch({ answer, draftRevision: base.getSnapshot().draftRevision + 1 }) },
    updateRun(value: DocumentTemplateOverview) { if (value.id === run.id && value.requirementRevision === run.requirementRevision) run = value },
    discard() { if (base.canLeave().kind !== 'BLOCK') patch({ answer: '', draftRevision: base.getSnapshot().draftRevision + 1 }) },
    recover: () => recoverOperation(operation).catch(() => {}), operation: () => operation,
  }
}

export interface SupplementState { opened: boolean; options?: DocumentSupplementOptions; busy: boolean; error: string; invalidFiles: boolean; files: { name: string; size: number }[]; draftRevision: number; command: CommandState }
export function validateSupplement(files: readonly File[], existing: DocumentTemplateOverview['files'] = []) {
  if (files.some(file => !/\.(docx|md|markdown|pdf)$/i.test(file.name))) return '仅支持 DOCX、Markdown 和文本 PDF'
  if (files.length + existing.length > 10 || files.some(file => !file.size || file.size > 20 * 1024 * 1024)
    || files.reduce((sum, file) => sum + file.size, 0) + existing.reduce((sum, file) => sum + file.sizeBytes, 0) > 50 * 1024 * 1024) return '原文与补充文档合计最多 10 份、50 MiB，单文件最多 20 MiB'
  return ''
}
export function createSupplementOwner(run: DocumentTemplateOverview, updated: (run: DocumentTemplateOverview) => void) {
  let files: File[] = []
  const core = runOwner<SupplementState>('document-supplement', run.id, { opened: false, busy: false, error: '', invalidFiles: false, files: [], draftRevision: 0, command: idleCommand }, state => dirtyDecision(files.length > 0, state.draftRevision))
  const { base, patch, ticket } = core
  let operation: OperationOwner<DocumentSupplementRequest, DocumentTemplateOverview> | undefined
  async function open() {
    if (base.getSnapshot().busy || pendingCommand(base.getSnapshot().command)) return
    const request = ticket('options'); patch({ busy: true, error: '' })
    try { const options = await api.documentSupplementOptions(run.id); if (request.current()) patch({ opened: true, options }) }
    catch (failure) { if (request.current()) patch({ error: userFacingError(failure, '补充入口读取失败，请重试') }) }
    finally { if (request.current()) patch({ busy: false }) }
  }
  function choose(value: File[]) {
    if (base.getSnapshot().busy || pendingCommand(base.getSnapshot().command) || !value.length) return
    files = [...value]; const error = validateSupplement(files, run.files)
    patch({ files: files.map(file => ({ name: file.name, size: file.size })), invalidFiles: !!error, error, draftRevision: base.getSnapshot().draftRevision + 1 })
  }
  async function submit() {
    const state = base.getSnapshot(), body = state.options?.request
    if (state.busy || pendingCommand(state.command) || !body || !files.length || state.invalidFiles || !base.canStartWrite()) return
    operation = core.command({ label: '补充文档', input: { endpoint: `/template-tasks/document-runs/${run.id}/supplements`, method: 'POST', body, requestKey: body.requestKey, files }, capability: { kind: 'IDEMPOTENT_KEY' },
      write: identity => api.uploadDocumentSupplement(run.id, identity.body, [...identity.files]),
      read: async (receipt, context) => { validateReceipt(receipt, run.id, body.expectedVersion); context.apply(() => { if (base.capture().isCurrent()) { files = []; patch({ opened: false, files: [], options: undefined, draftRevision: base.getSnapshot().draftRevision + 1 }); updated(receipt as DocumentTemplateOverview) } }) },
      changed: command => patch({ command, busy: command.busy, error: command.error }),
    })
    await operation.execute().catch(() => {})
  }
  return { ...base, open, choose, submit, getFiles: () => [...files],
    remove(index: number) { if (!base.getSnapshot().busy && !pendingCommand(base.getSnapshot().command)) { const next = files.filter((_, position) => position !== index); if (next.length) choose(next); else { files = []; patch({ files: [], error: '', invalidFiles: false, draftRevision: base.getSnapshot().draftRevision + 1 }) } } },
    discard() { if (base.canLeave().kind !== 'BLOCK') { files = []; patch({ files: [], opened: false, draftRevision: base.getSnapshot().draftRevision + 1 }) } },
    close() { patch({ opened: false }) }, recover: () => recoverOperation(operation).catch(() => {}), operation: () => operation,
  }
}
