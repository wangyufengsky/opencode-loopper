<script setup lang="ts">
import type { Stage } from '@/types/domain'
defineProps<{ stages: Stage[] }>()
</script>

<template>
  <div class="stage-rail" aria-label="阶段进度">
    <div v-for="stage in stages" :key="stage.id" :class="['stage-step', `is-${stage.status.toLowerCase()}`]">
      <span class="stage-dot">{{ stage.ordinal }}</span>
      <span class="stage-name">{{ stage.objective }}</span>
    </div>
  </div>
</template>
