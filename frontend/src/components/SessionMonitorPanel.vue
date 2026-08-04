<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { api } from '@/api/client'
import type { TaskSessionActivity, TaskSessionSummary } from '@/types/domain'

const props = defineProps<{ taskId: string }>()
const sessions = ref<TaskSessionSummary[]>([])
const selectedKey = ref('')
const activity = ref<TaskSessionActivity>()
const loading = ref(true)
const refreshing = ref(false)
const error = ref('')
const stream = ref<HTMLElement>()
const followOutput = ref(true)
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0

const selected = computed(() => sessions.value.find((session) => session.key === selectedKey.value))
const active = computed(() => ['CREATING', 'RUNNING', 'BUSY', 'RETRY'].includes((activity.value?.remoteState ?? selected.value?.state ?? '').toUpperCase()))
const thinking = computed(() => active.value && !activity.value?.parts.some((part) => part.type === 'OUTPUT'))
const observedTime = computed(() => activity.value?.observedAt
  ? new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(activity.value.observedAt))
  : '等待首次刷新')

function stop() {
  if (timer) clearTimeout(timer)
  timer = undefined
}

function schedule(runGeneration: number) {
  stop()
  timer = setTimeout(() => void refresh(runGeneration), active.value ? 1200 : 3000)
}

async function refresh(runGeneration = generation) {
  if (refreshing.value || runGeneration !== generation) return
  refreshing.value = true
  try {
    const nextSessions = await api.getTaskSessions(props.taskId)
    if (runGeneration !== generation) return
    sessions.value = nextSessions
    if (!nextSessions.some((session) => session.key === selectedKey.value)) selectedKey.value = nextSessions[0]?.key ?? ''
    const requestedKey = selectedKey.value
    const nextActivity = requestedKey ? await api.getTaskSessionActivity(props.taskId, requestedKey) : undefined
    if (runGeneration !== generation) return
    if (requestedKey === selectedKey.value) activity.value = nextActivity
    error.value = ''
    await nextTick()
    if (followOutput.value && stream.value) stream.value.scrollTop = stream.value.scrollHeight
  } catch (cause) {
    if (runGeneration === generation) error.value = cause instanceof Error ? cause.message : 'Session 监控暂时不可用'
  } finally {
    if (runGeneration === generation) {
      loading.value = false
      refreshing.value = false
      schedule(runGeneration)
    }
  }
}

function selectSession(key: string) {
  selectedKey.value = key
  activity.value = undefined
  loading.value = true
  stop()
  void refresh(generation)
}

function onStreamScroll() {
  if (!stream.value) return
  followOutput.value = stream.value.scrollHeight - stream.value.scrollTop - stream.value.clientHeight < 48
}

function restart() {
  stop()
  generation += 1
  sessions.value = []
  selectedKey.value = ''
  activity.value = undefined
  error.value = ''
  loading.value = true
  refreshing.value = false
  void refresh(generation)
}

watch(() => props.taskId, restart)
onMounted(restart)
onBeforeUnmount(() => { generation += 1; stop() })
</script>

