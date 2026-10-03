import { vi } from 'vitest'
import { coreFixture, coreFrame, setupCoreDom } from '@/pages/w2/core/coreTestHelpers'
import { defaultSettings } from '@/pages/w2/core/settingsController'
import { workflowRuns } from '@/api/workflowRuns'
import { workflowApi } from '@/api/workflow'
import { api } from '@/api/client'
import { workflowPublication } from '@/api/workflowPublication'
import { workflowPush } from '@/api/workflowPush'
import { workflowWriteback } from '@/api/workflowWriteback'
import { attempt, candidate, execution, requirement } from '@/components/workflow/workflowRunTestFixtures'
import { template } from '@/components/workflow/workflowTestFixtures'
import type { WorkflowPublicationPreview, WorkflowPushPreview, WorkflowWritebackPreview, WorkflowUpload } from '@/types/domain'
export { coreFixture as requirementFixture, coreFrame as requirementFrame, setupCoreDom as setupRequirementDom, attempt, candidate, execution, requirement, template }
export function deferred<T>() { let resolve!: (value: T) => void, reject!: (error: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
export const receipt = { id: 'req', revision: 2, version: 7, layoutVersion: 4, state: 'RUNNING' }
export function publicationPreview(workspaceKind: 'GIT' | 'DIRECT' = 'GIT'): WorkflowPublicationPreview {
  return { requirementId: 'req', requirementVersion: 7, planRevision: 2, requirementState: 'COMPLETED', source: { nodeKey: 'work', nodeTitle: '实施节点', attemptId: 'original-attempt', ordinal: 1, attemptState: 'SUCCEEDED', outputName: 'code', outputTitle: '代码', createdAt: '', changedFiles: 1, totalFiles: 1 }, workspaceKind, sourceBranch: 'main', reference: { version: 1, snapshotId: 'snapshot', sha256: 'a'.repeat(64) }, deliverySha256: 'b'.repeat(64), baseTree: 'base', resultTree: 'result', added: 1, modified: 0, deleted: 0, totalBytes: 1, sha256: 'c'.repeat(64) }
}
export const pushPreview: WorkflowPushPreview = { requirementId: 'req', publicationVersion: 3, remote: 'origin', url: 'git@example.test:team/project', branch: 'results', commit: 'commit', remoteCommit: null, sha256: 'push-sha' }
export const writebackPreview: WorkflowWritebackPreview = { requirementId: 'req', requirementVersion: 7, revision: 2, sourceSha256: 'c'.repeat(64), directory: '/safe/project', currentSha256: 'current', targetSha256: 'target', sha256: 'writeback-sha', added: 1, modified: 0, deleted: 0, preservedChanges: 1, conflictCount: 0, conflicts: [] }
export function uploadReceipt(names: Array<{ name: string; sha256: string; size: number }>, ready = true): WorkflowUpload { return { id: 'upload', createdAt: '', ready, reference: { version: 1, type: 'UPLOADED_DOCUMENTS', uploadId: 'upload', sha256: 'upload-sha' }, parserVersion: 'parser-1', resume: null, originals: names.map((file, index) => ({ filename: file.name, path: `original/${index + 1}/${file.name}`, sizeBytes: file.size, sha256: file.sha256, representationSha256: file.sha256, format: 'MARKDOWN', sections: 1, limitations: [] })) } }
export function mockRequirementReads(state: Parameters<typeof execution>[0] = 'RUNNING') {
  vi.spyOn(workflowRuns, 'get').mockResolvedValue(requirement({ state }))
  vi.spyOn(workflowRuns, 'execution').mockResolvedValue(execution(state))
  vi.spyOn(workflowRuns, 'finishStatus').mockResolvedValue({ requirementId: 'req', state, version: 7, intent: null, pending: { attempts: 0, resources: 0 } })
  vi.spyOn(workflowRuns, 'attempts').mockResolvedValue({ items: [attempt()], nextCursor: null })
  vi.spyOn(workflowRuns, 'attempt').mockResolvedValue(attempt())
  vi.spyOn(workflowRuns, 'definition').mockResolvedValue(requirement().graph.nodes[0]!)
  vi.spyOn(workflowRuns, 'candidates').mockResolvedValue({ items: [{ ...candidate(), sourceState: 'RUNNING', createdAt: '' }], nextCursor: null })
  vi.spyOn(workflowRuns, 'candidate').mockResolvedValue(candidate())
  vi.spyOn(workflowPublication, 'status').mockResolvedValue(null); vi.spyOn(workflowPush, 'status').mockResolvedValue(null); vi.spyOn(workflowWriteback, 'status').mockResolvedValue(null)
  vi.spyOn(api, 'getSettings').mockResolvedValue({ ...defaultSettings(), openCode: { ...defaultSettings().openCode, provider: 'provider', model: 'model' } })
  vi.spyOn(api, 'getSettingsModels').mockResolvedValue([])
  vi.spyOn(workflowApi, 'get').mockResolvedValue(template({ revision: 3 }))
  vi.spyOn(workflowRuns, 'project').mockResolvedValue({ id: 'project', name: '测试项目', createdAt: '' })
}
