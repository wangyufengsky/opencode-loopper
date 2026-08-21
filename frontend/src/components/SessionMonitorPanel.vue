<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import TokenUsageWindow from '@/components/TokenUsageWindow.vue'
import type { TaskSessionActivity, TaskSessionPendingQuestion, TaskSessionSummary } from '@/types/domain'
import { activityLabel, activityTypeLabel, displayLabel, sessionLabel, statusLabel, userFacingError } from '@/utils/displayLabels'

const props = defineProps<{ taskId: string }>()
const sessions = ref<TaskSessionSummary[]>([])
const selectedKey = ref('')
const activity = ref<TaskSessionActivity>()
const loading = ref(true)
const refreshing = ref(false)
const error = ref('')
const stream = ref<HTMLElement>()
const followOutput = ref(true)
const expandedParts = ref(new Set<string>())
const overflowingParts = ref(new Set<string>())
const answerDrafts = ref<Record<string, string[][]>>({})
const customDrafts = ref<Record<string, string[]>>({})
const submittingQuestion = ref('')
const partContentElements = new Map<string, HTMLElement>()
let contentResizeObserver: ResizeObserver | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0

const selected = computed(() => sessions.value.find((session) => session.key === selectedKey.value))
const active = computed(() => ['CREATING', 'RUNNING', 'BUSY', 'RETRY'].includes((activity.value?.remoteState ?? selected.value?.state ?? '').toUpperCase()))
const pendingQuestions = computed(() => activity.value?.pendingQuestions ?? [])
const thinking = computed(() => active.value && pendingQuestions.value.length === 0 && !activity.value?.parts.some((part) => part.type === 'OUTPUT'))
const observedTime = computed(() => activity.value?.observedAt
  ? new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(activity.value.observedAt))
  : '等待首次刷新')

function sessionTitle(session?: TaskSessionSummary) {
  if (!session) return '任务会话'
  if (session.kind === 'IMPLEMENTATION') return session.stageOrdinal ? `阶段 ${session.stageOrdinal} · 执行会话` : '执行会话'
  return sessionLabel(session)
}

function sessionIcon(session: TaskSessionSummary) {
  if (session.kind === 'IMPLEMENTATION') return 'lucide:terminal-square'
  return session.label.toUpperCase().includes('RISK') ? 'lucide:shield-check' : 'lucide:list-checks'
}

function formatSessionTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date)
}

function formatActivityTime(value?: string) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(date)
}

function stop() {
  if (timer) clearTimeout(timer)
  timer = undefined
}

function initializeQuestions(questions: TaskSessionPendingQuestion[]) {
  for (const pending of questions) {
    if (!answerDrafts.value[pending.id]) answerDrafts.value[pending.id] = pending.questions.map(() => [])
    if (!customDrafts.value[pending.id]) customDrafts.value[pending.id] = pending.questions.map(() => '')
  }
}

function setSingleAnswer(questionId: string, index: number, value: string | number | boolean | undefined) {
  const answers = answerDrafts.value[questionId] ?? (answerDrafts.value[questionId] = [])
  const custom = customDrafts.value[questionId] ?? (customDrafts.value[questionId] = [])
  answers[index] = value === undefined || value === '' ? [] : [String(value)]
  custom[index] = ''
}

function answerDraft(questionId: string, index: number) {
  return answerDrafts.value[questionId]?.[index] ?? []
}

function setMultipleAnswers(questionId: string, index: number, value: unknown) {
  const answers = answerDrafts.value[questionId] ?? (answerDrafts.value[questionId] = [])
  answers[index] = Array.isArray(value) ? value.map(String) : []
}

function customDraft(questionId: string, index: number) {
  return customDrafts.value[questionId]?.[index] ?? ''
}

function setCustomDraft(questionId: string, index: number, value: unknown) {
  const custom = customDrafts.value[questionId] ?? (customDrafts.value[questionId] = [])
  custom[index] = typeof value === 'string' ? value : String(value ?? '')
}

function answersFor(pending: TaskSessionPendingQuestion) {
  return pending.questions.map((prompt, index) => {
    const selected = answerDrafts.value[pending.id]?.[index] ?? []
    const custom = customDrafts.value[pending.id]?.[index]?.trim() ?? ''
    if (!custom) return selected
    return prompt.multiple ? [...selected, custom] : [custom]
  })
}

