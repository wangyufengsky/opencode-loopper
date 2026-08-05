<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import LayeredErrorPanel from '@/components/LayeredErrorPanel.vue'
import LoopSpecEditor from '@/components/LoopSpecEditor.vue'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import ExecutionAcceptancePanel from '@/components/ExecutionAcceptancePanel.vue'
import PendingQuestionCard from '@/components/PendingQuestionCard.vue'
import { api, subscribeDesignerEvents, type DesignerEventStream } from '@/api/client'
import { demoDraft, demoMessages } from '@/mock/demoData'
import { useTaskStore } from '@/stores/taskStore'
import type { AppSettings, DesignerMessage, DesignerSession, ErrorEvent, LoopDraft, TaskSessionPendingQuestion } from '@/types/domain'

const store = useTaskStore()
const router = useRouter()
const draft = ref<LoopDraft>()
const designerSession = ref<DesignerSession>()
const messages = ref<DesignerMessage[]>([])
const editorValue = ref('')
const fieldError = ref<ErrorEvent>()
const busy = ref(false)
const designerReconnecting = ref(false)
const designerStreamState = ref<'idle' | 'connecting' | 'connected' | 'reconnecting'>('idle')
const designerRuntimeConnected = ref(false)
const designerRemoteState = ref('')
const designerLiveResponse = ref('')
const designerLiveError = ref('')
const designerLiveDetail = ref('')
const designerObservedAt = ref('')
const submittingDesignerQuestion = ref('')
const selectedProjectId = ref('')
const designerWorkspaceKey = 'opencode-loopper.designer-workspace'
const draftPromptKey = 'opencode-loopper.designer-draft-prompt'
const messageDraftKey = 'opencode-loopper.designer-message-draft'

function readSessionText(key: string) {
  try { return sessionStorage.getItem(key) ?? '' } catch { return '' }
}

function persistSessionText(key: string, value: string) {
  try {
    if (value) sessionStorage.setItem(key, value)
    else sessionStorage.removeItem(key)
  } catch { /* Storage can be unavailable in privacy-restricted browser contexts. */ }
}

const draftPrompt = ref(readSessionText(draftPromptKey))
const userMessage = ref(readSessionText(messageDraftKey))
let designerPollTimer: ReturnType<typeof setTimeout> | undefined
let designerPollInFlight = false
let designerPollFailures = 0
let designerPollGeneration = 0
let designerEventStream: DesignerEventStream | undefined
let designerStreamGeneration = 0
const selectedProject = computed(() => store.projects.find((project) => project.id === selectedProjectId.value))
const activeProjectName = computed(() => selectedProject.value?.name ?? '选择项目')
const briefTemplates = [
  {
    label: '开发新功能',
    icon: 'lucide:blocks',
    prompt: '我想实现一个新功能。\n\n目标：\n使用场景：\n功能范围：\n限制与禁止项：\n验收标准：',
  },
  {
    label: '修复问题',
    icon: 'lucide:bug',
    prompt: '我想修复一个问题。\n\n当前现象：\n期望行为：\n复现条件：\n可修改范围：\n验收方式：',
  },
  {
    label: '重构模块',
    icon: 'lucide:network',
    prompt: '我想重构现有模块。\n\n重构目标：\n必须保持的行为：\n可调整范围：\n不可修改项：\n验收命令与标准：',
  },
]
const dirty = computed(() => draft.value !== undefined && editorValue.value !== JSON.stringify(draft.value.spec, null, 2))
const designerBadgeStatus = computed(() => {
  if (designerSession.value?.state === 'RUNNING') return 'RUNNING' as const
  if (designerSession.value?.state === 'COMPLETED') return 'SUCCEEDED' as const
  if (designerSession.value?.state === 'SESSION_ERROR') return 'RETRY_WAIT' as const
  return 'PENDING' as const
})
const designerSessionError = computed(() => designerSession.value?.state === 'SESSION_ERROR'
  ? [...messages.value].reverse().find((message) => message.deliveryState === 'SESSION_ERROR')
  : undefined)
const designerIsThinking = computed(() => designerSession.value?.state === 'RUNNING' && !designerLiveResponse.value
  && (designerSession.value.pendingQuestions?.length ?? 0) === 0)
const designerTransportLabel = computed(() => {
  if (designerStreamState.value === 'connected') return '实时通道已连接'
  if (designerStreamState.value === 'reconnecting') return '实时通道重连中'
  return '正在连接实时通道'
})
const designerRuntimeLabel = computed(() => {
  if (designerRuntimeConnected.value) return 'OpenCode 已连接'
  if (designerLiveError.value) return 'OpenCode 异常'
  return 'OpenCode 状态探测中'
})
const visibleMessages = computed(() => messages.value.filter((message) => !(
  message.role === 'SYSTEM'
  && message.deliveryState === 'PENDING_HANDOFF'
  && !message.content.startsWith('SYSTEM_ERROR')
)))

function loadDemo(goal?: string) {
  draft.value = structuredClone(demoDraft)
  messages.value = structuredClone(demoMessages)
  if (goal) {
    draft.value.spec.goal = goal
    const openingMessage = messages.value.find((message) => message.role === 'USER')
    if (openingMessage) openingMessage.content = goal
  }
  selectedProjectId.value = draft.value.spec.projectId
  editorValue.value = JSON.stringify(draft.value.spec, null, 2)
}

function mergeMessages(incoming: DesignerMessage[]) {
  const byId = new Map(messages.value.map((message) => [message.id, message]))
  for (const message of incoming) byId.set(message.id, message)
  messages.value = [...byId.values()]
}

function stopDesignerPolling() {
  if (designerPollTimer) clearTimeout(designerPollTimer)
  designerPollTimer = undefined
}

