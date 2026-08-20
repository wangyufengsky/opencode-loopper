<script setup lang="ts">
import { computed } from 'vue'
import { Icon } from '@iconify/vue'
import type { DesignerMessage } from '@/types/domain'
import { formatDateTime } from '@/utils/dateTime'
import { statusLabel, userFacingError } from '@/utils/displayLabels'

const props = defineProps<{ entries: DesignerMessage[] }>()

const hasError = computed(() => props.entries.some((entry) => ['RETRYABLE_ERROR', 'TERMINAL_ERROR', 'SESSION_ERROR'].includes(entry.deliveryState ?? '')
  || entry.content.includes('SYSTEM_ERROR')))

function messageContent(entry: DesignerMessage) {
  return ['RETRYABLE_ERROR', 'TERMINAL_ERROR', 'SESSION_ERROR'].includes(entry.deliveryState ?? '')
    || entry.content.includes('SYSTEM_ERROR')
    ? userFacingError(entry.content)
    : entry.content
}
</script>

<template>
  <details v-if="entries.length" :class="['designer-system-message-history', { error: hasError }]">
    <summary>
      <span class="system-summary-title">
        <Icon :icon="hasError ? 'lucide:triangle-alert' : 'lucide:info'" width="16" />
        系统消息
      </span>
      <span class="system-summary-meta">{{ entries.length }} 条<Icon icon="lucide:chevron-down" width="16" /></span>
    </summary>
    <div class="system-message-body">
      <article v-for="entry in entries" :key="entry.id" class="system-message-entry">
        <header>
          <span>{{ entry.workPackageId ? '当前工作包' : '整体需求' }}</span>
          <time :datetime="entry.createdAt">{{ entry.deliveryState ? `${statusLabel(entry.deliveryState)} · ` : '' }}{{ formatDateTime(entry.createdAt) }}</time>
        </header>
        <p>{{ messageContent(entry) }}</p>
      </article>
    </div>
  </details>
</template>

<style scoped>
.designer-system-message-history { margin: 14px 0; overflow: hidden; border: 1px solid var(--color-border-default); border-radius: 12px; background: rgb(15 23 42 / 54%); }
.designer-system-message-history summary { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 12px; padding: 12px 14px; color: var(--color-text-secondary); cursor: pointer; list-style: none; }
.designer-system-message-history summary::-webkit-details-marker { display: none; }
.system-summary-title, .system-summary-meta { display: inline-flex; align-items: center; gap: 7px; }
.system-summary-title { font: 800 10px/1.4 var(--font-code); letter-spacing: .04em; }
.system-summary-meta { margin-left: auto; color: var(--color-text-muted); font: 700 9px/1.4 var(--font-code); }
.system-summary-meta svg { transition: transform .18s ease; }
.designer-system-message-history[open] summary { border-bottom: 1px solid var(--color-border-default); color: var(--color-accent-cyan); }
.designer-system-message-history[open] .system-summary-meta svg { transform: rotate(180deg); }
.system-message-body { display: grid; }
.system-message-entry { padding: 12px 14px; border-bottom: 1px solid var(--color-border-default); }
.system-message-entry:last-child { border-bottom: 0; }
.system-message-entry header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 6px 12px; margin-bottom: 7px; color: var(--color-text-muted); font: 700 9px/1.4 var(--font-code); }
.system-message-entry p { margin: 0; color: var(--color-text-secondary); font-size: 11px; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
.designer-system-message-history.error { border-color: rgb(239 68 68 / 38%); background: rgb(239 68 68 / 7%); }
.designer-system-message-history.error summary { color: #fca5a5; }
.designer-system-message-history.error[open] summary, .designer-system-message-history.error .system-message-entry { border-color: rgb(239 68 68 / 24%); }
</style>
