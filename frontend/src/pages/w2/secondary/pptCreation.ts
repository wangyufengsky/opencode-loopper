import { ApiError } from '@/api/client'
import type { pptApi } from '@/api/ppt'
import type { PptMessage, PptProjectChoice } from '@/types/domain'
import { createOperationOwner, type OperationOwner } from '@/foundation/contracts/receipt'
import type { NavigationRequest } from '@/foundation/contracts/navigation'
import type { LeaveDecision } from '@/foundation/contracts/types'
import type { W2Navigation } from '../shared/types'
import type { PptCreationFile } from './ports'
import { userFacingError } from '@/utils/displayLabels'

type CreationApi = Pick<typeof pptApi, 'create' | 'get' | 'upload' | 'send' | 'messages'>
type InputFile = PptCreationFile & { sha256?: string }
type SendBody = Parameters<CreationApi['send']>[1]
type Draft = { prompt: string; project: PptProjectChoice | null; files: InputFile[]; creationId: string; documentId: string; generationKey: string; generationRevision?: number; sentAccepted?: boolean }
const storageKey = 'loopper.ppt.creation.v2'
export function pptTitleFromPrompt(prompt: string) {
  const topic = prompt.match(/主题(?:是|为)?\s*[：:]?\s*(?:[“"'《]([^”"'》\n]+)[”"'》]|([^，,。.!！;；\n]+))/)
  const title = (topic?.[1] || topic?.[2] || prompt.trim().split(/[。！？!?\n]/)[0] || '未命名演示').trim().replace(/\s+/g, ' ')
  return Array.from(title).slice(0, 24).join('')
}
async function fingerprint(file: File) {
  const bytes = typeof file.arrayBuffer === 'function' ? await file.arrayBuffer() : await new Promise<ArrayBuffer>((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(reader.result as ArrayBuffer); reader.onerror = () => reject(new Error('附件读取失败，请重新选择原附件')); reader.readAsArrayBuffer(file) })
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), byte => byte.toString(16).padStart(2, '0')).join('')
}
export function createPptCreation(api: CreationApi, navigation: W2Navigation, storage: Pick<Storage, 'getItem' | 'setItem'> = sessionStorage) {
  const empty = (): Draft => ({ prompt: '', project: null, files: [], creationId: '', documentId: '', generationKey: '' })
  let draft = empty(), busy = false, detail = '', error = '', unknown = false, retired = false, revision = 0
  let readonlyRecoveredDestination = ''
  let send: OperationOwner<SendBody, PptMessage> | undefined, detachSend: (() => void) | undefined
  const listeners = new Set<() => void>(), identity = Object.freeze({ domain: 'ppt-creation', id: crypto.randomUUID(), epoch: 0 })
  try {
    const saved = JSON.parse(storage.getItem(storageKey) || 'null') as Partial<Draft> | null
    if (saved && typeof saved.prompt === 'string' && Array.isArray(saved.files)) {
      draft = { ...empty(), ...saved, files: saved.files.map(file => ({ ...file, file: undefined })) }
      unknown = !!draft.creationId
    }
  } catch { /* A malformed recovery copy never starts a command. */ }
  let snapshot = capture()
  function capture() { return Object.freeze({ ...draft, files: [...draft.files], busy, locked: !!draft.creationId, detail, error, unknown, phase: send?.getSnapshot().phase ?? (busy ? 'SENDING' : unknown ? 'UNKNOWN' : 'IDLE') }) }
  function publish() { snapshot = capture(); for (const listener of listeners) listener() }
  function persist() {
    if (!retired) { try { storage.setItem(storageKey, JSON.stringify({ ...draft, files: draft.files.map(({ file: _file, ...metadata }) => metadata) })) } catch { /* Original File objects remain in this owner; storage is best effort. */ } }
    publish()
  }
  function isCurrent() { return !retired }
  async function originalMessage(id: string, key: string, body: SendBody) {
    let cursor = ''; const visited = new Set<string>()
    do {
      const page = await api.messages(id, cursor)
      const message = page.items.find(item => item.idempotencyKey === key && item.text === body.text && item.expectedRevision === body.expectedRevision && item.scope.kind === body.scope.kind)
      if (message) return { kind: 'ACCEPTED' as const, receipt: message }
      cursor = page.nextCursor || ''
      if (visited.has(cursor)) break
      visited.add(cursor)
    } while (cursor && isCurrent())
    return { kind: 'UNCONFIRMED' as const }
  }
  const owner = {
    getSnapshot: () => snapshot,
    subscribe: (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener) } },
    canLeave(request?: NavigationRequest): LeaveDecision {
      if (request && readonlyRecoveredDestination && request.destination === readonlyRecoveredDestination) return { kind: 'ALLOW' }
      if (send && request && send.leaveRisk().permitsHandoff(request.handoff, request.destination)) return { kind: 'ALLOW' }
      if (busy || unknown || send && ['SENDING', 'UNKNOWN', 'ACCEPTED_READBACK'].includes(send.getSnapshot().phase)) return { kind: 'BLOCK', reason: '原制作要求尚未确认，请保留正文与附件并恢复同一操作', recoveryAction: '继续原要求' }
      return draft.prompt || draft.files.length ? { kind: 'CONFIRM_DISCARD', description: '制作要求或附件尚未交接，是否放弃后离开？', draftRevision: revision } : { kind: 'ALLOW' }
    },
    setPrompt(value: string) { if (retired || busy || draft.creationId) return; draft.prompt = value; revision++; persist() },
    setProject(value: PptProjectChoice | null) { if (retired || busy || draft.creationId) return; draft.project = value; revision++; persist() },
    removeFile(id: string) { if (retired || busy || draft.creationId) return; draft.files = draft.files.filter(file => file.id !== id); revision++; persist() },
    async addFiles(files: File[]) {
      if (retired || busy) return
      error = ''; busy = true; publish()
      try {
        for (const file of files) {
          if (file.size > 20 * 1024 * 1024) { error = '单个附件不能超过 20 MiB，请压缩后再添加。'; continue }
          if (!/\.(md|docx|xlsx|pptx|pdf|png|jpe?g)$/i.test(file.name)) { error = '支持 Word、Excel、PPT、PDF、Markdown 和 PNG/JPEG 图片。'; continue }
          const existing = draft.files.find(item => item.name === file.name && item.size === file.size && item.modified === file.lastModified)
          const sha256 = await fingerprint(file)
          if (!isCurrent()) return
          if (existing) {
            if (draft.creationId && (!existing.sha256 || existing.sha256 !== sha256)) { error = '无法证明重选文件与原附件字节相同。请保留原作品身份；旧恢复记录没有保存完整附件。'; continue }
            existing.file = file; existing.sha256 = sha256; if (!draft.creationId) revision++; continue
          }
          if (draft.creationId) { error = '请重新选择原附件，或核对已创建的作品。'; continue }
          const kind = /\.(png|jpe?g)$/i.test(file.name) ? 'assets' : 'sources', sources = draft.files.filter(item => item.kind === 'sources')
          if (kind === 'sources' && (sources.length >= 10 || sources.reduce((sum, item) => sum + item.size, 0) + file.size > 50 * 1024 * 1024)) { error = '资料最多 10 份，总计不超过 50 MiB。'; continue }
          draft.files.push({ id: crypto.randomUUID(), key: crypto.randomUUID(), name: file.name, size: file.size, modified: file.lastModified, kind, file, sha256, uploaded: false })
          revision++
        }
      } catch (failure) { if (isCurrent()) error = userFacingError(failure, '附件读取失败，请重新选择') }
      finally { if (isCurrent()) { busy = false; persist() } }
    },
    async submit() {
      if (retired || busy || !draft.prompt.trim()) return
      if (draft.files.some(file => !file.uploaded && !file.file)) { error = '请重新选择标记的原附件；旧记录不能恢复 File 字节。'; publish(); return }
      busy = true; error = ''; publish()
      try {
        draft.creationId ||= crypto.randomUUID(); draft.generationKey ||= crypto.randomUUID(); persist()
        if (!draft.documentId) {
          detail = '正在创建演示文稿'; publish()
          let found: Awaited<ReturnType<CreationApi['get']>> | undefined
          if (unknown) { try { found = await api.get(draft.creationId) } catch (failure) { if (!(failure instanceof ApiError && failure.status === 404)) throw failure } }
          if (!isCurrent()) return
          const document = found || await api.create({ id: draft.creationId, title: pptTitleFromPrompt(draft.prompt), ...(draft.project ? { projectId: draft.project.id } : {}) })
          draft.documentId = document.id; persist(); if (!isCurrent()) return
        }
        for (const [index, file] of draft.files.entries()) {
          if (file.uploaded) continue
          detail = `正在读取附件 ${index + 1} / ${draft.files.length}`; publish()
          const result = await api.upload(draft.documentId, file.file!, file.kind, file.key)
          if (result.state !== 'READY') throw new Error(result.detail || '附件尚未读取完成，请重试原附件。')
          file.uploaded = true; persist(); if (!isCurrent()) return
        }
        if (draft.generationRevision === undefined) { const document = await api.get(draft.documentId); draft.generationRevision = document.revision; persist(); if (!isCurrent()) return }
        if (draft.sentAccepted && !send) {
          // Refresh recovery is a real GET, not a second write or a simulated POST receipt.
          const found = await originalMessage(draft.documentId, draft.generationKey, { idempotencyKey: draft.generationKey, expectedRevision: draft.generationRevision, text: draft.prompt.trim(), scope: { kind: 'DOCUMENT' } })
          if (found.kind !== 'ACCEPTED') throw new Error('未读到与原正文、版本和身份一致的消息，请保留原作品并继续核对。')
          if (!isCurrent()) return
          const destination = `/ppt/${draft.documentId}`, originalId = draft.creationId
          readonlyRecoveredDestination = destination
          busy = false; publish()
          try {
            if (await navigation.go(destination)) {
              try { if (JSON.parse(storage.getItem(storageKey) || 'null')?.creationId === originalId) storage.setItem(storageKey, JSON.stringify(empty())) } catch { /* Keep the in-memory receipt if storage is unavailable. */ }
              draft = empty(); unknown = false; publish()
            } else { error = '原要求已核对，作品入口未打开。请保留原操作，仅重新打开作品。'; publish() }
          } finally { readonlyRecoveredDestination = '' }
          return
        }
        if (!send) {
          const id = draft.documentId, body: SendBody = { idempotencyKey: draft.generationKey, expectedRevision: draft.generationRevision, text: draft.prompt.trim(), scope: { kind: 'DOCUMENT' } }
          send = createOperationOwner({ owner: identity, label: 'PPT 首条制作要求', input: { endpoint: `/ppt/documents/${encodeURIComponent(id)}/messages`, method: 'POST', requestKey: body.idempotencyKey, body, files: draft.files.flatMap(file => file.file ? [file.file] : []) },
            capability: { kind: 'IDEMPOTENT_KEY', readOriginal: original => originalMessage(id, original.requestKey ?? body.idempotencyKey, original.body as SendBody) },
            write: original => api.send(id, original.body as SendBody), read: async () => {}, handoffTarget: () => `/ppt/${id}`,
            isDefinitiveRejection: failure => failure instanceof ApiError && failure.status >= 400 && failure.status < 500 && ![408, 429].includes(failure.status) })
          detachSend = send.subscribe(publish)
        }
        detail = '正在联系 PPT 助手'; publish()
        const phase = send.getSnapshot().phase
        if (phase === 'IDLE') await send.execute()
        else if (phase === 'UNKNOWN') await send.readOriginal().then(() => send!.getSnapshot().phase === 'UNKNOWN' && !draft.sentAccepted ? send!.recoverWrite() : undefined)
        else if (phase === 'ACCEPTED_READBACK') await send.retryReadback()
        if (!send.getSnapshot().accepted || !isCurrent()) return
        draft.sentAccepted = true; unknown = false; persist()
        const permit = send.prepareHandoff(), destination = `/ppt/${draft.documentId}`
        busy = false; publish()
        const originalId = draft.creationId
        if (await navigation.goAccepted(destination, permit)) {
          try { if (JSON.parse(storage.getItem(storageKey) || 'null')?.creationId === originalId) storage.setItem(storageKey, JSON.stringify(empty())) } catch { /* Storage may be unavailable. */ }
          draft = empty(); unknown = false; publish()
        }
        else if (isCurrent()) { error = '原要求已接受，作品入口未打开。请继续原操作，仅重新打开作品。'; publish() }
      } catch (failure) {
        const rejected = failure instanceof ApiError && failure.status >= 400 && failure.status < 500 && ![408, 429].includes(failure.status)
        unknown = !rejected
        if (isCurrent()) {
          if (rejected && !draft.documentId) { draft.creationId = ''; draft.generationKey = ''; draft.generationRevision = undefined }
          error = userFacingError(failure, '暂时未收到结果，正文和原附件已保留。请显式继续原要求。'); persist()
        }
      }
      finally { if (isCurrent()) { busy = false; detail = ''; publish() } }
    },
    retire() { if (retired) return; retired = true; send?.retire(true); detachSend?.(); listeners.clear() },
  }
  return owner
}
