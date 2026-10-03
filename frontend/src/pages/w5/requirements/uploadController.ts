import { workflowDocuments } from '@/api/workflowDocuments'
import type { WorkflowFile, WorkflowUpload, WorkflowUploadRequest } from '@/types/domain'
import { createResourceScope } from '@/foundation/contracts/resource'
import { identifyFiles, documentUploadError } from '@/pages/w3/templates/catalog/creation'
import { createRequirementScope, ownedState, requireIdentity } from './core'

/** Native files live outside DTO projections. The frozen operation owns their exact instances and order. */
type FileIdentity = Awaited<ReturnType<typeof identifyFiles>>[number]
export function createUploadController(requirement: string, initial: { version: number; revision: number; value: string }, onChange: (value: string) => void) {
  let context = initial, files: readonly File[] = [], identities: FileIdentity[] = [], metadata: WorkflowUploadRequest | null = null, resumed: WorkflowUpload | null = null
  const owner = createRequirementScope('requirement-documents', requirement, { ...ownedState(), chosen: null as WorkflowUpload | null, history: [] as WorkflowUpload[], cursor: null as string | null, fileNames: [] as string[], hashing: false,
    paths: [] as WorkflowFile[], pathCursor: null as string | null, preview: '', previewPath: '', nextOffset: null as number | null,
  }, state => state.hashing ? { kind: 'BLOCK', reason: '正在核对原文档字节，请等待后再离开。', recoveryAction: '等待文档核对' } : state.dirty ? { kind: 'CONFIRM_DISCARD', description: '原文档尚未上传，离开会丢失本地文件，仍要离开？', draftRevision: state.draftRevision } : { kind: 'ALLOW' })
  async function chooseFiles(incoming: readonly File[]) {
    if (owner.locked() || !owner.active() || owner.getSnapshot().hashing) return false
    const invalid = documentUploadError(incoming); if (invalid) { owner.fail(new Error(invalid)); return false }
    const ticket = owner.ticket('hash'), resources = createResourceScope(owner.identity), release = owner.own(() => resources.dispose()); owner.patch({ hashing: true })
    try {
      const selected = Array.from(incoming), observed = await identifyFiles(selected, resources)
      if (!ticket.current()) return false
      if (resumed && (resumed.originals.length !== observed.length || resumed.originals.some((original, index) => original.filename !== selected[index]!.name || original.sha256 !== observed[index]!.sha256 || original.sizeBytes !== selected[index]!.size))) throw new Error('补传文件与原文档的字节或顺序不一致，请重新选择全部原文件。')
      files = Object.freeze(selected); identities = observed
      if (!metadata) metadata = { requestKey: crypto.randomUUID(), expectedVersion: context.version, expectedRevision: context.revision }
      owner.edit({ fileNames: selected.map(file => file.name), error: '' }); return true
    } catch (cause) { if (ticket.current()) owner.fail(cause, '原文档无法核对，请重新选择。'); return false }
    finally { release(); if (ticket.current()) owner.patch({ hashing: false }) }
  }
  async function upload() {
    if (!metadata || !files.length || owner.locked() || owner.getSnapshot().hashing || !owner.active()) return
    const body = metadata, originalFiles = files, expectedFiles = [...identities]
    await owner.mutate({ label: '上传并选用文档', input: { endpoint: `/workflows/requirements/${encodeURIComponent(requirement)}/documents`, method: 'POST', requestKey: body.requestKey, body, files: originalFiles, versions: { version: body.expectedVersion, revision: body.expectedRevision } }, write: (original, retained) => workflowDocuments.upload(requirement, original, [...retained]),
      read: async (receipt, read) => {
        if (!receipt.id) throw new Error('上传回执缺少可核对身份，请保留原文件。')
        // Normal POST returns ready:true. A known but incomplete receipt can only be
        // recovered with the existing upload GET; it never authorizes a second POST.
        const value = receipt.ready ? receipt : await workflowDocuments.get(requirement, receipt.id)
        if (!read.isCurrent()) return
        requireIdentity(value.id, receipt.id, '上传记录')
        if (!value.ready || value.reference.uploadId !== value.id || value.reference.type !== 'UPLOADED_DOCUMENTS' || value.originals.length !== expectedFiles.length || value.originals.some((file, index) => file.filename !== originalFiles[index]!.name || file.sizeBytes !== originalFiles[index]!.size || file.sha256 !== expectedFiles[index]!.sha256)) throw new Error('原文件尚未完整核对，请保留原上传并读取结果。')
        read.apply(() => { owner.patch({ chosen: value, fileNames: [], paths: [], preview: '', dirty: false, error: '' }); metadata = null; resumed = null; files = []; identities = []; onChange(JSON.stringify(value.reference)) })
      },
    })
  }
  async function select(value: WorkflowUpload) {
    if (!owner.active() || !value.ready || owner.canLeave().kind !== 'ALLOW') return
    owner.patch({ chosen: value, paths: [], preview: '', error: '' }); onChange(JSON.stringify(value.reference))
  }
  async function load(more = false) {
    if (!owner.active()) return
    const ticket = owner.ticket('uploads'); owner.patch({ loading: true })
    try { const page = await workflowDocuments.list(requirement, more ? owner.getSnapshot().cursor ?? '' : ''); if (ticket.current()) owner.patch({ history: more ? [...owner.getSnapshot().history, ...page.items] : page.items, cursor: page.nextCursor ?? null, error: '' }) }
    catch (cause) { if (ticket.current()) owner.fail(cause, '已上传资料无法读取，请重试。') }
    finally { if (ticket.current()) owner.patch({ loading: false }) }
  }
  async function readSelected() {
    if (!owner.active()) return
    let id = ''; try { id = String(JSON.parse(context.value || '{}').uploadId || '') } catch { return }
    if (!id || owner.locked()) return
    const ticket = owner.ticket('chosen-upload')
    try { const chosen = await workflowDocuments.get(requirement, id); if (ticket.current()) { requireIdentity(chosen.id, id, '已选资料'); owner.patch({ chosen }) } }
    catch (cause) { if (ticket.current()) owner.fail(cause, '已选文档无法读取，请重新选择。') }
  }
  async function loadFiles(more = false) {
    if (!owner.active()) return
    const selected = owner.getSnapshot().chosen; if (!selected) return
    const ticket = owner.ticket('upload-files'); owner.patch({ loading: true })
    try { const page = await workflowDocuments.files(requirement, selected.id, more ? owner.getSnapshot().pathCursor ?? '' : ''); if (ticket.current() && owner.getSnapshot().chosen?.id === selected.id) owner.patch({ paths: more ? [...owner.getSnapshot().paths, ...page.items] : page.items, pathCursor: page.nextCursor ?? null }) }
    catch (cause) { if (ticket.current()) owner.fail(cause) }
    finally { if (ticket.current()) owner.patch({ loading: false }) }
  }
  async function read(path: string, more = false) {
    if (!owner.active()) return
    const selected = owner.getSnapshot().chosen; if (!selected) return
    const ticket = owner.ticket('upload-text'), offset = more ? owner.getSnapshot().nextOffset ?? 0 : 0; owner.patch({ loading: true })
    try { const result = await workflowDocuments.text(requirement, selected.id, path, offset); if (ticket.current() && owner.getSnapshot().chosen?.id === selected.id) { if (result.nextOffset !== null && (!Number.isInteger(result.nextOffset) || result.nextOffset <= offset) || more && owner.getSnapshot().previewPath !== path) throw new Error('解析正文分页与原文档不一致，请重新读取原章节。'); owner.patch({ preview: more ? owner.getSnapshot().preview + result.text : result.text, previewPath: path, nextOffset: result.nextOffset }) } }
    catch (cause) { if (ticket.current()) owner.fail(cause) }
    finally { if (ticket.current()) owner.patch({ loading: false }) }
  }
  owner.setStart(() => { void readSelected() })
  return Object.assign(owner, { requirement, chooseFiles, upload, select, load, readSelected, loadFiles, read,
    originalFiles: () => files, originalMetadata: () => metadata,
    updateContext(next: typeof initial) { const changed = next.value !== context.value; context = next; if (changed && owner.active() && !owner.locked()) void readSelected() },
    resume(row: WorkflowUpload) { if (!row.resume || owner.canLeave().kind !== 'ALLOW') return; metadata = { ...row.resume }; resumed = row; files = []; identities = []; owner.edit({ fileNames: [], error: '请按原顺序重新选择这次上传的全部原文件，然后点击上传。' }) },
    discard() { if (owner.locked() || owner.getSnapshot().hashing) return; files = []; identities = []; metadata = null; resumed = null; owner.patch({ fileNames: [], dirty: false, draftRevision: owner.getSnapshot().draftRevision + 1 }) },
    discardDraft() { this.discard() },
    clearSelection() { if (!owner.locked()) { owner.patch({ chosen: null }); onChange('') } },
  })
}
export type UploadController = ReturnType<typeof createUploadController>
