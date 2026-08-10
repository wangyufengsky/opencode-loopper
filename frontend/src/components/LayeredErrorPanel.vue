<script setup lang="ts">
import { computed } from 'vue'
import { Icon } from '@iconify/vue'
import type { ErrorEvent, JudgeRun } from '@/types/domain'
import { judgeRoleLabel, statusLabel } from '@/utils/displayLabels'

const props = withDefaults(defineProps<{ error: ErrorEvent, judges?: JudgeRun[] }>(), { judges: () => [] })

const isJudgeReview = computed(() => props.error.layer === 'VERIFICATION' && props.error.code.startsWith('JUDGE_'))
const judgeRows = computed(() => [...props.judges]
  .sort((left, right) => left.role.localeCompare(right.role))
  .map((judge) => ({
    ...judge,
    outcome: judge.verdict ?? judge.status,
    summary: judgeSummary(judge),
  })))

function judgeSummary(judge: JudgeRun) {
  const fallback = judge.status === 'SESSION_ERROR'
    ? '评审会话发生异常，可重新发起一组新的只读评审。'
    : '当前评审没有返回可展示的结论。'
  const reason = judge.reason?.trim() || fallback
  const firstContentLine = reason.split('\n')
    .map((line) => line.trim())
    .find((line) => line && !line.startsWith('#'))
  return (firstContentLine ?? reason).replace(/^\d+[.)]\s*/, '')
}
</script>

<template>
  <section v-if="isJudgeReview" class="judge-attention-panel" role="status" aria-live="polite">
    <header class="judge-attention-header">
      <span class="judge-attention-icon" aria-hidden="true"><Icon icon="lucide:scale" width="18" /></span>
      <div>
        <p class="eyebrow">FINAL REVIEW / ACTION REQUIRED</p>
        <h3>{{ error.code === 'JUDGE_CONFLICT' ? '需求 / 风险双评审尚未达成一致' : '双评审结论需要处理' }}</h3>
        <p>当前结论已拆分为结构化摘要；完整评审证据保留在上方“需求 / 风险双评审”。</p>
      </div>
      <span class="judge-attention-code">{{ error.code }}</span>
    </header>

    <div v-if="judgeRows.length" class="judge-attention-grid">
      <article v-for="judge in judgeRows" :key="judge.id" class="judge-attention-review">
        <header>
          <span class="judge-review-role">
            <Icon :icon="judge.role === 'RISK' ? 'lucide:shield-check' : 'lucide:list-checks'" width="15" />
            {{ judgeRoleLabel(judge.role) }}
          </span>
          <span :class="['judge-review-outcome', `outcome-${judge.outcome.toLowerCase()}`]">{{ statusLabel(judge.outcome) }}</span>
        </header>
        <p>{{ judge.summary }}</p>
        <small>第 {{ judge.ordinal }} 次 · 独立只读评审</small>
      </article>
    </div>
    <p v-else class="judge-attention-fallback">{{ error.message }}</p>

    <footer class="judge-attention-footer">
      <span><Icon icon="lucide:rotate-ccw" width="14" />补齐条件后，可从页首重新发起需求 / 风险双评审。</span>
      <time class="mono">{{ error.occurredAt }}</time>
    </footer>
  </section>
  <section v-else-if="error.layer !== 'FIELD'" :class="['error-panel', `error-panel-${error.layer.toLowerCase()}`]" :role="error.layer === 'TASK' ? 'alert' : 'status'" aria-live="polite">
    <Icon class="error-panel-icon" :icon="error.layer === 'TASK' ? 'lucide:octagon-x' : error.layer === 'SESSION' ? 'lucide:refresh-cw' : 'lucide:shield-alert'" />
    <div>
      <h3 v-if="error.layer === 'SESSION'">当前 Session 已结束，新 Session 将继续 Loop</h3>
      <h3 v-else-if="error.layer === 'VERIFICATION'">验证未通过，平台将携带证据进入下一轮</h3>
      <h3 v-else>Task 已终止，后续 Session 不会再创建</h3>
      <p>{{ error.message }}</p>
      <p class="mono tiny">{{ error.code }} · {{ error.occurredAt }}</p>
    </div>
  </section>
  <p v-else class="inline-field-error" role="alert"><Icon icon="lucide:circle-alert" /> {{ error.message }}</p>
