<script setup lang="ts">
import { computed, ref } from 'vue'
import { Icon } from '@iconify/vue'
import type { Task } from '@/types/domain'
import { displayLabel } from '@/utils/displayLabels'
import SnapshotReviewBatchesPanel from './SnapshotReviewBatchesPanel.vue'
import StageRail from './StageRail.vue'
import TemplateBatchRecoveryPanel from './TemplateBatchRecoveryPanel.vue'
const props = defineProps<{ task: Task }>()
const copied = ref(false)
const progress = computed(() => props.task.templateProgress)
const total = computed(() => progress.value?.reviewBatches == null || progress.value.contributorBatches == null ? null : progress.value.reviewBatches + progress.value.contributorBatches)
const completed = computed(() => (progress.value?.completedReviews ?? 0) + (progress.value?.completedContributors ?? 0))
const remaining = computed(() => total.value == null ? null : Math.max(0, total.value - completed.value))
const percentage = computed(() => total.value ? Math.min(100, Math.floor(completed.value / total.value * 100)) : 0)
const categories = computed(() => progress.value?.snapshot ? progress.value.snapshot.phases.map(p => ({ label: p.label, done: p.completed, total: p.total })) : [{ label: '代码分析', done: progress.value?.completedReviews ?? 0, total: progress.value?.reviewBatches }, ...(progress.value?.contributorBatches ? [{ label: '人员贡献', done: progress.value.completedContributors, total: progress.value.contributorBatches }] : [])])
const phase = computed(() => {
  if (['COMPLETED','CANCELLED','FAILED','STOPPING','WAITING_INPUT','PENDING_START','QUEUED','PREPARING','PAUSED','RETRY_WAIT'].includes(props.task.status)) return displayLabel(props.task.status)
  const snapshotStep = progress.value?.snapshot && progress.value.steps?.find(step => step.key === progress.value?.currentPhase)
  if (snapshotStep) return snapshotStep.label
  const labels: Record<string,string> = { SNAPSHOT:'冻结证据', PLAN:'规划范围', ANALYSIS:'功能分析', SNAPSHOT_REVIEW:'独立复核与报告', COLLECT:'采集提交', CODE:'分析代码', CONTRIBUTORS:'分析人员贡献', REPORT:'生成并校验报告', REVIEW:'评审报告', COMPLETE:'已确认完成' }
  if (progress.value?.currentPhase) return labels[progress.value.currentPhase] ?? displayLabel(progress.value.currentPhase)
  if (props.task.status === 'AWAITING_DECISION' && progress.value?.dualReviewRequired === false) return '完成收尾'
  if (['JUDGING','AWAITING_DECISION'].includes(props.task.status)) return '评审报告'
  return total.value == null ? '采集提交' : remaining.value === 0 ? '生成并校验报告' : (progress.value?.completedReviews ?? 0) < (progress.value?.reviewBatches ?? 0) ? '分析代码' : '分析人员贡献'
})
const folderName = computed(() => progress.value?.documentPath?.split(/[\\/]/).filter(Boolean).at(-1) ?? '')
async function copyPath() { try { await navigator.clipboard.writeText(progress.value?.documentPath ?? ''); copied.value = true } catch { copied.value = false } }
</script>

