<script setup lang="ts">
defineProps<{ eyebrow: string; title: string; subtitle: string }>()
</script>

<template>
  <header class="page-header">
    <div>
      <p class="eyebrow">{{ eyebrow }}</p>
      <h1 class="page-title">{{ title }}</h1>
      <p class="page-subtitle">{{ subtitle }}</p>
    </div>
    <div class="header-actions"><slot name="actions" /></div>
  </header>
</template>
