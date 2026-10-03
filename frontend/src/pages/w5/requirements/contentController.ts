import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowCodeChange, WorkflowFile, WorkflowInputs, WorkflowKnowledgeBody, WorkflowKnowledgeEntry, WorkflowSnapshotPartialReport } from '@/types/domain'
import { createRequirementScope, ownedState, requireIdentity } from './core'

export interface AttemptContentScope { requirement: string; node: string; attempt: string; direction: 'inputs' | 'outputs'; name: string }
export function createContentController(scope: AttemptContentScope, fixedInput?: WorkflowInputs['values'][number]) {
  const owner = createRequirementScope('requirement-content', `${scope.requirement}/${scope.node}/${scope.attempt}/${scope.direction}/${scope.name}`, { ...ownedState(),
    files: [] as WorkflowFile[], fileCursor: null as string | null, filesLoaded: false, changes: [] as WorkflowCodeChange[], changeCursor: null as string | null, changesLoaded: false,
    previewPath: '', body: null as { text: string; nextOffset: number | null; sha256: string } | null,
    inputText: '', inputLoaded: false, inputNext: 0 as number | null, inputTotal: 0,
    evidence: [] as WorkflowKnowledgeEntry[], evidenceCursor: null as string | null, evidenceLoaded: false, selectedEvidence: '', evidenceBody: null as WorkflowKnowledgeBody | null,
    partial: null as WorkflowSnapshotPartialReport | null,
  })
  let abort: AbortController | null = null, abortRelease = () => {}
  const bodyCache = new Map<string, NonNullable<ReturnType<typeof owner.getSnapshot>['body']>>(), evidenceCache = new Map<string, WorkflowKnowledgeBody>()
  function request(channel: string, cancelPrevious = false) { if (cancelPrevious) abortRelease(); const controller = new AbortController(), release = owner.own(() => controller.abort()), ticket = owner.ticket(channel); if (cancelPrevious) { abort = controller; abortRelease = release }; return { signal: controller.signal, release, current: ticket.current } }
  async function files(more = false) {
    if (!owner.active()) return
    const ticket = owner.ticket('files'), s = owner.getSnapshot(); owner.patch({ loading: true })
    try { const page = await workflowRuns.files(scope.requirement, scope.node, scope.attempt, scope.direction, scope.name, more ? s.fileCursor ?? '' : ''); if (ticket.current()) owner.patch({ files: more ? [...s.files, ...page.items] : page.items, fileCursor: page.nextCursor ?? null, filesLoaded: true, error: '' }) }
    catch (cause) { if (ticket.current()) owner.fail(cause, '固定版本文件暂时无法读取，请重试。') }
    finally { if (ticket.current()) owner.patch({ loading: false }) }
  }
  async function changes(more = false) {
    if (!owner.active()) return
    const ticket = owner.ticket('changes'), s = owner.getSnapshot(); owner.patch({ loading: true })
    try { const page = await workflowRuns.changes(scope.requirement, scope.node, scope.attempt, scope.direction, scope.name, more ? s.changeCursor ?? '' : ''); if (ticket.current()) owner.patch({ changes: more ? [...s.changes, ...page.items] : page.items, changeCursor: page.nextCursor ?? null, changesLoaded: true, error: '' }) }
    catch (cause) { if (ticket.current()) owner.fail(cause, '改动文件暂时无法读取，请重试。') }
    finally { if (ticket.current()) owner.patch({ loading: false }) }
  }
  async function readFile(path: string, more = false) {
    if (!owner.active()) return
    const previous = more && owner.getSnapshot().previewPath === path ? owner.getSnapshot().body : null
    if (previous && previous.text.length >= 2 * 1024 * 1024) return
    const read = request('file-body', true), offset = previous?.nextOffset ?? 0
    owner.patch({ previewPath: path, body: previous ?? bodyCache.get(path) ?? null, loading: true, error: '' })
    if (!more && bodyCache.has(path)) { read.release(); owner.patch({ loading: false }); return }
    try {
      const page = await workflowRuns.fileText(scope.requirement, scope.node, scope.attempt, scope.direction, scope.name, path, offset, read.signal)
      if (!read.current()) return
      if (page.path !== path || page.offset !== offset || previous && page.sha256 !== previous.sha256 || page.nextOffset !== null && page.nextOffset <= page.offset) throw new Error('报告分页版本不一致，请读取原报告。')
      const body = { text: (previous?.text ?? '') + page.text, nextOffset: page.nextOffset, sha256: page.sha256 }; bodyCache.set(path, body); if (bodyCache.size > 8) bodyCache.delete(bodyCache.keys().next().value!); owner.patch({ body })
    } catch (cause) { if (read.current()) owner.fail(cause, '报告正文暂时无法读取，请重试或下载。') }
    finally { read.release(); if (read.current()) owner.patch({ loading: false }) }
  }
  async function input() {
    if (!owner.active()) return
    const s = owner.getSnapshot(); if (!fixedInput || s.loading || s.inputNext === null) return
    const offset = s.inputNext, read = request('input-text', true); owner.patch({ loading: true, error: '' })
    try {
      const page = await workflowRuns.inputContent(scope.requirement, scope.node, scope.attempt, fixedInput.name, offset, read.signal)
      if (!read.current()) return
      const end = offset + page.text.length
      if (page.name !== fixedInput.name || page.kind !== fixedInput.kind || page.sha256 !== fixedInput.sha256 || page.offset !== offset || !Number.isInteger(page.totalLength) || page.totalLength < end || page.totalLength > 307200 || s.inputLoaded && page.totalLength !== s.inputTotal || (page.nextOffset === null ? end !== page.totalLength : page.nextOffset !== end || end <= offset || end >= page.totalLength)) throw new Error('返回的正文与固定输入不一致，请重试读取。')
      owner.patch({ inputText: s.inputText + page.text, inputNext: page.nextOffset, inputTotal: page.totalLength, inputLoaded: true })
    } catch (cause) { if (read.current()) owner.fail(cause, '固定输入正文暂时无法读取，请重试。') }
    finally { read.release(); if (read.current()) owner.patch({ loading: false }) }
  }
  async function evidence(more = false) {
    if (!owner.active()) return
    const ticket = owner.ticket('evidence'), s = owner.getSnapshot(); owner.patch({ loading: true })
    try { const page = await workflowRuns.knowledgeEvidence(scope.requirement, scope.node, scope.attempt, more ? s.evidenceCursor ?? '' : ''); if (ticket.current()) owner.patch({ evidence: more ? [...s.evidence, ...page.items] : page.items, evidenceCursor: page.nextCursor ?? null, evidenceLoaded: true, error: '' }) }
    catch (cause) { if (ticket.current()) owner.fail(cause, '检索证据暂时无法读取，请重试。') }
    finally { if (ticket.current()) owner.patch({ loading: false }) }
  }
  async function evidenceBody(id: string) {
    if (!owner.active()) return
    const ticket = owner.ticket('evidence-body'); owner.patch({ selectedEvidence: id, evidenceBody: evidenceCache.get(id) ?? null, loading: true, error: '' })
    if (evidenceCache.has(id)) { owner.patch({ loading: false }); return }
    try { const body = await workflowRuns.knowledgeEvidenceBody(scope.requirement, scope.node, scope.attempt, id); if (!ticket.current()) return; requireIdentity(body.id, id, '检索证据'); evidenceCache.set(id, body); if (evidenceCache.size > 3) evidenceCache.delete(evidenceCache.keys().next().value!); owner.patch({ evidenceBody: body }) }
    catch (cause) { if (ticket.current()) owner.fail(cause, '证据正文暂时无法读取，请重试。') }
    finally { if (ticket.current()) owner.patch({ loading: false }) }
  }
  async function partial() {
    if (!owner.active()) return
    const read = request('partial-report', true); owner.patch({ loading: true, error: '' })
    try { const report = await workflowRuns.snapshotPartialReport(scope.requirement, scope.node, scope.attempt, read.signal); if (read.current()) owner.patch({ partial: report }) }
    catch (cause) { if (read.current()) owner.fail(cause, '阶段报告读取失败，请稍后重试。') }
    finally { read.release(); if (read.current()) owner.patch({ loading: false }) }
  }
  return Object.assign(owner, { scope, files, changes, readFile, input, evidence, evidenceBody, partial,
    closePreview() { owner.ticket('file-body'); abort?.abort(); owner.patch({ previewPath: '', body: null, loading: false }) },
    fileUrl: (path: string) => workflowRuns.fileUrl(scope.requirement, scope.node, scope.attempt, scope.direction, scope.name, path),
    archiveUrl: () => workflowRuns.archiveUrl(scope.requirement, scope.node, scope.attempt, scope.direction, scope.name),
  })
}