<template>
  <section class="session-monitor card" aria-labelledby="session-monitor-heading">
    <header class="monitor-header">
      <div>
        <p class="eyebrow">MODEL SESSION LIVE</p>
        <h2 id="session-monitor-heading" class="card-title">模型输出 / Thinking</h2>
        <p class="card-description">动态读取 OpenCode 已公开的 reasoning、正文输出与工具调用；不会伪造模型尚未返回的内容。</p>
      </div>
      <div class="live-indicator" :class="{ active }"><span />{{ active ? 'LIVE · 1.2s' : 'MONITOR · 3s' }}</div>
    </header>

    <div v-if="loading && !sessions.length" class="monitor-empty"><span class="monitor-spinner" /><p>正在连接 Task Session…</p></div>
    <div v-else-if="!sessions.length" class="monitor-empty"><Icon icon="lucide:message-square-dashed" width="26" /><p>这个 Task 尚未创建模型 Session。</p></div>
    <div v-else class="monitor-layout">
      <nav class="session-list" aria-label="Task Session 列表">
        <button
          v-for="session in sessions"
          :key="session.key"
          type="button"
          :class="['session-option', { selected: session.key === selectedKey }]"
          @click="selectSession(session.key)"
        >
          <span class="session-option-top"><strong>{{ session.label }}</strong><i :class="session.state.toLowerCase()">{{ session.state }}</i></span>
          <span class="mono">{{ session.externalSessionId ?? session.localSessionId }}</span>
          <small>{{ session.kind === 'JUDGE' ? '只读 Judge' : '执行 Session' }} · {{ session.createdAt }}</small>
        </button>
      </nav>

      <article class="session-console">
        <div class="console-toolbar">
          <div><strong>{{ selected?.label }}</strong><span class="mono">{{ activity?.remoteState ?? selected?.state }}</span></div>
          <div><span :class="['transport-dot', { live: activity?.live }]" />{{ activity?.live ? 'OpenCode 已连接' : '持久化状态' }} · {{ observedTime }}</div>
        </div>
        <div ref="stream" class="console-stream" aria-live="polite" @scroll="onStreamScroll">
          <div v-if="thinking" class="live-thinking" role="status" aria-label="模型正在思考">
            <span class="thinking-orbit"><span /></span>
            <div><strong>模型正在思考<span class="thinking-dots"><i /><i /><i /></span></strong><p>等待 OpenCode 返回可公开的 reasoning 或正文增量…</p></div>
          </div>
          <div v-if="error" class="monitor-warning"><Icon icon="lucide:wifi-off" />{{ error }}</div>
          <div v-else-if="activity?.detail && !activity.live" class="monitor-warning"><Icon icon="lucide:info" />{{ activity.detail }}</div>
          <section v-for="part in activity?.parts ?? []" :key="part.id" :class="['activity-part', `part-${part.type.toLowerCase()}`]">
            <header><span>{{ part.type === 'THINKING' ? 'THINKING' : part.type === 'TOOL' ? 'TOOL' : 'OUTPUT' }}</span><strong>{{ part.label }}</strong><i v-if="part.status">{{ part.status }}</i></header>
            <pre v-if="part.content">{{ part.content }}</pre>
          </section>
          <div v-if="!thinking && !error && activity && activity.parts.length === 0" class="monitor-placeholder">Session 当前没有可显示的模型输出。</div>
        </div>
        <button v-if="!followOutput" type="button" class="follow-button" @click="followOutput = true; nextTick(() => { if (stream) stream.scrollTop = stream.scrollHeight })"><Icon icon="lucide:arrow-down" />跟随最新输出</button>
      </article>
    </div>
  </section>
</template>