async function refreshDesignerSession() {
  if (!designerSession.value || store.usingDemo || designerPollInFlight) return
  const sessionId = designerSession.value.id
  const generation = designerPollGeneration
  designerPollInFlight = true
  try {
    const refreshed = await api.getDesignerSession(sessionId)
    if (generation !== designerPollGeneration || designerSession.value?.id !== sessionId) return
    const hadLocalChanges = dirty.value
    designerSession.value = refreshed
    mergeMessages(refreshed.messages)
    if (refreshed.draft) {
      draft.value = refreshed.draft
      if (!hadLocalChanges) editorValue.value = JSON.stringify(refreshed.draft.spec, null, 2)
      else if (editorValue.value !== JSON.stringify(refreshed.draft.spec, null, 2)) {
        ElMessage.warning('Designer 已生成新的 LoopSpec；右侧保留了你的未保存编辑，请保存或刷新后查看服务端版本。')
      }
    }
    designerPollFailures = 0
    designerReconnecting.value = false
    if (refreshed.state !== 'RUNNING') {
      stopDesignerPolling()
      if (refreshed.state === 'COMPLETED') designerLiveResponse.value = ''
    }
  } catch (error) {
    if (generation !== designerPollGeneration || designerSession.value?.id !== sessionId) return
    designerPollFailures += 1
    designerReconnecting.value = true
    if (designerPollFailures === 1) {
      ElMessage.warning(error instanceof Error ? `${error.message}；正在自动重连` : 'Designer 会话刷新失败，正在自动重连')
    }
  } finally {
    if (generation === designerPollGeneration) designerPollInFlight = false
  }
}

function scheduleDesignerPoll(delay = 0) {
  if (designerPollTimer || designerSession.value?.state !== 'RUNNING' || store.usingDemo) return
  designerPollTimer = setTimeout(async () => {
    designerPollTimer = undefined
    await refreshDesignerSession()
    if (designerSession.value?.state === 'RUNNING') {
      const retryDelay = designerPollFailures === 0 ? 1500 : Math.min(1500 * (2 ** Math.min(designerPollFailures, 3)), 12000)
      scheduleDesignerPoll(retryDelay)
    }
  }, delay)
}

function startDesignerPolling() {
  scheduleDesignerPoll(0)
}

function reconnectDesigner() {
  stopDesignerPolling()
  designerPollFailures = 0
  designerReconnecting.value = false
  startDesignerPolling()
  if (designerSession.value) startDesignerStream(designerSession.value.id)
}

async function answerDesignerQuestion(pending: TaskSessionPendingQuestion, answers: string[][]) {
  if (!designerSession.value || submittingDesignerQuestion.value) return
  submittingDesignerQuestion.value = pending.id
  try {
    await api.replyDesignerQuestion(designerSession.value.id, pending.id, answers)
    designerSession.value = {
      ...designerSession.value,
      pendingQuestions: (designerSession.value.pendingQuestions ?? []).filter((question) => question.id !== pending.id),
    }
    designerRemoteState.value = 'RUNNING'
    designerLiveDetail.value = '回答已提交，OpenCode Designer 继续生成设计稿'
    ElMessage.success('回答已提交，Designer 继续执行')
    await refreshDesignerSession()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '回答提交失败')
  } finally {
    submittingDesignerQuestion.value = ''
  }
}

async function rejectDesignerQuestion(pending: TaskSessionPendingQuestion) {
  if (!designerSession.value || submittingDesignerQuestion.value) return
  try {
    await ElMessageBox.confirm('拒绝后，本次 question 工具调用会结束并把拒绝结果返回 Designer。', '拒绝这个问题？', { confirmButtonText: '确认拒绝', cancelButtonText: '返回回答', type: 'warning' })
  } catch { return }
  submittingDesignerQuestion.value = pending.id
  try {
    await api.rejectDesignerQuestion(designerSession.value.id, pending.id)
    designerSession.value = {
      ...designerSession.value,
      pendingQuestions: (designerSession.value.pendingQuestions ?? []).filter((question) => question.id !== pending.id),
    }
    designerRemoteState.value = 'RUNNING'
    designerLiveDetail.value = '问题已拒绝，OpenCode Designer 将自行处理'
    ElMessage.success('问题已拒绝，Designer 继续执行')
    await refreshDesignerSession()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '拒绝操作失败')
  } finally {
    submittingDesignerQuestion.value = ''
  }
}

function stopDesignerStream() {
  designerStreamGeneration += 1
  designerEventStream?.close()
  designerEventStream = undefined
  designerStreamState.value = 'idle'
}

function startDesignerStream(sessionId: string) {
  stopDesignerStream()
  const generation = designerStreamGeneration
  designerStreamState.value = 'connecting'
  designerEventStream = subscribeDesignerEvents(sessionId, (event) => {
    if (generation !== designerStreamGeneration || designerSession.value?.id !== sessionId) return
    designerRuntimeConnected.value = event.runtimeConnected
    designerRemoteState.value = event.remoteState ?? event.state
    designerObservedAt.value = event.at
    designerLiveDetail.value = event.detail
    if (event.content && (event.type === 'PARTIAL' || event.type === 'COMPLETED')) designerLiveResponse.value = event.content
    if (event.type === 'ERROR') designerLiveError.value = event.detail || 'OpenCode Designer 返回错误'
    if (designerSession.value && designerSession.value.state !== event.state) {
      designerSession.value = { ...designerSession.value, state: event.state, updatedAt: event.at }
    }
    if (event.type === 'COMPLETED' || event.type === 'ERROR') refreshDesignerAfterTerminalEvent()
  }, (state) => {
    if (generation === designerStreamGeneration) designerStreamState.value = state
  })
}

function formatObservedAt(value: string) {
  if (!value) return '等待首次状态'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(date)
}

function refreshDesignerAfterTerminalEvent(attempt = 0) {
  if (designerPollInFlight) {
    if (attempt < 50) setTimeout(() => refreshDesignerAfterTerminalEvent(attempt + 1), 100)
    return
  }
  void refreshDesignerSession()
}

watch(() => designerSession.value?.state, (state) => {
  if (state === 'RUNNING') startDesignerPolling()
  else stopDesignerPolling()
})

watch(() => designerSession.value?.id, (sessionId) => {
  if (sessionId && !store.usingDemo) startDesignerStream(sessionId)
  else stopDesignerStream()
})

watch(() => store.projects, (projects) => {
  if (selectedProjectId.value && !projects.some((project) => project.id === selectedProjectId.value)) {
    selectedProjectId.value = ''
  }
  const onlyProject = projects.length === 1 ? projects[0] : undefined
  if (!selectedProjectId.value && onlyProject) selectedProjectId.value = onlyProject.id
}, { immediate: true, deep: true })

watch(draftPrompt, (value) => persistSessionText(draftPromptKey, value))
watch(userMessage, (value) => persistSessionText(messageDraftKey, value))

