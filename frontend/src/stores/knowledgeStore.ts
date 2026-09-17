import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { knowledgeApi as api } from '@/api/knowledge'
import type { KnowledgeConversation, KnowledgeMessage, KnowledgeCreate } from '@/types/domain'

export const useKnowledgeStore = defineStore('knowledge', () => {
  const conversation = ref<KnowledgeConversation | null>(null)
  const messages = ref<KnowledgeMessage[]>([])
  const pendingText = ref('')
  const error = ref(''), disconnected = ref(false), loading = ref(false), sending = ref(false)
  const nextCursor = ref<string | null>(null)
  let epoch = 0, subscription: EventSource | undefined, refreshing = false, refreshAgain = false
  let pageCursors = ['']
  const active = computed(() => !!conversation.value && conversation.value.state !== 'IDLE')
  const explain = (failure: unknown) => failure instanceof Error && /[\u4e00-\u9fff]/.test(failure.message) ? failure.message : '暂时无法读取会话，请重试核对状态'
  function close() { subscription?.close(); subscription = undefined }
  function reset() { ++epoch; close(); conversation.value = null; messages.value = []; error.value = ''; nextCursor.value = null; disconnected.value = false; pendingText.value = ''; loading.value = false; pageCursors = [''] }
  function combine(rows: KnowledgeMessage[]) { return [...new Map(rows.map(row => [row.id, row])).values()].sort((a, b) => a.ordinal - b.ordinal) }
  async function reconcile(id: string, ticket: number) {
    const key = `loopper.knowledge.pending.${id}`, saved = sessionStorage.getItem(key)
    if (!saved) { if (ticket === epoch) pendingText.value = ''; return }
    const pending: { key: string; text: string } = JSON.parse(saved)
    const receipt = await api.receipt(id, pending.key)
    if (receipt.accepted && sessionStorage.getItem(key) === saved) sessionStorage.removeItem(key)
    if (ticket === epoch) pendingText.value = receipt.accepted ? '' : pending.text
  }
  async function refresh() {
    if (!conversation.value) return
    if (refreshing) { refreshAgain = true; return }
    const id = conversation.value.id, ticket = epoch; refreshing = true
    try {
      const [summary, pages] = await Promise.all([api.get(id), Promise.all(pageCursors.map(cursor => api.messages(id, cursor)))])
      if (ticket !== epoch || conversation.value?.id !== id) return
      conversation.value = summary; messages.value = combine([...messages.value, ...pages.flatMap(page => page.items)]); nextCursor.value = pages.at(-1)?.nextCursor ?? null
      error.value = ''; await reconcile(id, ticket)
    } catch (failure) { if (ticket === epoch) error.value = explain(failure) }
    finally { refreshing = false; if (refreshAgain) { refreshAgain = false; void refresh() } }
  }
  function subscribe(id: string) {
    close(); subscription = api.events(id)
    subscription.onmessage = () => { disconnected.value = false; void refresh() }
    subscription.onopen = () => { disconnected.value = false; void refresh() }
    subscription.onerror = () => { disconnected.value = true }
  }
  async function load(id: string) {
    if (conversation.value?.id === id) return refresh()
    reset(); const ticket = epoch; loading.value = true
    try {
      const [summary, page] = await Promise.all([api.get(id), api.messages(id)])
      if (ticket !== epoch) return
      conversation.value = summary; messages.value = page.items; nextCursor.value = page.nextCursor ?? null; subscribe(id); await reconcile(id, ticket)
    } catch (failure) { if (ticket === epoch) error.value = explain(failure) }
    finally { if (ticket === epoch) loading.value = false }
  }
  async function more() {
    if (!conversation.value || !nextCursor.value || loading.value) return
    const id = conversation.value.id, cursor = nextCursor.value, ticket = epoch; loading.value = true
    try { const page = await api.messages(id, cursor); if (ticket === epoch) { pageCursors.push(cursor); messages.value = combine([...page.items, ...messages.value]); nextCursor.value = page.nextCursor ?? null } }
    catch (failure) { if (ticket === epoch) error.value = explain(failure) }
    finally { if (ticket === epoch) loading.value = false }
  }
  async function send(text: string, create: KnowledgeCreate) {
    if (sending.value) return false
    sending.value = true; error.value = ''; const ticket = epoch
    try {
      if (!conversation.value) { const created = await api.create(create); if (ticket !== epoch) return false; conversation.value = created; subscribe(created.id) }
      const id = conversation.value.id, storageKey = `loopper.knowledge.pending.${id}`
      const saved = sessionStorage.getItem(storageKey)
      const pending: { key: string; text: string } = saved ? JSON.parse(saved) : { key: crypto.randomUUID(), text }
      if (pending.text !== text) throw new Error('上一条发送结果尚未核对，请先重试原问题或重新打开历史会话')
      sessionStorage.setItem(storageKey, JSON.stringify(pending))
      await api.send(id, pending.key, pending.text); sessionStorage.removeItem(storageKey); if (ticket !== epoch) return false; await refresh(); return true
    } catch (failure) { if (ticket === epoch) error.value = explain(failure); return false }
    finally { sending.value = false }
  }
  async function stop() {
    if (!conversation.value || sending.value) return
    sending.value = true; const id = conversation.value.id, ticket = epoch
    try { const stopped = await api.stop(id); if (ticket !== epoch) return; conversation.value = stopped; await refresh() }
    catch (failure) { if (ticket === epoch) error.value = explain(failure) }
    finally { sending.value = false }
  }
  return { conversation, messages, pendingText, error, disconnected, loading, sending, active, nextCursor, reset, close, load, refresh, more, send, stop }
})
