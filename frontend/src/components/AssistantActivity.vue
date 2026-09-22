<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { Icon } from '@iconify/vue'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import { knowledgeStateLabel, knowledgeToolLabel } from '@/utils/displayLabels'

const props = withDefaults(defineProps<{
  message: { state: string; calls: { id: string; tool: string; state: string; detail?: string }[]; questions?: { state: string }[] }
  thinking: string
  answer: string
  toolLabel?: (tool: string) => string
}>(), { toolLabel: knowledgeToolLabel })
const contentId = useId(), thinkingOpen = ref(false), toolsOpen = ref(false)
const active = computed(() => ['PREPARED', 'CREATING', 'SENDING', 'RUNNING'].includes(props.message.state) && !props.message.questions?.some(q => ['PENDING', 'PREPARED', 'SENDING', 'UNKNOWN'].includes(q.state)))
const latestThinking = computed(() => props.thinking.trim().split(/\n\s*\n/).at(-1)?.replace(/\s+/g, ' ').slice(-160) || '')
const calls = computed(() => props.message.calls.slice(-30))
const latestCall = computed(() => calls.value.at(-1))
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
      <strong>工具调用</strong><span class="knowledge-thinking-hint">{{ toolLabel(latestCall.tool) }}<template v-if="latestCall.detail"> · {{ latestCall.detail }}</template></span><small>{{ knowledgeStateLabel(latestCall.state) }}</small><Icon :icon="toolsOpen ? 'lucide:chevron-up' : 'lucide:chevron-down'" />
    </button>
    <div v-show="toolsOpen" :id="`${contentId}-tools`" class="knowledge-thinking-content"><ol class="knowledge-thinking-tools"><li v-for="call in calls" :key="call.id"><span class="knowledge-tool-dot" :class="{ running: active && call.state === 'RUNNING' }" /><span>{{ toolLabel(call.tool) }}<small v-if="call.detail">{{ call.detail }}</small></span><small>{{ knowledgeStateLabel(call.state) }}</small></li></ol><small v-if="message.calls.length >= 30" class="knowledge-muted">最近 30 项调用</small></div>
  </section>
</template>

<style scoped>
.knowledge-waiting{display:flex;align-items:center;gap:10px;width:fit-content;max-width:100%;margin:8px 0 14px;padding:11px 14px;border-radius:calc(var(--radius-control) + 6px);background:var(--color-bg-surface);color:var(--color-text-secondary);font-size:13px}
.knowledge-waiting>svg{flex:none;color:var(--color-accent-cyan)}
.knowledge-waiting-dots{display:flex;align-items:center;gap:4px;margin-left:2px;height:16px;color:var(--color-accent-cyan)}
.knowledge-waiting-dots i{width:4px;height:4px;border-radius:50%;background:currentColor;animation:knowledge-waiting-pulse 1.4s ease-in-out infinite}
.knowledge-waiting-dots i:nth-child(2){animation-delay:.18s}
.knowledge-waiting-dots i:nth-child(3){animation-delay:.36s}
@keyframes knowledge-waiting-pulse{0%,60%,100%{opacity:.35;transform:translateY(0)}30%{opacity:1;transform:translateY(-3px)}}
@media(prefers-reduced-motion:reduce){.knowledge-waiting-dots i{animation:none}}
.knowledge-thinking{margin:0 0 18px;border:1px solid var(--color-border-default);border-radius:calc(var(--radius-control) + 4px);background:var(--color-bg-surface);overflow:hidden}
.knowledge-thinking.is-active{border-color:color-mix(in srgb,var(--color-accent-cyan) 28%,var(--color-border-default));background:var(--appearance-knowledge-knowledge-thinking-is-active-background)}
.knowledge-thinking-toggle{width:100%;justify-content:flex-start;padding:12px 14px;border:0;border-radius:0;background:transparent;font-size:12px;text-align:left;gap:10px}
.knowledge-thinking-toggle>svg,.knowledge-thinking .knowledge-spinner{flex:none;color:var(--color-accent-cyan)}
.knowledge-thinking-toggle strong{font-weight:500;color:var(--color-text-secondary);white-space:nowrap}
.knowledge-thinking-hint{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px;color:var(--color-text-muted)}
.knowledge-thinking-chevron{margin-left:auto}
.knowledge-thinking-content{padding:4px 16px 14px;max-height:320px;overflow:auto;overscroll-behavior:contain}
.knowledge-thinking-content .markdown-document{font-size:12px;color:var(--color-text-secondary);line-height:1.85}
.knowledge-thinking-content>.knowledge-muted{margin:0 0 8px}
.knowledge-thinking-tools{list-style:none;padding:10px 0 0;margin:10px 0 0;border-top:1px solid var(--color-border-default);font-size:11px;color:var(--color-text-secondary)}
.knowledge-thinking-tools li{display:flex;align-items:baseline;gap:8px;padding:6px 0}
.knowledge-thinking-tools li>span:nth-child(2){flex:1;min-width:0}
.knowledge-thinking-tools small{color:var(--color-text-muted);font-size:10px}
.knowledge-thinking-tools li>span small{display:block;margin-top:4px;overflow-wrap:anywhere}
.knowledge-tool-dot{width:5px;height:5px;border-radius:50%;background:var(--color-text-muted);flex:none}
.knowledge-tool-dot.running{background:var(--color-accent-cyan)}
.knowledge-spinner{display:inline-block;width:14px;height:14px;flex:none;border:1.5px solid color-mix(in srgb,var(--color-accent-cyan) 22%,transparent);border-top-color:var(--color-accent-cyan);border-right-color:var(--color-accent-cyan);border-radius:50%;animation:knowledge-spin .85s linear infinite}
@keyframes knowledge-spin{to{transform:rotate(360deg)}}
.knowledge-thinking{margin:8px 0;border:1px solid var(--color-border-default);border-radius:calc(var(--radius-control) + 2px);background:var(--color-bg-surface)}
.knowledge-thinking-toggle{padding:8px 10px;gap:8px;min-height:35px}.knowledge-thinking-toggle strong{font-size:11px}.knowledge-thinking-toggle>svg:last-child{margin-left:auto}
.knowledge-thinking-hint{flex:1;font-size:11px}.knowledge-thinking-toggle small{font-size:10px;white-space:nowrap;color:var(--color-text-muted)}
.knowledge-thinking-tools{margin:0;padding:0;border:0}.knowledge-thinking-content{padding:8px 12px 12px}
.knowledge-thinking-toggle { display:flex; align-items:center; color:var(--color-text-secondary); cursor:pointer; font:inherit; }
.knowledge-thinking-toggle:focus-visible { outline:2px solid var(--color-accent-cyan); outline-offset:-2px; }
.knowledge-muted { color:var(--color-text-muted); font-size:11px; }
@media(prefers-reduced-motion:reduce){.knowledge-spinner{animation:none}}
</style>
