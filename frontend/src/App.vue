<script setup lang="ts">
import StoryAccountingDialog from '@/components/StoryAccountingDialog.vue'
import AppSidebar from '@/components/AppSidebar.vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Icon } from '@iconify/vue'

const route = useRoute(), navigationOpen = ref(false), navigationToggle = ref<HTMLButtonElement>(), navigationPanel = ref<HTMLElement>()
const canvasRoute = computed(() => /^\/workflows\/[^/]+\/?$/.test(route.path) || /^\/requirements\/(?!new\/?$)[^/]+\/?$/.test(route.path) || /^\/ppt\/[^/]+\/?$/.test(route.path))
watch(() => route.fullPath, () => { navigationOpen.value = false })
function closeNavigation() { navigationOpen.value = false; void nextTick(() => navigationToggle.value?.focus()) }
function toggleNavigation() {
  if (navigationOpen.value) { closeNavigation(); return }
  navigationOpen.value = true
  void nextTick(() => navigationPanel.value?.querySelector<HTMLElement>('a, button')?.focus())
}
function navigationKeys(event: KeyboardEvent) {
  if (!canvasRoute.value || !navigationOpen.value) return
  if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); closeNavigation(); return }
  if (event.key !== 'Tab') return
  const items = [navigationToggle.value, ...Array.from(navigationPanel.value?.querySelectorAll<HTMLElement>('a[href], button:not(:disabled)') || [])].filter((item): item is HTMLElement => !!item)
  const first = items[0], last = items.at(-1)
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
</script>

<template><el-config-provider :locale="zhCn"><a class="skip-link" href="#main-content">跳到主内容</a><div class="app-shell" :class="{ 'canvas-shell': canvasRoute }" @keydown.capture="navigationKeys">
  <button v-if="canvasRoute" ref="navigationToggle" class="canvas-navigation-toggle" :aria-expanded="navigationOpen" aria-controls="canvas-navigation" :aria-label="navigationOpen ? '收起导航' : '展开导航'" @click="toggleNavigation"><Icon :icon="navigationOpen ? 'lucide:x' : 'lucide:panel-left'" /></button>
  <template v-if="canvasRoute"><div v-if="navigationOpen" class="canvas-navigation-backdrop" @click="closeNavigation" /><div v-if="navigationOpen" id="canvas-navigation" ref="navigationPanel" class="canvas-navigation" role="dialog" aria-modal="true" aria-label="应用导航"><AppSidebar /></div></template>
  <AppSidebar v-else />
  <div class="app-main" :inert="canvasRoute && navigationOpen"><RouterView /></div>
</div><StoryAccountingDialog /></el-config-provider></template>
