<script setup lang="ts">
import { templateBatchPurpose } from '@/utils/templateSessionLabels'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { Task, TemplateFailedBatch, DocumentTemplateOverview } from '@/types/domain'
const props = defineProps<{ task?: Task; documentRun?: DocumentTemplateOverview }>()
const ownerId = computed(() => props.task?.id ?? props.documentRun?.id ?? '')
const rows = ref<TemplateFailedBatch[]>([])
const cursor = ref<string | null>(null)
const loading = ref(false)
const busy = ref(false)
const ready = ref(false)
const resumeAvailable = ref(false)
const taskVersion = ref(0)
const blockingBatches = ref(0)
const environmentBlocked = ref(false)
const selected = ref<string[]>([])
const error = ref('')
let revision = 0
let disposed = false
let timer: ReturnType<typeof setTimeout> | undefined
function schedule() {
  if (timer) clearTimeout(timer)
  if (disposed || !props.task || !['RUNNING', 'WAITING_INPUT', 'STOPPING'].includes(props.task.status)) return
  timer = setTimeout(() => {
    if (busy.value || selected.value.length || document.hidden) schedule()
    else void load()
  }, 5000)
}
onBeforeUnmount(() => { disposed = true; revision++; if (timer) clearTimeout(timer) })
async function load(more = false) {
  if (disposed) return
  const current = ++revision
  const id = ownerId.value
  loading.value = true
  try {
    const page = await (props.documentRun ? api.documentFailedBatches : api.templateFailedBatches)(id, more ? cursor.value ?? '' : '')
    if (current !== revision || id !== ownerId.value) return
    error.value = ''
    ready.value = page.facets?.retrySelectionReady === 1
    resumeAvailable.value = !props.documentRun && page.facets?.resumeAvailable === 1
    taskVersion.value = page.facets?.taskVersion ?? 0
    blockingBatches.value = page.facets?.blockingBatches ?? 0
    environmentBlocked.value = page.facets?.environmentBlocked === 1
    rows.value = more ? [...rows.value, ...page.items] : page.items
    cursor.value = page.nextCursor ?? null
    selected.value = selected.value.filter(id => rows.value.some(row => row.id === id))
  } catch {
    if (current === revision) { ready.value = false; resumeAvailable.value = false; error.value = '未能读取待处理批次，请重新加载。' }
  } finally { if (current === revision) { loading.value = false; schedule() } }
}
async function retry() {
  if (busy.value || loading.value || error.value || !ready.value || !selected.value.length) return
  const id = ownerId.value
  const selection = rows.value.filter(row => selected.value.includes(row.id))
  busy.value = true
  error.value = ''
  try {
    await (props.documentRun ? api.retrySelectedDocumentBatches : api.retrySelectedTemplateBatches)(id, selection)
    if (id === ownerId.value) { selected.value = []; await load() }
  } catch {
    if (id === ownerId.value) error.value = '暂不能重新触发。请刷新批次，并检查旧会话停止状态及任务预算、时限。'
  } finally { if (id === ownerId.value) busy.value = false }
}
async function recheck() {
  if (busy.value || loading.value || error.value || !resumeAvailable.value) return
  const id = ownerId.value
  busy.value = true
  try {
    await api.recheckTemplateTask(id, taskVersion.value)
    if (id === ownerId.value) { resumeAvailable.value = false; await load() }
  } catch {
    if (id === ownerId.value) error.value = '恢复请求尚未确认，请重新加载状态后再操作。'
  } finally { if (id === ownerId.value) busy.value = false }
}
watch(() => [ownerId.value, props.task?.status, props.task?.templateProgress?.failedBatches, props.task?.templateProgress?.activeBatches, props.task?.templateProgress?.completedReviews, props.task?.templateProgress?.completedContributors, props.documentRun?.progress.attempts, props.documentRun?.progress.active, props.documentRun?.progress.revision, props.documentRun?.state], (value, previous) => {
  if (value[0] !== previous?.[0]) { rows.value = []; selected.value = []; ready.value = false; resumeAvailable.value = false; busy.value = false; cursor.value = null; error.value = '' }
  void load()
}, { immediate: true })
</script>

