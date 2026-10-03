import { api, ApiError } from '@/api/client'
import { createResourceScope, OwnedResourceCleanupError } from '@/foundation/contracts/resource'
import type { ResourceScope } from '@/foundation/contracts/types'
import { captureDto } from '@/foundation/contracts/immutable'
import { createSnapshotController } from '@/foundation/contracts/controller'
import { createOperationOwner, type AcceptedHandoff, type OperationOwner } from '@/foundation/contracts/receipt'
import type { NavigationRequest } from '@/foundation/contracts/navigation'
import type { ReceiptPhase } from '@/foundation/contracts/types'
import { userFacingError } from '@/utils/displayLabels'
import type { DocumentTemplateRequest, SourceTemplateRequest, TemplateTaskRequest } from '@/types/domain'

export type CreationKind = 'report' | 'source' | 'document'
export type CreationInput = Omit<TemplateTaskRequest, 'requestKey'> | Omit<SourceTemplateRequest, 'requestKey'> | Omit<DocumentTemplateRequest, 'requestKey'>
type Request = TemplateTaskRequest | SourceTemplateRequest | DocumentTemplateRequest
type Created = { id: string; state?: string }
type FileIdentity = { name: string; sha256: string }
interface Saved { request: Request; fingerprint: string; completed?: boolean; runId?: string; taskId?: string; fileIdentities?: FileIdentity[] }
export interface CreationState {
  phase: ReceiptPhase; busy: boolean; hashing: boolean; error: string
  knownId: string; destination: string; canRead: boolean; canRetry: boolean; needsFiles: boolean
  startUnknown: boolean; startAllowed: boolean; fileIdentities: FileIdentity[]
}
const storageKeys = { report: 'loopper.template-task-request.v1', source: 'loopper.source-template-request.v1', document: 'loopper.document-template-upload.v1' }
let epoch = 0
function storageDefault(): Storage | undefined { try { return sessionStorage } catch { return undefined } }
export function documentUploadError(files: readonly File[]): string {
  if (!files.length || files.length > 10) return '请上传 1–10 份需求文档'
  if (files.some(file => !/\.(docx|md|markdown|pdf)$/i.test(file.name))) return '支持 DOCX、Markdown 和文本 PDF，请转换旧 DOC 或其他格式'
  if (files.some(file => !file.size || file.size > 20 * 1024 * 1024)) return '每份文档不能为空或超过 20 MiB'
  if (files.reduce((sum, file) => sum + file.size, 0) > 50 * 1024 * 1024) return '文档总大小不能超过 50 MiB'
  return ''
}
export async function fileBytes(file: File, scope?: ResourceScope): Promise<ArrayBuffer> {
  if (typeof file.arrayBuffer === 'function') return file.arrayBuffer()
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    let finished = false, release: (() => void) | undefined
    function finish(error?: unknown) { if (finished) return; finished = true; const result = reader.result; reader.onload = null; reader.onerror = null; reader.onabort = null; release?.(); if (error) reject(error); else resolve(result as ArrayBuffer) }
    reader.onload = () => finish()
    reader.onerror = () => finish(reader.error ?? new Error('文档读取失败，请重新选择原文档'))
    reader.onabort = () => finish(new Error('原文档读取已取消，未发起写入'))
    release = scope?.own(() => {
      if (finished) return
      finished = true; reader.onload = null; reader.onerror = null; reader.onabort = null
      try { if (reader.readyState === 1) reader.abort() } finally { reject(new Error('原文档读取已取消，未发起写入')) }
    })
    if (!finished) reader.readAsArrayBuffer(file)
  })
}
export async function identifyFiles(files: readonly File[], scope?: ResourceScope): Promise<FileIdentity[]> {
  const result: FileIdentity[] = []
  for (const file of files) {
    const digest = await crypto.subtle.digest('SHA-256', await fileBytes(file, scope))
    result.push({ name: file.name, sha256: Array.from(new Uint8Array(digest), value => value.toString(16).padStart(2, '0')).join('') })
  }
  return result
}
function definitive(failure: unknown) { return failure instanceof ApiError && [400, 401, 403, 422].includes(failure.status) }

