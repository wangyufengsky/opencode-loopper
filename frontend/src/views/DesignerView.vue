<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import LayeredErrorPanel from '@/components/LayeredErrorPanel.vue'
import LoopSpecEditor from '@/components/LoopSpecEditor.vue'
import { api } from '@/api/client'
import { demoDraft, demoMessages } from '@/mock/demoData'
import { useTaskStore } from '@/stores/taskStore'
import type { DesignerMessage, DesignerSession, ErrorEvent, LoopDraft } from '@/types/domain'

const store = useTaskStore()
const draft = ref<LoopDraft>()
const designerSession = ref<DesignerSession>()
const messages = ref<DesignerMessage[]>([])
const editorValue = ref('')
const userMessage = ref('')
const fieldError = ref<ErrorEvent>()
const busy = ref(false)
const designerReconnecting = ref(false)
const selectedProjectId = ref('')
const designerWorkspaceKey = 'opencode-loopper.designer-workspace'
let designerPollTimer: ReturnType<typeof setTimeout> | undefined
let designerPollInFlight = false
let designerPollFailures = 0
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

function loadDemo() {
  draft.value = structuredClone(demoDraft)
  messages.value = structuredClone(demoMessages)
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
  designerPollInFlight = true
  try {
    const refreshed = await api.getDesignerSession(designerSession.value.id)
    designerSession.value = refreshed
    mergeMessages(refreshed.messages)
    designerPollFailures = 0
    designerReconnecting.value = false
    if (refreshed.state !== 'RUNNING') stopDesignerPolling()
  } catch (error) {
    designerPollFailures += 1
    designerReconnecting.value = true
    if (designerPollFailures === 1) {
      ElMessage.warning(error instanceof Error ? `${error.message}；正在自动重连` : 'Designer 会话刷新失败，正在自动重连')
    }
  } finally {
    designerPollInFlight = false
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

function blankSpec(projectId: string): LoopDraft['spec'] {
  return { schemaVersion: 'v1', projectId, goal: '请描述要完成的工程目标。', context: 'Execution 只允许在该 Task 的隔离 worktree 中修改。', stages: [{ objective: '分析目标并实现最小可验证改动', allowedPaths: ['src/**'], forbiddenPaths: ['data/**'], deliverables: ['可验证实现'], verifiers: [{ type: 'GIT_DIFF', requireChanges: true, allowedPaths: ['src/**'], forbiddenPaths: ['data/**'], forbidDeletes: true }] }], limits: { maxStageAttempts: 3, maxTaskAttempts: 12, maxDuration: 'PT2H', attemptTimeout: 'PT30M' } }
}

async function startDraft() {
  if (store.usingDemo) { loadDemo(); return }
  const project = selectedProject.value
  if (!project) { ElMessage.warning('请先在“项目”页面登记一个可用项目根目录。'); return }
  busy.value = true
  try {
    designerSession.value = await api.createDesignerSession(project.id)
    messages.value = designerSession.value.messages
    draft.value = await api.createDraft(blankSpec(project.id))
    editorValue.value = JSON.stringify(draft.value.spec, null, 2)
    sessionStorage.setItem(designerWorkspaceKey, JSON.stringify({ sessionId: designerSession.value.id, draftId: draft.value.id }))
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '无法创建 LoopSpec 草案') } finally { busy.value = false }
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
    draft.value = restoredDraft
    selectedProjectId.value = restoredDraft.spec.projectId
    editorValue.value = JSON.stringify(restoredDraft.spec, null, 2)
  } catch {
    sessionStorage.removeItem(designerWorkspaceKey)
  }
}

