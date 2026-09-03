<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '@/api/client'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import type { StoryAccountingCall } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'

const calls = ref<StoryAccountingCall[]>([])
const selectedId = ref('')
const error = ref('')
const cancelling = ref(false)
const now = ref(Date.now())
let timer: ReturnType<typeof setTimeout> | undefined
let clock: ReturnType<typeof setInterval> | undefined
let alive = true
let selection = 0
const current = computed(() => calls.value.find(call => call.id === selectedId.value))
const active = (call: StoryAccountingCall) => call.state === 'PREPARED' || call.state === 'CANCELLING'
const running = computed(() => current.value ? active(current.value) : false)
const roleNames: Record<string, string> = { ROUTER: '任务识别', REQUIREMENT_DESIGNER: '需求设计', PACKAGE_DESIGNER: '工作包设计', IMPLEMENTATION: '实施', JUDGE: '评审', REVIEWER: '只读审查', DECOMPOSER: '需求拆分', COMPILER: '设计编译' }
const roleLabel = (call: StoryAccountingCall) => roleNames[call.role] ?? '设计与执行'
const operationLabel = (call: StoryAccountingCall) => call.operation === 'complete' ? '完成' : '开启'
const title = computed(() => current.value ? `${running.value ? '正在' : '已结束：'}${operationLabel(current.value)}故事点统计${running.value ? '…' : ''}` : '故事点统计')
const elapsed = computed(() => {
  if (!current.value) return 0
  return Math.max(0, Math.floor(((current.value.finishedAt ? Date.parse(current.value.finishedAt) : now.value) - Date.parse(current.value.startedAt)) / 1_000))
})
const status = computed(() => ({ PREPARED: '正在等待统计结果', CANCELLING: '正在取消本次统计', SUCCEEDED: '统计已完成', FAILED: '统计失败，任务继续执行', UNKNOWN: '统计结果未知，任务继续执行', CANCELLED: '已取消本次统计，任务继续执行' })[current.value?.state ?? 'PREPARED'])

async function refresh() {
  const generation = selection
  try {
    const next = await api.getStoryAccountingCalls()
    if (!alive || generation !== selection) return
    const previous = current.value
    calls.value = next
    const candidate = next.find(call => active(call))
    if (!next.some(call => call.id === selectedId.value) || (previous && !active(previous) && candidate)) {
      selectedId.value = candidate?.id ?? next[0]?.id ?? ''
    }
    const id = selectedId.value
    if (id) {
      const detail = await api.getStoryAccountingCall(id)
      if (!alive || selection !== generation || selectedId.value !== id) return
      calls.value = calls.value.map(call => call.id === id ? detail : call)
    }
    error.value = ''
  } catch (failure) {
    if (alive && current.value) error.value = userFacingError(failure, '统计状态暂时无法刷新')
  } finally {
    if (alive) { now.value = Date.now(); timer = setTimeout(() => void refresh(), 1_200) }
  }
}

async function cancel() {
  if (!current.value || cancelling.value || !running.value) return
  const id = current.value.id
  selection++
  cancelling.value = true
  try {
    const result = await api.cancelStoryAccountingCall(id)
    if (alive) calls.value = calls.value.map(call => call.id === id ? result : call)
  } catch (failure) { if (alive) error.value = userFacingError(failure, '暂时无法取消统计，请重试') }
  finally { cancelling.value = false }
}

async function close() {
  if (running.value) return
  selection++
  const finished = calls.value.filter(call => !active(call))
  try {
    for (const call of finished) await api.dismissStoryAccountingCall(call.id)
    calls.value = calls.value.filter(call => active(call))
    selectedId.value = calls.value[0]?.id ?? ''
  } catch (failure) { error.value = userFacingError(failure, '关闭统计结果失败，请重试') }
}
onMounted(() => { clock = setInterval(() => { now.value = Date.now() }, 1_000); void refresh() })
onBeforeUnmount(() => { alive = false; selection++; if (timer) clearTimeout(timer); if (clock) clearInterval(clock) })
</script>

<template>
  <el-dialog :model-value="!!current" :title="title" width="760px" append-to-body destroy-on-close
    class="story-accounting-dialog" :z-index="4000" :close-on-click-modal="false" :close-on-press-escape="!running"
    :show-close="!running" @close="close">
    <template v-if="current">
      <p class="accounting-context">系统 {{ current.systemCode }} · 故事 {{ current.storyCode }} · {{ roleLabel(current) }} · 已用 {{ elapsed }} 秒</p>
      <el-select v-if="calls.length > 1" v-model="selectedId" :teleported="false" aria-label="选择统计会话" @change="selection++">
        <el-option v-for="(call, index) in calls" :key="call.id" :value="call.id"
          :label="`${index + 1}. ${operationLabel(call)}统计 · ${roleLabel(call)} · ${call.systemCode} / ${call.storyCode}${active(call) ? ' · 进行中' : ''}`" />
      </el-select>
      <div class="accounting-status" role="status" aria-live="polite"><span v-if="running" class="accounting-spinner" />{{ status }}</div>
      <el-alert v-if="error || current.refreshError" :title="error || current.refreshError || ''" type="warning" :closable="false" />
      <div class="accounting-output" aria-label="统计模型输出">
        <article v-for="part in current.parts" :key="part.id">
          <small>{{ part.type === 'TOOL' ? '统计工具' : part.type === 'THINKING' ? '思考' : '模型输出' }}</small>
          <MarkdownDocument v-if="part.content" :content="part.content" />
          <p v-else>{{ part.label || '正在处理…' }}</p>
        </article>
        <p v-if="!current.parts.length">{{ running ? '正在等待模型输出…' : '本次统计未返回模型正文。' }}</p>
      </div>
      <p v-if="current.detail" class="accounting-detail">{{ current.detail }}</p>
      <p v-if="running" class="accounting-hint">统计会持续等待。觉得等待太久，可以取消本次统计并继续任务；已送达平台的请求无法撤回。</p>
    </template>
    <template #footer>
      <el-button v-if="running" type="danger" plain :loading="cancelling || current?.state === 'CANCELLING'" @click="cancel">取消本次统计，继续任务</el-button>
      <el-button v-else type="primary" @click="close">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.accounting-context, .accounting-hint { color: var(--color-text-secondary); font-size: 12px; line-height: 1.6; }
.accounting-status { display: flex; align-items: center; gap: 10px; margin: 18px 0; color: var(--color-text-primary); }
.accounting-spinner { width: 18px; height: 18px; border: 2px solid var(--color-border-soft); border-top-color: var(--color-accent-cyan); border-radius: 50%; animation: accounting-spin 1s linear infinite; }
.accounting-output { max-height: 360px; overflow: auto; padding: 16px; border: 1px solid var(--color-border-soft); border-radius: 10px; background: rgb(7 11 20 / 52%); }
.accounting-output article + article { margin-top: 16px; }
.accounting-output small { color: var(--color-accent-cyan); }.accounting-detail { white-space: pre-wrap; line-height: 1.65; }
@keyframes accounting-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .accounting-spinner { animation: none; } }
</style>
