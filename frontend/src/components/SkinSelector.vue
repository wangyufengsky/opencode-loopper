<script setup lang="ts">
import { skins } from '@/themes/registry'
import { applySkin, skinId } from '@/themes/state'

function select(event: Event) {
  applySkin((event.target as HTMLSelectElement).value)
}
</script>

<template>
  <label class="skin-selector">
    <span>皮肤</span>
    <select aria-label="选择皮肤" :value="skinId" @change="select">
      <option v-for="skin in skins" :key="skin.id" :value="skin.id">{{ skin.label }}</option>
    </select>
  </label>
</template>

<style scoped>
.skin-selector { display: inline-flex; align-items: center; gap: 8px; color: var(--color-text-secondary); font-size: 12px; }
.skin-selector select { min-height: 34px; max-width: 100%; padding: 6px 28px 6px 10px; border: 1px solid var(--color-border-default); border-radius: var(--radius-control); color: var(--color-text-primary); background: var(--color-bg-surface); cursor: pointer; }
.skin-selector select:hover { border-color: var(--color-text-muted); }
</style>
