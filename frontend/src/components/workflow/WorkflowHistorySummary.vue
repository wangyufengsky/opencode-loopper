<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { workflowReasonLabel } from '@/utils/displayLabels'
const props = defineProps<{ content: unknown }>()
const report = computed(() => {
  const v = props.content as Record<string, unknown> | null
  if (!v || v.version !== 1 || !['HISTORY_PLAN', 'HISTORY_DOCUMENT'].includes(String(v.type)) || typeof v.complete !== 'boolean') return null
  const plan = v.type === 'HISTORY_PLAN'
  if (!v.complete) return { complete: false, plan, code: typeof v.code === 'string' ? v.code : null }
  const count = plan ? 'commitCount' : 'sourceCount'
  if (![count, 'unitCount', 'batchCount', 'contributorCount', ...(plan ? [] : ['fileCount', 'assessedContributorCount'])].every(key => Number.isSafeInteger(v[key]) && Number(v[key]) >= 0)) return null
  if (Number(v.batchCount) > 63 || Number(v[count]) > 100000 || !plan && (Number(v.fileCount) < 3 || Number(v.fileCount) > 10000 || Number(v.assessedContributorCount) > Number(v.contributorCount) || !['CODE_REVIEW', 'CONTRIBUTION_REPORT'].includes(String(v.reportKind)))) return null
  if (Number(v[count]) === 0 && (v.unitCount !== 0 || v.batchCount !== 0 || v.contributorCount !== 0)) return null
  return { complete: true, plan, commits: Number(v[count]), units: Number(v.unitCount), batches: Number(v.batchCount), people: Number(v.contributorCount), files: Number(v.fileCount), contribution: v.reportKind === 'CONTRIBUTION_REPORT' }
})
</script>
<template><div class="workflow-history-summary">
  <template v-if="report?.complete"><StatusBadge status="SUCCEEDED" :label="report.plan ? '候选计划已生成' : '完整报告已生成'" />
    <p>{{ report.commits }} 个提交 · {{ report.units }} 个证据片段 · {{ report.batches }} 个审查批次</p>
    <p v-if="report.plan">{{ report.people ? `另有 ${report.people} 个个人贡献评价节点。` : '' }}在候选计划中查看变更并确认，再选择执行方式。</p>
    <p v-else>{{ report.files }} 份报告文件<span v-if="report.contribution">，包含 {{ report.people }} 位贡献者的事实与程序计分</span>。可预览主报告、跳转明细或下载整套报告。</p>
    <p v-if="!report.commits">所选日期范围内无提交；保留完整报告说明和导航，无需模型分析。</p>
    <p v-if="!report.plan">结论对应固定历史证据；本流程未复核问题在当前版本中的存续状态，也未运行测试。</p>
  </template>
  <template v-else-if="report"><StatusBadge status="FAILED" :label="report.plan ? '候选计划未生成' : '报告未生成'" /><p>{{ workflowReasonLabel(report.code) }}</p></template>
  <p v-else role="alert">历史流程汇总无法读取，请重新读取本次交付物。</p>
</div></template>
