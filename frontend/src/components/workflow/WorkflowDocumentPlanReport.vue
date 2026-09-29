<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { workflowReasonLabel } from '@/utils/displayLabels'
const props = defineProps<{ content: unknown }>()
const report = computed(() => {
  const value = props.content as Record<string, unknown> | null
  if (!value || value.version !== 1 || value.type !== 'DOCUMENT_REVIEW_PLAN' || typeof value.complete !== 'boolean') return null
  if (!value.complete) return { complete: false, code: typeof value.code === 'string' ? value.code : null }
  if (!Number.isSafeInteger(value.sectionCount) || Number(value.sectionCount) < 1 || !Number.isSafeInteger(value.batchCount) || Number(value.batchCount) < 1 || Number(value.batchCount) > 62 || !Array.isArray(value.batches) || value.batches.length !== value.batchCount) return null
  const seen = new Set<string>(), batches: { ordinal: number; characters: number; sections: string[] }[] = []
  for (const [index, batch] of value.batches.entries()) {
    if (!batch || batch.ordinal !== index || !Number.isSafeInteger(batch.characters) || batch.characters < 0 || !Array.isArray(batch.sections) || !batch.sections.length || batch.sections.length > 256 || batch.sections.length > 1 && batch.characters > 48000) return null
    const sections: string[] = []
    for (const source of batch.sections) {
      if (!source || typeof source.fileId !== 'string' || !/^DOC-[1-9][0-9]?$/.test(source.fileId) || !Number.isSafeInteger(source.section) || source.section < 1) return null
      const id = `${source.fileId}:${source.section}`
      if (seen.has(id)) return null
      seen.add(id); sections.push(`原文 ${source.fileId.slice(4)} · 第 ${source.section} 章`)
    }
    batches.push({ ordinal: index, characters: batch.characters, sections })
  }
  if (seen.size !== value.sectionCount) return null
  return { complete: true, sectionCount: Number(value.sectionCount), batchCount: Number(value.batchCount), batches }
})
</script>
<template><div class="workflow-document-plan-report">
  <template v-if="report?.complete"><StatusBadge status="SUCCEEDED" label="候选计划已生成" /><p>{{ report.sectionCount }} 个原文章节，分为 {{ report.batchCount }} 批。</p><p>在候选计划中查看变更并确认，确认后再选择执行方式。</p><details v-for="batch in report.batches" :key="batch.ordinal"><summary>第 {{ batch.ordinal + 1 }} 批 · {{ batch.sections.length }} 章 · {{ batch.characters }} 字符</summary><ul><li v-for="section in batch.sections" :key="section">{{ section }}</li></ul></details></template>
  <template v-else-if="report"><StatusBadge status="FAILED" label="分批计划未生成" /><p>{{ workflowReasonLabel(report.code) }}</p></template>
  <p v-else role="alert">原文分批报告无法读取，请重新读取本次交付物。</p>
</div></template>