async function applyBriefTemplate(prompt: string) {
  if (draftPrompt.value.trim() && draftPrompt.value !== prompt) {
    try {
      await ElMessageBox.confirm('使用快速模板会覆盖当前尚未提交的草稿内容。', '覆盖当前草稿？', {
        confirmButtonText: '确认覆盖', cancelButtonText: '保留当前内容', type: 'warning',
      })
    } catch { return }
  }
  draftPrompt.value = prompt
  const focusEditor = () => document.querySelector<HTMLTextAreaElement>('#designer-draft-prompt')?.focus()
  if (typeof requestAnimationFrame === 'function') requestAnimationFrame(focusEditor)
  else focusEditor()
}

function blankSpec(projectId: string, goal: string, settings: AppSettings): LoopDraft['spec'] {
  return { schemaVersion: 'v1', projectId, goal, context: 'Execution 只允许在该 Task 的执行目录中修改；有 Git HEAD 时使用隔离 worktree，否则使用登记的项目目录。', stages: [{ objective: '分析目标并实现最小可验证改动', allowedPaths: [], forbiddenPaths: [], deliverables: ['可验证实现'], verifiers: [] }], limits: { maxStageAttempts: 3, maxTaskAttempts: settings.maxTaskAttempts, maxDuration: 'PT2H', attemptTimeout: `PT${settings.timeoutMinutes}M` } }
}

async function startDraft() {
  const goal = draftPrompt.value.trim()
  if (!goal) { ElMessage.warning('请先填写设计目标、约束或验收标准。'); return }
  if (store.usingDemo) {
    loadDemo(goal)
    draftPrompt.value = ''
    return
  }
  const project = selectedProject.value
  if (!project) { ElMessage.warning('请先在“项目”页面登记一个可用项目根目录。'); return }
  busy.value = true
  try {
    const settings = await api.getSettings()
    const createdDraft = await api.createDraft(blankSpec(project.id, goal, settings))
    designerSession.value = await api.createDesignerSession(project.id, createdDraft.id, goal)
    messages.value = designerSession.value.messages
    draft.value = designerSession.value.draft ?? createdDraft
    editorValue.value = JSON.stringify(draft.value.spec, null, 2)
    sessionStorage.setItem(designerWorkspaceKey, JSON.stringify({ sessionId: designerSession.value.id, draftId: draft.value.id }))
    draftPrompt.value = ''
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '无法创建 LoopSpec 草案') } finally { busy.value = false }
}

function clearDesignerWorkspace() {
  stopDesignerPolling()
  stopDesignerStream()
  designerPollGeneration += 1
  designerPollInFlight = false
  designerPollFailures = 0
  designerReconnecting.value = false
  designerRuntimeConnected.value = false
  designerRemoteState.value = ''
  designerLiveResponse.value = ''
  designerLiveError.value = ''
  designerLiveDetail.value = ''
  designerObservedAt.value = ''
  designerSession.value = undefined
  messages.value = []
  draft.value = undefined
  editorValue.value = ''
  fieldError.value = undefined
  draftPrompt.value = ''
  userMessage.value = ''
  sessionStorage.removeItem(designerWorkspaceKey)
  sessionStorage.removeItem(draftPromptKey)
  sessionStorage.removeItem(messageDraftKey)
}

async function restartDesigner() {
  try {
    await ElMessageBox.confirm(
      '将清空当前浏览器中的 Designer 工作区和未发送输入。后端历史记录保留用于审计，已创建的 Task 不受影响。',
      '重新开始 Designer？',
      { type: 'warning', confirmButtonText: '清理并重新开始', cancelButtonText: '取消' },
    )
    clearDesignerWorkspace()
    ElMessage.success('已清理当前 Designer 工作区，可以创建新草案')
  } catch { /* The user cancelled the destructive local reset. */ }
}

async function restoreDesignerWorkspace() {
  const saved = sessionStorage.getItem(designerWorkspaceKey)
  if (!saved) return
  try {
    const ids = JSON.parse(saved) as { sessionId?: string, draftId?: string }
    if (!ids.sessionId || !ids.draftId) throw new Error('Designer workspace reference is incomplete')
    const [restoredSession, restoredDraft] = await Promise.all([
      api.getDesignerSession(ids.sessionId),
      api.getDraft(ids.draftId),
    ])
    designerSession.value = restoredSession
    messages.value = restoredSession.messages
    draft.value = restoredSession.draft ?? restoredDraft
    selectedProjectId.value = draft.value.spec.projectId
    editorValue.value = JSON.stringify(draft.value.spec, null, 2)
  } catch {
    sessionStorage.removeItem(designerWorkspaceKey)
  }
}

onMounted(async () => {
  window.addEventListener('beforeunload', warnBeforeUnload)
  if (!store.usingDemo) await restoreDesignerWorkspace()
})

onBeforeUnmount(() => {
  stopDesignerPolling()
  stopDesignerStream()
  window.removeEventListener('beforeunload', warnBeforeUnload)
})
onBeforeRouteLeave(async () => {
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm('LoopSpec 有未保存的修改，离开后这些修改不会保留。', '离开 Designer？', { type: 'warning', confirmButtonText: '离开', cancelButtonText: '继续编辑' })
    return true
  } catch { return false }
})

function warnBeforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

function parsedSpec() {
  try {
    const spec = JSON.parse(editorValue.value) as LoopDraft['spec']
    if (!spec.goal?.trim()) throw new Error('goal 不能为空')
    if (!Array.isArray(spec.stages) || spec.stages.length === 0) throw new Error('至少需要一个 stage')
    return spec
  } catch (error) {
    fieldError.value = { id: 'field-json', layer: 'FIELD', code: 'LOOPSPEC_INVALID', message: error instanceof Error ? `LoopSpec 校验失败：${error.message}` : 'LoopSpec 不是有效 JSON', retryable: true, occurredAt: '刚刚' }
    return undefined
  }
}

async function saveDraft(): Promise<boolean> {
  const spec = parsedSpec()
  if (!spec || !draft.value) return false
  busy.value = true
  designerLiveResponse.value = ''
  designerLiveError.value = ''
  designerLiveDetail.value = '正在将提示词交给 OpenCode'
  try {
    draft.value = store.usingDemo ? { ...draft.value, spec, updatedAt: new Date().toISOString() } : await api.updateDraft(draft.value.id, spec)
    editorValue.value = JSON.stringify(draft.value.spec, null, 2)
    fieldError.value = undefined
    ElMessage.success('LoopSpec 已保存并通过基础校验')
    return true
  } catch (error) {
    fieldError.value = { id: 'field-api', layer: 'FIELD', code: 'LOOPSPEC_SAVE_FAILED', message: error instanceof Error ? error.message : '保存失败', retryable: true, occurredAt: '刚刚' }
    return false
  } finally { busy.value = false }
}

