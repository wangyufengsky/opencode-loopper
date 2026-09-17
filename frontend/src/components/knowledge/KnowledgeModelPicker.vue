<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { Icon } from '@iconify/vue'
import type { AvailableModel } from '@/types/domain'
defineProps<{ model: string; defaultModel: string; models: AvailableModel[]; loading: boolean; error: string; disabled?: boolean }>()
const emit = defineEmits<{ select: [model: string]; load: []; retry: [] }>()
const open = ref(false), root = ref<HTMLElement>(), trigger = ref<HTMLButtonElement>(), choices = ref<HTMLSelectElement>()
async function toggle() { open.value = !open.value; if (open.value) { emit('load'); await nextTick(); choices.value?.focus() } }
async function close() { open.value = false; await nextTick(); trigger.value?.focus() }
function outside(event: PointerEvent) { if (event.target instanceof Node && !root.value?.contains(event.target)) open.value = false }
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
</script>
<template>
  <div ref="root" class="knowledge-model-picker" @keydown.esc.stop.prevent="close">
    <button ref="trigger" type="button" class="knowledge-model-trigger" aria-label="更换问答模型" :aria-expanded="open" aria-haspopup="dialog" :disabled="disabled" @click="toggle">
      <span>{{ model || '选择问答模型' }}</span><small v-if="model && model === defaultModel">全局</small><Icon icon="lucide:chevron-down" />
    </button>
    <section v-if="open" class="knowledge-model-popover" role="dialog" aria-label="选择问答模型">
      <header><strong>问答模型</strong><button type="button" aria-label="关闭模型选择" @click="close">×</button></header>
      <label>当前选择<select ref="choices" :value="model" aria-label="问答模型" :disabled="disabled" @change="emit('select', ($event.target as HTMLSelectElement).value)">
        <option v-if="!model" value="" disabled>选择问答模型</option>
        <option v-if="defaultModel" :value="defaultModel">全局默认 · {{ defaultModel }}</option>
        <option v-for="item in models.filter(item => item.id !== defaultModel)" :key="item.id" :value="item.id">{{ item.label || item.id }}</option>
      </select></label>
      <p v-if="loading" role="status"><span class="knowledge-spinner" aria-hidden="true" />正在读取其他模型…</p>
      <p v-else-if="error" role="alert">{{ error }}<button type="button" @click="emit('retry')">重新读取</button></p>
      <p v-else>仅用于新对话，发送后固定。</p>
    </section>
  </div>
</template>
