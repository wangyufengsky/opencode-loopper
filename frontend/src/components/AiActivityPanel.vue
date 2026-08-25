<script setup lang="ts">
import { computed } from 'vue'
import { Icon } from '@iconify/vue'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import TokenUsageWindow from '@/components/TokenUsageWindow.vue'
import type { DesignerActivity, ProjectConventionActivity } from '@/types/domain'
import { activityLabel, activityTypeLabel, statusLabel } from '@/utils/displayLabels'

const props = defineProps<{
  title: string
  panelLabel: string
  stateText: string
  activity?: DesignerActivity | ProjectConventionActivity
  loading?: boolean
  error?: string
}>()

const latest = computed(() => props.activity?.parts.at(-1))
</script>

<template>
  <article class="ai-activity" role="status" aria-live="polite" :aria-label="panelLabel">
    <div class="activity-heading">
      <span class="thinking-orbit" aria-hidden="true"><span /></span>
      <div class="thinking-copy">
        <strong>{{ title }}<span class="thinking-dots" aria-hidden="true"><i /><i /><i /></span></strong>
        <small>{{ stateText }}</small>
      </div>
      <TokenUsageWindow :total-tokens="activity?.usage.totalTokens" />
    </div>
    <div class="current-activity">
      <p v-if="error" class="activity-warning"><Icon icon="lucide:wifi-off" />{{ error }}</p>
      <template v-if="latest">
        <header>
          <span>{{ activityTypeLabel(latest.type) }}</span>
          <strong>{{ activityLabel(latest) }}</strong>
          <i v-if="latest.status">{{ statusLabel(latest.status) }}</i>
        </header>
        <MarkdownDocument v-if="latest.content" :content="latest.content" />
      </template>
      <p v-else class="activity-detail">{{ loading ? '正在连接当前会话…' : activity?.detail || '正在等待最新思考或输出…' }}</p>
    </div>
  </article>
</template>

<style scoped>
.ai-activity { position: relative; display: grid; gap: 14px; min-height: 180px; padding: 17px 18px; overflow: hidden; border: 1px solid rgb(139 92 246 / 30%); border-radius: 12px; background: linear-gradient(100deg, rgb(139 92 246 / 9%), rgb(34 211 238 / 5%), rgb(139 92 246 / 9%)); background-size: 220% 100%; animation: thinking-sheen 3s ease-in-out infinite; }
.activity-heading { display: flex; align-items: center; gap: 14px; min-width: 0; }
.thinking-orbit { display: grid; flex: 0 0 auto; place-items: center; width: 38px; height: 38px; border: 2px solid rgb(139 92 246 / 18%); border-top-color: #a78bfa; border-right-color: var(--color-accent-cyan); border-radius: 50%; animation: thinking-spin 1s linear infinite; }
.thinking-orbit span { width: 8px; height: 8px; border-radius: 50%; background: linear-gradient(135deg, #a78bfa, var(--color-accent-cyan)); }
.thinking-copy { min-width: 0; margin-right: auto; }.thinking-copy strong { display: flex; align-items: baseline; color: #f5f3ff; font-size: 13px; }.thinking-copy small { display: block; margin-top: 5px; color: var(--color-text-secondary); font: 9px/1.45 var(--font-code); }
.thinking-dots { display: inline-flex; gap: 3px; margin-left: 5px; }.thinking-dots i { width: 4px; height: 4px; border-radius: 50%; background: var(--color-accent-cyan); animation: thinking-dot 1.15s ease-in-out infinite; }.thinking-dots i:nth-child(2) { animation-delay: .16s; }.thinking-dots i:nth-child(3) { animation-delay: .32s; }
.current-activity { min-width: 0; padding: 12px 14px; border: 1px solid rgb(148 163 184 / 18%); border-radius: 9px; background: rgb(7 11 20 / 52%); }.current-activity > header { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; margin-bottom: 9px; color: var(--color-text-secondary); font: 8px/1.2 var(--font-code); }.current-activity > header strong { color: var(--color-text-primary); font-size: 9px; }.current-activity > header i { margin-left: auto; color: var(--color-accent-cyan); font-style: normal; }.current-activity :deep(.markdown-document) { max-height: 260px; overflow: auto; color: var(--color-text-primary); font-size: 12px; line-height: 1.65; }
.activity-detail, .activity-warning { display: flex; align-items: center; gap: 7px; margin: 0; color: var(--color-text-secondary); font-size: 11px; line-height: 1.55; }.activity-warning { margin-bottom: 9px; color: var(--color-session-warning); }
@keyframes thinking-spin { to { transform: rotate(360deg); } } @keyframes thinking-dot { 0%, 65%, 100% { opacity: .25; transform: translateY(0); } 35% { opacity: 1; transform: translateY(-4px); } } @keyframes thinking-sheen { 0%, 100% { background-position: 0 50%; } 50% { background-position: 100% 50%; } }
@media (prefers-reduced-motion: reduce) { .ai-activity, .thinking-orbit, .thinking-dots i { animation: none; } }
</style>
