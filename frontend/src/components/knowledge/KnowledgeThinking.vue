<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { Icon } from '@iconify/vue'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import { knowledgeStateLabel, knowledgeToolLabel } from '@/utils/displayLabels'
import type { KnowledgeMessage } from '@/types/domain'
const props = defineProps<{ message: KnowledgeMessage; thinking: string; answer: string }>()
const contentId = useId(), thinkingOpen = ref(false), toolsOpen = ref(false)
const active = computed(() => ['PREPARED', 'CREATING', 'SENDING', 'RUNNING'].includes(props.message.state) && !props.message.questions?.some(q => ['PENDING', 'PREPARED', 'SENDING', 'UNKNOWN'].includes(q.state)))
const latestThinking = computed(() => props.thinking.trim().split(/\n\s*\n/).at(-1)?.replace(/\s+/g, ' ').slice(-160) || '')
const latestCall = computed(() => props.message.calls.at(-1))
const waitingLabel = computed(() => {
  if (!active.value || props.thinking.trim() || props.answer.trim() || latestCall.value?.state === 'RUNNING') return ''
  if (props.message.state === 'RUNNING') return '正在思考'
  return props.message.state === 'SENDING' ? '正在发送问题' : '正在准备回答'
})
</script>
<template>
  <div v-if="waitingLabel" class="knowledge-waiting" role="status" aria-live="polite">
    <Icon icon="lucide:sparkles" aria-hidden="true" />
    <span>{{ waitingLabel }}</span>
    <span class="knowledge-waiting-dots" aria-hidden="true"><i /><i /><i /></span>
  </div>
  <section v-if="thinking.trim()" class="knowledge-thinking" aria-label="思考">
    <button type="button" class="knowledge-thinking-toggle" :aria-expanded="thinkingOpen" :aria-controls="`${contentId}-thinking`" @click="thinkingOpen = !thinkingOpen">
      <Icon icon="lucide:brain" /><strong>思考</strong><span class="knowledge-thinking-hint">{{ latestThinking }}</span><Icon :icon="thinkingOpen ? 'lucide:chevron-up' : 'lucide:chevron-down'" />
    </button>
    <div v-show="thinkingOpen" :id="`${contentId}-thinking`" class="knowledge-thinking-content"><MarkdownDocument :content="thinking" :allow-images="false" /></div>
  </section>
  <section v-if="latestCall" class="knowledge-thinking knowledge-tools" aria-label="工具调用">
    <button type="button" class="knowledge-thinking-toggle" :aria-expanded="toolsOpen" :aria-controls="`${contentId}-tools`" @click="toolsOpen = !toolsOpen">
      <span v-if="active && latestCall.state === 'RUNNING'" class="knowledge-spinner" aria-label="正在调用" /><Icon v-else icon="lucide:wrench" />
      <strong>工具调用</strong><span class="knowledge-thinking-hint">{{ knowledgeToolLabel(latestCall.tool) }}<template v-if="latestCall.detail"> · {{ latestCall.detail }}</template></span><small>{{ knowledgeStateLabel(latestCall.state) }}</small><Icon :icon="toolsOpen ? 'lucide:chevron-up' : 'lucide:chevron-down'" />
    </button>
    <div v-show="toolsOpen" :id="`${contentId}-tools`" class="knowledge-thinking-content"><ol class="knowledge-thinking-tools"><li v-for="call in message.calls" :key="call.id"><span class="knowledge-tool-dot" :class="{ running: active && call.state === 'RUNNING' }" /><span>{{ knowledgeToolLabel(call.tool) }}<small v-if="call.detail">{{ call.detail }}</small></span><small>{{ knowledgeStateLabel(call.state) }}</small></li></ol><small v-if="message.calls.length >= 30" class="knowledge-muted">最近 30 项调用</small></div>
  </section>
</template>