function canSubmit(pending: TaskSessionPendingQuestion) {
  return answersFor(pending).every((answer) => answer.length > 0)
}

async function submitAnswer(pending: TaskSessionPendingQuestion) {
  if (!canSubmit(pending) || submittingQuestion.value) return
  submittingQuestion.value = pending.id
  try {
    await api.replyTaskSessionQuestion(props.taskId, selectedKey.value, pending.id, answersFor(pending))
    ElMessage.success('回答已提交，OpenCode 会话继续执行')
    await refresh(generation)
  } catch (cause) {
    ElMessage.error(userFacingError(cause, '回答提交失败'))
  } finally {
    submittingQuestion.value = ''
  }
}

async function rejectQuestion(pending: TaskSessionPendingQuestion) {
  if (submittingQuestion.value) return
  try {
    await ElMessageBox.confirm('拒绝后，本次 question 工具调用会结束并把拒绝结果返回模型。', '拒绝这个问题？', { confirmButtonText: '确认拒绝', cancelButtonText: '返回回答', type: 'warning' })
  } catch { return }
  submittingQuestion.value = pending.id
  try {
    await api.rejectTaskSessionQuestion(props.taskId, selectedKey.value, pending.id)
    ElMessage.success('已拒绝问题，OpenCode 会话将自行处理')
    await refresh(generation)
  } catch (cause) {
    ElMessage.error(userFacingError(cause, '拒绝操作失败'))
  } finally {
    submittingQuestion.value = ''
  }
}

function haveSameIds(left: Set<string>, right: Set<string>) {
  if (left.size !== right.size) return false
  return [...left].every((id) => right.has(id))
}

function measurePartOverflow() {
  const next = new Set<string>()
  for (const [id, element] of partContentElements) {
    if (expandedParts.value.has(id)) {
      if (overflowingParts.value.has(id)) next.add(id)
      continue
    }
    if (element.scrollHeight > element.clientHeight + 1) next.add(id)
  }
  if (!haveSameIds(next, overflowingParts.value)) overflowingParts.value = next
}

function bindPartContent(id: string, element: Element | null) {
  const previous = partContentElements.get(id)
  if (previous === element) return
  if (previous && previous !== element) contentResizeObserver?.unobserve(previous)
  if (element instanceof HTMLElement) {
    partContentElements.set(id, element)
    contentResizeObserver?.observe(element)
    void nextTick(measurePartOverflow)
  } else {
    partContentElements.delete(id)
  }
}

