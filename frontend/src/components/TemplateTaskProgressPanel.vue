<script setup lang="ts">
import { computed } from 'vue'
import type { Task } from '@/types/domain'
import { displayLabel } from '@/utils/displayLabels'

const props = defineProps<{ task: Task }>()
const progress = computed(() => props.task.templateProgress)
const total = computed(() => progress.value?.reviewBatches == null || progress.value.contributorBatches == null
  ? null : progress.value.reviewBatches + progress.value.contributorBatches)
const completed = computed(() => (progress.value?.completedReviews ?? 0) + (progress.value?.completedContributors ?? 0))
const remaining = computed(() => Math.max(0, (total.value ?? 0) - completed.value))
const percentage = computed(() => total.value ? Math.min(100, Math.floor(completed.value / total.value * 100)) : 0)
const phase = computed(() => {
  const status = props.task.status
  if (['COMPLETED', 'CANCELLED', 'FAILED', 'STOPPING', 'WAITING_INPUT', 'PENDING_START', 'QUEUED', 'PREPARING'].includes(status)) return displayLabel(status)
  if (status === 'AWAITING_DECISION' && progress.value?.dualReviewRequired === false) return '完成收尾'
  if (status === 'JUDGING' || status === 'AWAITING_DECISION') return '评审报告'
  if (status === 'VERIFYING') return '校验报告'
  if (total.value === null) return '采集提交'
  if (remaining.value === 0) return '生成报告'
  return completed.value < (progress.value?.reviewBatches ?? 0) ? '分析代码' : '分析人员贡献'
})
</script>

<template>
  <section class="card card-pad template-progress" aria-label="执行进度">
    <div class="progress-heading"><h2>执行进度</h2><span>{{ phase }}<span v-if="progress?.repairRound" class="muted"> · 第 {{ progress.repairRound }} 轮返修</span></span></div>
    <template v-if="total !== null && total > 0">
      <div class="progress-count" aria-live="polite"><strong>已完成 {{ completed }} / {{ total }} 个分析批次</strong><span class="muted">剩余 {{ remaining }} 个</span></div>
      <el-progress :percentage="percentage" :show-text="false" :stroke-width="8" aria-label="分析批次进度" />
      <div class="progress-breakdown muted tiny"><span>代码分析 {{ progress?.completedReviews }} / {{ progress?.reviewBatches }}</span><span v-if="progress?.contributorBatches">人员贡献 {{ progress.completedContributors }} / {{ progress.contributorBatches }}</span><span v-if="progress?.activeBatches">{{ progress.activeBatches }} 个执行中</span><span v-if="progress?.failedBatches">{{ progress.failedBatches }} 个需处理</span></div>
    </template>
    <p v-else-if="total === 0" class="muted">所选范围无需模型分析</p>
    <p v-else-if="!['COMPLETED', 'CANCELLED', 'FAILED'].includes(task.status)" class="muted">采集完成后显示分析批次总数</p>
    <div v-if="progress?.documentPath" class="output-directory"><span class="muted tiny">文档路径</span><span class="mono tiny">{{ progress.documentPath }}</span></div>
  </section>
</template>

<style scoped>
.template-progress { display: grid; gap: 16px; min-width: 0; margin-bottom: 20px; }
.progress-heading, .progress-count, .progress-breakdown { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.progress-heading h2 { font-size: 18px; margin: 0; }
.progress-breakdown { justify-content: flex-start; gap: 20px; }
.template-progress p { margin: 0; }
.output-directory { display: grid; gap: 8px; overflow-wrap: anywhere; }
</style>