async function confirm() {
  if (!draft.value) return
  if (draft.value.status !== 'CONFIRMED' && !await saveDraft()) return
  busy.value = true
  try {
    if (store.usingDemo) { draft.value = { ...draft.value, status: 'CONFIRMED' }; ElMessage.success('演示：Task 已创建，等待 MCP 交接') }
    else {
      const result = await api.confirmDraft(draft.value.id)
      draft.value = await api.getDraft(draft.value.id)
      editorValue.value = JSON.stringify(draft.value.spec, null, 2)
      const task = await store.loadTask(result.taskId)
      if (task?.status === 'FAILED') {
        const reason = task.errors?.find((error) => error.layer === 'TASK')
        ElMessage.error(`Task ${result.taskId} 已创建，但准备失败${reason ? `：${reason.message}` : ''}`)
      } else {
        ElMessage.success(`Task ${result.taskId} 已创建并交接到任务控制台`)
      }
      await router.push(`/tasks/${result.taskId}`)
    }
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '确认失败') } finally { busy.value = false }
}

async function sendMessage() {
  const content = userMessage.value.trim()
  if (!content) return
  if (store.usingDemo) {
    messages.value.push({ id: `local-${Date.now()}`, role: 'USER', content, deliveryState: 'PERSISTED', createdAt: '刚刚' })
    userMessage.value = ''
    return
  }
  if (!designerSession.value) {
    ElMessage.warning('请先创建 Designer session。')
    return
  }
  busy.value = true
  try {
    const result = await api.sendDesignerMessage(designerSession.value.id, content)
    mergeMessages(result.persistedMessages)
    designerSession.value = { ...designerSession.value, state: result.state }
    userMessage.value = ''
    ElMessage.info(result.notice || '消息已交给只读 OpenCode Designer。')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法保存 Designer 消息')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <PageHeader :eyebrow="draft ? 'Designer / Plan' : 'Designer'" :title="draft ? 'Designer 与 LoopSpec' : '设计工作台'">
    <template v-if="draft" #actions><StatusBadge :status="draft.status === 'CONFIRMED' ? 'SUCCEEDED' : 'PENDING'" :label="draft.status" /><el-button class="restart-designer-button" plain :disabled="busy" @click="restartDesigner"><Icon icon="lucide:rotate-ccw" />重新开始</el-button><el-button type="primary" :loading="busy" @click="confirm"><Icon icon="lucide:circle-check-big" />确认并交接</el-button></template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <section v-if="!draft" class="designer-start-page">
      <header class="designer-start-heading">
        <span class="designer-start-mark"><Icon icon="lucide:sparkles" /></span>
        <div>
          <p class="eyebrow">NEW DESIGN</p>
          <h2>今天想推进什么？</h2>
          <p>选择一个项目，描述你期望达成的结果。</p>
        </div>
      </header>

      <div class="designer-start-layout">
        <article class="card brief-composer">
          <div v-if="!store.usingDemo" class="composer-project-row">
            <div class="composer-project-label">
              <span class="composer-project-icon"><Icon icon="lucide:folder-git-2" /></span>
              <span><small>项目上下文</small><strong>{{ selectedProject?.name ?? '选择项目' }}</strong></span>
            </div>
            <el-select id="designer-project" v-model="selectedProjectId" filterable placeholder="选择项目" aria-label="选择 Designer 项目">
              <el-option v-for="project in store.projects" :key="project.id" :label="project.name" :value="project.id"><span>{{ project.name }}</span><span class="project-path">{{ project.rootPath }}</span></el-option>
            </el-select>
          </div>

          <div class="brief-editor">
            <div class="draft-goal-heading">
              <label for="designer-draft-prompt">描述你的目标</label>
              <span class="draft-save-state"><Icon icon="lucide:cloud-check" />自动保存</span>
            </div>
            <el-input
              id="designer-draft-prompt"
              v-model="draftPrompt"
              class="draft-goal-input"
              type="textarea"
              :rows="8"
              maxlength="12000"
              resize="vertical"
              placeholder="描述期望结果、功能范围和验收标准……"
              aria-label="草案设计目标"
              @keydown.meta.enter.prevent="startDraft"
              @keydown.ctrl.enter.prevent="startDraft"
            />
          </div>

          <div class="brief-template-row" aria-label="需求模板">
            <span>快速起稿</span>
            <button v-for="template in briefTemplates" :key="template.label" type="button" @click="applyBriefTemplate(template.prompt)">
              <Icon :icon="template.icon" />{{ template.label }}
            </button>
          </div>

          <footer class="draft-create-actions">
            <span class="composer-boundary"><Icon icon="lucide:shield-check" />只读分析项目</span>
            <div class="composer-submit">
              <span class="composer-shortcut">⌘ / Ctrl + Enter</span>
              <el-button class="create-draft-button" type="primary" size="large" :loading="busy" :disabled="!draftPrompt.trim() || (!store.usingDemo && !selectedProjectId)" @click="startDraft">
                {{ store.usingDemo ? '开始演示' : '开始设计' }}<Icon icon="lucide:arrow-up-right" />
              </el-button>
            </div>
          </footer>
        </article>

        <aside class="card project-context-card" aria-label="当前项目上下文">
          <div class="context-card-heading">
            <span><Icon icon="lucide:scan-search" /></span>
            <div><small>DESIGN CONTEXT</small><h3>当前上下文</h3></div>
          </div>
          <template v-if="selectedProject">
            <div class="context-project-name">
              <strong>{{ selectedProject.name }}</strong>
              <span :class="['project-readiness', `is-${selectedProject.status.toLowerCase()}`]">{{ selectedProject.status === 'READY' ? '已就绪' : selectedProject.status === 'NEEDS_GIT' ? '无 Git HEAD' : '需处理' }}</span>
            </div>
            <dl class="context-details">
              <div><dt>路径</dt><dd class="mono">{{ selectedProject.rootPath }}</dd></div>
              <div><dt>分支</dt><dd class="mono">{{ selectedProject.branch || '未指定' }}</dd></div>
              <div><dt>历史任务</dt><dd>{{ selectedProject.taskCount }} 个</dd></div>
            </dl>
            <div class="context-capabilities">
              <span><Icon icon="lucide:check" />读取项目文件</span>
              <span><Icon icon="lucide:check" />生成 LoopSpec</span>
              <span class="is-muted"><Icon icon="lucide:minus" />不修改工作区</span>
            </div>
          </template>
          <div v-else class="context-empty">
            <Icon icon="lucide:mouse-pointer-2" />
            <strong>先选择项目</strong>
            <p>Designer 将使用该项目的文件和约定生成方案。</p>
          </div>
        </aside>
      </div>
    </section>
    <section v-else class="designer-layout">
      <article class="card designer-chat">
        <div class="card-pad card-header"><div><p class="eyebrow">READ-ONLY DESIGNER</p><h2 class="card-title">{{ designerSession?.projectName ?? activeProjectName }}</h2></div><div class="designer-state-actions"><el-button v-if="designerReconnecting || designerStreamState === 'reconnecting'" plain size="small" @click="reconnectDesigner"><Icon icon="lucide:refresh-cw" />立即重连</el-button><StatusBadge :status="designerBadgeStatus" :label="designerReconnecting || designerStreamState === 'reconnecting' ? 'RECONNECTING' : designerSession?.state ?? '等待 session'" /></div></div>
        <div class="designer-connection-strip" role="status" aria-live="polite">
          <span><i :class="['connection-dot', designerStreamState]" />{{ designerTransportLabel }}</span>
          <span><i :class="['connection-dot', { connected: designerRuntimeConnected, error: designerLiveError }]" />{{ designerRuntimeLabel }}</span>
          <span class="mono">远端 {{ designerRemoteState || 'WAITING' }}</span>
          <time :datetime="designerObservedAt">{{ formatObservedAt(designerObservedAt) }}</time>
        </div>
        <section v-if="designerSessionError" class="designer-session-alert" role="status" aria-live="polite"><Icon icon="lucide:refresh-cw" /><div><strong>Designer Session 已结束</strong><p>{{ designerSessionError.content }}</p><span class="tiny muted">只读设计会话受影响；Task 状态未改变。可重新发送，也可清理当前工作区后重新开始。</span><el-button class="restart-designer-inline" plain size="small" @click="restartDesigner"><Icon icon="lucide:rotate-ccw" />清理并重新开始</el-button></div></section>
        <section v-else-if="designerLiveError" class="designer-session-alert live-error" role="alert" aria-live="assertive"><Icon icon="lucide:triangle-alert" /><div><strong>OpenCode 实时错误</strong><p>{{ designerLiveError }}</p><span class="tiny muted">错误已从实时通道收到，正在同步持久化会话状态。</span></div></section>
        <div class="designer-conversation">
          <div class="chat-history">
          <article v-for="message in visibleMessages" :key="message.id" :class="['chat-message', `chat-${message.role.toLowerCase()}`]">
            <header class="chat-message-header">
              <span class="chat-author">
                <span class="chat-avatar"><Icon :icon="message.role === 'ASSISTANT' ? 'lucide:sparkles' : message.role === 'USER' ? 'lucide:user-round' : 'lucide:info'" /></span>
                <span><strong class="chat-role">{{ message.role === 'USER' ? '你' : message.role === 'ASSISTANT' ? 'Designer' : '系统' }}</strong><small v-if="message.role === 'ASSISTANT'">Markdown 设计文档</small></span>
              </span>
              <span class="chat-message-time">{{ message.deliveryState ? `${message.deliveryState} · ` : '' }}{{ message.createdAt }}</span>
            </header>
            <MarkdownDocument v-if="message.role === 'ASSISTANT'" :content="message.content" collapsible />
            <p v-else class="plain-message-content">{{ message.content }}</p>
          </article>
          <article v-if="designerLiveResponse" class="chat-message chat-assistant chat-live" aria-label="Designer 正在流式回复" aria-live="polite">
            <header class="chat-message-header">
              <span class="chat-author"><span class="chat-avatar"><Icon icon="lucide:sparkles" /></span><span><strong class="chat-role">Designer</strong><small>LIVE · Markdown 设计文档</small></span></span>
              <span class="chat-message-time">{{ formatObservedAt(designerObservedAt) }}</span>
            </header>
            <MarkdownDocument :content="designerLiveResponse" collapsible />
            <span v-if="designerSession?.state === 'RUNNING'" class="stream-caret" aria-hidden="true" />
          </article>
          <PendingQuestionCard
            v-for="pending in designerSession?.pendingQuestions ?? []"
            :key="pending.id"
            :pending="pending"
            :submitting="submittingDesignerQuestion === pending.id"
            @submit="(answers: string[][]) => answerDesignerQuestion(pending, answers)"
            @reject="rejectDesignerQuestion(pending)"
          />
          <article v-if="designerIsThinking" class="thinking-message" role="status" aria-live="polite" aria-label="Agent 正在思考，等待 AI 回复">
            <span class="thinking-orbit" aria-hidden="true"><span /></span>
            <div class="thinking-copy">
              <strong>Agent 正在思考<span class="thinking-dots" aria-hidden="true"><i /><i /><i /></span></strong>
              <p>{{ designerReconnecting || designerStreamState === 'reconnecting' ? '连接暂时中断，正在恢复并继续等待真实回复。' : designerLiveDetail || 'OpenCode 已收到请求，等待首段模型回复。' }}</p>
            </div>
          </article>
          </div>
          <div class="chat-compose">
            <div class="compose-heading"><label class="field-label" for="designer-message">继续补充设计要求</label><span class="tiny muted">自动暂存</span></div>
            <el-input
              id="designer-message"
              v-model="userMessage"
              class="designer-message-input"
              type="textarea"
              :rows="10"
              maxlength="12000"
              resize="vertical"
              :disabled="designerSession?.state === 'RUNNING'"
              :placeholder="designerSession?.state === 'RUNNING' ? 'Designer 正在处理上一条消息…' : '继续描述目标、约束、边界条件或验收标准…'"
              aria-label="发送给只读 OpenCode Designer 的消息"
              @keydown.meta.enter.prevent="sendMessage"
              @keydown.ctrl.enter.prevent="sendMessage"
            />
            <div class="compose-actions"><span class="tiny muted">{{ designerSession?.state === 'RUNNING' ? '正在接收实时回复；断流后自动轮询恢复' : '⌘ / Ctrl + Enter 发送；发送失败会保留原文' }}</span><el-button type="primary" :loading="busy" :disabled="designerSession?.state === 'RUNNING' || !userMessage.trim()" @click="sendMessage"><Icon icon="lucide:send" />发送</el-button></div>
          </div>
        </div>
      </article>
      <article class="card spec-panel">
        <div class="card-pad card-header"><div><p class="eyebrow">REVIEW GATE</p><h2 class="card-title">LoopSpec v{{ draft.spec.schemaVersion.replace('v', '') }}</h2><p class="card-description">后台仍使用标准 JSON；这里用中文字段逐项编辑，保存时重新通过 Schema 与 Java 业务校验。</p></div><el-button plain size="small" :loading="busy" @click="saveDraft"><Icon icon="lucide:save" />保存</el-button></div>
        <div class="spec-meta"><span><Icon icon="lucide:folder-git-2" />{{ draft.spec.projectId }}</span><span><Icon icon="lucide:flag" />{{ draft.spec.stages.length }} 个阶段</span><span><Icon icon="lucide:timer" />{{ draft.spec.limits.maxDuration }}</span></div>
        <LoopSpecEditor v-model="editorValue" class="spec-editor" aria-label="LoopSpec 中文结构化编辑器">
          <template #after-stages><ExecutionAcceptancePanel :source="editorValue" /></template>
        </LoopSpecEditor>
        <LayeredErrorPanel v-if="fieldError" :error="fieldError" style="margin-top: 12px" />
        <div class="spec-footer"><span class="tiny muted"><Icon icon="lucide:lock-keyhole" /> Git 项目隔离执行；无 HEAD 项目直接执行</span><span class="mono tiny">{{ draft.updatedAt }}</span></div>
      </article>
    </section>
  </main>
</template>

<style scoped>
.designer-start-page { width: min(1080px, 100%); margin: 10px auto 0; }
.designer-start-heading { display: flex; align-items: center; gap: 15px; margin: 0 0 18px 2px; }
.designer-start-mark { display: grid; flex: 0 0 auto; place-items: center; width: 46px; height: 46px; border: 1px solid rgb(34 211 238 / 24%); border-radius: 14px; color: var(--color-accent-cyan); background: linear-gradient(145deg, rgb(34 211 238 / 13%), rgb(139 92 246 / 12%)); box-shadow: 0 12px 34px rgb(0 0 0 / 22%); }
.designer-start-mark svg { width: 22px; height: 22px; }
.designer-start-heading h2 { margin: 0; color: var(--color-text-primary); font-size: 26px; font-weight: 720; letter-spacing: -.035em; }
.designer-start-heading > div > p:last-child { margin: 5px 0 0; color: var(--color-text-secondary); font-size: 12px; }
.designer-start-layout { display: grid; align-items: start; grid-template-columns: minmax(0, 1fr) 276px; gap: 16px; }
.brief-composer { overflow: hidden; border-color: rgb(57 78 113 / 78%); background: linear-gradient(155deg, rgb(17 27 46 / 98%), rgb(10 16 29 / 98%)); box-shadow: 0 24px 70px rgb(0 0 0 / 25%); }
.composer-project-row { display: flex; align-items: center; justify-content: space-between; gap: 18px; min-height: 66px; padding: 11px 18px; border-bottom: 1px solid var(--color-border-default); background: rgb(7 11 20 / 32%); }
.composer-project-row :deep(.el-select) { width: min(280px, 48%); }
.composer-project-label { display: flex; align-items: center; gap: 10px; min-width: 0; }
.composer-project-label > span:last-child { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.composer-project-label small { color: var(--color-text-muted); font: 9px/1.2 var(--font-code); letter-spacing: .08em; text-transform: uppercase; }
.composer-project-label strong { overflow: hidden; color: var(--color-text-primary); font-size: 12px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.composer-project-icon { display: grid; flex: 0 0 auto; place-items: center; width: 34px; height: 34px; border: 1px solid rgb(34 211 238 / 18%); border-radius: 9px; color: var(--color-accent-cyan); background: rgb(34 211 238 / 6%); }
.brief-editor { padding: 16px 20px 12px; }
.draft-goal-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.draft-goal-heading label { color: var(--color-text-primary); font-size: 13px; font-weight: 650; }
.field-label { display: block; color: var(--color-text-primary); font-family: var(--font-code); font-size: 10px; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }
.project-path { float: right; max-width: 250px; overflow: hidden; color: var(--color-text-muted); font-family: var(--font-code); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.compose-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 9px; }
.draft-goal-input, .designer-message-input { display: block; position: relative; z-index: 0; width: 100%; }
.draft-goal-input :deep(.el-textarea__inner) { min-height: 174px !important; padding: 16px 17px; border-color: rgb(45 63 94 / 82%); border-radius: 10px; background: rgb(5 10 20 / 72%); font-size: 13px; line-height: 1.7; box-shadow: inset 0 1px 0 rgb(255 255 255 / 2%); }
.draft-goal-input :deep(.el-textarea__inner:focus) { border-color: rgb(59 130 246 / 76%); box-shadow: 0 0 0 3px rgb(59 130 246 / 10%); }
.designer-message-input :deep(.el-textarea__inner) { min-height: 224px !important; padding: 14px 15px; line-height: 1.65; }
.brief-template-row { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; padding: 0 20px 14px; }
.brief-template-row > span { margin-right: 2px; color: var(--color-text-muted); font-size: 10px; }
.brief-template-row button { display: inline-flex; align-items: center; gap: 6px; min-height: 30px; padding: 0 10px; border: 1px solid rgb(47 65 96 / 82%); border-radius: 999px; color: var(--color-text-secondary); background: rgb(14 23 40 / 72%); font-size: 10px; cursor: pointer; transition: border-color .14s ease, color .14s ease, background .14s ease; }
.brief-template-row button:hover { border-color: rgb(34 211 238 / 32%); color: var(--color-text-primary); background: rgb(34 211 238 / 7%); }
.brief-template-row button svg { color: var(--color-accent-cyan); }
.draft-create-actions { position: relative; z-index: 2; display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 66px; padding: 10px 18px; border-top: 1px solid var(--color-border-default); background: rgb(7 11 20 / 32%); }
.draft-save-state { display: inline-flex; align-items: center; gap: 6px; color: var(--color-text-muted); font-size: 10px; }
.draft-save-state svg { color: var(--color-success); }
.composer-boundary { display: inline-flex; align-items: center; gap: 7px; color: var(--color-text-secondary); font-size: 10px; }
.composer-boundary svg { color: var(--color-success); }
.composer-submit { display: flex; align-items: center; gap: 11px; }
.composer-shortcut { color: var(--color-text-muted); font: 9px/1 var(--font-code); }
.create-draft-button { min-width: 134px; }
.project-context-card { min-height: 100%; padding: 18px; background: linear-gradient(160deg, rgb(15 24 42 / 96%), rgb(9 15 27 / 96%)); box-shadow: none; }
.context-card-heading { display: flex; align-items: center; gap: 10px; padding-bottom: 17px; border-bottom: 1px solid var(--color-border-default); }
.context-card-heading > span { display: grid; place-items: center; width: 34px; height: 34px; border: 1px solid rgb(139 92 246 / 20%); border-radius: 9px; color: #b9a4fb; background: rgb(139 92 246 / 8%); }
.context-card-heading small { color: var(--color-text-muted); font: 8px/1.2 var(--font-code); letter-spacing: .1em; }
.context-card-heading h3 { margin: 3px 0 0; color: var(--color-text-primary); font-size: 12px; }
.context-project-name { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 17px 0 14px; }
.context-project-name strong { overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.project-readiness { display: inline-flex; flex: 0 0 auto; align-items: center; gap: 5px; padding: 4px 7px; border: 1px solid currentcolor; border-radius: 999px; color: var(--color-text-secondary); background: rgb(154 168 189 / 6%); font-size: 8px; }
.project-readiness::before { width: 5px; height: 5px; border-radius: 50%; background: currentcolor; content: ''; }
.project-readiness.is-ready { color: var(--color-success); background: rgb(34 197 94 / 7%); }
.project-readiness.is-needs_git { color: var(--color-session-warning); background: rgb(245 158 11 / 7%); }
.project-readiness.is-invalid { color: var(--color-task-danger); background: rgb(239 68 68 / 7%); }
.context-details { display: grid; gap: 12px; margin: 0; }
.context-details div { display: grid; gap: 4px; }
.context-details dt { color: var(--color-text-muted); font-size: 9px; }
.context-details dd { margin: 0; overflow: hidden; color: var(--color-text-secondary); font-size: 10px; line-height: 1.45; overflow-wrap: anywhere; }
.context-capabilities { display: grid; gap: 9px; margin-top: 18px; padding-top: 15px; border-top: 1px solid var(--color-border-default); }
.context-capabilities span { display: flex; align-items: center; gap: 7px; color: var(--color-text-secondary); font-size: 10px; }
.context-capabilities svg { color: var(--color-success); }
.context-capabilities .is-muted { color: var(--color-text-muted); }
.context-capabilities .is-muted svg { color: var(--color-text-muted); }
.context-empty { display: grid; place-items: center; padding: 52px 8px 30px; color: var(--color-text-muted); text-align: center; }
.context-empty > svg { width: 22px; height: 22px; margin-bottom: 12px; }
.context-empty strong { color: var(--color-text-secondary); font-size: 11px; }
.context-empty p { margin: 7px 0 0; font-size: 10px; line-height: 1.6; }
.designer-layout { display: grid; align-items: start; grid-template-columns: minmax(460px, 1.12fr) minmax(500px, .88fr); gap: 18px; }
.designer-chat, .spec-panel { min-width: 0; }
.designer-chat { display: flex; align-self: start; flex-direction: column; min-height: 0; }
.spec-panel { min-height: 820px; }
.designer-connection-strip { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 14px; margin: -4px 20px 10px; padding: 9px 11px; border: 1px solid rgb(71 85 105 / 42%); border-radius: 9px; color: var(--color-text-muted); background: rgb(2 6 23 / 30%); font: 9px/1.3 var(--font-code); }
.designer-connection-strip span { display: inline-flex; align-items: center; gap: 6px; }
.designer-connection-strip time { margin-left: auto; color: var(--color-text-muted); font-variant-numeric: tabular-nums; }
.connection-dot { display: inline-block; flex: 0 0 auto; width: 6px; height: 6px; border-radius: 50%; background: var(--color-text-muted); }
.connection-dot.connecting { animation: live-pulse 1.2s ease-in-out infinite; }
.connection-dot.connected { background: var(--color-success); box-shadow: 0 0 9px rgb(34 197 94 / 60%); }
.connection-dot.reconnecting, .connection-dot.error { background: var(--color-session-warning); box-shadow: 0 0 9px rgb(245 158 11 / 45%); }
.designer-conversation { display: flex; flex-direction: column; min-height: 0; }
.chat-history { min-height: 0; padding: 0 20px 22px; }
.chat-message { margin: 14px 0; padding: 13px 14px; border: 1px solid var(--color-border-default); border-radius: 12px; background: rgb(7 11 20 / 45%); }
.chat-message-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 10px; }
.chat-author { display: flex; align-items: center; gap: 9px; min-width: 0; }
.chat-author > span:last-child { display: flex; flex-direction: column; gap: 2px; }
.chat-avatar { display: grid; flex: 0 0 auto; place-items: center; width: 28px; height: 28px; border: 1px solid rgb(139 92 246 / 28%); border-radius: 8px; color: #c4b5fd; background: rgb(139 92 246 / 10%); }
.chat-avatar svg { width: 14px; height: 14px; }
.chat-role { color: var(--color-accent-ai); font-family: var(--font-code); font-size: 10px; font-weight: 750; letter-spacing: .06em; text-transform: uppercase; }
.chat-author small, .chat-message-time { color: var(--color-text-muted); font-family: var(--font-code); font-size: 9px; }
.chat-message-time { flex: 0 0 auto; padding-top: 4px; }
.chat-user { margin-left: clamp(24px, 7%, 54px); border-color: rgb(59 130 246 / 26%); background: rgb(59 130 246 / 7%); }
.chat-user .chat-avatar { border-color: rgb(34 211 238 / 27%); color: var(--color-accent-cyan); background: rgb(34 211 238 / 8%); }
.chat-user .chat-role { color: var(--color-accent-cyan); }
.chat-assistant { padding: clamp(16px, 2.2vw, 24px); border-color: rgb(139 92 246 / 22%); background: radial-gradient(circle at 8% 0, rgb(139 92 246 / 8%), transparent 32%), rgb(7 11 20 / 68%); box-shadow: 0 14px 38px rgb(0 0 0 / 13%); }
.chat-assistant .chat-message-header { margin-bottom: 18px; padding-bottom: 12px; border-bottom: 1px solid rgb(139 92 246 / 14%); }
.chat-live { position: relative; border-color: rgb(34 211 238 / 30%); box-shadow: 0 14px 38px rgb(0 0 0 / 13%), inset 2px 0 rgb(34 211 238 / 55%); }
.stream-caret { display: inline-block; width: 7px; height: 14px; margin: 4px 0 -2px 3px; background: var(--color-accent-cyan); animation: stream-blink .85s steps(1) infinite; box-shadow: 0 0 8px rgb(34 211 238 / 55%); }
.chat-system { padding-block: 10px; border-style: dashed; background: rgb(7 11 20 / 25%); }
.chat-system .chat-avatar { border-color: var(--color-border-default); color: var(--color-text-muted); background: transparent; }
.chat-system .chat-role { color: var(--color-text-secondary); }
.plain-message-content { margin: 7px 0 0; color: var(--color-text-primary); font-size: 12px; line-height: 1.65; white-space: pre-wrap; }
.thinking-message { position: relative; display: flex; align-items: center; gap: 14px; margin: 14px 0; padding: 17px 18px; overflow: hidden; border: 1px solid rgb(139 92 246 / 28%); border-radius: 12px; background: linear-gradient(100deg, rgb(139 92 246 / 9%), rgb(34 211 238 / 5%), rgb(139 92 246 / 9%)); background-size: 220% 100%; box-shadow: 0 12px 36px rgb(0 0 0 / 12%); animation: thinking-sheen 3s ease-in-out infinite; }
.thinking-message::after { position: absolute; inset: auto 16px 0; height: 1px; background: linear-gradient(90deg, transparent, rgb(34 211 238 / 55%), transparent); content: ""; animation: thinking-scan 2.2s ease-in-out infinite; }
.thinking-orbit { position: relative; display: grid; flex: 0 0 auto; place-items: center; width: 38px; height: 38px; border: 2px solid rgb(139 92 246 / 18%); border-top-color: #a78bfa; border-right-color: var(--color-accent-cyan); border-radius: 50%; box-shadow: 0 0 18px rgb(139 92 246 / 18%); animation: thinking-spin 1s linear infinite; }
.thinking-orbit span { width: 8px; height: 8px; border-radius: 50%; background: linear-gradient(135deg, #a78bfa, var(--color-accent-cyan)); box-shadow: 0 0 12px rgb(34 211 238 / 55%); }
.thinking-copy { min-width: 0; }
.thinking-copy strong { display: flex; align-items: baseline; color: #f5f3ff; font-size: 13px; font-weight: 720; letter-spacing: -.01em; }
.thinking-copy p { margin: 5px 0 0; color: var(--color-text-secondary); font-size: 11px; line-height: 1.55; }
.thinking-dots { display: inline-flex; align-items: flex-end; gap: 3px; height: 12px; margin-left: 5px; }
.thinking-dots i { width: 4px; height: 4px; border-radius: 50%; background: var(--color-accent-cyan); animation: thinking-dot 1.15s ease-in-out infinite; }
.thinking-dots i:nth-child(2) { animation-delay: .16s; }
.thinking-dots i:nth-child(3) { animation-delay: .32s; }
@keyframes thinking-spin { to { transform: rotate(360deg); } }
@keyframes thinking-dot { 0%, 65%, 100% { opacity: .25; transform: translateY(0); } 35% { opacity: 1; transform: translateY(-4px); } }
@keyframes thinking-sheen { 0%, 100% { background-position: 0 50%; } 50% { background-position: 100% 50%; } }
@keyframes thinking-scan { 0%, 100% { opacity: .2; transform: scaleX(.25); } 50% { opacity: .85; transform: scaleX(1); } }
@keyframes live-pulse { 0%, 100% { opacity: .35; transform: scale(.85); } 50% { opacity: 1; transform: scale(1.15); } }
@keyframes stream-blink { 0%, 48% { opacity: 1; } 49%, 100% { opacity: 0; } }
.chat-compose { padding: 18px; border-top: 1px solid var(--color-border-default); background: rgb(7 12 23 / 72%); }
.compose-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 12px; }
.spec-meta, .spec-footer { display: flex; align-items: center; gap: 12px; padding: 0 20px 14px; color: var(--color-text-secondary); font-family: var(--font-code); font-size: 10px; }
.spec-meta span { display: inline-flex; align-items: center; gap: 5px; }
.spec-editor { display: block; }
.spec-editor :deep(.acceptance-panel) { margin: 0; }
.spec-footer { justify-content: space-between; padding-top: 14px; border-top: 1px solid var(--color-border-default); }
.spec-footer span { display: inline-flex; align-items: center; gap: 5px; }
.designer-session-alert { display: flex; gap: 10px; margin: 0 20px 8px; padding: 12px; border: 1px solid rgb(245 158 11 / 35%); border-radius: 10px; background: rgb(245 158 11 / 9%); color: var(--color-status-session); }
.designer-session-alert svg { flex: 0 0 auto; margin-top: 2px; }
.designer-session-alert p { margin: 5px 0; color: var(--color-text-primary); font-size: 11px; line-height: 1.5; }
.restart-designer-inline { display: flex; margin-top: 10px; }
.designer-state-actions { display: flex; align-items: center; gap: 8px; }

@media (max-width: 1180px) {
  .designer-start-layout { grid-template-columns: 1fr; }
  .project-context-card { display: none; }
  .designer-layout { grid-template-columns: 1fr; }
  .spec-panel { min-height: 720px; }
}

@media (max-width: 680px) {
  .designer-start-heading { align-items: flex-start; }
  .composer-project-row { align-items: stretch; flex-direction: column; }
  .composer-project-row :deep(.el-select) { width: 100%; }
  .brief-editor { padding-inline: 16px; }
  .brief-template-row { padding-inline: 16px; }
  .draft-goal-input :deep(.el-textarea__inner) { min-height: 330px !important; }
  .draft-create-actions, .compose-actions { align-items: stretch; flex-direction: column; }
  .composer-submit { justify-content: space-between; width: 100%; }
  .create-draft-button, .compose-actions :deep(.el-button) { width: 100%; }
  .designer-message-input :deep(.el-textarea__inner) { min-height: 260px !important; }
  .designer-connection-strip { align-items: flex-start; flex-direction: column; }
  .designer-connection-strip time { margin-left: 0; }
}
</style>
