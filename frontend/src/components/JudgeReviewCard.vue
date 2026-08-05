<script setup lang="ts">
import { computed } from 'vue'
import { Icon } from '@iconify/vue'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import type { JudgeRun } from '@/types/domain'
import { judgeReasonMarkdown } from '@/utils/judgeReasonMarkdown'
import { judgeRoleLabel, statusLabel } from '@/utils/displayLabels'

const props = defineProps<{ judge: JudgeRun }>()

const outcome = computed(() => props.judge.verdict ?? props.judge.status)
const outcomeClass = computed(() => `judge-${outcome.value.toLowerCase()}`)
const roleIcon = computed(() => props.judge.role === 'RISK' ? 'lucide:shield-check' : 'lucide:list-checks')
const outcomeIcon = computed(() => {
  if (outcome.value === 'PASS') return 'lucide:circle-check'
  if (outcome.value === 'REVISE') return 'lucide:file-pen-line'
  if (outcome.value === 'BLOCKED') return 'lucide:circle-stop'
  if (outcome.value === 'SESSION_ERROR' || outcome.value === 'UNPARSEABLE') return 'lucide:triangle-alert'
  return 'lucide:loader-circle'
})
const reason = computed(() => props.judge.reason
  ?? (props.judge.status === 'SESSION_ERROR'
    ? '评审会话出错，系统将在预算内创建新的只读会话。'
    : '等待独立审阅结果…'))
const formattedReason = computed(() => judgeReasonMarkdown(reason.value))
</script>

<template>
  <article :class="['judge-card', outcomeClass]">
    <header class="judge-card-head">
      <div class="judge-role">
        <span class="judge-role-icon" aria-hidden="true"><Icon :icon="roleIcon" width="17" /></span>
        <div>
          <strong>{{ judgeRoleLabel(judge.role) }}</strong>
          <span>独立只读 · 第 {{ judge.ordinal }} 次</span>
        </div>
      </div>
      <span class="judge-verdict"><Icon :icon="outcomeIcon" width="14" />{{ statusLabel(outcome) }}</span>
    </header>

    <div class="judge-card-body">
      <p class="judge-section-label">评审结论</p>
      <MarkdownDocument :content="formattedReason" />
    </div>

    <footer v-if="judge.externalSessionId" class="judge-card-footer">
      <Icon icon="lucide:fingerprint" width="13" aria-hidden="true" />
      <span>会话</span>
      <code>{{ judge.externalSessionId }}</code>
    </footer>
  </article>
</template>

<style scoped>
.judge-card { min-width: 0; overflow: hidden; border: 1px solid var(--color-border-default); border-radius: var(--radius-card); background: linear-gradient(145deg, rgb(14 22 38 / 88%), rgb(7 11 20 / 72%)); box-shadow: inset 0 1px rgb(255 255 255 / 2%); }
.judge-card-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 15px 16px; border-bottom: 1px solid rgb(130 147 173 / 12%); background: rgb(11 18 32 / 62%); }
.judge-role { display: flex; min-width: 0; align-items: center; gap: 10px; }
.judge-role-icon { display: grid; flex: 0 0 32px; width: 32px; height: 32px; place-items: center; border: 1px solid rgb(34 211 238 / 22%); border-radius: 9px; background: rgb(34 211 238 / 7%); color: var(--color-accent-cyan); }
.judge-role div { display: grid; min-width: 0; gap: 2px; }
.judge-role strong { color: var(--color-text-primary); font-size: 13px; }
.judge-role span { color: var(--color-text-tertiary); font-family: var(--font-code); font-size: 10px; }
.judge-verdict { display: inline-flex; flex: 0 0 auto; align-items: center; gap: 5px; padding: 4px 8px; border: 1px solid currentcolor; border-radius: 999px; color: var(--color-accent-ai); font-size: 10px; font-weight: 700; line-height: 1; }
.judge-card-body { min-height: 150px; padding: 15px 16px 17px; }
.judge-section-label { margin: 0 0 9px; color: var(--color-text-tertiary); font-size: 10px; font-weight: 750; letter-spacing: .12em; text-transform: uppercase; }
.judge-card-body :deep(.markdown-document) { color: var(--color-text-secondary); font-size: 12px; line-height: 1.68; }
.judge-card-body :deep(.markdown-document h1),
.judge-card-body :deep(.markdown-document h2),
.judge-card-body :deep(.markdown-document h3) { margin: 16px 0 7px; padding: 0; border: 0; color: #e8eef9; font-size: 12px; letter-spacing: 0; }
.judge-card-body :deep(.markdown-document p) { margin: 7px 0; }
.judge-card-body :deep(.markdown-document ol),
.judge-card-body :deep(.markdown-document ul) { margin: 9px 0; padding-left: 21px; }
.judge-card-body :deep(.markdown-document li) { margin: 7px 0; padding-left: 3px; }
.judge-card-body :deep(.markdown-document code) { font-size: .88em; }
.judge-card-footer { display: flex; min-width: 0; align-items: center; gap: 6px; padding: 10px 16px; border-top: 1px solid rgb(130 147 173 / 10%); color: var(--color-text-tertiary); font-size: 10px; }
.judge-card-footer code { min-width: 0; overflow: hidden; color: var(--color-text-secondary); font-family: var(--font-code); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.judge-pass { border-color: rgb(34 197 94 / 34%); }
.judge-pass .judge-verdict { border-color: rgb(34 197 94 / 36%); background: rgb(34 197 94 / 8%); color: var(--color-success); }
.judge-revise, .judge-blocked, .judge-unparseable, .judge-session_error { border-color: rgb(245 158 11 / 38%); }
.judge-revise .judge-verdict, .judge-blocked .judge-verdict, .judge-unparseable .judge-verdict, .judge-session_error .judge-verdict { border-color: rgb(245 158 11 / 38%); background: rgb(245 158 11 / 8%); color: var(--color-session-warning); }

@media (max-width: 620px) {
  .judge-card-head { align-items: flex-start; }
  .judge-role span { font-family: var(--font-ui); }
  .judge-card-body { min-height: 0; }
}
</style>