function togglePart(id: string) {
  const next = new Set(expandedParts.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedParts.value = next
  followOutput.value = false
  void nextTick(measurePartOverflow)
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
    if (requestedKey === selectedKey.value) {
      initializeQuestions(nextActivity?.pendingQuestions ?? [])
      activity.value = nextActivity
      const partIds = new Set(nextActivity?.parts.map((part) => part.id) ?? [])
      expandedParts.value = new Set([...expandedParts.value].filter((id) => partIds.has(id)))
      overflowingParts.value = new Set([...overflowingParts.value].filter((id) => partIds.has(id)))
    }
    error.value = ''
    await nextTick()
    measurePartOverflow()
    if (followOutput.value && stream.value) stream.value.scrollTop = stream.value.scrollHeight
  } catch (cause) {
    if (runGeneration === generation) error.value = userFacingError(cause, '会话监控暂时不可用')
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
  expandedParts.value = new Set()
  overflowingParts.value = new Set()
  answerDrafts.value = {}
  customDrafts.value = {}
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
  expandedParts.value = new Set()
  overflowingParts.value = new Set()
  answerDrafts.value = {}
  customDrafts.value = {}
  error.value = ''
  loading.value = true
  refreshing.value = false
  void refresh(generation)
}

watch(() => props.taskId, restart)
onMounted(() => {
  if (typeof ResizeObserver !== 'undefined') contentResizeObserver = new ResizeObserver(measurePartOverflow)
  restart()
})
onBeforeUnmount(() => {
  generation += 1
  stop()
  contentResizeObserver?.disconnect()
  partContentElements.clear()
})
</script>

<template>
  <section class="session-monitor card" aria-labelledby="session-monitor-heading">
    <header class="monitor-header">
      <div>
        <p class="eyebrow">模型会话实时输出</p>
        <h2 id="session-monitor-heading" class="card-title">模型输出 / 思考</h2>
      </div>
      <div class="monitor-actions">
        <TokenUsageWindow :key="taskId" :total-tokens="activity?.usage.totalTokens" />
        <div class="live-indicator" :class="{ active }"><span />{{ active ? '实时 · 1.2 秒' : '监控 · 3 秒' }}</div>
      </div>
    </header>

    <div v-if="loading && !sessions.length" class="monitor-empty"><span class="monitor-spinner" /><p>正在连接任务会话…</p></div>
    <div v-else-if="!sessions.length" class="monitor-empty"><Icon icon="lucide:message-square-dashed" width="26" /><p>这个任务尚未创建模型会话。</p></div>
    <div v-else class="monitor-layout">
      <nav class="session-list" aria-label="任务会话列表">
        <button
          v-for="session in sessions"
          :key="session.key"
          type="button"
          :class="['session-option', { selected: session.key === selectedKey }]"
          @click="selectSession(session.key)"
        >
          <span class="session-option-top"><strong>{{ sessionTitle(session) }}</strong><i :class="session.state.toLowerCase()">{{ statusLabel(session.state) }}</i></span>
          <small><Icon :icon="sessionIcon(session)" width="12" />{{ session.kind === 'JUDGE' ? '只读评审' : `阶段 ${session.stageOrdinal ?? '—'}` }} · {{ formatSessionTime(session.createdAt) }}</small>
        </button>
      </nav>

      <article class="session-console">
        <div class="console-toolbar">
          <div><strong>{{ sessionTitle(selected) }}</strong><span class="mono">{{ statusLabel(activity?.remoteState ?? selected?.state) }}</span></div>
          <div><span :class="['transport-dot', { live: activity?.live }]" />{{ activity?.live ? 'OpenCode 已连接' : '持久化状态' }} · {{ observedTime }}</div>
        </div>
        <div ref="stream" class="console-stream" aria-live="polite" @scroll="onStreamScroll">
          <div v-if="error" class="monitor-warning"><Icon icon="lucide:wifi-off" />{{ userFacingError(error, '会话连接失败，请重试') }}</div>
          <div v-else-if="activity?.detail && !activity.live" class="monitor-warning"><Icon icon="lucide:info" />{{ userFacingError(activity.detail, '会话暂时不可用') }}</div>
          <section v-if="selected?.kind === 'IMPLEMENTATION'" class="todo-panel" aria-label="OpenCode 实施计划">
            <header><div><span>实施清单</span><strong>OpenCode 进度</strong></div><i>{{ displayLabel(activity?.todoCapability) }}</i></header>
            <p v-if="activity?.todoDetail" class="todo-detail">{{ activity.todoDetail }}</p>
            <p v-if="activity?.todoCapability === 'AVAILABLE' && !activity.todos.length" class="todo-empty">暂无实施项。</p>
            <ol v-else-if="activity?.todos.length">
              <li v-for="todo in activity.todos" :key="todo.id" :class="`todo-${todo.status.toLowerCase()}`">
                <span class="todo-state"><Icon :icon="todo.status === 'COMPLETED' ? 'lucide:circle-check' : todo.status === 'IN_PROGRESS' ? 'lucide:loader-circle' : todo.status === 'CANCELLED' ? 'lucide:circle-x' : 'lucide:circle'" width="14" /></span>
                <span>{{ todo.content }}</span><small>{{ displayLabel(todo.status) }}{{ todo.priority ? ` · ${displayLabel(todo.priority)}` : '' }}</small>
              </li>
            </ol>
            <footer v-if="activity?.todoTruncated">Todo 过长，当前仅显示安全截断后的投影。</footer>
          </section>
          <section v-for="part in activity?.parts ?? []" :key="part.id" :class="['activity-part', `part-${part.type.toLowerCase()}`]">
            <header><span>{{ activityTypeLabel(part.type) }}</span><strong>{{ activityLabel(part) }}</strong><time v-if="part.startedAt" :datetime="part.startedAt" :title="part.startedAt">{{ formatActivityTime(part.startedAt) }}</time><i v-if="part.status">{{ statusLabel(part.status) }}</i></header>
            <div v-if="part.content" class="part-content">
              <pre :ref="(element) => bindPartContent(part.id, element as Element | null)" :class="{ 'is-collapsed': !expandedParts.has(part.id) }">{{ part.content }}</pre>
              <button
                v-if="overflowingParts.has(part.id)"
                type="button"
                class="part-expand-button"
                :aria-expanded="expandedParts.has(part.id)"
                @click="togglePart(part.id)"
              >
                {{ expandedParts.has(part.id) ? '收起输出' : '展开完整输出' }}
                <span aria-hidden="true">{{ expandedParts.has(part.id) ? '↑' : '↓' }}</span>
              </button>
            </div>
          </section>
          <section v-for="pending in pendingQuestions" :key="pending.id" class="question-card" aria-label="OpenCode 等待回答">
            <header class="question-card-header"><div><span>需要你的回答</span></div><Icon icon="lucide:message-square-more" width="19" /></header>
            <div v-for="(prompt, index) in pending.questions" :key="`${pending.id}-${index}`" class="question-prompt">
              <p class="question-header">{{ prompt.header || `问题 ${index + 1}` }}</p>
              <h3>{{ prompt.question }}</h3>
              <el-checkbox-group v-if="prompt.multiple" :model-value="answerDraft(pending.id, index)" class="question-options" @update:model-value="(value: unknown) => setMultipleAnswers(pending.id, index, value)">
                <el-checkbox v-for="option in prompt.options" :key="option.label" :value="option.label" border>
                  <span><b>{{ option.label }}</b><small>{{ option.description }}</small></span>
                </el-checkbox>
              </el-checkbox-group>
              <el-radio-group v-else :model-value="answerDraft(pending.id, index)[0] ?? ''" class="question-options" @update:model-value="(value: string | number | boolean | undefined) => setSingleAnswer(pending.id, index, value)">
                <el-radio v-for="option in prompt.options" :key="option.label" :value="option.label" border>
                  <span><b>{{ option.label }}</b><small>{{ option.description }}</small></span>
                </el-radio>
              </el-radio-group>
              <el-input v-if="prompt.custom" :model-value="customDraft(pending.id, index)" class="custom-answer" type="textarea" :rows="2" :placeholder="prompt.multiple ? '可补充自定义回答（会与已选项一起提交）' : '或输入自定义回答（会替代已选项）'" @update:model-value="(value: unknown) => setCustomDraft(pending.id, index, value)" />
            </div>
            <footer><el-button plain :disabled="Boolean(submittingQuestion)" @click="rejectQuestion(pending)">拒绝</el-button><el-button type="primary" :loading="submittingQuestion === pending.id" :disabled="!canSubmit(pending) || Boolean(submittingQuestion && submittingQuestion !== pending.id)" @click="submitAnswer(pending)">提交回答并继续</el-button></footer>
          </section>
          <div v-if="thinking" class="live-thinking" role="status" aria-label="模型正在思考">
            <span class="thinking-orbit"><span /></span>
            <div><strong>模型正在思考<span class="thinking-dots"><i /><i /><i /></span></strong></div>
          </div>
          <div v-if="!thinking && !error && activity && activity.parts.length === 0" class="monitor-placeholder">当前会话没有可显示的模型输出。</div>
        </div>
        <button v-if="!followOutput" type="button" class="follow-button" @click="followOutput = true; nextTick(() => { if (stream) stream.scrollTop = stream.scrollHeight })"><Icon icon="lucide:arrow-down" />跟随最新输出</button>
      </article>
    </div>
  </section>
</template>

<style scoped>
.session-monitor { margin-top: 16px; overflow: hidden; border-color: rgb(139 92 246 / 27%); background: linear-gradient(145deg, rgb(139 92 246 / 5%), transparent 42%), var(--color-bg-surface); box-shadow: 0 18px 48px rgb(0 0 0 / 18%); }
.monitor-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding: 20px; border-bottom: 1px solid var(--color-border-default); }.monitor-header h2 { margin-top: 3px; }.monitor-header p:last-child { margin-bottom: 0; }
.monitor-actions { display: flex; flex: 0 0 auto; align-items: center; flex-wrap: wrap; justify-content: flex-end; gap: 9px; }
.live-indicator { display: inline-flex; align-items: center; gap: 7px; flex: 0 0 auto; padding: 6px 9px; border: 1px solid var(--color-border-default); border-radius: 999px; color: var(--color-text-muted); font: 700 10px/1 var(--font-code); }.live-indicator span { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }.live-indicator.active { border-color: rgb(34 211 238 / 32%); color: var(--color-accent-cyan); }.live-indicator.active span { animation: live-pulse 1.2s ease-in-out infinite; box-shadow: 0 0 12px currentColor; }
.monitor-layout { display: grid; grid-template-columns: minmax(275px, .38fr) minmax(0, 1fr); min-height: 390px; }.session-list { max-height: 530px; overflow: auto; padding: 10px; border-right: 1px solid var(--color-border-default); background: rgb(7 11 20 / 34%); }.session-option { display: grid; width: 100%; gap: 9px; margin: 0 0 8px; padding: 13px; border: 1px solid transparent; border-radius: 9px; color: var(--color-text-secondary); text-align: left; background: transparent; cursor: pointer; }.session-option:hover { border-color: var(--color-border-default); background: var(--color-bg-hover); }.session-option.selected { border-color: rgb(139 92 246 / 40%); color: var(--color-text-primary); background: linear-gradient(100deg, rgb(139 92 246 / 14%), rgb(34 211 238 / 5%)); box-shadow: inset 2px 0 #8b5cf6; }.session-option-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }.session-option strong { min-width: 0; font-size: 11px; line-height: 1.35; }.session-option i { flex: 0 0 auto; padding: 3px 5px; border-radius: 4px; color: var(--color-text-muted); background: rgb(101 115 138 / 12%); font: 700 8px/1 var(--font-code); font-style: normal; }.session-option i.running, .session-option i.creating { color: var(--color-accent-cyan); background: rgb(34 211 238 / 10%); }.session-purpose { display: flex; align-items: flex-start; gap: 7px; color: #aebbd0; font-size: 10px; line-height: 1.55; overflow-wrap: anywhere; }.session-purpose :deep(svg) { flex: 0 0 auto; margin-top: 1px; color: var(--color-accent-ai); }.session-option.selected .session-purpose :deep(svg) { color: var(--color-accent-cyan); }.session-option small { color: var(--color-text-muted); font: 8px/1.35 var(--font-code); }
.session-console { position: relative; display: grid; grid-template-rows: auto 1fr; min-width: 0; background: #070b14; }.console-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 15px; min-height: 48px; padding: 9px 14px; border-bottom: 1px solid var(--color-border-default); color: var(--color-text-muted); font-size: 9px; }.console-toolbar > div { display: flex; align-items: center; gap: 9px; }.console-toolbar strong { color: var(--color-text-primary); font-size: 11px; }.console-toolbar .mono { color: var(--color-accent-cyan); }.transport-dot { display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: var(--color-text-muted); }.transport-dot.live { background: var(--color-success); box-shadow: 0 0 9px rgb(34 197 94 / 60%); }
.console-stream { max-height: 480px; min-height: 340px; overflow: auto; padding: 15px; scroll-behavior: smooth; }.activity-part { margin-bottom: 12px; overflow: hidden; border: 1px solid var(--color-border-default); border-radius: 8px; background: rgb(13 20 36 / 72%); }.activity-part header { display: flex; align-items: center; gap: 8px; padding: 7px 10px; border-bottom: 1px solid var(--color-border-default); color: var(--color-text-muted); font: 9px/1.2 var(--font-code); }.activity-part header span { color: var(--color-accent-cyan); font-weight: 800; letter-spacing: .08em; }.activity-part header strong { color: var(--color-text-secondary); font-weight: 600; }.activity-part header time { margin-left: auto; color: var(--color-text-muted); font: inherit; font-variant-numeric: tabular-nums; }.activity-part header time + i { margin-left: 0; }.activity-part header i { margin-left: auto; font-style: normal; }.activity-part pre { margin: 0; padding: 11px; overflow: visible; color: #d7e1f0; font: 11px/1.65 var(--font-code); white-space: pre-wrap; overflow-wrap: anywhere; }.activity-part pre.is-collapsed { max-height: calc(5 * 1.65em + 22px); overflow: hidden; }.part-expand-button { display: inline-flex; align-items: center; gap: 5px; margin: 0 10px 9px; padding: 4px 0; border: 0; color: var(--color-accent-cyan); background: transparent; font: 600 10px/1.4 var(--font-ui); cursor: pointer; }.part-expand-button:hover { color: #e0f2fe; }.part-expand-button:focus-visible { border-radius: 4px; outline: 2px solid var(--color-accent-cyan); outline-offset: 3px; }.part-thinking { border-color: rgb(139 92 246 / 28%); background: rgb(139 92 246 / 5%); }.part-thinking header span { color: #a78bfa; }.part-tool { border-color: rgb(245 158 11 / 22%); }.part-tool header span { color: var(--color-session-warning); }
.todo-panel { margin: 0 0 14px; overflow: hidden; border: 1px solid rgb(34 211 238 / 26%); border-radius: 9px; background: linear-gradient(120deg, rgb(34 211 238 / 7%), rgb(139 92 246 / 5%)); }.todo-panel > header { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 9px 11px; border-bottom: 1px solid rgb(34 211 238 / 18%); }.todo-panel > header div { display: grid; gap: 3px; }.todo-panel > header span { color: var(--color-accent-cyan); font: 800 8px/1 var(--font-code); letter-spacing: .1em; }.todo-panel > header strong { color: var(--color-text-primary); font-size: 11px; }.todo-panel > header i { color: var(--color-text-muted); font: 700 8px var(--font-code); font-style: normal; }.todo-panel ol { display: grid; gap: 7px; margin: 0; padding: 10px 11px; list-style: none; }.todo-panel li { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: start; gap: 8px; color: var(--color-text-secondary); font-size: 10px; line-height: 1.45; }.todo-panel li small { color: var(--color-text-muted); font: 8px var(--font-code); }.todo-state { color: var(--color-text-muted); }.todo-in_progress .todo-state { color: var(--color-accent-cyan); }.todo-in_progress .todo-state :deep(svg) { animation: thinking-spin 1.3s linear infinite; }.todo-completed { opacity: .68; }.todo-completed .todo-state { color: var(--color-success); }.todo-cancelled { text-decoration: line-through; opacity: .55; }.todo-detail,.todo-empty,.todo-panel footer { margin: 0; padding: 9px 11px; color: var(--color-text-muted); font-size: 9px; line-height: 1.5; }.todo-panel footer { border-top: 1px solid var(--color-border-default); color: var(--color-session-warning); }
.question-card { margin: 16px 0 12px; overflow: hidden; border: 1px solid rgb(34 211 238 / 42%); border-radius: 10px; background: linear-gradient(135deg, rgb(34 211 238 / 9%), rgb(139 92 246 / 7%)); box-shadow: 0 14px 36px rgb(0 0 0 / 22%); }.question-card-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 14px; border-bottom: 1px solid rgb(34 211 238 / 24%); color: var(--color-accent-cyan); }.question-card-header > div { display: flex; align-items: baseline; gap: 10px; }.question-card-header span { font-size: 12px; font-weight: 800; }.question-card-header strong { color: var(--color-text-muted); font-size: 8px; font-weight: 500; }.question-prompt { padding: 14px; border-bottom: 1px solid var(--color-border-default); }.question-header { margin: 0 0 5px; color: #a78bfa; font: 800 9px/1.2 var(--font-code); letter-spacing: .08em; text-transform: uppercase; }.question-prompt h3 { margin: 0 0 12px; color: var(--color-text-primary); font-size: 12px; line-height: 1.6; }.question-options { display: grid; gap: 8px; }.question-options :deep(.el-radio), .question-options :deep(.el-checkbox) { width: 100%; height: auto; min-height: 44px; margin: 0; padding: 8px 10px; white-space: normal; }.question-options :deep(.el-radio__label), .question-options :deep(.el-checkbox__label) { width: 100%; white-space: normal; }.question-options span { display: grid; gap: 3px; }.question-options b { color: var(--color-text-primary); font-size: 10px; }.question-options small { color: var(--color-text-muted); font-size: 9px; line-height: 1.4; }.custom-answer { margin-top: 9px; }.question-card footer { display: flex; justify-content: flex-end; gap: 8px; padding: 12px 14px; }
.live-thinking { display: flex; align-items: center; gap: 13px; margin-bottom: 14px; padding: 14px; border: 1px solid rgb(139 92 246 / 28%); border-radius: 9px; background: linear-gradient(100deg, rgb(139 92 246 / 10%), rgb(34 211 238 / 4%), rgb(139 92 246 / 10%)); background-size: 200% 100%; animation: thinking-sheen 3s ease-in-out infinite; }.thinking-orbit { display: grid; place-items: center; width: 32px; height: 32px; border: 2px solid rgb(139 92 246 / 20%); border-top-color: #a78bfa; border-right-color: var(--color-accent-cyan); border-radius: 50%; animation: thinking-spin .9s linear infinite; }.thinking-orbit > span { width: 7px; height: 7px; border-radius: 50%; background: var(--color-accent-cyan); box-shadow: 0 0 10px rgb(34 211 238 / 55%); }.live-thinking strong { display: flex; align-items: baseline; color: #f5f3ff; font-size: 11px; }.live-thinking p { margin: 4px 0 0; color: var(--color-text-muted); font-size: 9px; }.thinking-dots { display: inline-flex; gap: 3px; margin-left: 5px; }.thinking-dots i { width: 3px; height: 3px; border-radius: 50%; background: var(--color-accent-cyan); animation: thinking-dot 1.1s ease-in-out infinite; }.thinking-dots i:nth-child(2) { animation-delay: .15s; }.thinking-dots i:nth-child(3) { animation-delay: .3s; }
.monitor-empty { display: grid; min-height: 220px; place-content: center; place-items: center; gap: 12px; color: var(--color-text-muted); font-size: 11px; }.monitor-spinner { width: 28px; height: 28px; border: 2px solid var(--color-border-default); border-top-color: var(--color-accent-cyan); border-radius: 50%; animation: thinking-spin .9s linear infinite; }.monitor-warning { display: flex; align-items: flex-start; gap: 8px; margin-bottom: 12px; padding: 10px; border: 1px solid rgb(245 158 11 / 28%); border-radius: 7px; color: var(--color-session-warning); background: rgb(245 158 11 / 6%); font-size: 10px; line-height: 1.5; }.monitor-placeholder { padding: 70px 20px; color: var(--color-text-muted); font-size: 10px; text-align: center; }.follow-button { position: absolute; right: 16px; bottom: 14px; display: inline-flex; align-items: center; gap: 6px; padding: 7px 9px; border: 1px solid rgb(34 211 238 / 30%); border-radius: 999px; color: var(--color-accent-cyan); background: #0d1424; font: 9px var(--font-code); cursor: pointer; }
@keyframes live-pulse { 0%, 100% { opacity: .35; transform: scale(.85); } 50% { opacity: 1; transform: scale(1.15); } }@keyframes thinking-spin { to { transform: rotate(360deg); } }@keyframes thinking-dot { 0%, 70%, 100% { opacity: .25; transform: translateY(0); } 35% { opacity: 1; transform: translateY(-3px); } }@keyframes thinking-sheen { 0%, 100% { background-position: 0 50%; } 50% { background-position: 100% 50%; } }
@media (max-width: 1050px) { .monitor-layout { grid-template-columns: 1fr; }.session-list { display: flex; max-height: none; overflow-x: auto; border-right: 0; border-bottom: 1px solid var(--color-border-default); }.session-option { min-width: 230px; }.console-toolbar { align-items: flex-start; flex-direction: column; } }
@media (max-width: 640px) { .monitor-header { align-items: stretch; flex-direction: column; }.monitor-actions { justify-content: flex-start; } }
</style>
