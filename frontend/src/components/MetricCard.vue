<script setup lang="ts">
import { Icon } from '@iconify/vue'
defineProps<{ label: string; value: string | number; detail: string; icon: string; accent?: string }>()
</script>

<template>
  <article class="metric-card" :style="{ '--metric-accent': accent }">
    <Icon :icon="icon" :style="{ color: accent ?? 'var(--color-action-primary)' }" width="18" />
    <div class="metric-label">{{ label }}</div>
    <div class="metric-value">{{ value }}</div>
    <div class="metric-detail">{{ detail }}</div>
  </article>
</template>
