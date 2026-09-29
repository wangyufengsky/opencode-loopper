<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { workflowReasonLabel } from '@/utils/displayLabels'
const props = defineProps<{ content: unknown }>()
const report = computed(() => {
  const v = props.content as Record<string, unknown> | null
  if (!v || v.version !== 1 || !['SNAPSHOT_PLAN', 'SNAPSHOT_DOCUMENT'].includes(String(v.type)) || typeof v.complete !== 'boolean') return null
  const plan = v.type === 'SNAPSHOT_PLAN'
  if (!v.complete) return { complete: false, plan, code: typeof v.code === 'string' ? v.code : null }
  const units = plan ? 'unitCount' : 'sourceCount'
  if (![units, 'excludedCount', 'batchCount', ...(plan ? [] : ['draftCount', 'reviewedCount', 'candidateCount', 'supportedCount', 'fileCount'])].every(key => Number.isSafeInteger(v[key]) && Number(v[key]) >= 0)) return null
  if (Number(v.excludedCount) > Number(v[units]) || Number(v.batchCount) > 63 || plan && typeof v.conditionalReviews !== 'boolean') return null
  if (!plan && (v.draftCount !== v.batchCount || Number(v.reviewedCount) > Number(v.batchCount) || Number(v.supportedCount) > Number(v.candidateCount) || Number(v.fileCount) < 4 || Number(v.fileCount) > 10000 || !['REQUIRED', 'NONE'].includes(String(v.reviewPolicy)) || typeof v.targetSha !== 'string' || !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(v.targetSha))) return null
  return { complete: true, plan, units: Number(v[units]), excluded: Number(v.excludedCount), batches: Number(v.batchCount), reviews: Number(v.reviewedCount), candidates: Number(v.candidateCount), supported: Number(v.supportedCount), files: Number(v.fileCount), conditional: v.conditionalReviews, required: v.reviewPolicy === 'REQUIRED', target: String(v.targetSha) }
})
</script>
<template><div class="workflow-snapshot-summary">
  <template v-if="report?.complete"><StatusBadge status="SUCCEEDED" :label="report.plan ? '候选计划已生成' : '完整报告已生成'" />
    <p>{{ report.units }} 个证据片段 · {{ report.excluded }} 个排除项 · {{ report.batches }} 个分析批次</p>
    <p v-if="report.plan">{{ report.conditional ? '仅有候选问题的批次进入独立复核。' : '本流程没有独立复核节点。' }}在候选计划中查看变更并确认，再选择执行方式。</p>
    <template v-else><p>{{ report.candidates }} 个候选问题 · {{ report.supported }} 个获独立支持 · {{ report.reviews }} 批已复核</p><p>{{ report.required ? '有候选问题的批次均须完成独立复核。' : '按用户选择不要求独立复核；未复核的问题仅作为候选保留。' }}</p><p>{{ report.files }} 份报告文件，可预览主报告或下载整套报告。</p><p>目标版本 <code>{{ report.target }}</code></p></template>
    <p v-if="!report.batches">没有可审查的代码变化或全部属于排除范围；报告保留范围及排除说明，无需模型分析。</p>
    <p>无问题结论未经独立复核；本流程为静态审查，未运行目标项目测试。</p>
  </template>
  <template v-else-if="report"><StatusBadge status="FAILED" :label="report.plan ? '候选计划未生成' : '报告未生成'" /><p>{{ workflowReasonLabel(report.code) }}</p></template>
  <p v-else role="alert">版本流程汇总无法读取，请重新读取本次交付物。</p>
</div></template>
<style scoped>.workflow-snapshot-summary code { overflow-wrap: anywhere; }</style>