<template>
  <section class="card template-progress" aria-label="执行进度">
    <header class="progress-heading"><div><span class="eyebrow">任务执行</span><h2>执行进度</h2></div><div class="phase-state"><span :class="['state-dot', { complete: task.status === 'COMPLETED' }]" />{{ phase }}<small v-if="progress?.repairRound">第 {{ progress.repairRound }} 轮返修</small></div></header>
    <ol v-if="progress?.steps?.length" class="flow" aria-label="执行流程">
      <li v-for="step in progress.steps" :key="step.key" :class="step.state.toLowerCase()" :aria-current="step.state === 'ACTIVE' ? 'step' : undefined"><span class="flow-icon"><Icon :icon="step.state === 'COMPLETE' ? 'lucide:check' : step.state === 'INTERRUPTED' ? 'lucide:pause' : step.state === 'UNKNOWN' ? 'lucide:minus' : 'lucide:circle'" width="15" /></span><span>{{ step.label }}</span><small v-if="step.state === 'UNKNOWN'">历史记录不足</small></li>
    </ol>
    <div class="progress-body">
      <div class="ring-block">
        <div class="ring" role="img" :aria-label="progress?.snapshot ? `已验证 ${completed} 个审查批次` : total ? `分析批次完成 ${completed}/${total}，${percentage}%` : total === 0 ? '无需模型分析' : '分析批次总数待确定'">
          <svg viewBox="0 0 128 128" aria-hidden="true"><circle class="ring-track" cx="64" cy="64" r="55" /><circle class="ring-value" cx="64" cy="64" r="55" pathLength="100" :stroke-dasharray="`${percentage} 100`" /></svg>
          <div><strong v-if="progress?.snapshot">{{ completed }}</strong><strong v-else-if="total">{{ percentage }}<small>%</small></strong><Icon v-else-if="total === 0" icon="lucide:minus" width="28" /><strong v-else class="unknown">待确定</strong><span>{{ progress?.snapshot ? '已验证批次' : '分析批次' }}</span></div>
        </div>
        <strong v-if="progress?.snapshot" class="ring-caption">已验证 {{ completed }} 个审查批次</strong><strong v-else-if="total" class="ring-caption">已完成 {{ completed }} / {{ total }} 个分析批次</strong><span v-else-if="total === 0">所选范围无需模型分析</span><span v-else>采集完成后显示分析批次总数</span>
      </div>
      <div class="analysis-details">
        <div v-for="item in categories" :key="item.label" class="category"><div><span>{{ item.label }}</span><strong>{{ item.done }} / {{ item.total ?? '—' }}</strong></div><div class="meter" aria-hidden="true"><i :style="{ width: `${item.total ? Math.min(100, item.done / item.total * 100) : 0}%` }" /></div></div>
        <div class="batch-counts"><span v-if="remaining !== null"><b>{{ remaining }}</b><span :aria-label="`剩余 ${remaining} 个`">剩余批次</span></span><span><b>{{ progress?.activeBatches ?? 0 }}</b>执行中</span><span :class="{ attention: progress?.failedBatches }"><b>{{ progress?.failedBatches ?? 0 }}</b>需处理</span></div>
        <p class="progress-note">{{ task.status === 'COMPLETED' ? '报告已校验并保存。' : progress?.snapshot?.lightweight ? '分析批次固定；仅发现候选问题的批次增加复核。' : progress?.snapshot ? '显示已生成批次；功能、衔接和补充计划可能增加批次，完成以四阶段状态为准。' : total && percentage === 100 ? '分析已完成，继续生成、校验或评审报告。' : '按已验证批次更新，分析进度不代表任务最终完成。' }}</p>
      </div>
    </div>
    <div v-if="progress?.snapshot" class="progress-note">
      <p>{{ progress.snapshot.mode === 'FULL' ? '全面审查' : '日期增量审查' }}<template v-if="progress.snapshot.lightweight"> · 轻量审查 · 无问题结论不另行复核</template><template v-else> · 计划修订 {{ progress.snapshot.planRevision }} · 补充批次 {{ progress.snapshot.supplements }}</template></p>
      <details v-if="progress.snapshot.targetSha"><summary>审查版本</summary><p v-if="progress.snapshot.baselineSha">基线：{{ progress.snapshot.baselineSha }}</p><p>目标：{{ progress.snapshot.targetSha }}</p></details>
      <SnapshotReviewBatchesPanel :task="task" />
    </div>
    <TemplateBatchRecoveryPanel v-if="task.status === 'RUNNING' || task.status === 'WAITING_INPUT'" :task="task" />
    <footer v-if="progress?.documentPath" class="output-directory"><Icon icon="lucide:folder" width="16" /><details><summary>{{ folderName }}</summary><code>{{ progress.documentPath }}</code></details><el-button text size="small" @click="copyPath">{{ copied ? '已复制' : '复制路径' }}</el-button></footer>
    <details v-if="task.stages?.length" class="stage-details"><summary>阶段详情</summary><StageRail :stages="task.stages" /></details>
  </section>
</template>

