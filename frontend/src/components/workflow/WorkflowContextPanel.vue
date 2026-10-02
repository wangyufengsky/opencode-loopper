<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { Icon } from '@iconify/vue'

const props = withDefaults(defineProps<{ title: string; closeDisabled?: boolean; focusOnOpen?: boolean }>(), { focusOnOpen: true })
const emit = defineEmits<{ close: [] }>()
const panel = ref<HTMLElement>()
onMounted(() => { if (props.focusOnOpen !== false) void nextTick(() => panel.value?.focus({ preventScroll: true })) })
</script>

<template>
  <section ref="panel" class="workflow-context-panel" :aria-label="title" tabindex="-1">
    <header class="workflow-context-heading"><span>{{ title }}</span><button :disabled="closeDisabled" :aria-label="`关闭${title}`" title="关闭 · Esc" @click="emit('close')"><Icon icon="lucide:x" /></button></header>
    <div class="workflow-context-content"><slot /></div>
  </section>
</template>
