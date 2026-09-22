<script setup lang="ts">
import { Icon } from '@iconify/vue'
defineProps<{ filename: string; sectionCount: number; limitations?: number }>()
</script>
<template>
  <span class="document-file-summary">
    <span class="document-file-icon"><Icon icon="lucide:file-text" width="20" /></span>
    <span class="document-file-copy"><strong>{{ filename }}</strong><small>{{ sectionCount }} 个分段<span v-if="limitations"> · {{ limitations }} 项提取局限</span></small></span>
    <Icon class="document-file-chevron" icon="lucide:chevron-down" width="16" />
  </span>
</template>
<style scoped>
.document-file-summary { display: flex; align-items: center; gap: 12px; padding: 14px 16px; border: 1px solid var(--color-border-default); border-radius: var(--radius-control); background: var(--color-bg-elevated); }
.document-file-icon { display: grid; place-items: center; flex-shrink: 0; width: 36px; height: 40px; color: var(--color-accent-cyan); background: var(--color-bg-surface); border-radius: var(--radius-control); }
.document-file-copy { display: grid; flex: 1; min-width: 0; gap: 4px; }.document-file-copy strong { font-size: 13px; line-height: 1.7; font-weight: 500; overflow-wrap: anywhere; }.document-file-copy small { color: var(--color-text-secondary); font-size: 12px; }
.document-file-chevron { flex-shrink: 0; color: var(--color-text-secondary); }
details[open] > summary .document-file-chevron { transform: rotate(180deg); }
</style>
