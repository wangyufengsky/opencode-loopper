import { ref } from 'vue'
import { defineStore } from 'pinia'
import { api, ApiError } from '@/api/client'
import type { DocumentTemplateOverview, DocumentTemplateRequest } from '@/types/domain'

const storageKey = 'loopper.document-template-upload.v1'
interface Pending { fingerprint: string; request: DocumentTemplateRequest; runId?: string; completed?: boolean }
export function documentUploadError(files: File[]): string {
  if (!files.length || files.length > 10) return '请上传 1–10 份需求文档'
  if (files.some(file => !/\.(docx|md|markdown|pdf)$/i.test(file.name))) return '支持 DOCX、Markdown 和文本 PDF，请转换旧 DOC 或其他格式'
  if (files.some(file => !file.size || file.size > 20 * 1024 * 1024)) return '每份文档不能为空或超过 20 MiB'
  if (files.reduce((sum, file) => sum + file.size, 0) > 50 * 1024 * 1024) return '文档总大小不能超过 50 MiB'
  return ''
}
function load(): Pending | undefined {
  try {
    const value = JSON.parse(sessionStorage.getItem(storageKey) || 'null') as Pending | null
    if (value && typeof value.fingerprint === 'string' && /^[A-Za-z0-9_-]{16,100}$/.test(value.request?.requestKey)) return value
  } catch { /* Browser storage is optional; the in-memory identity still prevents duplicate clicks. */ }
}
function persist(value: Pending) { try { sessionStorage.setItem(storageKey, JSON.stringify(value)) } catch { /* Keep the same in-memory request. */ } }
export const useDocumentTemplateStore = defineStore('documentTemplates', () => {
  const submitting = ref(false)
  const previousRun = ref<DocumentTemplateOverview>()
  let pending = load()
  async function restore() {
    if (!pending) return
    try { previousRun.value = await api.documentTemplateRequest(pending.request.requestKey) }
    catch (failure) { if (!(failure instanceof ApiError && failure.status === 404)) throw failure }
  }
  async function start(input: Omit<DocumentTemplateRequest, 'requestKey'>, files: File[]) {
    if (submitting.value) return
    const invalid = documentUploadError(files)
    if (invalid) throw new Error(invalid)
    submitting.value = true
    try {
      const identities = []
      for (const file of files) {
        const hash = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
        identities.push({ name: file.name, sha256: Array.from(new Uint8Array(hash), value => value.toString(16).padStart(2, '0')).join('') })
      }
      const fingerprint = JSON.stringify({ input, files: identities })
      if (!pending || pending.completed || pending.fingerprint !== fingerprint)
        pending = { fingerprint, request: { ...input, requestKey: crypto.randomUUID() } }
      const operation = pending
      persist(operation)
      const run = await api.createDocumentTemplate(operation.request, files)
      operation.runId = run.id; operation.completed = true; persist(operation)
      previousRun.value = run
      return run.id
    } finally { submitting.value = false }
  }
  return { submitting, previousRun, start, restore }
})