<template>
  <section v-if="rows.length || error || resumeAvailable || task?.status === 'STOPPING' || (task?.status === 'WAITING_INPUT' && blockingBatches > 0) || environmentBlocked" class="batch-recovery" aria-label="批次恢复">
    <p v-if="task?.status === 'STOPPING'">取消请求已记录，正在确认未完成会话停止。连接异常时会继续重查；可在批次运行诊断中查看原因并立即重新检查。</p>
    <p v-else-if="!ready && task?.status === 'WAITING_INPUT'">任务已暂停，后续批次尚未执行。{{ blockingBatches ? `还有 ${blockingBatches} 个批次未确认结束，确认停止前不能重新分析。` : '请先处理任务提示的阻断原因。' }}</p>
    <p v-else-if="environmentBlocked">运行环境暂不可用，已暂停派发新的分析请求，保留原会话并自动重查。请在批次运行诊断中查看原因。</p>
    <p v-else-if="!ready && rows.length">失败批次已记录，后续批次继续执行；本轮完成后统一选择重新触发。</p>
    <p v-else-if="rows.length">本轮已执行完毕，请选择失败批次重新触发。已完成结果保留，全部必需结果完成后继续汇总。</p>
    <template v-if="resumeAvailable">
      <p>重新检查将沿用原批次和原会话，继续核对执行与停止状态；不会重置任务预算。</p>
      <el-button :loading="busy" :disabled="busy || loading || Boolean(error)" @click="recheck">重新检查并恢复原批次</el-button>
    </template>
    <p v-if="error" role="alert">{{ error }} <el-button text :disabled="busy" @click="load()">重新加载</el-button></p>
    <template v-if="rows.length">
      <p v-if="ready && task">重新触发的每个批次，本轮失败后最多自动重试 3 次；已完成批次保留。</p>
      <label v-if="ready" class="select-all"><input type="checkbox" :checked="selected.length === rows.length" :disabled="busy || loading || Boolean(error)" @change="selected = selected.length === rows.length ? [] : rows.map(row => row.id)">选择已加载批次</label>
      <ul>
        <li v-for="batch in rows" :key="batch.id">
          <label><input v-if="ready" v-model="selected" type="checkbox" :value="batch.id" :disabled="busy || loading || Boolean(error)">
            <span><strong>{{ templateBatchPurpose(batch.purpose) }} · 第 {{ batch.ordinal + 1 }} 批</strong>
              <span class="failure-message">{{ batch.errorMessage || '该批次已停止，尚未完成分析。' }}</span>
            </span>
          </label>
        </li>
      </ul>
      <el-button v-if="cursor" :loading="loading" :disabled="busy" @click="load(true)">加载更多</el-button>
      <el-button v-if="ready" :loading="busy" :disabled="busy || loading || Boolean(error) || !selected.length || selected.length > 100" @click="retry">重新触发所选批次（{{ selected.length }}）</el-button>
      <p v-if="selected.length > 100">每次最多选择 100 个批次。</p>
    </template>
  </section>
</template>

<style scoped>
.batch-recovery { margin: 16px 0; padding: 16px; border: 1px solid var(--color-border-default); border-radius: calc(var(--radius-control) + 6px); }
ul { padding: 0; list-style: none; max-height: 360px; overflow: auto; }
li { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 0; }
.failure-message { display: block; margin-top: 6px; color: var(--color-text-secondary); }
label { display: flex; align-items: flex-start; gap: 10px; cursor: pointer; }
p, small { color: var(--color-text-secondary); overflow-wrap: anywhere; }
@media (max-width: 640px) { li { align-items: flex-start; flex-direction: column; } }
</style>
