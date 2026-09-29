<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { workflowReasonLabel } from '@/utils/displayLabels'
const props = defineProps<{ content: unknown }>()
const report = computed(() => {
  const value = props.content as Record<string, unknown> | null
  if (!value || value.version !== 1 || value.type !== 'SOURCE_SNAPSHOT' || typeof value.complete !== 'boolean') return null
  const count = (name: string) => typeof value[name] === 'number' && Number.isSafeInteger(value[name]) && Number(value[name]) >= 0 ? Number(value[name]) : null
  if (value.complete && (!count('targetCount') || count('incompleteCount') !== 0 || count('fileCount') == null || count('excludedCount') == null)) return null
  const exclusions = (Array.isArray(value.exclusions) ? value.exclusions : []).flatMap(row => row && typeof row.path === 'string' && typeof row.reason === 'string' ? [{ path: row.path as string, reason: row.reason as string }] : [])
  return { complete: value.complete, path: typeof value.sourcePath === 'string' ? value.sourcePath : '', code: typeof value.code === 'string' ? value.code : null,
    targets: count('targetCount'), files: count('fileCount'), incomplete: count('incompleteCount'), excluded: count('excludedCount'), exclusions }
})
</script>
<template>
  <div class="workflow-source-report">
    <p v-if="!report" role="alert">采集报告无法读取，请重新读取本次交付物。</p>
    <template v-else>
      <StatusBadge :status="report.complete ? 'SUCCEEDED' : 'FAILED'" :label="report.complete ? '源码已冻结' : '采集未完成'" />
      <p v-if="report.path">源码路径：{{ report.path }}</p>
      <p v-if="report.targets != null">适用目标 {{ report.targets }} 项 · 清单 {{ report.files }} 项<span v-if="report.incomplete"> · 未完整读取 {{ report.incomplete }} 项</span></p>
      <p v-if="report.code" role="alert">{{ workflowReasonLabel(report.code) }}</p>
      <details v-if="report.exclusions.length" :open="!report.complete"><summary>排除与读取说明（{{ report.excluded }} 项）</summary><ul><li v-for="row in report.exclusions" :key="row.path"><strong>{{ row.path }}</strong><p>{{ row.reason }}</p></li></ul><p v-if="(report.excluded || 0) > report.exclusions.length">这里只显示前 {{ report.exclusions.length }} 项。完整清单仍保存在本节点的冻结记录中。</p></details>
    </template>
  </div>
</template>
