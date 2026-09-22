<script setup lang="ts">
import { computed } from 'vue'
import { Icon } from '@iconify/vue'
import type { DesignerMessage } from '@/types/domain'
import { formatDateTime } from '@/utils/dateTime'
import { statusLabel, userFacingError } from '@/utils/displayLabels'

const props = defineProps<{ entries: DesignerMessage[] }>()

const latestEntry = computed(() => props.entries.at(-1))
</script>

<template>
  <details v-if="entries.length" class="designer-validator-history">
    <summary>
      <span class="validator-summary-title"><Icon icon="lucide:badge-check" width="16" />确定性校验</span>
      <span class="validator-summary-meta">{{ entries.length }} 条<template v-if="latestEntry?.deliveryState"> · {{ statusLabel(latestEntry.deliveryState) }}</template><Icon icon="lucide:chevron-down" width="16" /></span>
    </summary>
    <div class="validator-history-body">
      <article
        v-for="entry in entries"
        :key="entry.id"
        :class="['validator-entry', `validator-${entry.deliveryState?.toLowerCase() ?? 'status'}`]"
      >
        <header>
          <span>{{ entry.workPackageId ? '当前工作包' : '整体需求' }}</span>
          <time :datetime="entry.createdAt">{{ entry.deliveryState ? `${statusLabel(entry.deliveryState)} · ` : '' }}{{ formatDateTime(entry.createdAt) }}</time>
        </header>
        <p>{{ ['RETRYABLE_ERROR', 'TERMINAL_ERROR', 'SESSION_ERROR'].includes(entry.deliveryState ?? '') ? userFacingError(entry.content) : entry.content }}</p>
      </article>
    </div>
  </details>
</template>

<style scoped>
.designer-validator-history { margin: 14px 0; overflow: hidden; border: 1px solid rgb(var(--shade-success-01-rgb) / 30%); border-radius: calc(var(--radius-control) + 6px); background: rgb(var(--shade-success-01-rgb) / 7%); box-shadow: var(--appearance-designer-validator-history-designer-validator-history-box-shadow); }
.designer-validator-history summary { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 12px; padding: 12px 14px; color: var(--shade-success-04); cursor: pointer; list-style: none; }
.designer-validator-history summary::-webkit-details-marker { display: none; }
.validator-summary-title, .validator-summary-meta { display: inline-flex; align-items: center; gap: 7px; }
.validator-summary-title { font: 800 10px/1.4 var(--font-code); letter-spacing: .04em; text-transform: uppercase; }
.validator-summary-meta { margin-left: auto; color: var(--color-text-muted); font: 700 9px/1.4 var(--font-code); }
.validator-summary-meta svg { transition: transform .18s ease; }
.designer-validator-history[open] summary { border-bottom: 1px solid rgb(var(--shade-success-01-rgb) / 24%); }
.designer-validator-history[open] .validator-summary-meta svg { transform: rotate(180deg); }
.validator-history-body { display: grid; }
.validator-entry { padding: 12px 14px; border-bottom: 1px solid rgb(var(--shade-success-01-rgb) / 18%); }
.validator-entry:last-child { border-bottom: 0; }
.validator-entry header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 7px; color: var(--shade-success-04); font: 700 9px/1.4 var(--font-code); }
.validator-entry p { margin: 0; color: var(--color-text-secondary); font-size: 11px; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
.validator-retryable_error header { color: var(--shade-warning-01); }
.validator-normalized header { color: var(--shade-cyan-02); }
.validator-terminal_error header, .validator-session_error header { color: var(--shade-danger-02); }
</style>
