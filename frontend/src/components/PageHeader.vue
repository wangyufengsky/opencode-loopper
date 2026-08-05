<script setup lang="ts">
defineProps<{
  eyebrow: string
  title: string
  subtitle?: string
  titleTooltip?: string
}>()
</script>

<template>
  <header class="page-header">
    <div class="page-header-copy">
      <p class="eyebrow">{{ eyebrow }}</p>
      <h1 :class="['page-title', { 'page-title-tooltip': titleTooltip }]" :title="titleTooltip">{{ title }}</h1>
      <p v-if="subtitle" class="page-subtitle">{{ subtitle }}</p>
    </div>
    <div class="header-actions"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.page-header-copy { min-width: 0; flex: 1; }
.page-title-tooltip { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: help; }
</style>
