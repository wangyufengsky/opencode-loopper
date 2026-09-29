<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { workflowReasonLabel } from '@/utils/displayLabels'
import { repositoryBranchLabel } from './repository'
const props = defineProps<{ content: unknown }>()
const report = computed(() => {
  const v = props.content as Record<string, unknown> | null
  if (!v || v.version !== 1 || v.type !== 'REVIEW_SOURCE' || typeof v.complete !== 'boolean' || typeof v.branchId !== 'string'
    || !['FULL', 'DATE_INCREMENTAL'].includes(String(v.mode)) || v.timezone !== 'Asia/Shanghai') return null
  const date = (value: unknown) => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)
  if (v.mode === 'FULL' ? v.startDate != null || v.endDate != null : !date(v.startDate) || !date(v.endDate) || String(v.endDate) < String(v.startDate)) return null
  const sha = (value: unknown) => typeof value === 'string' && /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(value)
  const count = (value: unknown) => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= 100000
  if (v.complete && (!sha(v.sourceSha) || !sha(v.targetSha) || (v.mode === 'FULL' ? v.baselineSha != null : !sha(v.baselineSha))
    || typeof v.projectPrefix !== 'string' || typeof v.capturedAt !== 'string' || !Number.isFinite(Date.parse(v.capturedAt))
    || typeof v.noChanges !== 'boolean' || typeof v.nonMonotonic !== 'boolean' || !count(v.unitCount) || !count(v.excludedCount)
    || Number(v.excludedCount) > Number(v.unitCount) || v.noChanges && v.unitCount !== 0)) return null
  return { complete: v.complete, mode: v.mode, branch: repositoryBranchLabel(v.branchId), start: v.startDate, end: v.endDate,
    source: String(v.sourceSha || ''), baseline: String(v.baselineSha || ''), target: String(v.targetSha || ''), prefix: v.projectPrefix,
    units: v.unitCount, excluded: v.excludedCount, noChanges: v.noChanges, nonMonotonic: v.nonMonotonic, code: typeof v.code === 'string' ? v.code : null }
})
</script>
<template><div class="workflow-review-source-report">
  <p v-if="!report" role="alert">版本审查采集报告无法读取，请重新读取本次交付物。</p>
  <template v-else>
    <StatusBadge :status="report.complete ? 'SUCCEEDED' : 'FAILED'" :label="report.complete ? '审查资料已固定' : '审查资料采集未完成'" />
    <p>代码来源：{{ report.branch }}</p>
    <p v-if="report.mode === 'FULL'">审查范围：全面审查</p><p v-else>日期范围：{{ report.start }} 至 {{ report.end }}（北京时间，含结束日）</p>
    <template v-if="report.complete">
      <p>来源提交：<code>{{ report.source }}</code></p><p v-if="report.baseline">基线版本：<code>{{ report.baseline }}</code></p><p>目标版本：<code>{{ report.target }}</code></p>
      <p v-if="report.prefix">项目目录：{{ report.prefix }}</p><p>{{ report.units }} 个代码单元 · {{ report.excluded }} 项未纳入正文</p>
      <p v-if="report.noChanges">所选边界版本的代码树相同，无需新增代码分析。</p>
      <p v-if="report.nonMonotonic">提交时间存在倒序，已按主线顺序选择边界版本。</p>
      <p>版本与代码证据已保存，尚未生成审查结论；本节点不运行项目构建或测试。</p>
    </template>
    <p v-if="report.code" role="alert">{{ workflowReasonLabel(report.code) }}</p>
  </template>
</div></template>
<style scoped>.workflow-review-source-report code { overflow-wrap: anywhere; }</style>
