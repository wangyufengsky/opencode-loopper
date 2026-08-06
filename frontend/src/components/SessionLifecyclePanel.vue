<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api/client'
import type { SessionCheckpoint, SessionTodo, TaskStatus } from '@/types/domain'

const props = defineProps<{ taskId: string; sessionId: string; sessionState?: string; taskState: TaskStatus; directExecution?: boolean }>()
const todos = ref<SessionTodo[]>([])
const checkpoints = ref<SessionCheckpoint[]>([])
const loading = ref(false)
const busy = ref('')
const error = ref('')
const messageId = ref('')
const partId = ref('')
const title = computed(() => props.sessionState ? `会话快照 · ${props.sessionState}` : '会话快照')
const paused = computed(() => props.taskState === 'PAUSED')

async function load() {
  if (!props.sessionId) return
  loading.value = true
  try {
    const [nextTodos, nextCheckpoints] = await Promise.all([
      api.getTaskSessionTodos(props.taskId, props.sessionId),
      api.getTaskSessionCheckpoints(props.taskId, props.sessionId),
    ])
    todos.value = nextTodos
    checkpoints.value = nextCheckpoints
    error.value = ''
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '无法读取持久化会话快照' } finally { loading.value = false }
}
async function mutate(action: 'refresh' | 'checkpoint' | 'fork' | 'revert' | 'summarize') {
  busy.value = action
  try {
    if (action === 'refresh') await api.refreshTaskSessionTodos(props.taskId, props.sessionId)
    if (action === 'checkpoint') await api.createTaskSessionCheckpoint(props.taskId, props.sessionId, messageId.value || undefined)
    if (action === 'fork') await api.forkTaskSession(props.taskId, props.sessionId, messageId.value)
    if (action === 'revert') await api.revertTaskSession(props.taskId, props.sessionId, messageId.value, partId.value)
    if (action === 'summarize') await api.summarizeTaskSession(props.taskId, props.sessionId)
    await load()
    ElMessage.success(action === 'refresh' ? '已从 OpenCode 同步并持久化 todo' : '会话操作已完成')
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '会话操作失败') } finally { busy.value = '' }
}
watch(() => [props.taskId, props.sessionId], load)
onMounted(load)
</script>

<template>
  <section class="lifecycle card card-pad" aria-label="会话生命周期">
    <header class="card-header"><div><p class="eyebrow">PERSISTED SESSION SNAPSHOT</p><h2 class="card-title">{{ title }}</h2></div><button class="text-button" :disabled="loading || !!busy" @click="load">刷新快照</button></header>
    <p v-if="error" class="lifecycle-error">{{ error }}</p>
    <div class="lifecycle-actions"><button :disabled="!!busy" @click="mutate('refresh')">同步真实 todo</button><button :disabled="!!busy" @click="mutate('checkpoint')">创建 checkpoint</button><button :disabled="!!busy || !paused || !messageId" @click="mutate('fork')">Fork transcript</button><button :disabled="!!busy || !paused || !messageId || !partId || directExecution" @click="mutate('revert')">回退 worktree</button><button :disabled="!!busy" @click="mutate('summarize')">请求总结</button></div>
    <p v-if="directExecution" class="lifecycle-hint">直接执行目录不支持原地回退；请创建派生 Recovery 任务。</p>
    <p v-else-if="!paused" class="lifecycle-hint">Fork 与回退前必须先暂停任务，并由服务端确认旧 writer 已终止。</p>
    <div class="refs"><label>OpenCode message id<input v-model.trim="messageId" placeholder="用于 checkpoint / fork / revert 的真实 message id"></label><label>OpenCode part id<input v-model.trim="partId" placeholder="仅用于 worktree revert"></label></div>
    <div class="snapshot-grid"><article><h3>服务端 todo</h3><p v-if="!todos.length" class="muted">尚无持久化 todo；可点击“同步真实 todo”。</p><ol v-else><li v-for="todo in todos" :key="todo.id"><span>{{ todo.content }}</span><small>{{ todo.status }}{{ todo.priority ? ` · ${todo.priority}` : '' }}</small></li></ol></article><article><h3>Checkpoint</h3><p v-if="!checkpoints.length" class="muted">尚未固化 transcript、todo 和 diff 引用。</p><ol v-else><li v-for="checkpoint in checkpoints" :key="checkpoint.id"><code>{{ checkpoint.contentSha256 }}</code><small>{{ checkpoint.externalMessageId || '完整 transcript' }}</small></li></ol></article></div>
  </section>
</template>

<style scoped>
.lifecycle { margin-top: 16px; border-color: rgb(139 92 246 / 28%); background: linear-gradient(135deg, rgb(15 23 42 / 92%), rgb(30 20 55 / 66%)); }.lifecycle-actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 14px 0; }.lifecycle-actions button,.text-button { border: 1px solid var(--color-border-default); border-radius: 7px; padding: 7px 10px; color: var(--color-text-secondary); background: rgb(7 12 22 / 78%); font-size: 12px; cursor: pointer; }.lifecycle-actions button:hover:not(:disabled),.text-button:hover:not(:disabled) { color: var(--color-text-primary); border-color: var(--color-accent); }.lifecycle-actions button:disabled,.text-button:disabled { opacity: .48; cursor: not-allowed; }.refs { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }.refs label { display: grid; gap: 5px; color: var(--color-text-tertiary); font-size: 10px; }.refs input { min-width: 0; border: 1px solid var(--color-border-default); border-radius: 7px; padding: 8px 9px; color: var(--color-text-primary); background: rgb(5 9 17 / 85%); font: 11px var(--font-code); }.snapshot-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 14px; }.snapshot-grid article { min-width: 0; padding: 11px; border: 1px solid var(--color-border-default); border-radius: 8px; background: rgb(5 9 17 / 48%); }.snapshot-grid h3 { margin: 0 0 9px; color: var(--color-text-primary); font-size: 12px; }.snapshot-grid ol { display: grid; gap: 7px; padding: 0; margin: 0; list-style: none; }.snapshot-grid li { display: grid; gap: 3px; color: var(--color-text-secondary); font-size: 12px; }.snapshot-grid small,.lifecycle-hint { color: var(--color-text-tertiary); font: 10px var(--font-code); }.snapshot-grid code { overflow: hidden; color: #c4b5fd; font-size: 10px; text-overflow: ellipsis; }.lifecycle-error { color: var(--color-danger); font-size: 12px; }.lifecycle-hint { margin: 0 0 10px; }.muted { color: var(--color-text-tertiary); font-size: 12px; }@media (max-width: 760px) { .refs,.snapshot-grid { grid-template-columns: 1fr; } }
</style>
