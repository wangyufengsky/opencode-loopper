import { ref } from 'vue'
import { defineStore } from 'pinia'
import { api } from '@/api/client'
import type { TemplateTaskCatalog, TemplateTaskRequest, TemplateTaskSummary } from '@/types/domain'

export const useTemplateTaskStore = defineStore('templateTasks', () => {
  const catalog = ref<TemplateTaskCatalog>()
  const runs = ref<TemplateTaskSummary[]>([])
  const nextCursor = ref<string | null>()
  const loading = ref(false)
  const submitting = ref(false)
  let generation = 0
  let pending: { fingerprint: string; request: TemplateTaskRequest; taskId?: string } | undefined

  async function loadCatalog() { catalog.value = await api.templateCatalog() }
  async function loadRuns(projectId = '', append = false) {
    const current = ++generation
    loading.value = true
    try {
      const page = await api.templateRuns(projectId, append ? nextCursor.value ?? undefined : undefined)
      if (current !== generation) return
      runs.value = append ? [...runs.value, ...page.items] : page.items
      nextCursor.value = page.nextCursor
    } finally { if (current === generation) loading.value = false }
  }
  async function start(input: Omit<TemplateTaskRequest, 'requestKey'>) {
    if (submitting.value) return undefined
    const fingerprint = JSON.stringify(input)
    if (!pending || pending.fingerprint !== fingerprint) pending = { fingerprint, request: { ...input, requestKey: crypto.randomUUID() } }
    const operation = pending
    submitting.value = true
    try {
      const created = operation.taskId ? { id: operation.taskId } : await api.createTemplateTask(operation.request)
      operation.taskId = created.id
      await api.startTemplateTask(created.id)
      pending = undefined
      return created.id
    } finally { submitting.value = false }
  }
  return { catalog, runs, nextCursor, loading, submitting, loadCatalog, loadRuns, start }
})
