<script setup lang="ts">
import { Icon } from '@iconify/vue'
defineProps<{ disabled?: boolean }>()
const emit = defineEmits<{ add: [module: 'free.readonly' | 'free.write' | 'human']; presets: [] }>()
</script>

<template>
  <div class="workflow-add-menu">
    <button class="workflow-module-choice" aria-label="预设工作模块" :disabled="disabled" @click="emit('presets')"><Icon icon="lucide:blocks" /><strong>预设工作模块</strong><span>从已有工作能力开始</span><Icon icon="lucide:arrow-up-right" /></button>
    <p class="workflow-menu-label">自由节点</p>
    <button class="workflow-module-choice" aria-label="只读分析" :disabled="disabled" @click="emit('add', 'free.readonly')"><Icon icon="lucide:scan-text" /><strong>只读分析</strong><span>阅读资料、分析与设计</span></button>
    <button class="workflow-module-choice" aria-label="文件工作" :disabled="disabled" @click="emit('add', 'free.write')"><Icon icon="lucide:code-xml" /><strong>文件工作</strong><span>编写代码与修改文件</span></button>
    <button class="workflow-module-choice" aria-label="人工检查" :disabled="disabled" @click="emit('add', 'human')"><Icon icon="lucide:user-round-check" /><strong>人工检查</strong><span>由你补充结果并确认</span></button>
  </div>
</template>