<style scoped>
.template-progress { padding:24px; margin-bottom:20px; min-width:0; }
.progress-heading { display:flex; align-items:center; justify-content:space-between; gap:16px; }.progress-heading h2 { margin:5px 0 0; font-size:18px; }.eyebrow { color:var(--color-text-muted); font-size:11px; }.phase-state { display:flex; align-items:center; flex-wrap:wrap; gap:8px; font-size:13px; }.phase-state small { color:var(--color-text-secondary); }.state-dot { width:7px;height:7px;border-radius:50%;background:var(--color-accent-cyan); }.state-dot.complete{background:var(--color-success);}
.flow { display:flex;list-style:none;padding:24px 0;margin:20px 0 0;border-top:1px solid var(--color-border-default);gap:8px; }.flow li{flex:1;position:relative;display:flex;flex-direction:column;align-items:center;text-align:center;gap:9px;font-size:12px;color:var(--color-text-muted);}.flow li:not(:last-child)::after{content:'';position:absolute;top:16px;left:calc(50% + 21px);width:calc(100% - 34px);height:1px;background:var(--color-border-default);}.flow-icon{display:grid;place-items:center;width:32px;height:32px;border:1px solid var(--color-border-default);border-radius:50%;background:var(--color-bg-surface);}.flow .complete{color:var(--color-success);}.flow .active{color:var(--color-accent-cyan);font-weight:600;}.flow .active .flow-icon,.flow .complete .flow-icon{border-color:currentColor;}.flow .interrupted{color:var(--color-session-warning);}.flow small{font-size:10px;}
.progress-body{display:grid;grid-template-columns:250px 1fr;align-items:center;gap:40px;padding:18px 0 22px;}.ring-block{display:grid;justify-items:center;gap:14px;font-size:12px;color:var(--color-text-secondary);}.ring{width:148px;height:148px;position:relative;}.ring svg{width:100%;height:100%;transform:rotate(-90deg);}.ring circle{fill:none;stroke-width:8;}.ring-track{stroke:var(--color-border-default);}.ring-value{stroke:var(--color-accent-cyan);stroke-linecap:round;transition:stroke-dasharray .25s ease;}.ring>div{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:5px;}.ring strong{font-size:35px;color:var(--color-text-primary);font-variant-numeric:tabular-nums;}.ring strong small{font-size:17px;margin-left:3px;}.ring strong.unknown{font-size:22px;}.ring-caption{font-size:13px;font-weight:500;}.analysis-details{min-width:0;}.category{margin-bottom:18px;}.category>div:first-child{display:flex;justify-content:space-between;font-size:13px;margin-bottom:10px;}.category strong{font-variant-numeric:tabular-nums;}.meter{height:6px;border-radius:8px;overflow:hidden;background:var(--color-border-default);}.meter i{height:100%;display:block;background:var(--color-accent-cyan);border-radius:8px;}.batch-counts{display:flex;gap:32px;padding-top:6px;}.batch-counts>span{display:grid;gap:5px;font-size:11px;color:var(--color-text-secondary);}.batch-counts b{font-size:22px;font-weight:600;color:var(--color-text-primary);}.batch-counts .attention b{color:var(--color-session-warning);}.progress-note{font-size:12px;color:var(--color-text-muted);margin:16px 0 0;line-height:1.6;}.output-directory{display:flex;align-items:flex-start;gap:10px;border-top:1px solid var(--color-border-default);padding-top:16px;font-size:12px;color:var(--color-text-secondary);}.output-directory details{flex:1;min-width:0;}.output-directory summary{overflow-wrap:anywhere;cursor:pointer;}.output-directory code{display:block;overflow-wrap:anywhere;margin-top:10px;font-size:11px;}.stage-details{border-top:1px solid var(--color-border-default);margin-top:16px;padding-top:14px;}.stage-details>summary{font-size:12px;color:var(--color-text-secondary);cursor:pointer;margin-bottom:10px;}
@media(max-width:720px){.template-progress{padding:18px;}.progress-body{grid-template-columns:1fr;gap:24px;}.flow{flex-direction:column;gap:14px;}.flow li{flex-direction:row;text-align:left;}.flow li:not(:last-child)::after{left:16px;top:35px;width:1px;height:10px;}.progress-heading{align-items:flex-start;}.batch-counts{justify-content:space-between;}.output-directory{flex-wrap:wrap;}}
@media(prefers-reduced-motion:reduce){.ring-value{transition:none;}}
</style>
