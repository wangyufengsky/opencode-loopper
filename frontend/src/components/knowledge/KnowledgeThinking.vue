<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { Icon } from '@iconify/vue'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import { knowledgeStateLabel, knowledgeToolLabel } from '@/utils/displayLabels'
import type { KnowledgeMessage } from '@/types/domain'
const props = defineProps<{ message: KnowledgeMessage; thinking: string }>()
const contentId = useId()
const active = computed(() => ['PREPARED', 'CREATING', 'SENDING', 'RUNNING'].includes(props.message.state))
const open = ref(active.value)
watch(active, value => { if (!value) open.value = false })
const currentTool = computed(() => [...props.message.calls].reverse().find(call => call.state === 'RUNNING'))
const title = computed(() => {
  if (props.message.state === 'STOPPING') return '正在确认停止'
  if (['UNKNOWN', 'CREATE_UNKNOWN'].includes(props.message.state)) return '正在核对连接'
  if (props.message.state === 'STOPPED') return '思考已停止'
  if (props.message.state === 'FAILED') return '本次回答未完成'
  if (!active.value) return props.thinking ? '思考过程' : '资料读取过程'
  if (currentTool.value) return '正在查阅资料'
  if (props.message.state !== 'RUNNING') return '正在准备回答'
  return props.message.answer && !props.message.answer.trim().startsWith('<think>') ? '正在组织回答' : '正在思考'
})
const visible = computed(() => active.value || props.thinking || props.message.calls.length || ['STOPPING', 'UNKNOWN', 'CREATE_UNKNOWN'].includes(props.message.state))
</script>
<template>
  <section v-if="visible" class="knowledge-thinking" :class="{ 'is-active': active }" aria-label="思考与资料读取">
    <button type="button" class="knowledge-thinking-toggle" :aria-expanded="open" :aria-controls="contentId" @click="open = !open">
      <span v-if="active" class="knowledge-spinner" aria-label="正在生成" role="img" /><Icon v-else :icon="message.state === 'COMPLETED' ? 'lucide:check' : 'lucide:circle-pause'" />
      <strong role="status">{{ title }}</strong><span v-if="currentTool" class="knowledge-thinking-hint">{{ knowledgeToolLabel(currentTool.tool) }}</span><span v-else-if="message.calls.length" class="knowledge-thinking-hint">{{ message.calls.length }} 项资料读取</span>
      <Icon class="knowledge-thinking-chevron" :icon="open ? 'lucide:chevron-up' : 'lucide:chevron-down'" />
    </button>
    <div v-show="open" :id="contentId" class="knowledge-thinking-content">
      <MarkdownDocument v-if="thinking" :content="thinking" :allow-images="false" />
      <p v-else class="knowledge-muted">{{ active ? '等待模型返回思考内容，资料读取会同步显示在这里。' : '模型未返回可展示的思考内容。' }}</p>
      <ol v-if="message.calls.length" class="knowledge-thinking-tools"><li v-for="call in message.calls" :key="call.id"><span class="knowledge-tool-dot" :class="{ running: call.state === 'RUNNING' }" /><span>{{ knowledgeToolLabel(call.tool) }}<small v-if="call.detail">{{ call.detail }}</small></span><small>{{ knowledgeStateLabel(call.state) }}</small></li></ol>
    </div>
  </section>
</template>
