<script setup lang="ts">
import { ref } from 'vue'
import { canvasAreas, readCanvasPreferences, writeCanvasPreference, type CanvasArea, type CanvasRuntime } from './canvasRuntime'
function storage() { try { return window.localStorage } catch { return undefined } }
const preferences = ref(readCanvasPreferences(storage())), message = ref(''), failed = ref(false)
function change(area: CanvasArea, event: Event) {
  const value = (event.target as HTMLSelectElement).value
  if (value !== 'react' && value !== 'vue') return
  const runtime: CanvasRuntime = value
  failed.value = !writeCanvasPreference(storage(), area, runtime)
  if (!failed.value) preferences.value = { ...preferences.value, [area]: runtime }
  else (event.target as HTMLSelectElement).value = preferences.value[area] ?? 'react'
  message.value = failed.value ? '浏览器未能保存偏好，请检查本地存储权限后重试。' : '已保存。下次重新进入对应页面时使用所选画布；当前打开的页面保持不变。'
}
</script>
<template>
  <section aria-labelledby="canvas-settings-title" class="canvas-runtime-settings">
    <h2 id="canvas-settings-title">画布显示</h2>
    <p>遇到显示或交互问题时，可选择兼容画布。偏好仅保存在此浏览器，下次重新进入对应页面时生效。</p>
    <p>请先完成或恢复当前操作、保存草稿后再离开；更改偏好不会刷新页面，也不会重发操作。</p>
    <label v-for="area in canvasAreas" :key="area.id"><span>{{ area.title }}</span>
      <select :value="preferences[area.id] ?? 'react'" @change="change(area.id, $event)"><option value="react">新版画布</option><option value="vue">兼容画布</option></select>
    </label>
    <p v-if="message" :role="failed ? 'alert' : 'status'">{{ message }}</p>
  </section>
</template>
<style scoped>
.canvas-runtime-settings { max-width: 760px; color: var(--color-text-primary); }
h2 { margin-top: 0; font-size: 18px; } p { color: var(--color-text-secondary); font-size: 13px; line-height: 1.7; }
label { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 14px 0; border-bottom: 1px solid var(--color-border-muted); }
select { min-width: 130px; padding: 8px; font: inherit; color: var(--color-text-primary); border: 1px solid var(--color-border-default); border-radius: var(--radius-control); background: var(--color-bg-surface); }
select:focus-visible { outline: 2px solid var(--color-action-primary); outline-offset: 3px; }
@media(max-width: 500px) { label { align-items: stretch; flex-direction: column; gap: 8px; } }
</style>
