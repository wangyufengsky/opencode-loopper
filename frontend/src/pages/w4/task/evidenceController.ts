import { api } from '@/api/client'
import type { Artifact, EvidenceBody, EvidenceFailureDetail, EvidenceFailurePage, EvidencePage, EvidenceSearch, SnapshotReviewBatch, SnapshotReviewPartialReport, TaskDiffPreview } from '@/types/domain'
import { createW4Owner } from '../shared/core'
import { userFacingError } from '@/utils/displayLabels'

interface EvidenceState {
  bodies: Record<string, string>; loading: Record<string, boolean>; errors: Record<string, string>
  selectedReport: string; previewPath: string; preview?: TaskDiffPreview; partial?: SnapshotReviewPartialReport
  evidence?: EvidencePage; failures?: EvidenceFailurePage; body?: EvidenceBody; failure?: EvidenceFailureDetail; search?: EvidenceSearch; query: string
  batches: SnapshotReviewBatch[]; batchCursor?: string
}
export function linkedReport(source: Artifact, artifacts: Artifact[], href: string): Artifact | undefined {
  if (/^(?:[a-z][a-z0-9+.-]*:|\/\/|#)/i.test(href)) return
  const parts = source.title.split('/').slice(0, -1)
  let decoded: string
  try { decoded = decodeURIComponent(href.split(/[?#]/)[0] ?? '') } catch { return }
  if (decoded.startsWith('/') || decoded.includes('\\')) return
  for (const part of decoded.split('/')) {
    if (part === '..') { if (!parts.length) return; parts.pop() }
    else if (part && part !== '.') parts.push(part)
  }
  return artifacts.find(row => row.kind === 'REPORT' && row.title === parts.join('/') && (source.metadata?.bundleId ? row.metadata?.bundleId === source.metadata.bundleId : row.metadata?.repairRound === source.metadata?.repairRound))
}
export function createTaskEvidenceController(id: string) {
  const env = createW4Owner<EvidenceState>('task-evidence', id, { bodies: {}, loading: {}, errors: {}, selectedReport: '', previewPath: '', query: '', batches: [] })
  const { base, patch, ticket } = env
  async function read<T>(channel: string, load: (isCurrent: () => boolean) => Promise<T>, apply: (result: T) => void) {
    const current = ticket(channel)
    if (!current.current()) return
    const old = base.getSnapshot(); patch({ loading: { ...old.loading, [channel]: true }, errors: { ...old.errors, [channel]: '' } })
    try { const value = await load(current.current); if (current.current()) apply(value) }
    catch (cause) { if (current.current()) patch({ errors: { ...base.getSnapshot().errors, [channel]: userFacingError(cause, '证据读取失败，请重试。') } }) }
    finally { if (current.current()) patch({ loading: { ...base.getSnapshot().loading, [channel]: false } }) }
  }
  function save(blob: Blob, name: string) {
    if (!base.capture().isCurrent() || !env.active()) return
    const url = URL.createObjectURL(blob), release = env.own(() => URL.revokeObjectURL(url))
    try { const link = document.createElement('a'); link.href = url; link.download = name; link.click() } finally { release() }
  }
  async function loadBody(kind: 'verification' | 'artifact' | 'error' | 'judge', item: string) {
    const key = `${kind}:${item}`, s = base.getSnapshot(); if (s.bodies[key] !== undefined || s.loading[key]) return
    await read(key, () => ({ verification: api.getVerificationEvidence, artifact: api.getArtifactContent, error: api.getErrorEvidence, judge: api.getJudgeOutput })[kind](id, item), value => {
      let content = value.content
      if (kind === 'verification') { try { const parsed: unknown = JSON.parse(content); if (parsed && typeof parsed === 'object' && 'output' in parsed && typeof parsed.output === 'string') content = parsed.output } catch { } }
      patch({ bodies: { ...base.getSnapshot().bodies, [key]: content } })
    })
  }
  async function previewReport(report: Artifact) {
    if (report.taskId && report.taskId !== id || report.kind !== 'REPORT') return
    patch({ selectedReport: report.id }); await loadBody('artifact', report.id)
  }
  return { ...base, loadBody, previewReport,
    closeDiff() { ticket('diff'); patch({ previewPath: '', preview: undefined }) },
    async showDiff(path: string) {
      patch({ previewPath: path, preview: undefined })
      await read('diff', () => api.getTaskDiffPreview(id, path), value => { if (value.path !== path) throw new Error('差异结果不属于原文件。'); patch({ preview: value }) })
    },
    async partial() { await read('partial', () => api.snapshotReviewPartialReport(id), value => patch({ partial: value })) },
    async downloadBundle(report: Artifact) {
      if (!report.metadata?.bundleId || report.taskId && report.taskId !== id) return
      await read('download', () => api.downloadTemplateReport(id, report.id), value => save(value, `${report.metadata!.directoryName || '报告'}.zip`))
    },
    downloadMarkdown(report: Artifact) { const content = base.getSnapshot().bodies[`artifact:${report.id}`]; if (content !== undefined) save(new Blob([content], { type: 'text/markdown;charset=utf-8' }), report.title.split('/').pop() || '报告.md') },
    downloadPartial() { const content = base.getSnapshot().partial?.content; if (content !== undefined) save(new Blob([content], { type: 'text/markdown;charset=utf-8' }), '代码审查阶段报告.md') },
    async evidence(cursor = '', failureCursor = '') { await read('evidence', async isCurrent => {
      const evidence = await api.executionEvidence(id, cursor)
      if (!isCurrent()) throw new Error('原证据读取视图已离开。')
      return { evidence, failures: await api.evidenceFailures(id, failureCursor) }
    }, value => patch(value)) },
    async evidenceBody(reference: string, offset = 0) { await read('evidenceBody', () => api.executionEvidenceBody(id, reference, offset), value => patch({ body: value, failure: undefined })) },
    async evidenceFailure(failureId: string) { await read('evidenceBody', () => api.evidenceFailure(id, failureId), value => patch({ failure: value, body: undefined })) },
    changeQuery(query: string) { ticket('search'); patch({ query, search: undefined }) },
    async search(cursor = '') { const query = base.getSnapshot().query.trim(); if (!query) return; await read('search', () => api.searchExecutionEvidence(id, query, cursor), value => { if (base.getSnapshot().query.trim() === query) patch({ search: value }) }) },
    async batches(more = false) { const s = base.getSnapshot(); await read('batches', () => api.snapshotReviewBatches(id, more ? s.batchCursor ?? '' : ''), value => patch({ batches: more ? [...s.batches, ...value.items] : value.items, batchCursor: value.nextCursor ?? undefined })) },
  }
}
export type TaskEvidenceController = ReturnType<typeof createTaskEvidenceController>
