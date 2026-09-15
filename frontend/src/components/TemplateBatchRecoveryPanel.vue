<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { Task, TemplateFailedBatch, DocumentTemplateOverview } from '@/types/domain'
const props = defineProps<{ task?: Task; documentRun?: DocumentTemplateOverview }>()
const ownerId = computed(() => props.task?.id ?? props.documentRun?.id ?? '')
const rows = ref<TemplateFailedBatch[]>([])
const cursor = ref<string | null>(null)
const loading = ref(false)
const busy = ref('')
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
    rows.value = more ? [...rows.value, ...page.items] : page.items
    cursor.value = page.nextCursor ?? null
  } catch {
    if (current === revision) error.value = '未能读取待处理批次，请重新加载。'
  } finally { if (current === revision) loading.value = false }
}
async function retry(batch: TemplateFailedBatch) {
  if (busy.value) return
  const id = ownerId.value
  busy.value = batch.id
  error.value = ''
  try {
    await (props.documentRun ? api.retryDocumentBatch : api.retryTemplateBatch)(id, batch)
    if (id === ownerId.value) await load()
  } catch {
    if (id === ownerId.value) error.value = '暂不能重试。请刷新批次，并检查旧会话停止状态及任务预算、时限。'
  } finally { busy.value = '' }
}
watch(() => [ownerId.value, props.task?.status, props.task?.templateProgress?.failedBatches, props.documentRun?.progress.attempts, props.documentRun?.progress.active, props.documentRun?.state], (value, previous) => {
  if (value[0] !== previous?.[0]) { rows.value = []; cursor.value = null; error.value = '' }
  void load()
}, { immediate: true })
</script>

<template>
  <section v-if="rows.length || error" class="batch-recovery" aria-label="批次恢复">
    <p>失败批次可单独重试；每次点击只发起一次，已完成结果保留。</p>
    <p v-if="error" role="alert">{{ error }} <el-button text @click="load()">重新加载</el-button></p>
    <ul>
      <li v-for="batch in rows" :key="batch.id">
        <div><strong>{{ batch.purpose === 'CONTRIBUTOR' ? '人员贡献' : batch.purpose === 'DOCUMENT_CODE_REVIEW_V2' ? '独立复核' : '代码分析' }} · 第 {{ batch.ordinal + 1 }} 批</strong>
          <p>{{ batch.errorMessage || '该批次已停止，尚未完成分析。' }}</p>
          <small v-if="batch.generation >= 2">自动重试额度已用完，可手动继续一次。</small>
        </div>
        <el-button :loading="busy === batch.id" :disabled="!!busy" @click="retry(batch)">重试该批次</el-button>
      </li>
    </ul>
    <el-button v-if="cursor" :loading="loading" @click="load(true)">加载更多</el-button>
  </section>
</template>

<style scoped>
.batch-recovery { margin: 16px 0; padding: 16px; border: 1px solid var(--color-border-default); border-radius: 12px; }
ul { padding: 0; list-style: none; max-height: 360px; overflow: auto; }
li { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 0; }
p, small { color: var(--color-text-secondary); overflow-wrap: anywhere; }
@media (max-width: 640px) { li { align-items: flex-start; flex-direction: column; } }
</style>
