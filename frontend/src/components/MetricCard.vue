<script setup lang="ts">
import { Icon } from '@iconify/vue'
defineProps<{ label: string; value: string | number; detail?: string; icon: string; accent?: string; interactive?: boolean; active?: boolean }>()
const emit = defineEmits<{ select: [] }>()
</script>

<template>
  <button v-if="interactive" type="button" :class="['metric-card', 'metric-button', { active }]" :style="{ '--metric-accent': accent }" :aria-pressed="active" :aria-label="`按${label}筛选，共 ${value} 条`" @click="emit('select')">
    <Icon :icon="icon" :style="{ color: accent ?? 'var(--color-action-primary)' }" width="18" aria-hidden="true" />
    <span class="metric-label">{{ label }}</span>
    <span class="metric-value">{{ value }}</span>
    <span v-if="detail" class="metric-detail">{{ detail }}</span>
  </button>
  <article v-else class="metric-card" :style="{ '--metric-accent': accent }">
    <Icon :icon="icon" :style="{ color: accent ?? 'var(--color-action-primary)' }" width="18" aria-hidden="true" />
    <div class="metric-label">{{ label }}</div>
    <div class="metric-value">{{ value }}</div>
    <div v-if="detail" class="metric-detail">{{ detail }}</div>
  </article>
</template>

<style scoped>
.metric-button { width: 100%; color: inherit; font: inherit; text-align: left; cursor: pointer; }
.metric-button:hover { border-color: color-mix(in srgb, var(--metric-accent) 48%, var(--color-border-default)); transform: translateY(-1px); }
.metric-button:focus-visible { outline: 2px solid var(--metric-accent); outline-offset: 3px; }
.metric-button.active { border-color: var(--metric-accent); box-shadow: 0 0 0 1px color-mix(in srgb, var(--metric-accent) 35%, transparent), var(--shadow-card); }
</style>
