<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { api } from '@/api/client'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import TokenUsageWindow from '@/components/TokenUsageWindow.vue'
import type { DesignerActivity } from '@/types/domain'
import {
  activityLabel,
  activityTypeLabel,
  designerActorLabel,
  displayLabel,
  statusLabel,
  userFacingError,
} from '@/utils/displayLabels'

const props = defineProps<{ sessionId: string }>()
const activity = ref<DesignerActivity>()
const loading = ref(true)
const error = ref('')
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0

const latest = computed(() => activity.value?.parts.at(-1))
const actor = computed(() => designerActorLabel(activity.value?.actor))
const actorClass = computed(() => (activity.value?.actor ?? 'SYSTEM').toLowerCase())
const currentStep = computed(() => activity.value?.structuredStep
  ? displayLabel(activity.value.structuredStep)
  : activity.value?.remoteState ? statusLabel(activity.value.remoteState) : '处理中')

function stop() {
  if (timer) clearTimeout(timer)
  timer = undefined
}

function schedule() {
  stop()
  const current = generation
  timer = setTimeout(() => void refresh(current), 1_200)
}

async function refresh(current = generation) {
  try {
    const next = await api.getDesignerActivity(props.sessionId)
    if (current !== generation) return
    const previous = latest.value
    activity.value = !next.connected && !next.parts.length && previous
      ? { ...next, parts: [previous] }
      : { ...next, parts: next.parts.length ? [next.parts.at(-1)!] : [] }
    error.value = ''
  } catch (failure) {
    if (current !== generation) return
    error.value = userFacingError(failure, '当前角色活动暂时无法刷新')
  } finally {
    if (current === generation) {
      loading.value = false
      schedule()
    }
  }
}

function restart() {
  generation += 1
  activity.value = undefined
  loading.value = true
  error.value = ''
  stop()
  void refresh(generation)
}

watch(() => props.sessionId, restart)
onMounted(restart)
onBeforeUnmount(() => { generation += 1; stop() })
</script>

<template>
  <article :class="['thinking-message', `thinking-${actorClass}`]" role="status" aria-live="polite" :aria-label="`${actor}正在处理`">
    <div class="activity-heading">
      <span class="thinking-orbit" aria-hidden="true"><span /></span>
      <div class="thinking-copy">
        <strong>{{ actor }}正在处理<span class="thinking-dots" aria-hidden="true"><i /><i /><i /></span></strong>
        <small>{{ currentStep }}</small>
      </div>
      <TokenUsageWindow :key="sessionId" :total-tokens="activity?.usage.totalTokens" />
    </div>
    <div class="current-activity">
      <p v-if="error" class="activity-warning"><Icon icon="lucide:wifi-off" />{{ error }}</p>
      <template v-if="latest">
        <header>
          <span>{{ activityTypeLabel(latest.type) }}</span>
          <strong>{{ activityLabel(latest) }}</strong>
          <i v-if="latest.status">{{ statusLabel(latest.status) }}</i>
        </header>
        <MarkdownDocument v-if="latest.content" :content="latest.content" />
      </template>
      <p v-else class="activity-detail">{{ loading ? '正在连接当前角色…' : activity?.detail || '正在等待最新输出…' }}</p>
    </div>
  </article>
</template>

<style scoped>
.thinking-message { position: relative; display: grid; gap: 14px; margin: 14px 0; padding: 17px 18px; overflow: hidden; border: 1px solid rgb(var(--shade-ai-01-rgb) / 28%); border-radius: calc(var(--radius-control) + 6px); background: var(--appearance-designer-current-activity-thinking-message-background); background-size: 220% 100%; box-shadow: var(--appearance-designer-current-activity-thinking-message-box-shadow); animation: thinking-sheen 3s ease-in-out infinite; }
.thinking-message::after { position: absolute; inset: auto 16px 0; height: 1px; background: var(--appearance-designer-current-activity-thinking-message-after-background); content: ""; animation: thinking-scan 2.2s ease-in-out infinite; }
.activity-heading { display: flex; align-items: center; gap: 14px; min-width: 0; }
.thinking-orbit { position: relative; display: grid; flex: 0 0 auto; place-items: center; width: 38px; height: 38px; border: 2px solid rgb(var(--shade-ai-01-rgb) / 18%); border-top-color: var(--shade-ai-02); border-right-color: var(--color-accent-cyan); border-radius: 50%; box-shadow: var(--appearance-designer-current-activity-thinking-orbit-box-shadow); animation: thinking-spin 1s linear infinite; }
.thinking-orbit span { width: 8px; height: 8px; border-radius: 50%; background: var(--appearance-designer-current-activity-thinking-orbit-span-background); box-shadow: var(--appearance-designer-current-activity-thinking-orbit-span-box-shadow); }
.thinking-copy { min-width: 0; margin-right: auto; }
.thinking-copy strong { display: flex; align-items: baseline; color: var(--shade-text-01); font-size: 13px; font-weight: 720; letter-spacing: -.01em; }
.thinking-copy small { display: block; margin-top: 5px; color: var(--color-text-secondary); font: 9px/1.45 var(--font-code); }
.thinking-dots { display: inline-flex; align-items: flex-end; gap: 3px; height: 12px; margin-left: 5px; }
.thinking-dots i { width: 4px; height: 4px; border-radius: 50%; background: var(--color-accent-cyan); animation: thinking-dot 1.15s ease-in-out infinite; }
.thinking-dots i:nth-child(2) { animation-delay: .16s; }
.thinking-dots i:nth-child(3) { animation-delay: .32s; }
.current-activity { min-width: 0; padding: 12px 14px; border: 1px solid rgb(var(--shade-secondary-01-rgb) / 18%); border-radius: calc(var(--radius-control) + 3px); background: rgb(var(--shade-canvas-01-rgb) / 52%); }
.current-activity > header { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; margin-bottom: 9px; color: var(--color-text-secondary); font: 8px/1.2 var(--font-code); }
.current-activity > header strong { color: var(--color-text-primary); font-size: 9px; }
.current-activity > header i { margin-left: auto; color: var(--color-accent-cyan); font-style: normal; }
.current-activity :deep(.markdown-document) { color: var(--color-text-primary); font-size: 12px; line-height: 1.65; }
.activity-detail, .activity-warning { display: flex; align-items: center; gap: 7px; margin: 0; color: var(--color-text-secondary); font-size: 11px; line-height: 1.55; }
.activity-warning { margin-bottom: 9px; color: var(--color-session-warning); }
.thinking-compiler { border-color: rgb(var(--shade-cyan-01-rgb) / 38%); background: var(--appearance-designer-current-activity-thinking-compiler-background); }
.thinking-decomposer, .thinking-router { border-color: rgb(var(--shade-primary-01-rgb) / 42%); background: var(--appearance-designer-current-activity-thinking-decomposer-background); }
.thinking-validator { border-color: rgb(var(--shade-success-01-rgb) / 36%); background: var(--appearance-designer-current-activity-thinking-validator-background); }
@keyframes thinking-spin { to { transform: rotate(360deg); } }
@keyframes thinking-dot { 0%, 65%, 100% { opacity: .25; transform: translateY(0); } 35% { opacity: 1; transform: translateY(-4px); } }
@keyframes thinking-sheen { 0%, 100% { background-position: 0 50%; } 50% { background-position: 100% 50%; } }
@keyframes thinking-scan { 0%, 100% { opacity: .2; transform: scaleX(.25); } 50% { opacity: .85; transform: scaleX(1); } }
</style>
