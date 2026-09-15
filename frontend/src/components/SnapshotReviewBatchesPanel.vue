<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { SnapshotReviewBatch, Task } from '@/types/domain'
import { displayLabel } from '@/utils/displayLabels'
import { templateBatchPurpose } from '@/utils/templateSessionLabels'
const props = defineProps<{ task: Task }>()
const expanded = ref(false)
const rows = ref<SnapshotReviewBatch[]>([])
const cursor = ref<string | null>(null)
const loading = ref(false)
const error = ref('')
let generation = 0
async function load(append = false) {
  if (!expanded.value) return
  const current = ++generation
  loading.value = true
  try {
    const page = await api.snapshotReviewBatches(props.task.id, append ? cursor.value ?? '' : '')
    if (current !== generation) return
    rows.value = append ? [...rows.value, ...page.items] : page.items
    cursor.value = page.nextCursor ?? null
    error.value = ''
  } catch { if (current === generation) error.value = '批次列表加载失败，请重试。' }
  finally { if (current === generation) loading.value = false }
}
watch(() => [props.task.id, props.task.status, props.task.templateProgress?.completedReviews, props.task.templateProgress?.completedContributors, props.task.templateProgress?.activeBatches, props.task.templateProgress?.failedBatches, props.task.templateProgress?.snapshot?.planRevision], () => { void load() })
watch(() => props.task.id, () => { generation++; rows.value = []; cursor.value = null; error.value = ''; loading.value = false; void load() })
watch(expanded, () => { if (!expanded.value) { generation++; loading.value = false } else void load() })
onBeforeUnmount(() => { generation++ })
</script>
<template>
  <section aria-label="功能批次">
    <el-button text @click="expanded = !expanded">{{ expanded ? '收起功能批次' : '查看功能批次' }}</el-button>
    <div v-if="expanded">
      <p v-if="error" role="alert">{{ error }} <el-button text @click="load()">重试</el-button></p>
      <ul><li v-for="row in rows" :key="row.id"><strong>{{ row.title }}</strong><span>{{ templateBatchPurpose(row.purpose) }} · {{ displayLabel(row.state) }}</span><p v-if="row.errorMessage">{{ row.errorMessage }}</p></li></ul>
      <p v-if="!rows.length && !loading">尚未创建功能批次。</p>
      <el-button v-if="cursor" :loading="loading" @click="load(true)">加载更多</el-button>
    </div>
  </section>
</template>
<style scoped>
ul { list-style:none; padding:0; max-height:360px; overflow:auto; } li { padding:12px 0; border-bottom:1px solid var(--color-border-default); overflow-wrap:anywhere; } span { display:block; margin-top:6px; color:var(--color-text-secondary); }
</style>
