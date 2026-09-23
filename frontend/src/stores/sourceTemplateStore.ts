import { ref } from 'vue'
import { defineStore } from 'pinia'
import { api } from '@/api/client'
import type { SourceTemplateRequest } from '@/types/domain'

const storageKey = 'loopper.source-template-request.v1'
interface Pending { fingerprint: string; request: SourceTemplateRequest; completed?: boolean }
function load(): Pending | undefined {
  try {
    const saved = JSON.parse(sessionStorage.getItem(storageKey) || 'null') as Pending | null
    if (saved && typeof saved.fingerprint === 'string' && /^[A-Za-z0-9_-]{16,100}$/.test(saved.request?.requestKey)) return saved
  } catch { /* The in-memory request remains usable without browser storage. */ }
}
export const useSourceTemplateStore = defineStore('sourceTemplates', () => {
  const submitting = ref(false)
  let pending = load()
  function persist() { try { sessionStorage.setItem(storageKey, JSON.stringify(pending)) } catch { /* Optional storage. */ } }
  async function create(input: Omit<SourceTemplateRequest, 'requestKey'>) {
    if (submitting.value) return
    submitting.value = true
    try {
      const fingerprint = JSON.stringify(input)
      if (!pending || pending.completed || pending.fingerprint !== fingerprint)
        pending = { fingerprint, request: { ...input, requestKey: crypto.randomUUID() } }
      const operation = pending; persist()
      const result = await api.createSourceTemplate(operation.request)
      operation.completed = true; persist()
      return result.id
    } finally { submitting.value = false }
  }
  return { submitting, create }
})