/** One creation identity, including hash preparation and the separate keyless Task Start. */
export function createTemplateCreationController(kind: CreationKind, options: { storage?: Storage | null } = {}) {
  const storage = options.storage === null ? undefined : options.storage ?? storageDefault()
  const initial: CreationState = { phase: 'IDLE', busy: false, hashing: false, error: '', knownId: '', destination: '', canRead: false, canRetry: false, needsFiles: false, startUnknown: false, startAllowed: false, fileIdentities: [] }
  let metadata: Saved | undefined
  try {
    const value = JSON.parse(storage?.getItem(storageKeys[kind]) ?? 'null') as Saved | null
    if (value && typeof value.fingerprint === 'string' && /^[A-Za-z0-9_-]{16,100}$/.test(value.request?.requestKey)) metadata = value
  } catch { /* Optional metadata never replaces the in-memory operation. */ }
  let operation: OperationOwner<Request, Created> | undefined, originalFiles: readonly File[] = []
  let hashingResources: ResourceScope | undefined
  let preparing = false, startBusy = false, startUnknown = false, startAllowed = false, handedOff = false
  let pendingDestination = '', knownId = metadata?.taskId ?? metadata?.runId ?? ''
  const owner = createSnapshotController({ identity: { domain: `template-create-${kind}`, id: kind, epoch: ++epoch }, initial,
    canLeave: () => preparing || startBusy || startUnknown || metadata && !handedOff
      ? { kind: 'BLOCK', reason: '创建或开始结果尚未交接，请先等待、核对或恢复原操作', recoveryAction: '恢复原操作' }
      : { kind: 'ALLOW' },
  })
  function persist() { if (metadata) try { storage?.setItem(storageKeys[kind], JSON.stringify(metadata)) } catch { /* Keep identity and File instances in memory. */ } }
  function publish(error = owner.getSnapshot().error) {
    const current = operation?.getSnapshot()
    owner.project({ phase: preparing || startBusy ? 'SENDING' : startUnknown ? 'UNKNOWN' : current?.phase === 'SETTLED' && metadata && !handedOff ? 'ACCEPTED_READBACK' : current?.phase ?? (metadata && !handedOff ? 'UNKNOWN' : 'IDLE'),
      busy: preparing || startBusy || !!current?.busy, hashing: preparing, error: current?.error ? userFacingError(current.error, '创建结果尚未确认，请恢复原操作') : error,
      knownId, destination: pendingDestination, startUnknown, startAllowed,
      canRead: !preparing && !startBusy && (!!knownId || kind === 'document' && !!metadata || current?.recovery.kind === 'READ_ORIGINAL'),
      canRetry: !preparing && !startBusy && !knownId && !!metadata && (!current || current.phase === 'UNKNOWN'),
      needsFiles: kind === 'document' && !!metadata && !knownId && !originalFiles.length,
      fileIdentities: metadata?.fileIdentities ?? [] })
  }
  function fail(failure: unknown) { publish(userFacingError(failure, '操作未完成，请保留原输入并核对结果')) }
  function destination(id: string) { return kind === 'report' ? `/tasks/${encodeURIComponent(id)}` : `/template-tasks/${kind}-runs/${encodeURIComponent(id)}` }
  function makeOperation(request: Request) {
    const files = originalFiles
    const next = createOperationOwner<Request, Created>({ owner: owner.identity, label: '创建模板任务',
      input: { endpoint: kind === 'report' ? '/template-tasks' : `/template-tasks/${kind}-runs`, method: 'POST', body: request, requestKey: request.requestKey,
        versions: { templateVersion: request.templateVersion }, files },
      capability: { kind: 'IDEMPOTENT_KEY', ...(kind === 'document' ? { readOriginal: async () => {
        try { return { kind: 'ACCEPTED' as const, receipt: await api.documentTemplateRequest(request.requestKey) } }
        catch (failure) { if (failure instanceof ApiError && failure.status === 404) return { kind: 'UNCONFIRMED' as const }; throw failure }
      } } : {}) },
      write: identity => kind === 'report' ? api.createTemplateTask(identity.body as TemplateTaskRequest)
        : kind === 'source' ? api.createSourceTemplate(identity.body as SourceTemplateRequest)
          : api.createDocumentTemplate(identity.body as DocumentTemplateRequest, [...identity.files]),
      read: async (receipt, context) => {
        if (!receipt.id) throw new Error('已接受创建，但回执缺少任务，请先核对原结果')
        context.apply(() => { knownId = receipt.id; metadata!.runId = kind === 'report' ? undefined : receipt.id; metadata!.taskId = kind === 'report' ? receipt.id : undefined; metadata!.completed = true; persist(); publish('') })
        const read = kind === 'report' ? await api.getTask(receipt.id) : kind === 'source' ? await api.sourceTemplate(receipt.id) : await api.documentTemplate(receipt.id)
        if (!read || read.id !== receipt.id) throw new Error('已创建任务的读取结果不匹配，请只重新读取原任务')
        context.apply(() => { pendingDestination = destination(receipt.id); if (kind === 'report') startAllowed = 'status' in read && read.status === 'PENDING_START'; publish('') })
      },
      handoffTarget: receipt => destination(receipt.id), isDefinitiveRejection: definitive,
    })
    if (!owner.ownOperation(next)) { next.retire(true); return undefined }
    operation = next; next.subscribe(() => publish()); return next
  }
  async function startKnownTask() {
    if (kind !== 'report' || !knownId || startBusy || !owner.capture().isCurrent()) return
    const token = owner.capture(), id = knownId
    startBusy = true; publish('')
    try {
      const result = await api.startTemplateTask(id)
      if (!token.isCurrent()) return
      if (result?.id !== id) throw new Error('开始结果不匹配，请读取原任务核对')
      startUnknown = false; startAllowed = false; pendingDestination = destination(id); publish('')
    } catch (failure) { if (token.isCurrent()) { startUnknown = !definitive(failure); startAllowed = false; fail(failure) } }
    finally { if (token.isCurrent()) { startBusy = false; publish() } }
  }
  async function readKnownTask() {
    const token = owner.capture(), id = knownId
    const task = await api.getTask(id)
    if (!token.isCurrent()) return
    if (!task || task.id !== id) throw new Error('原任务读取结果不匹配，请保留任务身份')
    startAllowed = task.status === 'PENDING_START'
    // An authoritative state read enables an explicit lifecycle action, never an automatic POST.
    startUnknown = false; pendingDestination = destination(id); publish('')
  }
  async function recover() {
    if (!owner.capture().isCurrent() || owner.getSnapshot().busy) return
    try {
      if (kind === 'report' && knownId) {
        if (operation?.getSnapshot().phase === 'ACCEPTED_READBACK') await operation.retryReadback()
        else await readKnownTask()
      } else if (knownId && !operation) {
        const token = owner.capture(), id = knownId, run = kind === 'source' ? await api.sourceTemplate(id) : await api.documentTemplate(id)
        if (!token.isCurrent()) return
        if (run.id !== id) throw new Error('原运行记录身份不匹配，请保留原请求')
        pendingDestination = destination(id); metadata!.completed = true; persist(); publish('')
      } else if (operation) {
        if (operation.getSnapshot().accepted) await operation.retryReadback()
        else await operation.readOriginal()
      } else if (kind === 'document' && metadata) {
        const token = owner.capture(), run = await api.documentTemplateRequest(metadata.request.requestKey)
        if (!token.isCurrent()) return
        if (!run?.id) throw new Error('原创建结果缺少运行记录，请保留原请求')
        knownId = run.id; pendingDestination = destination(run.id); metadata.completed = true; metadata.runId = run.id; persist(); publish('')
      }
    } catch (failure) { if (!(failure instanceof ApiError && failure.status === 404)) fail(failure) }
    publish()
  }
  async function start(input: CreationInput, files: readonly File[] = []): Promise<string | undefined> {
    if (!owner.capture().isCurrent() || preparing || startBusy || operation?.getSnapshot().busy) return undefined
    if (knownId) {
      return kind === 'report' ? startOriginal() : owner.getSnapshot().error ? undefined : knownId
    }
    const token = owner.capture()
    if (kind === 'document') {
      const invalid = documentUploadError(files); if (invalid) { fail(new Error(invalid)); return undefined }
      preparing = true; publish('')
      const resources = createResourceScope(owner.identity); hashingResources = resources
      try {
        const identities = await identifyFiles(files, resources)
        if (!token.isCurrent()) return undefined
        const fingerprint = JSON.stringify({ input, files: identities })
        if (metadata && !metadata.completed && metadata.fingerprint !== fingerprint) { fail(new Error('原上传结果尚未确认，请重新选择原文档，保持字节与顺序')); return undefined }
        if (!metadata) metadata = { fingerprint, request: { ...input, requestKey: crypto.randomUUID() } as DocumentTemplateRequest, fileIdentities: identities }
        else metadata.fileIdentities = identities
        if (!originalFiles.length) originalFiles = Object.freeze([...files])
      } catch (failure) { if (token.isCurrent()) fail(failure); return undefined }
      finally { resources.dispose(); if (hashingResources === resources) hashingResources = undefined; if (token.isCurrent()) { preparing = false; publish() } }
    } else if (!metadata) metadata = { fingerprint: JSON.stringify(input), request: { ...input, requestKey: crypto.randomUUID() } as Request }
    if (!token.isCurrent() || !metadata) return undefined
    persist()
    const next = operation ?? makeOperation(metadata.request)
    if (!next) return undefined
    try { if (next.getSnapshot().phase === 'IDLE') await next.execute(); else if (!next.getSnapshot().accepted) await next.recoverWrite(); else await next.retryReadback() }
    catch (failure) {
      const failed = next.getSnapshot()
      if (!failed.accepted && failed.phase === 'SETTLED' && token.isCurrent()) {
        next.retire(true); operation = undefined; metadata = undefined; originalFiles = [];
        try { storage?.removeItem(storageKeys[kind]) } catch { /* A known rejection remains safe in memory. */ }
      }
      fail(failure); return undefined
    }
    if (!token.isCurrent()) return undefined
    if (kind === 'report' && startAllowed) await startKnownTask()
    return owner.getSnapshot().error ? undefined : knownId || undefined
  }
  async function retryOriginal(): Promise<string | undefined> {
    if (!metadata || !owner.capture().isCurrent()) return undefined
    if (kind === 'document' && !originalFiles.length) { fail(new Error('请选择同一批原文档，保持文件字节与顺序后恢复')); return undefined }
    const body = { ...metadata.request }; delete (body as Partial<Request>).requestKey
    return start(body, originalFiles)
  }
  async function startOriginal(): Promise<string | undefined> {
    if (kind !== 'report' || !knownId || !owner.capture().isCurrent() || preparing || startBusy || operation?.getSnapshot().busy) return undefined
    if (startUnknown || startAllowed) { await recover(); if (startAllowed) await startKnownTask() }
    return owner.getSnapshot().error ? undefined : knownId
  }
  function canLeave(request?: NavigationRequest) {
    if (request && pendingDestination === request.destination && !startBusy && !startUnknown
      && (operation ? !!request.handoff && operation.leaveRisk().permitsHandoff(request.handoff, request.destination) : !!knownId && !!metadata?.completed)) return { kind: 'ALLOW' as const }
    return owner.canLeave(request)
  }
  publish()
  return { ...owner, retire(forced = false) { const decision = canLeave(); if (!forced && decision.kind !== 'ALLOW') return decision; const failures: unknown[] = []; try { owner.retire(forced) } catch (failure) { failures.push(failure) }; try { hashingResources?.dispose() } catch (failure) { failures.push(failure) }; hashingResources = undefined; if (failures.length) throw new OwnedResourceCleanupError(failures); return {kind:'ALLOW' as const} }, canLeave, start, startOriginal, recover, retryOriginal, restore: recover,
    async resumeWithFiles(files: readonly File[]) { if (!metadata) return undefined; const input = { ...metadata.request }; delete (input as Partial<Request>).requestKey; return start(input, files) },
    originalIdentity: () => operation?.identity ?? (metadata ? captureDto(metadata.request) : undefined), originalFiles: () => originalFiles,
    prepareHandoff(): AcceptedHandoff | undefined {
      if (!pendingDestination || startBusy || startUnknown) throw new Error('原操作尚未安全交接，请先核对结果')
      if (!operation) return undefined // An actual by-request GET proves this exact read-only destination, not a forged write receipt.
      return operation.prepareHandoff()
    },
    completeHandoff() { handedOff = true; try { const saved = JSON.parse(storage?.getItem(storageKeys[kind]) ?? 'null') as Saved | null; if (saved?.request.requestKey === metadata?.request.requestKey) storage?.removeItem(storageKeys[kind]) } catch { /* Stale metadata only permits original-result reads. */ } publish('') },
  }
}
export const createReportCreationController = (options?: Parameters<typeof createTemplateCreationController>[1]) => createTemplateCreationController('report', options)
export const createSourceCreationController = (options?: Parameters<typeof createTemplateCreationController>[1]) => createTemplateCreationController('source', options)
export const createDocumentCreationController = (options?: Parameters<typeof createTemplateCreationController>[1]) => createTemplateCreationController('document', options)
