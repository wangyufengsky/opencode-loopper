import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { Artifact } from '@/types/domain'
import type { ReadContent } from '@/api/client'
import { createTaskEvidenceController, linkedReport } from './evidenceController'
import { deferred } from './test-support'
const owners: ReturnType<typeof createTaskEvidenceController>[] = []
const report: Artifact = { id: 'report', taskId: 'A', kind: 'REPORT', title: '报告/总结.md', createdAt: 'now', content: '', metadata: { bundleId: 'bundle-A', directoryName: '冻结报告', repairRound: 1 } }
beforeEach(() => { vi.spyOn(api, 'getArtifactContent').mockResolvedValue({ id: report.id, kind: 'REPORT', content: '# 原报告', metadata: {} }) })
afterEach(() => { for (const owner of owners.splice(0)) owner.retire(true); vi.restoreAllMocks(); vi.unstubAllGlobals() })
function mounted() { const owner = createTaskEvidenceController('A'); owners.push(owner); const release = owner.attachView(); return { owner, release } }
it('loads only a task-owned selected report and caches its body without initial eager reads', async () => {
  const { owner } = mounted(); expect(api.getArtifactContent).not.toHaveBeenCalled(); await owner.previewReport(report); await owner.previewReport(report)
  expect(api.getArtifactContent).toHaveBeenCalledTimes(1); expect(api.getArtifactContent).toHaveBeenCalledWith('A', report.id)
  await owner.previewReport({ ...report, taskId: 'B' }); expect(api.getArtifactContent).toHaveBeenCalledTimes(1)
})
it('resolves encoded Chinese and parent paths only within the exact report bundle/repair version', () => {
  const detail = { ...report, id: 'detail', title: '报告/明细/人员.md' }, foreign = { ...detail, id: 'foreign', metadata: { bundleId: 'bundle-B' } }
  expect(linkedReport(report, [report, foreign, detail], '明细/%E4%BA%BA%E5%91%98.md')?.id).toBe('detail')
  expect(linkedReport(detail, [report], '../总结.md')?.id).toBe(report.id)
  for (const path of ['../../../外部.md', '/报告/总结.md', '..\\总结.md', 'javascript:alert(1)', 'https://other.test/report']) expect(linkedReport(report, [report], path)).toBeUndefined()
})
it('last detach invalidates all read channels before a late report success/error, keeping the final snapshot', async () => {
  const { owner, release } = mounted(), pending = deferred<ReadContent>(); vi.mocked(api.getArtifactContent).mockReturnValue(pending.promise); const reading = owner.previewReport(report); release(); const before = owner.getSnapshot()
  pending.resolve({ id: 'report', kind: 'REPORT', content: '迟到正文', metadata: {} }); await reading; expect(owner.getSnapshot()).toBe(before)
})
it('retired execution page cannot initiate a second failure GET after its first response arrives', async () => {
  const { owner } = mounted(), pending = deferred<Awaited<ReturnType<typeof api.executionEvidence>>>()
  vi.spyOn(api, 'executionEvidence').mockReturnValueOnce(pending.promise)
  const failures = vi.spyOn(api, 'evidenceFailures'), reading = owner.evidence(); owner.retire(true); const snapshot = owner.getSnapshot()
  pending.resolve({ items: [], nextCursor: '' }); await reading
  expect(failures).not.toHaveBeenCalled(); expect(owner.getSnapshot()).toBe(snapshot)
})
it('diff previews reject a mismatched path and preserve a visible error rather than rendering another file', async () => {
  const { owner } = mounted(); vi.spyOn(api, 'getTaskDiffPreview').mockResolvedValue({ path: 'wrong.java', changeType: 'NEW', patch: '+wrong', truncated: false }); await owner.showDiff('correct.java')
  expect(owner.getSnapshot().preview).toBeUndefined(); expect(owner.getSnapshot().errors.diff).toContain('原文件')
})
it('verification full output is requested lazily and extracts the exact evidence output', async () => {
  const { owner } = mounted(), read = vi.spyOn(api, 'getVerificationEvidence').mockResolvedValue({ id: 'v', kind: 'VERIFICATION', content: JSON.stringify({ output: '真实 stdout' }), metadata: {} })
  expect(read).not.toHaveBeenCalled(); await owner.loadBody('verification', 'v'); expect(owner.getSnapshot().bodies['verification:v']).toBe('真实 stdout'); expect(read).toHaveBeenCalledWith('A', 'v')
})
it('downloads exact selected bundle and revokes the owned URL synchronously, late downloads create no URL', async () => {
  const { owner, release } = mounted(), create = vi.fn(() => 'blob:owned'), revoke = vi.fn(), OriginalURL = URL
  vi.stubGlobal('URL', class extends OriginalURL { static createObjectURL = create; static revokeObjectURL = revoke })
  let filename = ''
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function(this: HTMLAnchorElement) { filename = this.download })
  vi.spyOn(api, 'downloadTemplateReport').mockResolvedValue(new Blob(['zip'])); await owner.downloadBundle(report)
  expect(api.downloadTemplateReport).toHaveBeenCalledWith('A', report.id); expect(filename).toBe('冻结报告.zip'); expect(create).toHaveBeenCalledTimes(1); expect(revoke).toHaveBeenCalledWith('blob:owned')
  const late = deferred<Blob>(); vi.mocked(api.downloadTemplateReport).mockReturnValue(late.promise); const pending = owner.downloadBundle(report); release(); late.resolve(new Blob(['late'])); await pending; expect(create).toHaveBeenCalledTimes(1)
})
it('execution evidence paginates and searches persisted snapshots with precise original reference/offset', async () => {
  const { owner } = mounted(), read = vi.spyOn(api, 'executionEvidence').mockResolvedValue({ items: [], nextCursor: 'next-source' })
  vi.spyOn(api, 'evidenceFailures').mockResolvedValue({ items: [], nextCursor: '', reports: { items: [], nextCursor: '' }, detail: '读取已保存的测试快照' })
  await owner.evidence(); await owner.evidence('next-source'); expect(read).toHaveBeenLastCalledWith('A', 'next-source')
  vi.spyOn(api, 'searchExecutionEvidence').mockResolvedValue({ items: [], complete: false, nextCursor: '', scannedBytes: 20 }); owner.changeQuery('异常'); await owner.search(); expect(api.searchExecutionEvidence).toHaveBeenCalledWith('A', '异常', '')
})
