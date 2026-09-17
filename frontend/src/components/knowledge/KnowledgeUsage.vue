<script setup lang="ts">
import { computed, ref } from 'vue'
import { Icon } from '@iconify/vue'
const props = defineProps<{ usage?: { inputTokens: number | null; outputTokens: number | null } | null }>()
const open = ref(false)
const total = computed(() => props.usage?.inputTokens != null && props.usage.outputTokens != null ? props.usage.inputTokens + props.usage.outputTokens : null)
const number = (value: number | null | undefined) => value == null ? '暂未提供' : value.toLocaleString('zh-CN')
</script>
<template>
  <div class="knowledge-usage" @keydown.esc="open = false">
    <button type="button" aria-label="Token 用量" :aria-expanded="open" :title="`会话用量：输入 ${number(usage?.inputTokens)} / 输出 ${number(usage?.outputTokens)}`" @click="open = !open"><Icon icon="lucide:chart-no-axes-column" /></button>
    <div v-if="open" class="knowledge-usage-popover" role="status"><strong>会话 Token 用量</strong><dl><dt>输入</dt><dd>{{ number(usage?.inputTokens) }}</dd><dt>输出</dt><dd>{{ number(usage?.outputTokens) }}</dd><dt>合计</dt><dd>{{ number(total) }}</dd></dl><button type="button" aria-label="关闭用量" @click="open = false">关闭</button></div>
  </div>
</template>
