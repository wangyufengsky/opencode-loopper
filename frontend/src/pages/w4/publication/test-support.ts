import { vi } from 'vitest'
import { api } from '@/api/client'
import { skins } from '@/themes/registry'
import type { TaskPublicationStatus, LocalSyncConflictSession, LocalSyncConflictFile, LocalSyncConflictContent } from '@/types/domain'
import type { W2PageProps, W2LeaveGuard } from '@/pages/w2/shared/types'
import type { TaskParentPort, TaskChildOwner } from '../shared/types'
import { task, publication, session, content, file } from './fixtures'
export { task, publication, session, content, file, recovery, conflictText } from './fixtures'

export const deferred = <T,>() => { let resolve!: (value: T) => void, reject!: (error: unknown) => void; const promise = new Promise<T>((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
export function fixture(patch: Partial<TaskPublicationStatus> = {}) {
  let pub = publication(patch), currentSession = session(), currentContent = content(), currentFiles = [file()]
  const get = vi.spyOn(api, 'getTaskPublication').mockImplementation(async () => structuredClone(pub))
  const suggestion = vi.spyOn(api, 'generateTaskCommitMessage').mockResolvedValue({ subject: '根据已验收差异更新接口', aiGenerated: true })
  const publish = vi.spyOn(api, 'publishTask').mockImplementation(async (_id, commitMessage) => { pub = publication({ ...pub, commitMessage: commitMessage ?? pub.commitMessage, commitSha: 'commit', state: pub.remoteName ? 'PUSHED' : 'SYNCED_LOCAL', deliveryState: pub.remoteName ? 'PUSHED' : 'LOCAL_COMPLETED', deliveryFinal: !pub.remoteName }); return structuredClone(pub) })
  const reconcile = vi.spyOn(api, 'reconcileTaskPublication').mockImplementation(async () => { pub = { ...pub, lastCheckedAt: '2026-10-04' }; return structuredClone(pub) })
  const mr = vi.spyOn(api, 'createTaskMergeRequestDraft').mockImplementation(async (_id, body) => { pub = { ...pub, creationRequestedAt: '2026-10-03T12:00:00Z' }; return { provider: 'GITLAB', sourceBranch: pub.branch!, ...body, creationUrl: 'https://example.test/merge/new' } })
  vi.spyOn(api, 'createLocalSyncConflictSession').mockImplementation(async () => structuredClone(currentSession))
  vi.spyOn(api, 'getLocalSyncConflictSession').mockImplementation(async () => structuredClone(currentSession))
  vi.spyOn(api, 'getLocalSyncConflictFiles').mockImplementation(async () => structuredClone(currentFiles))
  vi.spyOn(api, 'getLocalSyncConflictContent').mockImplementation(async (_id, _session, path) => ({ ...structuredClone(currentContent), path }))
  const save = vi.spyOn(api, 'saveLocalSyncResolution').mockImplementation(async (_id, _session, body) => {
    currentContent = { ...currentContent, resolution: body.resolution, mergedContent: body.resolution === 'MANUAL' ? body.content : body.resolution === 'SOURCE' ? currentContent.sourceContent : currentContent.taskContent, version: currentContent.version + 1 }
    currentSession = { ...currentSession, state: 'READY', resolvedCount: 1, version: currentSession.version + 1 }
    currentFiles = currentFiles.map(row => ({ ...row, resolved: true, resolution: body.resolution, version: currentContent.version }))
    return structuredClone(currentContent)
  })
  const ai = vi.spyOn(api, 'suggestLocalSyncResolution').mockImplementation(async () => { currentContent = { ...currentContent, aiSuggestion: 'class Sample {\n  int value = 3;\n}\n', version: currentContent.version + 1 }; return { path: currentContent.path, suggestion: currentContent.aiSuggestion!, automaticallySelected: false, version: currentContent.version } })
  const apply = vi.spyOn(api, 'applyLocalSyncConflict').mockImplementation(async () => { currentSession = { ...currentSession, state: 'APPLIED', version: currentSession.version + 1 }; pub = publication({ ...pub, state: 'SYNCED_LOCAL', deliveryState: 'LOCAL_COMPLETED', deliveryFinal: true }); return structuredClone(currentSession) })
  return { get, suggestion, publish, reconcile, mr, save, ai, apply, setPublication: (value: Partial<TaskPublicationStatus>) => { pub = { ...pub, ...value } }, setSession: (value: Partial<LocalSyncConflictSession>) => { currentSession = { ...currentSession, ...value } }, setContent: (value: Partial<LocalSyncConflictContent>) => { currentContent = { ...currentContent, ...value } }, setFiles: (value: LocalSyncConflictFile[]) => { currentFiles = value } }
}
export function pageProps(path = '/tasks/task') {
  const guards = new Set<W2LeaveGuard>(), retained = new Map<object, () => void>(), children = new Set<TaskChildOwner>()
  const legacy: W2PageProps['legacy']['task'] = { getSnapshot: () => ({ usingDemo: false, projects: [], tasks: [], taskItems: [], taskFacets: {}, loading: false }), subscribe: () => () => {}, loadProjects: vi.fn(async () => []), loadTaskSummaries: vi.fn(async () => {}), invalidateTaskSummaries: vi.fn(), setTaskArchived: vi.fn(async () => {}), deleteArchivedTask: vi.fn(async () => {}), refreshRuntime: vi.fn(async () => undefined), startRuntime: vi.fn(async () => undefined), restartRuntime: vi.fn(async () => undefined), activateDemo: vi.fn(), deactivateDemo: vi.fn(async () => {}), replaceProject: vi.fn(), addProject: vi.fn(), removeProject: vi.fn() }
  const props: W2PageProps = { route: { path, fullPath: path, params: { id: 'task' }, query: {} }, legacy: { task: legacy }, skin: skins[0]!, setSkin: vi.fn(), navigation: { go: vi.fn(async () => true), goAccepted: vi.fn(async () => true), back: vi.fn(), guardChanged: vi.fn(), registerGuard: read => { guards.add(read); return () => { guards.delete(read) } } }, lifecycle: { retain: (key, dispose) => { if (!retained.has(key)) retained.set(key, dispose) } } }
  const parent: TaskParentPort = { registerChild: owner => { children.add(owner); return () => { children.delete(owner) } }, canStartWrite: caller => [...children].every(owner => owner === caller ? owner.canLeave().kind !== 'BLOCK' : owner.canLeave().kind === 'ALLOW'), refresh: vi.fn(async () => task()) }
  return { props, parent, children, leave: () => [...guards].map(read => read()), retire: () => { for (const dispose of retained.values()) dispose(); retained.clear() } }
}
