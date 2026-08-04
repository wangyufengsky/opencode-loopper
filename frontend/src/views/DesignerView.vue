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
import { api } from '@/api/client'
import { demoDraft, demoMessages } from '@/mock/demoData'
import { useTaskStore } from '@/stores/taskStore'
import type { DesignerMessage, DesignerSession, ErrorEvent, LoopDraft } from '@/types/domain'

const store = useTaskStore()
const router = useRouter()
const draft = ref<LoopDraft>()
const designerSession = ref<DesignerSession>()
const messages = ref<DesignerMessage[]>([])
const editorValue = ref('')
const fieldError = ref<ErrorEvent>()
const busy = ref(false)
const designerReconnecting = ref(false)
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
const selectedProject = computed(() => store.projects.find((project) => project.id === selectedProjectId.value))
const activeProjectName = computed(() => selectedProject.value?.name ?? '选择项目')
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
const designerIsThinking = computed(() => designerSession.value?.state === 'RUNNING')
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
    if (refreshed.state !== 'RUNNING') stopDesignerPolling()
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
}

watch(() => designerSession.value?.state, (state) => {
  if (state === 'RUNNING') startDesignerPolling()
  else stopDesignerPolling()
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

function blankSpec(projectId: string, goal: string): LoopDraft['spec'] {
  return { schemaVersion: 'v1', projectId, goal, context: 'Execution 只允许在该 Task 的隔离 worktree 中修改。', stages: [{ objective: '分析目标并实现最小可验证改动', allowedPaths: ['src/**'], forbiddenPaths: ['data/**'], deliverables: ['可验证实现'], verifiers: [{ type: 'GIT_DIFF', requireChanges: true, allowedPaths: ['src/**'], forbiddenPaths: ['data/**'], forbidDeletes: true }] }], limits: { maxStageAttempts: 3, maxTaskAttempts: 12, maxDuration: 'PT2H', attemptTimeout: 'PT30M' } }
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
    const createdDraft = await api.createDraft(blankSpec(project.id, goal))
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
  designerPollGeneration += 1
  designerPollInFlight = false
  designerPollFailures = 0
  designerReconnecting.value = false
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
  <PageHeader eyebrow="Designer / Plan" title="Designer 与 LoopSpec" subtitle="先规划、再确认；消息通过只读 OpenCode 会话处理，只有真实模型回复才会显示为 Designer 消息。">
    <template #actions><StatusBadge :status="draft?.status === 'CONFIRMED' ? 'SUCCEEDED' : 'PENDING'" :label="draft?.status ?? '等待草案'" /><el-button v-if="draft" class="restart-designer-button" plain :disabled="busy" @click="restartDesigner"><Icon icon="lucide:rotate-ccw" />重新开始</el-button><el-button type="primary" :loading="busy" :disabled="!draft" @click="confirm"><Icon icon="lucide:circle-check-big" />确认并交接</el-button></template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <section v-if="!draft" class="card draft-start-card">
      <div class="draft-start-grid">
        <div class="draft-start-copy">
          <span class="draft-start-icon"><Icon icon="lucide:sparkles" width="26" /></span>
          <p class="eyebrow">NEW DESIGN BRIEF</p>
          <h2>把需求完整交给 Designer</h2>
          <p>输入目标、约束和验收标准。内容会在当前浏览器中自动暂存，创建失败或刷新页面也不会丢失。</p>
          <div class="draft-safety-note"><Icon icon="lucide:shield-check" /><span>Designer 只读取项目上下文，无法编辑工作区或运行 Shell。</span></div>
        </div>
        <div class="draft-start-form">
          <label v-if="!store.usingDemo" class="field-label" for="designer-project">目标项目</label>
          <div v-if="!store.usingDemo" class="project-select-wrap">
            <el-select id="designer-project" v-model="selectedProjectId" filterable placeholder="选择 Designer 项目" aria-label="选择 Designer 项目">
              <el-option v-for="project in store.projects" :key="project.id" :label="project.name" :value="project.id"><span>{{ project.name }}</span><span class="project-path">{{ project.rootPath }}</span></el-option>
            </el-select>
          </div>
          <div class="draft-goal-heading">
            <label class="field-label" for="designer-draft-prompt">设计目标</label>
            <span class="tiny muted">最多 12,000 字符 · 自动暂存</span>
          </div>
          <el-input
            id="designer-draft-prompt"
            v-model="draftPrompt"
            class="draft-goal-input"
            type="textarea"
            :rows="12"
            maxlength="12000"
            resize="vertical"
            placeholder="例如：实现任务控制台的错误恢复。Task 错误必须退出任务；Session 错误保留上下文并继续下一轮。请同时补充允许修改的目录、禁止项和验收命令……"
            aria-label="草案设计目标"
            @keydown.meta.enter.prevent="startDraft"
            @keydown.ctrl.enter.prevent="startDraft"
          />
          <div class="draft-create-actions">
            <span class="draft-save-state"><Icon icon="lucide:cloud-check" />输入内容已在本地保留</span>
            <el-button class="create-draft-button" type="primary" size="large" :loading="busy" :disabled="!draftPrompt.trim() || (!store.usingDemo && !selectedProjectId)" @click="startDraft">
              <Icon icon="lucide:wand-sparkles" />{{ store.usingDemo ? '创建演示草案' : '创建草案' }}
            </el-button>
          </div>
        </div>
      </div>
    </section>
    <section v-else class="designer-layout">
      <article class="card designer-chat">
        <div class="card-pad card-header"><div><p class="eyebrow">READ-ONLY DESIGNER</p><h2 class="card-title">{{ designerSession?.projectName ?? activeProjectName }}</h2></div><div class="designer-state-actions"><el-button v-if="designerReconnecting" plain size="small" @click="reconnectDesigner"><Icon icon="lucide:refresh-cw" />立即重连</el-button><StatusBadge :status="designerBadgeStatus" :label="designerReconnecting ? 'RECONNECTING' : designerSession?.state ?? '等待 session'" /></div></div>
        <section v-if="designerSessionError" class="designer-session-alert" role="status" aria-live="polite"><Icon icon="lucide:refresh-cw" /><div><strong>Designer Session 已结束</strong><p>{{ designerSessionError.content }}</p><span class="tiny muted">只读设计会话受影响；Task 状态未改变。可重新发送，也可清理当前工作区后重新开始。</span><el-button class="restart-designer-inline" plain size="small" @click="restartDesigner"><Icon icon="lucide:rotate-ccw" />清理并重新开始</el-button></div></section>
        <div class="chat-history">
          <article v-for="message in visibleMessages" :key="message.id" :class="['chat-message', `chat-${message.role.toLowerCase()}`]">
            <header class="chat-message-header">
              <span class="chat-author">
                <span class="chat-avatar"><Icon :icon="message.role === 'ASSISTANT' ? 'lucide:sparkles' : message.role === 'USER' ? 'lucide:user-round' : 'lucide:info'" /></span>
                <span><strong class="chat-role">{{ message.role === 'USER' ? '你' : message.role === 'ASSISTANT' ? 'Designer' : '系统' }}</strong><small v-if="message.role === 'ASSISTANT'">Markdown 设计文档</small></span>
              </span>
              <span class="chat-message-time">{{ message.deliveryState ? `${message.deliveryState} · ` : '' }}{{ message.createdAt }}</span>
            </header>
            <MarkdownDocument v-if="message.role === 'ASSISTANT'" :content="message.content" />
            <p v-else class="plain-message-content">{{ message.content }}</p>
          </article>
          <article v-if="designerIsThinking" class="thinking-message" role="status" aria-live="polite" aria-label="Agent 正在思考，等待 AI 回复">
            <span class="thinking-orbit" aria-hidden="true"><span /></span>
            <div class="thinking-copy">
              <strong>Agent 正在思考<span class="thinking-dots" aria-hidden="true"><i /><i /><i /></span></strong>
              <p>{{ designerReconnecting ? '连接暂时中断，正在恢复并继续等待真实回复。' : '正在读取项目上下文并组织设计文档，请稍候。' }}</p>
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
          <div class="compose-actions"><span class="tiny muted">{{ designerSession?.state === 'RUNNING' ? '正在轮询真实回复' : '⌘ / Ctrl + Enter 发送；发送失败会保留原文' }}</span><el-button type="primary" :loading="busy" :disabled="designerSession?.state === 'RUNNING' || !userMessage.trim()" @click="sendMessage"><Icon icon="lucide:send" />发送</el-button></div>
        </div>
      </article>
      <article class="card spec-panel">
        <div class="card-pad card-header"><div><p class="eyebrow">REVIEW GATE</p><h2 class="card-title">LoopSpec v{{ draft.spec.schemaVersion.replace('v', '') }}</h2><p class="card-description">后台仍使用标准 JSON；这里用中文字段逐项编辑，保存时重新通过 Schema 与 Java 业务校验。</p></div><el-button plain size="small" :loading="busy" @click="saveDraft"><Icon icon="lucide:save" />保存</el-button></div>
        <div class="spec-meta"><span><Icon icon="lucide:folder-git-2" />{{ draft.spec.projectId }}</span><span><Icon icon="lucide:flag" />{{ draft.spec.stages.length }} 个阶段</span><span><Icon icon="lucide:timer" />{{ draft.spec.limits.maxDuration }}</span></div>
        <ExecutionAcceptancePanel :source="editorValue" />
        <LoopSpecEditor v-model="editorValue" class="spec-editor" aria-label="LoopSpec 中文结构化编辑器" />
        <LayeredErrorPanel v-if="fieldError" :error="fieldError" style="margin-top: 12px" />
        <div class="spec-footer"><span class="tiny muted"><Icon icon="lucide:lock-keyhole" /> 执行将创建隔离 worktree</span><span class="mono tiny">{{ draft.updatedAt }}</span></div>
      </article>
    </section>
  </main>
</template>

<style scoped>
.draft-start-card { overflow: hidden; background: radial-gradient(circle at 82% 12%, rgb(59 130 246 / 14%), transparent 32%), linear-gradient(135deg, rgb(10 17 31 / 98%), rgb(6 11 21 / 98%)); }
.draft-start-grid { display: grid; grid-template-columns: minmax(260px, .7fr) minmax(520px, 1.3fr); gap: 42px; padding: clamp(28px, 4vw, 52px); }
.draft-start-copy { align-self: start; padding-top: 8px; }
.draft-start-icon { display: inline-grid; place-items: center; width: 52px; height: 52px; margin-bottom: 28px; border: 1px solid rgb(96 165 250 / 30%); border-radius: 15px; background: linear-gradient(145deg, rgb(59 130 246 / 23%), rgb(34 211 238 / 7%)); color: var(--color-accent-cyan); box-shadow: 0 16px 45px rgb(0 0 0 / 28%); }
.draft-start-copy h2 { max-width: 360px; margin: 8px 0 14px; color: var(--color-text-primary); font-size: clamp(25px, 2.5vw, 38px); line-height: 1.13; letter-spacing: -.03em; }
.draft-start-copy > p:not(.eyebrow) { max-width: 390px; margin: 0; color: var(--color-text-secondary); font-size: 13px; line-height: 1.75; }
.draft-safety-note { display: flex; gap: 10px; max-width: 390px; margin-top: 28px; padding: 13px 14px; border: 1px solid rgb(34 211 238 / 18%); border-radius: 11px; background: rgb(34 211 238 / 5%); color: var(--color-text-secondary); font-size: 11px; line-height: 1.55; }
.draft-safety-note svg { flex: 0 0 auto; margin-top: 2px; color: var(--color-accent-cyan); }
.draft-start-form { position: relative; z-index: 1; min-width: 0; padding: 22px; border: 1px solid rgb(148 163 184 / 13%); border-radius: 16px; background: rgb(8 14 26 / 78%); box-shadow: 0 24px 70px rgb(0 0 0 / 25%); }
.field-label { display: block; color: var(--color-text-primary); font-family: var(--font-code); font-size: 10px; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }
.project-select-wrap { width: 100%; margin: 9px 0 20px; text-align: left; }
.project-select-wrap :deep(.el-select) { width: 100%; }
.project-path { float: right; max-width: 250px; overflow: hidden; color: var(--color-text-muted); font-family: var(--font-code); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.draft-goal-heading, .compose-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 9px; }
.draft-goal-input, .designer-message-input { display: block; position: relative; z-index: 0; width: 100%; }
.draft-goal-input :deep(.el-textarea__inner) { min-height: 286px !important; padding: 16px 17px; line-height: 1.65; }
.designer-message-input :deep(.el-textarea__inner) { min-height: 224px !important; padding: 14px 15px; line-height: 1.65; }
.draft-create-actions { position: relative; z-index: 2; display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--color-border-default); }
.draft-save-state { display: inline-flex; align-items: center; gap: 7px; color: var(--color-text-muted); font-size: 10px; }
.draft-save-state svg { color: var(--color-success); }
.create-draft-button { min-width: 154px; }
.designer-layout { display: grid; grid-template-columns: minmax(460px, 1.12fr) minmax(500px, .88fr); gap: 18px; }
.designer-chat, .spec-panel { min-height: 820px; }
.designer-chat { display: flex; flex-direction: column; }
.chat-history { flex: 1; min-height: 300px; padding: 0 20px 22px; overflow: auto; }
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
.chat-compose { padding: 18px; border-top: 1px solid var(--color-border-default); background: rgb(7 12 23 / 72%); }
.compose-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 12px; }
.spec-meta, .spec-footer { display: flex; align-items: center; gap: 12px; padding: 0 20px 14px; color: var(--color-text-secondary); font-family: var(--font-code); font-size: 10px; }
.spec-meta span { display: inline-flex; align-items: center; gap: 5px; }
.spec-editor { display: block; }
.spec-footer { justify-content: space-between; padding-top: 14px; border-top: 1px solid var(--color-border-default); }
.spec-footer span { display: inline-flex; align-items: center; gap: 5px; }
.designer-session-alert { display: flex; gap: 10px; margin: 0 20px 8px; padding: 12px; border: 1px solid rgb(245 158 11 / 35%); border-radius: 10px; background: rgb(245 158 11 / 9%); color: var(--color-status-session); }
.designer-session-alert svg { flex: 0 0 auto; margin-top: 2px; }
.designer-session-alert p { margin: 5px 0; color: var(--color-text-primary); font-size: 11px; line-height: 1.5; }
.restart-designer-inline { display: flex; margin-top: 10px; }
.designer-state-actions { display: flex; align-items: center; gap: 8px; }

@media (max-width: 1180px) {
  .draft-start-grid { grid-template-columns: 1fr; gap: 28px; }
  .draft-start-copy h2, .draft-start-copy > p:not(.eyebrow), .draft-safety-note { max-width: 680px; }
  .designer-layout { grid-template-columns: 1fr; }
  .designer-chat, .spec-panel { min-height: 720px; }
}

@media (max-width: 680px) {
  .draft-start-grid { padding: 22px 16px; }
  .draft-start-form { padding: 16px; }
  .draft-goal-input :deep(.el-textarea__inner) { min-height: 330px !important; }
  .draft-create-actions, .compose-actions { align-items: stretch; flex-direction: column; }
  .create-draft-button, .compose-actions :deep(.el-button) { width: 100%; }
  .designer-message-input :deep(.el-textarea__inner) { min-height: 260px !important; }
}
</style>