</template>

<style scoped>
.judge-attention-panel { overflow: hidden; border: 1px solid rgb(245 158 11 / 34%); border-radius: var(--radius-card); background: linear-gradient(135deg, rgb(245 158 11 / 7%), rgb(14 22 38 / 82%) 42%, rgb(7 11 20 / 76%)); box-shadow: inset 0 1px rgb(255 255 255 / 2%); }
.judge-attention-header { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: start; gap: 12px; padding: 15px 16px 14px; border-bottom: 1px solid rgb(245 158 11 / 14%); background: rgb(11 18 32 / 44%); }
.judge-attention-icon { display: grid; width: 34px; height: 34px; place-items: center; border: 1px solid rgb(245 158 11 / 28%); border-radius: 9px; background: rgb(245 158 11 / 8%); color: var(--color-session-warning); }
.judge-attention-header h3 { margin: 3px 0 0; color: var(--color-text-primary); font-size: 13px; }
.judge-attention-header p:not(.eyebrow) { margin: 6px 0 0; color: var(--color-text-secondary); font-size: 11px; line-height: 1.55; }
.judge-attention-code { padding: 5px 8px; border: 1px solid rgb(245 158 11 / 22%); border-radius: 999px; background: rgb(245 158 11 / 7%); color: #fbbf24; font: 700 9px/1 var(--font-code); }
.judge-attention-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; padding: 12px 16px; }
.judge-attention-review { min-width: 0; padding: 12px; border: 1px solid var(--color-border-default); border-radius: 9px; background: rgb(7 12 22 / 56%); }
.judge-attention-review > header { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.judge-review-role { display: inline-flex; min-width: 0; align-items: center; gap: 7px; color: var(--color-text-primary); font-size: 11px; font-weight: 700; }
.judge-review-role > svg { flex: 0 0 auto; color: var(--color-accent-cyan); }
.judge-review-outcome { flex: 0 0 auto; padding: 4px 7px; border-radius: 999px; background: rgb(101 115 138 / 10%); color: var(--color-text-secondary); font-size: 9px; font-weight: 750; }
.judge-review-outcome.outcome-pass { background: rgb(34 197 94 / 9%); color: var(--color-success); }
.judge-review-outcome.outcome-revise,
.judge-review-outcome.outcome-blocked,
.judge-review-outcome.outcome-unparseable,
.judge-review-outcome.outcome-session_error,
.judge-review-outcome.outcome-failed,
.judge-review-outcome.outcome-timed_out { background: rgb(245 158 11 / 9%); color: var(--color-session-warning); }
.judge-attention-review > p { margin: 10px 0 0; color: var(--color-text-secondary); font-size: 11px; line-height: 1.6; overflow-wrap: anywhere; }
.judge-attention-review > small { display: block; margin-top: 9px; color: var(--color-text-tertiary); font: 9px/1.4 var(--font-code); }
.judge-attention-fallback { margin: 0; padding: 13px 16px; color: var(--color-text-secondary); font-size: 11px; line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; }
.judge-attention-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 16px; border-top: 1px solid rgb(130 147 173 / 10%); color: var(--color-text-tertiary); font-size: 10px; }
.judge-attention-footer > span { display: inline-flex; align-items: center; gap: 6px; }
.judge-attention-footer > span > svg { color: var(--color-session-warning); }
.judge-attention-footer time { flex: 0 0 auto; font-size: 9px; }
@media (max-width: 760px) {
  .judge-attention-header { grid-template-columns: auto minmax(0, 1fr); }
  .judge-attention-code { grid-column: 2; width: fit-content; }
  .judge-attention-grid { grid-template-columns: 1fr; }
  .judge-attention-footer { align-items: flex-start; flex-direction: column; }
}
</style>
