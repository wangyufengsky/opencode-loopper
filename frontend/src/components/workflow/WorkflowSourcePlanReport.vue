<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { workflowReasonLabel } from '@/utils/displayLabels'
const props = defineProps<{ content: unknown }>()
const report = computed(() => {
  const value = props.content as Record<string, unknown> | null
  if (!value || value.version !== 1 || !['SOURCE_DESIGN_PLAN', 'SOURCE_TEST_PLAN'].includes(String(value.type)) || typeof value.complete !== 'boolean') return null
  if (!value.complete) return { complete: false, code: typeof value.code === 'string' ? value.code : null }
  if (!Number.isSafeInteger(value.sourceCount) || Number(value.sourceCount) < 1 || !Number.isSafeInteger(value.batchCount) || Number(value.batchCount) < 1 || Number(value.batchCount) > 63 || !Array.isArray(value.batches) || value.batches.length !== value.batchCount) return null
  const paths = new Set<string>(), batches: { ordinal: number; title: string; paths: string[] }[] = []
  for (const [index, batch] of value.batches.entries()) {
    if (!batch || batch.ordinal !== index || typeof batch.title !== 'string' || !Array.isArray(batch.paths) || !batch.paths.length || batch.paths.length > 12) return null
    for (const path of batch.paths) { if (typeof path !== 'string' || !path || paths.has(path)) return null; paths.add(path) }
    batches.push({ ordinal: index, title: batch.title, paths: batch.paths })
  }
  if (paths.size !== value.sourceCount) return null
  return { complete: true, sourceCount: Number(value.sourceCount), batchCount: Number(value.batchCount), batches }
})
</script>
<template><div class="workflow-source-plan-report">
  <template v-if="report?.complete"><StatusBadge status="SUCCEEDED" label="候选计划已生成" /><p>{{ report.sourceCount }} 个源码文件，分为 {{ report.batchCount }} 批。</p><p>在候选计划中查看变更与确认状态。</p><details v-for="batch in report.batches" :key="batch.ordinal"><summary>第 {{ batch.ordinal + 1 }} 批 · {{ batch.title }} · {{ batch.paths.length }} 个文件</summary><ul><li v-for="path in batch.paths" :key="path">{{ path }}</li></ul></details></template>
  <template v-else-if="report"><StatusBadge status="FAILED" label="分批计划未生成" /><p>{{ workflowReasonLabel(report.code) }}</p></template>
  <p v-else role="alert">分批报告无法读取，请重新读取本次交付物。</p>
</div></template>
