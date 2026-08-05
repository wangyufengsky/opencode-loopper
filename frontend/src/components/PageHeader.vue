<script setup lang="ts">
import { computed, useId } from 'vue'

const props = defineProps<{
  eyebrow: string
  title: string
  titleTooltip?: string
}>()

const tooltipId = `page-title-tooltip-${useId()}`
const tooltipText = computed(() => props.titleTooltip?.trim() ?? '')
</script>

<template>
  <header class="page-header">
    <div class="page-header-copy">
      <p class="eyebrow">{{ eyebrow }}</p>
      <div :class="['page-title-wrap', { 'has-tooltip': tooltipText }]">
        <h1
          :class="['page-title', { 'page-title-tooltip': tooltipText }]"
          :tabindex="tooltipText ? 0 : undefined"
          :aria-describedby="tooltipText ? tooltipId : undefined"
        >{{ title }}</h1>
        <span v-if="tooltipText" :id="tooltipId" class="page-title-hover" role="tooltip">{{ tooltipText }}</span>
      </div>
    </div>
    <div class="header-actions"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.page-header-copy { position: relative; z-index: 20; min-width: 0; flex: 1; }
.page-title-wrap { position: relative; min-width: 0; }
.page-title-tooltip { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: help; }
.page-title-hover { position: absolute; z-index: 20; top: calc(100% + 10px); left: 0; width: min(540px, calc(100vw - 72px)); padding: 10px 12px; visibility: hidden; border: 1px solid rgb(34 211 238 / 28%); border-radius: 8px; color: var(--color-text-primary); background: #10192b; box-shadow: 0 14px 34px rgb(0 0 0 / 38%); font-size: 11px; font-weight: 400; letter-spacing: normal; line-height: 1.55; opacity: 0; overflow-wrap: anywhere; pointer-events: none; transform: translateY(-4px); transition: opacity .12s ease, transform .12s ease, visibility .12s ease; white-space: normal; }
.page-title-wrap.has-tooltip:hover .page-title-hover, .page-title-tooltip:focus-visible + .page-title-hover { visibility: visible; opacity: 1; transform: translateY(0); }
.page-title-tooltip:focus-visible { border-radius: 4px; outline: 2px solid var(--color-accent-cyan); outline-offset: 3px; }
@media (prefers-reduced-motion: reduce) { .page-title-hover { transition: none; } }
</style>
