<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { workflowReasonLabel } from '@/utils/displayLabels'
const props = defineProps<{ content: unknown }>()
const report = computed(() => {
  const value = props.content as Record<string, unknown> | null
  if (!value || value.version !== 1 || !['DESIGN_DOCUMENT', 'ASSESSMENT_DOCUMENT'].includes(String(value.type)) || typeof value.complete !== 'boolean') return null
  const assessment = value.type === 'ASSESSMENT_DOCUMENT'
  if (!value.complete) return { complete: false, code: typeof value.code === 'string' ? value.code : null }
  if (!['sourceCount', 'draftCount', 'reviewedCount', 'reviseCount', 'fileCount'].every(key => Number.isSafeInteger(value[key]) && Number(value[key]) >= 0)) return null
  const sourceCount = Number(value.sourceCount), draftCount = Number(value.draftCount), reviewedCount = Number(value.reviewedCount), reviseCount = Number(value.reviseCount), fileCount = Number(value.fileCount)
  if (!sourceCount || !draftCount || fileCount < (assessment ? 2 : 3) || reviewedCount + reviseCount > draftCount || !['REQUIRED', 'NONE'].includes(String(value.reviewPolicy))) return null
  if (value.reviewPolicy === 'REQUIRED' && reviewedCount !== draftCount) return null
  if (assessment && (!['requirementCount', 'findingCount'].every(key => Number.isSafeInteger(value[key]) && Number(value[key]) >= 0) || typeof value.allRequirementsSatisfied !== 'boolean' || value.requirementCount === 0 && value.allRequirementsSatisfied || value.testExecution !== 'NOT_RUN_STATIC_REVIEW')) return null
  if (assessment && (!Number.isSafeInteger(value.crossBatchReviewedCount) || Number(value.crossBatchReviewedCount) < 0 || Number(value.crossBatchReviewedCount) > reviewedCount || value.reviewPolicy === 'REQUIRED' && value.crossBatchReviewedCount !== draftCount)) return null
  return { complete: true, crossBatchReviewedCount: Number(value.crossBatchReviewedCount ?? reviewedCount), assessment, requirementCount: Number(value.requirementCount), findingCount: Number(value.findingCount), satisfied: value.allRequirementsSatisfied === true, sourceCount, draftCount, reviewedCount, reviseCount, fileCount, required: value.reviewPolicy === 'REQUIRED' }
})
</script>
<template><div class="workflow-document-report">
  <template v-if="report?.complete"><StatusBadge status="SUCCEEDED" label="文档已生成" /><template v-if="report.assessment"><p>覆盖 {{ report.sourceCount }} 个原文章节，汇总 {{ report.draftCount }} 份评审稿，生成 {{ report.fileCount }} 个报告文件。</p><p>{{ report.requirementCount }} 条需求，{{ report.findingCount }} 项问题。{{ !report.requirementCount ? '未提取可评审需求，不能据此认定全部满足。' : report.satisfied ? '全部条目均有符合需求的静态证据。' : '存在未完全满足或无法判断的需求。' }}</p><p>本次未运行构建、测试或项目脚本。</p></template><p v-else>覆盖 {{ report.sourceCount }} 个源码文件，汇总 {{ report.draftCount }} 份设计稿，生成 {{ report.fileCount }} 个 Markdown 文件。</p><p>独立复核通过 {{ report.reviewedCount }} / {{ report.draftCount }} 份<span v-if="report.reviseCount">，{{ report.reviseCount }} 份要求返修</span>。</p><p v-if="report.assessment && report.crossBatchReviewedCount < report.reviewedCount">部分复核未读取全部批次稿件，跨批次核对尚未完整。</p><p v-if="!report.required">按自定义策略生成，未要求全部复核通过。</p></template>
  <template v-else-if="report"><StatusBadge status="FAILED" label="文档未生成" /><p>{{ workflowReasonLabel(report.code) }}</p></template>
  <p v-else role="alert">文档汇总报告无法读取，请重新读取本次交付物。</p>
</div></template>
