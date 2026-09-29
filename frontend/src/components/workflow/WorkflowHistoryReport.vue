<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { workflowReasonLabel } from '@/utils/displayLabels'
import { repositoryBranchLabel } from './repository'
const props = defineProps<{ content: unknown }>()
const report = computed(() => {
  const v = props.content as Record<string, unknown> | null
  if (!v || v.version !== 1 || v.type !== 'GIT_HISTORY' || typeof v.complete !== 'boolean' || typeof v.branchId !== 'string'
    || typeof v.startDate !== 'string' || typeof v.endDate !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(v.startDate) || !/^\d{4}-\d{2}-\d{2}$/.test(v.endDate)
    || v.endDate < v.startDate || v.timezone !== 'Asia/Shanghai') return null
  const count = (key: string) => typeof v[key] === 'number' && Number.isSafeInteger(v[key]) && Number(v[key]) >= 0 ? Number(v[key]) : null
  const commits = count('commitCount'), changes = count('changeCount'), excluded = count('excludedCount')
  if (v.complete && (typeof v.commitSha !== 'string' || !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(v.commitSha) || typeof v.projectPrefix !== 'string'
    || commits == null || commits > 100000 || changes == null || excluded == null || excluded > changes || commits === 0 && changes !== 0)) return null
  return { complete: v.complete, branch: repositoryBranchLabel(v.branchId), commit: v.complete ? String(v.commitSha) : '', prefix: v.projectPrefix,
    start: v.startDate, end: v.endDate, commits, changes, excluded, code: typeof v.code === 'string' ? v.code : null }
})
</script>
<template><div class="workflow-history-report">
  <p v-if="!report" role="alert">历史采集报告无法读取，请重新读取本次交付物。</p>
  <template v-else>
    <StatusBadge :status="report.complete ? 'SUCCEEDED' : 'FAILED'" :label="report.complete ? 'Git 历史已固定' : '历史采集未完成'" />
    <p>历史来源：{{ report.branch }}</p><p>日期范围：{{ report.start }} 至 {{ report.end }}（北京时间，含结束日）</p>
    <p v-if="report.commit">固定提交：<code>{{ report.commit }}</code></p><p v-if="report.prefix">项目目录：{{ report.prefix }}</p>
    <p v-if="report.complete">{{ report.commits }} 个提交 · {{ report.changes }} 项文件变更 · {{ report.excluded }} 项排除计量</p>
    <p v-if="report.complete && report.commits === 0">所选范围没有提交，已保存完整的空范围记录。</p>
    <p v-if="report.complete">固定资料按提交保存原始身份、差异和排除依据；敏感文件不包含正文。</p>
    <p v-if="report.code" role="alert">{{ workflowReasonLabel(report.code) }}</p>
  </template>
</div></template>
<style scoped>.workflow-history-report code { overflow-wrap: anywhere; }</style>