onMounted(async () => {
  window.addEventListener('beforeunload', warnBeforeUnload)
  if (store.usingDemo) { loadDemo(); return }
  await restoreDesignerWorkspace()
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

async function saveDraft() {
  const spec = parsedSpec()
  if (!spec || !draft.value) return
  busy.value = true
  try {
    draft.value = store.usingDemo ? { ...draft.value, spec, updatedAt: new Date().toISOString() } : await api.updateDraft(draft.value.id, spec)
    editorValue.value = JSON.stringify(draft.value.spec, null, 2)
    fieldError.value = undefined
    ElMessage.success('LoopSpec 已保存并通过基础校验')
  } catch (error) { fieldError.value = { id: 'field-api', layer: 'FIELD', code: 'LOOPSPEC_SAVE_FAILED', message: error instanceof Error ? error.message : '保存失败', retryable: true, occurredAt: '刚刚' } } finally { busy.value = false }
}

async function confirm() {
  await saveDraft()
  if (fieldError.value || !draft.value) return
  busy.value = true
  try {
    if (store.usingDemo) { draft.value = { ...draft.value, status: 'CONFIRMED' }; ElMessage.success('演示：Task 已创建，等待 MCP 交接') }
    else { const result = await api.confirmDraft(draft.value.id); ElMessage.success(`Task ${result.taskId} 已进入队列`) }
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
    ElMessage.info(result.notice || '消息已交给只读 OpenCode Designer。')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法保存 Designer 消息')
  } finally {
    busy.value = false
  }
  userMessage.value = ''
}
</script>

<template>
  <PageHeader eyebrow="Designer / Plan" title="Designer 与 LoopSpec" subtitle="先规划、再确认；消息通过只读 OpenCode 会话处理，只有真实模型回复才会显示为 Designer 消息。">
    <template #actions><StatusBadge :status="draft?.status === 'CONFIRMED' ? 'SUCCEEDED' : 'PENDING'" :label="draft?.status ?? '等待草案'" /><el-button type="primary" :loading="busy" :disabled="!draft" @click="confirm"><Icon icon="lucide:circle-check-big" />确认并交接</el-button></template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <section v-if="!draft" class="card empty-state"><div><Icon icon="lucide:sparkles" width="30" /><strong>尚未生成 LoopSpec</strong><p>选择项目并发送设计目标。Designer 只读取项目上下文，无法编辑工作区或运行 Shell。</p><div v-if="!store.usingDemo" class="project-select-wrap"><el-select v-model="selectedProjectId" filterable placeholder="选择 Designer 项目" aria-label="选择 Designer 项目"><el-option v-for="project in store.projects" :key="project.id" :label="project.name" :value="project.id"><span>{{ project.name }}</span><span class="project-path">{{ project.rootPath }}</span></el-option></el-select></div><el-button type="primary" :loading="busy" :disabled="!store.usingDemo && !selectedProjectId" style="margin-top: 16px" @click="startDraft">{{ store.usingDemo ? '打开交互演示' : '创建草案' }}</el-button></div></section>
    <section v-else class="designer-layout">
      <article class="card designer-chat">
        <div class="card-pad card-header"><div><p class="eyebrow">READ-ONLY DESIGNER</p><h2 class="card-title">{{ designerSession?.projectName ?? activeProjectName }}</h2></div><div class="designer-state-actions"><el-button v-if="designerReconnecting" plain size="small" @click="reconnectDesigner"><Icon icon="lucide:refresh-cw" />立即重连</el-button><StatusBadge :status="designerBadgeStatus" :label="designerReconnecting ? 'RECONNECTING' : designerSession?.state ?? '等待 session'" /></div></div>
        <section v-if="designerSessionError" class="designer-session-alert" role="status" aria-live="polite"><Icon icon="lucide:refresh-cw" /><div><strong>Designer Session 已结束</strong><p>{{ designerSessionError.content }}</p><span class="tiny muted">只读设计会话受影响；Task 状态未改变，可重新发送以创建新 Session。</span></div></section>
        <div class="chat-history"><div v-for="message in messages" :key="message.id" :class="['chat-message', `chat-${message.role.toLowerCase()}`]"><span class="chat-role">{{ message.role === 'USER' ? '你' : message.role === 'ASSISTANT' ? 'Designer' : '系统' }}</span><p>{{ message.content }}</p><span class="tiny muted">{{ message.deliveryState ? `${message.deliveryState} · ` : '' }}{{ message.createdAt }}</span></div></div>
        <div class="chat-compose"><el-input v-model="userMessage" type="textarea" :rows="3" :disabled="designerSession?.state === 'RUNNING'" :placeholder="designerSession?.state === 'RUNNING' ? 'Designer 正在处理上一条消息…' : '描述目标、约束或验收标准…'" aria-label="发送给只读 OpenCode Designer 的消息" @keydown.meta.enter.prevent="sendMessage" /><div class="compose-actions"><span class="tiny muted">{{ designerSession?.state === 'RUNNING' ? '正在轮询真实回复' : '⌘ + Enter 发送' }}</span><el-button type="primary" size="small" :loading="busy" :disabled="designerSession?.state === 'RUNNING'" @click="sendMessage"><Icon icon="lucide:send" />发送</el-button></div></div>
      </article>
      <article class="card spec-panel">
        <div class="card-pad card-header"><div><p class="eyebrow">REVIEW GATE</p><h2 class="card-title">LoopSpec v{{ draft.spec.schemaVersion.replace('v', '') }}</h2><p class="card-description">确认前的修改会重新通过 Schema 与 Java 业务校验。</p></div><el-button plain size="small" :loading="busy" @click="saveDraft"><Icon icon="lucide:save" />保存</el-button></div>
        <div class="spec-meta"><span><Icon icon="lucide:folder-git-2" />{{ draft.spec.projectId }}</span><span><Icon icon="lucide:flag" />{{ draft.spec.stages.length }} stages</span><span><Icon icon="lucide:timer" />{{ draft.spec.limits.maxDuration }}</span></div>
        <LoopSpecEditor v-model="editorValue" class="spec-editor" aria-label="LoopSpec JSON 编辑器" />
        <LayeredErrorPanel v-if="fieldError" :error="fieldError" style="margin-top: 12px" />
        <div class="spec-footer"><span class="tiny muted"><Icon icon="lucide:lock-keyhole" /> 执行将创建隔离 worktree</span><span class="mono tiny">{{ draft.updatedAt }}</span></div>
      </article>
    </section>
  </main>
</template>

<style scoped>
.designer-layout { display: grid; grid-template-columns: minmax(330px, .78fr) minmax(550px, 1.22fr); gap: 18px; }.designer-chat, .spec-panel { min-height: 638px; }.designer-chat { display: flex; flex-direction: column; }.chat-history { flex: 1; padding: 0 20px 20px; overflow: auto; }.chat-message { margin: 14px 0; padding: 12px; border: 1px solid var(--color-border-default); border-radius: 10px; background: rgb(7 11 20 / 45%); }.chat-user { margin-left: 30px; border-color: rgb(59 130 246 / 30%); background: rgb(59 130 246 / 8%); }.chat-assistant { margin-right: 18px; }.chat-role { color: var(--color-accent-ai); font-family: var(--font-code); font-size: 10px; font-weight: 700; }.chat-user .chat-role { color: var(--color-accent-cyan); }.chat-message p { margin: 7px 0; color: var(--color-text-primary); font-size: 12px; line-height: 1.6; }.chat-compose { padding: 15px; border-top: 1px solid var(--color-border-default); }.compose-actions { display: flex; align-items: center; justify-content: space-between; margin-top: 9px; }.spec-meta, .spec-footer { display: flex; align-items: center; gap: 12px; padding: 0 20px 14px; color: var(--color-text-secondary); font-family: var(--font-code); font-size: 10px; }.spec-meta span { display: inline-flex; align-items: center; gap: 5px; }.spec-editor { display: block; padding: 0 20px; }.spec-footer { justify-content: space-between; padding-top: 14px; border-top: 1px solid var(--color-border-default); }.spec-footer span { display: inline-flex; align-items: center; gap: 5px; }@media (max-width: 1320px) { .designer-layout { grid-template-columns: minmax(300px, .7fr) minmax(500px, 1.3fr); } }
.designer-session-alert { display: flex; gap: 10px; margin: 0 20px 8px; padding: 12px; border: 1px solid rgb(245 158 11 / 35%); border-radius: 10px; background: rgb(245 158 11 / 9%); color: var(--color-status-session); }.designer-session-alert svg { flex: 0 0 auto; margin-top: 2px; }.designer-session-alert p { margin: 5px 0; color: var(--color-text-primary); font-size: 11px; line-height: 1.5; }
.designer-state-actions { display: flex; align-items: center; gap: 8px; }
.project-select-wrap { width: min(460px, 72vw); margin: 18px auto 0; text-align: left; }.project-select-wrap :deep(.el-select) { width: 100%; }.project-path { float: right; max-width: 250px; overflow: hidden; color: var(--color-text-muted); font-family: var(--font-code); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
</style>