<style scoped>
.session-monitor { margin-top: 16px; overflow: hidden; border-color: rgb(139 92 246 / 27%); background: linear-gradient(145deg, rgb(139 92 246 / 5%), transparent 42%), var(--color-bg-surface); box-shadow: 0 18px 48px rgb(0 0 0 / 18%); }
.monitor-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding: 20px; border-bottom: 1px solid var(--color-border-default); }.monitor-header h2 { margin-top: 3px; }.monitor-header p:last-child { margin-bottom: 0; }
.live-indicator { display: inline-flex; align-items: center; gap: 7px; flex: 0 0 auto; padding: 6px 9px; border: 1px solid var(--color-border-default); border-radius: 999px; color: var(--color-text-muted); font: 700 10px/1 var(--font-code); }.live-indicator span { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }.live-indicator.active { border-color: rgb(34 211 238 / 32%); color: var(--color-accent-cyan); }.live-indicator.active span { animation: live-pulse 1.2s ease-in-out infinite; box-shadow: 0 0 12px currentColor; }
.monitor-layout { display: grid; grid-template-columns: minmax(220px, .32fr) minmax(0, 1fr); min-height: 390px; }.session-list { max-height: 530px; overflow: auto; padding: 10px; border-right: 1px solid var(--color-border-default); background: rgb(7 11 20 / 34%); }.session-option { display: grid; width: 100%; gap: 7px; margin: 0 0 7px; padding: 12px; border: 1px solid transparent; border-radius: 8px; color: var(--color-text-secondary); text-align: left; background: transparent; cursor: pointer; }.session-option:hover { border-color: var(--color-border-default); background: var(--color-bg-hover); }.session-option.selected { border-color: rgb(139 92 246 / 40%); color: var(--color-text-primary); background: linear-gradient(100deg, rgb(139 92 246 / 14%), rgb(34 211 238 / 5%)); box-shadow: inset 2px 0 #8b5cf6; }.session-option-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }.session-option strong { overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.session-option i { padding: 3px 5px; border-radius: 4px; color: var(--color-text-muted); background: rgb(101 115 138 / 12%); font: 700 8px/1 var(--font-code); font-style: normal; }.session-option i.running, .session-option i.creating { color: var(--color-accent-cyan); background: rgb(34 211 238 / 10%); }.session-option .mono { overflow: hidden; color: var(--color-accent-ai); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }.session-option small { color: var(--color-text-muted); font-size: 9px; line-height: 1.35; }
.session-console { position: relative; display: grid; grid-template-rows: auto 1fr; min-width: 0; background: #070b14; }.console-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 15px; min-height: 48px; padding: 9px 14px; border-bottom: 1px solid var(--color-border-default); color: var(--color-text-muted); font-size: 9px; }.console-toolbar > div { display: flex; align-items: center; gap: 9px; }.console-toolbar strong { color: var(--color-text-primary); font-size: 11px; }.console-toolbar .mono { color: var(--color-accent-cyan); }.transport-dot { display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: var(--color-text-muted); }.transport-dot.live { background: var(--color-success); box-shadow: 0 0 9px rgb(34 197 94 / 60%); }
.console-stream { max-height: 480px; min-height: 340px; overflow: auto; padding: 15px; scroll-behavior: smooth; }.activity-part { margin-bottom: 12px; overflow: hidden; border: 1px solid var(--color-border-default); border-radius: 8px; background: rgb(13 20 36 / 72%); }.activity-part header { display: flex; align-items: center; gap: 8px; padding: 7px 10px; border-bottom: 1px solid var(--color-border-default); color: var(--color-text-muted); font: 9px/1.2 var(--font-code); }.activity-part header span { color: var(--color-accent-cyan); font-weight: 800; letter-spacing: .08em; }.activity-part header strong { color: var(--color-text-secondary); font-weight: 600; }.activity-part header i { margin-left: auto; font-style: normal; }.activity-part pre { margin: 0; padding: 11px; overflow: visible; color: #d7e1f0; font: 11px/1.65 var(--font-code); white-space: pre-wrap; overflow-wrap: anywhere; }.part-thinking { border-color: rgb(139 92 246 / 28%); background: rgb(139 92 246 / 5%); }.part-thinking header span { color: #a78bfa; }.part-tool { border-color: rgb(245 158 11 / 22%); }.part-tool header span { color: var(--color-session-warning); }
.live-thinking { display: flex; align-items: center; gap: 13px; margin-bottom: 14px; padding: 14px; border: 1px solid rgb(139 92 246 / 28%); border-radius: 9px; background: linear-gradient(100deg, rgb(139 92 246 / 10%), rgb(34 211 238 / 4%), rgb(139 92 246 / 10%)); background-size: 200% 100%; animation: thinking-sheen 3s ease-in-out infinite; }.thinking-orbit { display: grid; place-items: center; width: 32px; height: 32px; border: 2px solid rgb(139 92 246 / 20%); border-top-color: #a78bfa; border-right-color: var(--color-accent-cyan); border-radius: 50%; animation: thinking-spin .9s linear infinite; }.thinking-orbit > span { width: 7px; height: 7px; border-radius: 50%; background: var(--color-accent-cyan); box-shadow: 0 0 10px rgb(34 211 238 / 55%); }.live-thinking strong { display: flex; align-items: baseline; color: #f5f3ff; font-size: 11px; }.live-thinking p { margin: 4px 0 0; color: var(--color-text-muted); font-size: 9px; }.thinking-dots { display: inline-flex; gap: 3px; margin-left: 5px; }.thinking-dots i { width: 3px; height: 3px; border-radius: 50%; background: var(--color-accent-cyan); animation: thinking-dot 1.1s ease-in-out infinite; }.thinking-dots i:nth-child(2) { animation-delay: .15s; }.thinking-dots i:nth-child(3) { animation-delay: .3s; }
.monitor-empty { display: grid; min-height: 220px; place-content: center; place-items: center; gap: 12px; color: var(--color-text-muted); font-size: 11px; }.monitor-spinner { width: 28px; height: 28px; border: 2px solid var(--color-border-default); border-top-color: var(--color-accent-cyan); border-radius: 50%; animation: thinking-spin .9s linear infinite; }.monitor-warning { display: flex; align-items: flex-start; gap: 8px; margin-bottom: 12px; padding: 10px; border: 1px solid rgb(245 158 11 / 28%); border-radius: 7px; color: var(--color-session-warning); background: rgb(245 158 11 / 6%); font-size: 10px; line-height: 1.5; }.monitor-placeholder { padding: 70px 20px; color: var(--color-text-muted); font-size: 10px; text-align: center; }.follow-button { position: absolute; right: 16px; bottom: 14px; display: inline-flex; align-items: center; gap: 6px; padding: 7px 9px; border: 1px solid rgb(34 211 238 / 30%); border-radius: 999px; color: var(--color-accent-cyan); background: #0d1424; font: 9px var(--font-code); cursor: pointer; }
@keyframes live-pulse { 0%, 100% { opacity: .35; transform: scale(.85); } 50% { opacity: 1; transform: scale(1.15); } }@keyframes thinking-spin { to { transform: rotate(360deg); } }@keyframes thinking-dot { 0%, 70%, 100% { opacity: .25; transform: translateY(0); } 35% { opacity: 1; transform: translateY(-3px); } }@keyframes thinking-sheen { 0%, 100% { background-position: 0 50%; } 50% { background-position: 100% 50%; } }
@media (max-width: 1050px) { .monitor-layout { grid-template-columns: 1fr; }.session-list { display: flex; max-height: none; overflow-x: auto; border-right: 0; border-bottom: 1px solid var(--color-border-default); }.session-option { min-width: 230px; }.console-toolbar { align-items: flex-start; flex-direction: column; } }
</style>
