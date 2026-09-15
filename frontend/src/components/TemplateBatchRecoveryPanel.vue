<script setup lang="ts">
import { templateBatchPurpose } from '@/utils/templateSessionLabels'
import { computed, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { Task, TemplateFailedBatch, DocumentTemplateOverview } from '@/types/domain'
const props = defineProps<{ task?: Task; documentRun?: DocumentTemplateOverview }>()
const ownerId = computed(() => props.task?.id ?? props.documentRun?.id ?? '')
const rows = ref<TemplateFailedBatch[]>([])
const cursor = ref<string | null>(null)
const loading = ref(false)
const busy = ref(false)
const ready = ref(false)
const selected = ref<string[]>([])
const error = ref('')
let revision = 0
async function load(more = false) {
  const current = ++revision
  const id = ownerId.value
  loading.value = true
  try {
    const page = await (props.documentRun ? api.documentFailedBatches : api.templateFailedBatches)(id, more ? cursor.value ?? '' : '')
    if (current !== revision || id !== ownerId.value) return
    error.value = ''
    ready.value = page.facets?.retrySelectionReady === 1
    rows.value = more ? [...rows.value, ...page.items] : page.items
    cursor.value = page.nextCursor ?? null
    selected.value = selected.value.filter(id => rows.value.some(row => row.id === id))
  } catch {
    if (current === revision) error.value = '未能读取待处理批次，请重新加载。'
  } finally { if (current === revision) loading.value = false }
}
async function retry() {
  if (busy.value || !ready.value || !selected.value.length) return
  const id = ownerId.value
  const selection = rows.value.filter(row => selected.value.includes(row.id))
  busy.value = true
  error.value = ''
  try {
    await (props.documentRun ? api.retrySelectedDocumentBatches : api.retrySelectedTemplateBatches)(id, selection)
    if (id === ownerId.value) { selected.value = []; await load() }
  } catch {
    if (id === ownerId.value) error.value = '暂不能重新触发。请刷新批次，并检查旧会话停止状态及任务预算、时限。'
  } finally { busy.value = false }
}
watch(() => [ownerId.value, props.task?.status, props.task?.templateProgress?.failedBatches, props.task?.templateProgress?.activeBatches, props.task?.templateProgress?.completedReviews, props.task?.templateProgress?.completedContributors, props.documentRun?.progress.attempts, props.documentRun?.progress.active, props.documentRun?.progress.revision, props.documentRun?.state], (value, previous) => {
  if (value[0] !== previous?.[0]) { rows.value = []; selected.value = []; ready.value = false; cursor.value = null; error.value = '' }
  void load()
}, { immediate: true })
</script>

<template>
  <section v-if="rows.length || error" class="batch-recovery" aria-label="批次恢复">
    <p v-if="!ready && rows.length">失败批次已记录，后续批次继续执行；本轮完成后统一选择重新触发。</p>
    <p v-else-if="rows.length">本轮已执行完毕，请选择失败批次重新触发。已完成结果保留，全部必需结果完成后继续汇总。</p>
    <p v-if="error" role="alert">{{ error }} <el-button text :disabled="busy" @click="load()">重新加载</el-button></p>
    <template v-if="ready">
      <label v-if="rows.length" class="select-all"><input type="checkbox" :checked="selected.length === rows.length" :disabled="busy" @change="selected = selected.length === rows.length ? [] : rows.map(row => row.id)">选择已加载批次</label>
      <ul>
        <li v-for="batch in rows" :key="batch.id">
          <label><input v-model="selected" type="checkbox" :value="batch.id" :disabled="busy">
            <span><strong>{{ templateBatchPurpose(batch.purpose) }} · 第 {{ batch.ordinal + 1 }} 批</strong>
              <span class="failure-message">{{ batch.errorMessage || '该批次已停止，尚未完成分析。' }}</span>
            </span>
          </label>
        </li>
      </ul>
      <el-button v-if="cursor" :loading="loading" :disabled="busy" @click="load(true)">加载更多</el-button>
      <el-button v-if="rows.length" :loading="busy" :disabled="busy || !selected.length || selected.length > 100" @click="retry">重新触发所选批次（{{ selected.length }}）</el-button>
      <p v-if="selected.length > 100">每次最多选择 100 个批次。</p>
    </template>
  </section>
</template>

<style scoped>
.batch-recovery { margin: 16px 0; padding: 16px; border: 1px solid var(--color-border-default); border-radius: 12px; }
ul { padding: 0; list-style: none; max-height: 360px; overflow: auto; }
li { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 0; }
.failure-message { display: block; margin-top: 6px; color: var(--color-text-secondary); }
label { display: flex; align-items: flex-start; gap: 10px; cursor: pointer; }
p, small { color: var(--color-text-secondary); overflow-wrap: anywhere; }
@media (max-width: 640px) { li { align-items: flex-start; flex-direction: column; } }
</style>
