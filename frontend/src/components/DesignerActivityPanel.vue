<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { api } from '@/api/client'
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
const stream = ref<HTMLElement>()
const followOutput = ref(true)
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0

function observedTime(value?: string) {
  if (!value) return '等待首次刷新'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).format(date)
}

function stop() {
  if (timer) clearTimeout(timer)
  timer = undefined
}

function schedule(delay = 1_200) {
  stop()
  const current = generation
  timer = setTimeout(() => void refresh(current), delay)
}

async function refresh(current = generation) {
  try {
    const next = await api.getDesignerActivity(props.sessionId)
    if (current !== generation) return
    const previousParts = activity.value?.parts ?? []
    activity.value = !next.connected && !next.parts.length && previousParts.length
      ? { ...next, parts: previousParts }
      : next
    error.value = ''
    await nextTick()
    if (followOutput.value && stream.value) stream.value.scrollTop = stream.value.scrollHeight
  } catch (failure) {
    if (current !== generation) return
    error.value = userFacingError(failure, '设计师活动暂时无法刷新')
  } finally {
    if (current === generation) {
      loading.value = false
      schedule()
    }
  }
}

function onScroll() {
  if (!stream.value) return
  followOutput.value = stream.value.scrollHeight - stream.value.scrollTop - stream.value.clientHeight < 32
}

function restart() {
  generation += 1
  activity.value = undefined
  error.value = ''
  loading.value = true
  stop()
  void refresh(generation)
}

watch(() => props.sessionId, restart)
onMounted(restart)
onBeforeUnmount(() => { generation += 1; stop() })
</script>

<template>
  <section class="designer-activity" aria-label="设计师实时活动">
    <header>
      <div>
        <span class="activity-pulse"><i />实时活动 · 1.2 秒</span>
        <strong>{{ designerActorLabel(activity?.actor) }}</strong>
        <small v-if="activity?.structuredStep">{{ displayLabel(activity.structuredStep) }}</small>
      </div>
      <div class="activity-state">
        <span :class="['connection-dot', { connected: activity?.connected }]" />
        {{ activity?.connected ? 'OpenCode 已连接' : '持久化状态' }}
        · {{ statusLabel(activity?.remoteState) }} · {{ observedTime(activity?.observedAt) }}
      </div>
    </header>
    <div ref="stream" class="activity-stream" aria-live="polite" @scroll="onScroll">
      <div v-if="loading && !activity" class="activity-empty"><span />正在连接当前角色…</div>
      <div v-if="error" class="activity-warning"><Icon icon="lucide:wifi-off" />{{ error }}</div>
      <div v-else-if="activity?.detail && !activity.connected" class="activity-warning"><Icon icon="lucide:info" />{{ activity.detail }}</div>
      <article v-for="part in activity?.parts ?? []" :key="part.id" :class="['activity-part', `part-${part.type.toLowerCase()}`]">
        <header>
          <span>{{ activityTypeLabel(part.type) }}</span>
          <strong>{{ activityLabel(part) }}</strong>
          <i v-if="part.status">{{ statusLabel(part.status) }}</i>
          <time v-if="part.startedAt" :datetime="part.startedAt">{{ observedTime(part.startedAt) }}</time>
        </header>
        <pre v-if="part.content">{{ part.content }}</pre>
      </article>
      <div v-if="activity && !activity.parts.length && !activity.detail" class="activity-empty">当前暂无新活动</div>
    </div>
    <footer>
      <label><el-switch v-model="followOutput" size="small" /><span>自动跟随</span></label>
      <button v-if="error" type="button" @click="restart"><Icon icon="lucide:refresh-cw" />重连</button>
    </footer>
  </section>
</template>

<style scoped>
.designer-activity { display: grid; gap: 8px; margin: 0 20px 10px; padding: 10px 12px; border: 1px solid rgb(99 102 241 / 24%); border-radius: 10px; background: rgb(15 23 42 / 58%); }
.designer-activity > header, .activity-part > header, .designer-activity > footer { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.designer-activity > header > div:first-child { display: flex; align-items: center; gap: 8px; }
.designer-activity strong { color: var(--color-text-primary); font-size: 11px; }
.designer-activity small, .activity-state, .designer-activity footer { color: var(--color-text-secondary); font: 8px/1.4 var(--font-code); }
.activity-pulse { display: inline-flex; align-items: center; gap: 5px; color: var(--color-success); font: 8px/1 var(--font-code); }
.activity-pulse i, .connection-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--color-text-muted); }
.activity-pulse i, .connection-dot.connected { background: var(--color-success); box-shadow: 0 0 8px rgb(34 197 94 / 64%); }
.activity-state { display: flex; align-items: center; gap: 5px; }
.activity-stream { max-height: 220px; overflow: auto; display: grid; gap: 7px; padding: 2px; scrollbar-width: thin; }
.activity-part { border-left: 2px solid rgb(148 163 184 / 28%); padding: 7px 9px; border-radius: 0 6px 6px 0; background: rgb(15 23 42 / 66%); }
.activity-part.part-tool { border-color: rgb(34 211 238 / 62%); }.activity-part.part-output { border-color: rgb(34 197 94 / 62%); }.activity-part.part-thinking { border-color: rgb(168 85 247 / 62%); }
.activity-part > header { justify-content: flex-start; }.activity-part > header span, .activity-part > header i, .activity-part > header time { color: var(--color-text-secondary); font: 8px/1 var(--font-code); font-style: normal; }.activity-part > header time { margin-left: auto; }
.activity-part pre { margin: 7px 0 0; max-height: 150px; overflow: auto; color: var(--color-text-secondary); font: 9px/1.5 var(--font-code); white-space: pre-wrap; overflow-wrap: anywhere; }
.activity-empty, .activity-warning { display: flex; align-items: center; justify-content: center; gap: 7px; min-height: 42px; color: var(--color-text-muted); font: 9px/1.5 var(--font-code); }
.activity-warning { color: var(--color-session-warning); }.activity-empty > span { width: 12px; height: 12px; border: 1px solid rgb(148 163 184 / 30%); border-top-color: var(--color-primary); border-radius: 50%; animation: spin 0.8s linear infinite; }
.designer-activity footer label { display: flex; align-items: center; gap: 6px; }.designer-activity footer button { display: inline-flex; align-items: center; gap: 4px; border: 0; color: var(--color-primary); background: transparent; cursor: pointer; font: inherit; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 760px) { .designer-activity > header { align-items: flex-start; flex-direction: column; }.activity-state { flex-wrap: wrap; } }
</style>
