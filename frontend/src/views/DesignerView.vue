<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import LayeredErrorPanel from '@/components/LayeredErrorPanel.vue'
import LoopSpecEditor from '@/components/LoopSpecEditor.vue'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import ExecutionAcceptancePanel from '@/components/ExecutionAcceptancePanel.vue'
import PendingQuestionCard from '@/components/PendingQuestionCard.vue'
import DesignerDiscussionHistory from '@/components/DesignerDiscussionHistory.vue'
import DesignerValidatorHistory from '@/components/DesignerValidatorHistory.vue'
import { ApiError, api, subscribeDesignerEvents, type DesignerEventStream } from '@/api/client'
import { demoDraft, demoMessages } from '@/mock/demoData'
import { useTaskStore } from '@/stores/taskStore'
import type { AnalysisReport, AppSettings, DesignerMessage, DesignerSession, ErrorEvent, LoopDraft, LoopSpecAssessment, StructuredModelStep, TaskSessionPendingQuestion } from '@/types/domain'
import { formatDateTime } from '@/utils/dateTime'
import {
  artifactKindLabel,
  executionStrategyLabel,
  profileResolutionLabel,
  statusLabel,
  taskIntentLabel,
  testPolicyLabel,
  workflowTemplateLabel,
} from '@/utils/displayLabels'

const store = useTaskStore()
const router = useRouter()
const route = useRoute()
const draft = ref<LoopDraft>()
const designerSession = ref<DesignerSession>()
const messages = ref<DesignerMessage[]>([])
const editorValue = ref('')
const fieldError = ref<ErrorEvent>()
const acceptanceAssessment = ref<LoopSpecAssessment>()
const busy = ref(false)
const autoModeBusy = ref(false)
const newAutoModeEnabled = ref(false)
const designerReconnecting = ref(false)
const designerStreamState = ref<'idle' | 'connecting' | 'connected' | 'reconnecting'>('idle')
const designerRuntimeConnected = ref(false)
const designerRemoteState = ref('')
const designerLiveResponse = ref('')
const designerLiveError = ref('')
const designerLiveDetail = ref('')
const designerObservedAt = ref('')
const designerStructuredStep = ref<StructuredModelStep>()
const submittingDesignerQuestion = ref('')
const selectedWorkPackageId = ref('')
const selectedProjectId = ref('')
const designerRecoveryError = ref('')
const profileIntent = ref<DesignerSession['taskProfile']['intent']>('SOFTWARE_CHANGE')
const profileArtifact = ref<DesignerSession['taskProfile']['artifactKinds'][number]>('SOURCE_CODE')
const reportDetail = ref<AnalysisReport>()
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
  if (designerSession.value?.state === 'WAITING_INPUT') return 'WAITING_INPUT' as const
  if (designerSession.value?.state === 'SESSION_ERROR') return 'RETRY_WAIT' as const
  return 'PENDING' as const
})
const designerSessionError = computed(() => designerSession.value?.state === 'SESSION_ERROR'
  ? [...messages.value].reverse().find((message) => ['SESSION_ERROR', 'TERMINAL_ERROR'].includes(message.deliveryState ?? ''))
  : undefined)
const designerIsThinking = computed(() => designerSession.value?.state === 'RUNNING' && !designerLiveResponse.value
  && (designerSession.value.pendingQuestions?.length ?? 0) === 0)
const actorMeta = {
  USER: { label: '你', subtitle: '需求与补充', icon: 'lucide:user-round' },
  ROUTER: { label: 'Task Router / 任务画像路由器', subtitle: '受控仓库事实与流程选择', icon: 'lucide:route' },
  DECOMPOSER: { label: 'Task Decomposer / 任务拆解器', subtitle: '需求覆盖与纵向工作包', icon: 'lucide:split' },
  DESIGNER: { label: 'Designer / 设计师', subtitle: 'Markdown 设计文档', icon: 'lucide:sparkles' },
  COMPILER: { label: 'LoopSpec Compiler / 规范编译器', subtitle: '编译摘要与设计缺口', icon: 'lucide:braces' },
  REVIEWER: { label: 'Reviewer / 只读评审器', subtitle: '报告与证据快照', icon: 'lucide:file-search' },
  VALIDATOR: { label: 'Deterministic Validator / 确定性校验器', subtitle: '服务端硬校验结果', icon: 'lucide:badge-check' },
  SYSTEM: { label: '系统', subtitle: '工作流通知', icon: 'lucide:info' },
} as const
const workflowLabels = {
  ROUTING: '画像识别中', DISCUSSING_REQUIREMENT: '需求讨论', DECOMPOSING: '拆解中', VALIDATING_DECOMPOSITION: '校验拆解中', DESIGNING: '设计中', COMPILING: '编译中', VALIDATING: '确定性校验中', REDESIGNING: '重新设计中', QUESTIONING_PACKAGE: '设计提问', REVIEWING_PACKAGE: '工作包待确认', AGGREGATING: '聚合中', FINAL_REVIEW: '总体确认', GENERATING_REPORT: '报告生成中', VALIDATING_REPORT: '报告校验中', REPORT_READY: '报告已就绪', COMPLETED: '已完成', FAILED: '已停止',
} as const
const activeActorMeta = computed(() => actorMeta[designerSession.value?.activeActor ?? 'SYSTEM'])
const activeWorkflowLabel = computed(() => workflowLabels[designerSession.value?.workflowPhase ?? 'DESIGNING'])
const structuredStepLabels: Record<StructuredModelStep, string> = {
  PLANNING: '语义规划', SERVER_COMPILING: '程序编译', GENERATING_JSON: 'JSON 生成', REPAIRING_JSON: 'JSON 修复', FINAL_JSON: 'JSON 已校验',
}
const activeStructuredStep = computed(() => designerStructuredStep.value
  ?? (designerSession.value?.activeActor === 'DECOMPOSER' ? designerSession.value.decomposition?.workflowStep : undefined)
  ?? (designerSession.value?.activeActor === 'COMPILER' ? designerSession.value.compiler?.workflowStep : undefined))
const activeDetailedWorkflowLabel = computed(() => activeStructuredStep.value
  ? `${activeWorkflowLabel.value} · ${structuredStepLabels[activeStructuredStep.value]}` : activeWorkflowLabel.value)
const isFinalReview = computed(() => ['FINAL_REVIEW', 'COMPLETED'].includes(
  designerSession.value?.workflowPhase ?? '',
))
const confirmationReady = computed(() => store.usingDemo || designerSession.value?.finalConfirmationEligible === true
  || designerSession.value?.workflowPhase === 'COMPLETED')
const workflowStep = computed(() => {
  if (draft.value?.status === 'CONFIRMED') return 3
  if (designerSession.value?.workflowPhase === 'FINAL_REVIEW' || designerSession.value?.workflowPhase === 'COMPLETED') return 2
  if (designerSession.value?.requirementRevision !== undefined) return 1
  return 0
})
const designerSteps = computed(() => {
  const template = designerSession.value?.taskProfile.workflowTemplate
  if (template === 'READ_ONLY_REPORT') return ['需求讨论', '只读报告']
  if (template === 'DIRECT_ARTIFACT') return ['需求讨论', '制品规划', '总体确认', '创建任务']
  if (template === 'PACKAGED_ARTIFACT') return ['需求讨论', '章节规划', '总体确认', '创建任务']
  if (template === 'LOCAL_MAINTENANCE') return ['需求讨论', '维护设计', '总体确认', '创建任务']
  return ['需求讨论', '工作包设计', '总体确认', '创建任务']
})
const currentPackage = computed(() => designerSession.value?.workPackages?.find((item) => item.id === designerSession.value?.activeWorkPackageId))
const currentReport = computed(() => designerSession.value?.reports?.[0])
watch(() => `${designerSession.value?.id ?? ''}:${currentReport.value?.id ?? ''}`, async () => {
  if (!designerSession.value?.id || !currentReport.value?.id || store.usingDemo) { reportDetail.value = undefined; return }
  try { reportDetail.value = await api.getAnalysisReport(designerSession.value.id, currentReport.value.id) }
  catch { reportDetail.value = undefined }
}, { immediate: true })
const artifactStage = computed(() => draft.value?.spec.stages.find((stage) => stage.executionStrategy?.startsWith('SERVER_')))
const artifactTarget = computed(() => artifactStage.value?.deliverables?.[0] ?? '')
const artifactSource = computed(() => artifactStage.value?.verifiers?.flatMap((item) => item.tabularAssertions ?? [])
  .find((item) => item.type === 'EQUIVALENT_TO')?.sourcePath ?? '')
const documentHeadingCount = computed(() => (draft.value?.spec.context.match(/^#{1,4}\s+/gm) ?? []).length)
const discussionScopeLabel = computed(() => designerSession.value?.discussionScope && designerSession.value.discussionScope !== 'REQUIREMENT'
  ? designerSession.value.discussionScope : '整体需求')
const hasPendingDesignerQuestion = computed(() => (designerSession.value?.pendingQuestions?.length ?? 0) > 0)
const autoModeActive = computed(() => designerSession.value?.autoMode.state === 'ACTIVE')
const autoModeBlocked = computed(() => designerSession.value?.autoMode.state === 'BLOCKED')
const autoModeResolvingProfile = computed(() => autoModeActive.value
  && designerSession.value?.taskProfile.decisionRequired === true)
const composerEnabled = computed(() => {
  if (!designerSession.value || autoModeActive.value || designerSession.value.state === 'RUNNING' || hasPendingDesignerQuestion.value) return false
  if (designerSession.value.workflowPhase === 'DISCUSSING_REQUIREMENT') return true
  return designerSession.value.workflowPhase === 'REVIEWING_PACKAGE' && currentPackage.value?.state === 'REVIEWING'
})
const candidateJson = computed(() => designerSession.value?.candidate?.spec
  ? JSON.stringify(designerSession.value.candidate.spec, null, 2) : '')
const blockedWorkflowMessage = computed(() => designerSession.value?.state === 'WAITING_INPUT'
  ? [...messages.value].reverse().find((message) => ['TERMINAL_ERROR', 'DESIGN_INCOMPLETE', 'RETRYABLE_ERROR'].includes(message.deliveryState ?? ''))
  : designerSessionError.value)
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
watch(() => designerSession.value?.taskProfile, (profile) => {
  if (!profile) return
  profileIntent.value = profile.intent
  profileArtifact.value = profile.artifactKinds[0] ?? 'OTHER'
}, { immediate: true })

async function updateTaskProfile() {
  const session = designerSession.value
  if (!session?.taskProfile.id) return
  busy.value = true
  try {
    await api.updateDesignerTaskProfile(session.id, profileIntent.value, profileArtifact.value, session.taskProfile.version)
    await refreshDesignerSession()
    ElMessage.success('任务画像和专属流程已更新')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '任务画像更新失败') }
  finally { busy.value = false }
}
async function convertReportToDesign() {
  const session = designerSession.value
  const report = currentReport.value
  if (!session || !report) return
  busy.value = true
  try {
    const created = await api.convertAnalysisReportToDesign(session.id, report.id)
    if (created.draft) sessionStorage.setItem(designerWorkspaceKey, JSON.stringify({ sessionId: created.id, draftId: created.draft.id }))
    await router.replace({ path: '/designer', query: { sessionId: created.id } })
    await restoreDesignerSessionById(created.id)
    ElMessage.success('已创建关联的可写 Designer 会话；尚未创建 Task')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '报告转换失败') }
  finally { busy.value = false }
}
const visibleMessages = computed(() => messages.value.filter((message) => !selectedWorkPackageId.value
  || !message.workPackageId || message.workPackageId === selectedWorkPackageId.value)
  .filter((message) => !message.content.includes('LOOPSPEC_COMPILATION_JSON_START')).filter((message) => !(
  message.role === 'SYSTEM'
  && message.deliveryState === 'PENDING_HANDOFF'
  && !message.content.startsWith('SYSTEM_ERROR')
)))
type DesignerTimelineItem =
  | { key: string; kind: 'message'; message: DesignerMessage }
  | { key: string; kind: 'discussion'; entries: NonNullable<DesignerSession['answeredQuestions']> }
  | { key: 'validators'; kind: 'validators'; entries: DesignerMessage[] }
const timelineItems = computed<DesignerTimelineItem[]>(() => {
  const items: DesignerTimelineItem[] = []
  const validatorEntries = visibleMessages.value.filter((message) => message.actor === 'VALIDATOR')
  const discussionGroups = new Map<string, NonNullable<DesignerSession['answeredQuestions']>>()
  for (const entry of designerSession.value?.answeredQuestions ?? []) {
    const scope = !entry.scope || entry.scope === 'REQUIREMENT' ? 'REQUIREMENT' : entry.scope
    if (selectedWorkPackageId.value && scope !== 'REQUIREMENT' && scope !== selectedWorkPackageId.value) continue
    const key = `${scope}:${entry.discussionRevision ?? 0}`
    const group = discussionGroups.get(key) ?? []
    group.push(entry)
    discussionGroups.set(key, group)
  }
  const groupsByScope = new Map<string, Array<{ key: string; entries: NonNullable<DesignerSession['answeredQuestions']> }>>()
  for (const [key, entries] of discussionGroups) {
    const scope = key.slice(0, key.lastIndexOf(':'))
    const groups = groupsByScope.get(scope) ?? []
    groups.push({ key, entries })
    groupsByScope.set(scope, groups)
  }
  const discussionsBeforeMessage = new Map<string, Array<{ key: string; entries: NonNullable<DesignerSession['answeredQuestions']> }>>()
  const trailingDiscussions: Array<{ key: string; entries: NonNullable<DesignerSession['answeredQuestions']> }> = []
  for (const [scope, groups] of groupsByScope) {
    const designs = visibleMessages.value.filter((message) => message.actor === 'DESIGNER'
      && (message.workPackageId ?? 'REQUIREMENT') === scope)
    const assignedDesignIds = new Set<string>()
    groups.forEach((group) => {
      const linkedDesignId = group.entries.find((entry) => entry.designMessageId)?.designMessageId
      const target = (linkedDesignId ? designs.find((message) => message.id === linkedDesignId) : undefined)
        ?? designs.find((message) => !assignedDesignIds.has(message.id))
      if (!target) trailingDiscussions.push(group)
      else {
        assignedDesignIds.add(target.id)
        discussionsBeforeMessage.set(target.id, [...(discussionsBeforeMessage.get(target.id) ?? []), group])
      }
    })
  }
  let validatorsInserted = false
  for (const message of visibleMessages.value) {
    for (const group of discussionsBeforeMessage.get(message.id) ?? []) {
      items.push({ key: `discussion:${group.key}`, kind: 'discussion', entries: group.entries })
    }
    if (message.actor === 'VALIDATOR') {
      if (!validatorsInserted) {
        items.push({ key: 'validators', kind: 'validators', entries: validatorEntries })
        validatorsInserted = true
      }
      continue
    }
    items.push({ key: message.id, kind: 'message', message })
  }
  for (const group of trailingDiscussions) {
    items.push({ key: `discussion:${group.key}`, kind: 'discussion', entries: group.entries })
  }
  return items
})

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
    designerStructuredStep.value = event.structuredStep
    if (event.activeActor !== 'DESIGNER') designerLiveResponse.value = ''
    else if (event.content && (event.type === 'PARTIAL' || event.type === 'COMPLETED')) designerLiveResponse.value = event.content
    if (event.type === 'ERROR') designerLiveError.value = event.detail || 'OpenCode Designer 返回错误'
    if (designerSession.value) {
      designerSession.value = { ...designerSession.value, state: event.state, workflowPhase: event.workflowPhase, activeActor: event.activeActor, updatedAt: event.at, requirementRevision: event.requirementRevision, activeWorkPackageId: event.activeWorkPackageId, requirement: designerSession.value.requirement ? { ...designerSession.value.requirement, modelCallsUsed: event.modelCallsUsed, maxModelCalls: event.maxModelCalls } : designerSession.value.requirement }
    }
    if (event.type === 'COMPLETED' || event.type === 'ERROR' || event.type === 'AUTO_MODE') {
      refreshDesignerAfterTerminalEvent()
    }
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

watch(() => designerSession.value?.activeWorkPackageId, (packageId) => {
  if (packageId) selectedWorkPackageId.value = packageId
})

let redirectedAutoTaskId = ''
watch(() => designerSession.value?.autoMode.taskId, async (taskId) => {
  if (!taskId || taskId === redirectedAutoTaskId || store.usingDemo) return
  redirectedAutoTaskId = taskId
  try {
    await store.loadTask(taskId)
    await router.push(`/tasks/${taskId}`)
  } catch (error) {
    redirectedAutoTaskId = ''
    ElMessage.error(error instanceof Error ? error.message : '自动任务已创建，但无法打开任务详情')
  }
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
  return { schemaVersion: 'v2', projectId, goal, context: 'Execution 只允许在该 Task 的执行目录中修改；有 Git HEAD 时把登记项目目录切换到任务分支，否则直接使用登记的项目目录。', stages: [{ objective: '分析目标并实现最小可验证改动', allowedPaths: [], forbiddenPaths: [], deliverables: ['可验证实现'], implementationKind: 'NON_JAVA', acceptanceCriteria: [], verifiers: [] }], limits: { maxStageAttempts: settings.limits.maxStageAttempts, maxTaskAttempts: settings.limits.maxTaskAttempts, maxDuration: `PT${settings.limits.maxDurationMinutes}M`, attemptTimeout: `PT${settings.limits.attemptTimeoutMinutes}M` } }
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
    designerSession.value = await api.createDesignerSession(project.id, createdDraft.id, goal, newAutoModeEnabled.value)
    messages.value = designerSession.value.messages
    draft.value = designerSession.value.draft ?? createdDraft
    editorValue.value = JSON.stringify(draft.value.spec, null, 2)
    sessionStorage.setItem(designerWorkspaceKey, JSON.stringify({ sessionId: designerSession.value.id, draftId: draft.value.id }))
    designerRecoveryError.value = ''
    draftPrompt.value = ''
    newAutoModeEnabled.value = false
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '无法创建 LoopSpec 草案') } finally { busy.value = false }
}

async function confirmAutoModeRisk() {
  try {
    await ElMessageBox.confirm(
      '开启后会自动采用 Router 推荐的任务画像和设计答案、确认需求和工作包、确认最终设计，并创建及启动任务。需求确认前仍可人工覆盖画像；执行期权限、异常恢复、结果确认、提交、推送和发布仍需人工处理。',
      '授权全自动设计？',
      { type: 'warning', confirmButtonText: '确认开启', cancelButtonText: '保持关闭' },
    )
    return true
  } catch { return false }
}

async function changeNewAutoMode(value: string | number | boolean) {
  if (value !== true) return
  if (!await confirmAutoModeRisk()) newAutoModeEnabled.value = false
}

async function changeAutoMode(value: string | number | boolean) {
  if (!designerSession.value || store.usingDemo) return
  const enabled = value === true
  if (enabled && !await confirmAutoModeRisk()) return
  autoModeBusy.value = true
  try {
    const updated = await api.updateDesignerAutoMode(designerSession.value.id, enabled,
      designerSession.value.autoMode.version)
    designerSession.value = { ...designerSession.value, autoMode: updated }
    ElMessage.success(enabled ? '全自动模式已开启' : '全自动模式已关闭')
    await refreshDesignerSession()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '全自动模式切换失败')
    await refreshDesignerSession()
  } finally { autoModeBusy.value = false }
}

function activateDesignerWorkspace(restoredSession: DesignerSession, restoredDraft: LoopDraft) {
  designerSession.value = restoredSession
  messages.value = restoredSession.messages
  draft.value = restoredSession.draft ?? restoredDraft
  selectedProjectId.value = draft.value.spec.projectId
  editorValue.value = JSON.stringify(draft.value.spec, null, 2)
  sessionStorage.setItem(designerWorkspaceKey, JSON.stringify({ sessionId: restoredSession.id, draftId: draft.value.id }))
  designerRecoveryError.value = ''
}

async function restoreDesignerSessionById(sessionId: string) {
  try {
    const restoredSession = await api.getDesignerSession(sessionId)
    if (restoredSession.archived) throw new Error('该设计已归档，请先在历史设计页恢复')
    if (!restoredSession.draft) throw new Error('设计会话缺少可恢复草稿')
    const restoredDraft = restoredSession.draft
    activateDesignerWorkspace(restoredSession, restoredDraft)
    return true
  } catch (error) {
    designerRecoveryError.value = error instanceof Error ? error.message : '无法恢复该设计会话'
    return false
  }
}

function focusDesignerComposer() {
  const focus = () => document.querySelector<HTMLTextAreaElement>('#designer-message')?.focus()
  if (typeof requestAnimationFrame === 'function') requestAnimationFrame(focus)
  else focus()
}

async function prepareHistoryEdit() {
  if (!designerSession.value) return
  if (designerSession.value.workflowPhase === 'DISCUSSING_REQUIREMENT') {
    ElMessage.info('已打开整体需求，可继续补充或修改')
    focusDesignerComposer()
    return
  }
  await reopenRequirement()
  focusDesignerComposer()
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
  designerStructuredStep.value = undefined
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
  let ids: { sessionId?: string, draftId?: string }
  try {
    ids = JSON.parse(saved) as { sessionId?: string, draftId?: string }
    if (!ids.sessionId || !ids.draftId) throw new Error('Designer workspace reference is incomplete')
  } catch {
    sessionStorage.removeItem(designerWorkspaceKey)
    return
  }
  try {
    const [restoredSession, restoredDraft] = await Promise.all([
      api.getDesignerSession(ids.sessionId),
      api.getDraft(ids.draftId),
    ])
    if (restoredSession.archived) {
      sessionStorage.removeItem(designerWorkspaceKey)
      return
    }
    activateDesignerWorkspace(restoredSession, restoredDraft)
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) sessionStorage.removeItem(designerWorkspaceKey)
    designerRecoveryError.value = error instanceof Error ? `上次设计暂时无法恢复：${error.message}` : '上次设计暂时无法恢复'
  }
}

onMounted(async () => {
  window.addEventListener('beforeunload', warnBeforeUnload)
  if (!store.projects.length) await store.loadProjects()
  const queryProjectId = typeof route.query.projectId === 'string' ? route.query.projectId : ''
  if (queryProjectId && store.projects.some((project) => project.id === queryProjectId)) {
    selectedProjectId.value = queryProjectId
  }
  if (!store.usingDemo) {
    const querySessionId = typeof route.query.sessionId === 'string' ? route.query.sessionId : ''
    if (querySessionId) {
      const restored = await restoreDesignerSessionById(querySessionId)
      if (restored && route.query.mode === 'edit') await prepareHistoryEdit()
    } else await restoreDesignerWorkspace()
  }
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
    if (spec.schemaVersion === 'v2' && spec.stages.some((stage) => !stage.implementationKind)) throw new Error('每个 v2 stage 都必须选择 implementationKind')
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
    if (!store.usingDemo) {
      const assessment = await api.validateDraft(spec)
      acceptanceAssessment.value = assessment
      if (!assessment.valid) throw new Error(assessment.errors.join('；'))
    }
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

async function copyLegacyDraftAsV2() {
  if (!draft.value || draft.value.spec.schemaVersion !== 'v1' || store.usingDemo) return
  busy.value = true
  try {
    const copied = await api.copyDraftAsV2(draft.value.id)
    designerSession.value = await api.createDesignerSession(copied.spec.projectId, copied.id)
    messages.value = designerSession.value.messages
    draft.value = designerSession.value.draft ?? copied
    editorValue.value = JSON.stringify(draft.value.spec, null, 2)
    acceptanceAssessment.value = undefined
    sessionStorage.setItem(designerWorkspaceKey, JSON.stringify({ sessionId: designerSession.value.id, draftId: draft.value.id }))
    ElMessage.success('已复制为新的 v2 草稿；请补齐验收条件与行为验证器后保存')
  } catch (error) {
    fieldError.value = { id: 'field-copy-v2', layer: 'FIELD', code: 'LOOPSPEC_COPY_V2_FAILED', message: error instanceof Error ? error.message : '复制 v2 草稿失败', retryable: true, occurredAt: '刚刚' }
  } finally { busy.value = false }
}

async function confirm() {
  if (!draft.value) return
  if (autoModeActive.value) return
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
    messages.value.push({ id: `local-${Date.now()}`, role: 'USER', actor: 'USER', content, deliveryState: 'PERSISTED', createdAt: '刚刚' })
    userMessage.value = ''
    return
  }
  if (!designerSession.value) {
    ElMessage.warning('请先创建 Designer session。')
    return
  }
  busy.value = true
  try {
    const session = designerSession.value
    const result = session.workflowPhase === 'DISCUSSING_REQUIREMENT'
      ? await api.sendRequirementMessage(session.id, content, session.discussionRevision)
      : currentPackage.value
        ? await api.sendWorkPackageMessage(session.id, currentPackage.value.id, content,
            session.discussionRevision, currentPackage.value.designRevision)
        : await api.sendDesignerMessage(session.id, content)
    mergeMessages(result.persistedMessages)
    designerSession.value = { ...designerSession.value, state: result.state }
    userMessage.value = ''
    ElMessage.info(result.notice || '消息已交给只读 OpenCode Designer。')
    await refreshDesignerSession()
    startDesignerPolling()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法保存 Designer 消息')
  } finally {
    busy.value = false
  }
}

async function confirmRequirement() {
  if (!designerSession.value || store.usingDemo || autoModeActive.value) return
  busy.value = true
  try {
    await api.confirmDesignerRequirement(designerSession.value.id, designerSession.value.discussionRevision)
    await refreshDesignerSession()
    startDesignerPolling()
    ElMessage.success(designerSession.value?.taskProfile.workflowTemplate === 'READ_ONLY_REPORT'
      ? '独立只读 Reviewer 已启动；不会创建任务或写入资源' : '整体需求已冻结，专属流程已启动')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '需求确认失败') }
  finally { busy.value = false }
}

async function reopenRequirement() {
  if (!designerSession.value || store.usingDemo) return
  try {
    await ElMessageBox.confirm('修改整体需求会废弃当前拆包和所有批准记录；历史快照仍保留。', '重新讨论整体需求？', {
      type: 'warning', confirmButtonText: '确认重新讨论', cancelButtonText: '保留当前设计',
    })
  } catch { return }
  busy.value = true
  try {
    await api.reopenDesignerRequirement(designerSession.value.id, designerSession.value.discussionRevision)
    await refreshDesignerSession()
    selectedWorkPackageId.value = ''
    ElMessage.success('整体需求已重新打开')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '重开需求失败') }
  finally { busy.value = false }
}

async function approvePackage() {
  if (!designerSession.value || !currentPackage.value || store.usingDemo || autoModeActive.value) return
  const approvedPackageId = currentPackage.value.id
  busy.value = true
  try {
    await api.approveWorkPackage(designerSession.value.id, approvedPackageId,
      designerSession.value.discussionRevision, currentPackage.value.designRevision)
    await refreshDesignerSession()
    startDesignerPolling()
    ElMessage.success(`${approvedPackageId} 已接受`)
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '工作包接受失败') }
  finally { busy.value = false }
}

function dependentPackageIds(packageId: string) {
  const packages = designerSession.value?.workPackages ?? []
  const result = new Set<string>()
  let changed = true
  while (changed) {
    changed = false
    for (const item of packages) {
      if (!result.has(item.id) && item.dependencies.some((dependency) => dependency === packageId || result.has(dependency))) {
        result.add(item.id); changed = true
      }
    }
  }
  return [...result]
}

async function reopenPackage(packageId: string) {
  if (!designerSession.value || store.usingDemo) return
  const item = designerSession.value.workPackages?.find((candidate) => candidate.id === packageId)
  if (!item?.approvedDesignRevision) return
  const dependents = dependentPackageIds(packageId)
  try {
    await ElMessageBox.confirm(
      dependents.length ? `将重新讨论 ${packageId}，并使下游 ${dependents.join('、')} 失效。无关工作包保持已接受。`
        : `将重新讨论 ${packageId}；没有下游工作包会失效。`,
      '重新讨论工作包？', { type: 'warning', confirmButtonText: '确认重新讨论', cancelButtonText: '取消' },
    )
  } catch { return }
  busy.value = true
  try {
    const invalidated = await api.reopenWorkPackage(designerSession.value.id, packageId,
      designerSession.value.discussionRevision, item.approvedDesignRevision)
    await refreshDesignerSession()
    selectedWorkPackageId.value = packageId
    ElMessage.success(invalidated.length ? `已重开；失效：${invalidated.join('、')}` : `${packageId} 已重开`)
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '工作包重开失败') }
  finally { busy.value = false }
}

async function retryCompiler() {
  if (!designerSession.value || store.usingDemo) return
  busy.value = true
  try {
    await api.retryDesignerCompiler(designerSession.value.id)
    await refreshDesignerSession()
    startDesignerPolling()
    ElMessage.success('已启动新的只读规范编译器 Session')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '重新编译失败') }
  finally { busy.value = false }
}

async function requestRedesign() {
  if (!designerSession.value || store.usingDemo) return
  busy.value = true
  try {
    await api.requestDesignerRedesign(designerSession.value.id)
    await refreshDesignerSession()
    startDesignerPolling()
    ElMessage.success('已要求设计师生成完整替代稿')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '重新设计失败') }
  finally { busy.value = false }
}

async function retryDecomposition() {
  if (!designerSession.value || store.usingDemo) return
  busy.value = true
  try {
    await api.retryDesignerDecomposition(designerSession.value.id)
    await refreshDesignerSession()
    startDesignerPolling()
    ElMessage.success('已启动新的只读任务拆解器 Session')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '重新拆解失败') }
  finally { busy.value = false }
}

async function retryPackageCompiler(packageId: string) {
  if (!designerSession.value || store.usingDemo) return
  busy.value = true
  try {
    await api.retryWorkPackageCompiler(designerSession.value.id, packageId)
    await refreshDesignerSession()
    startDesignerPolling()
    ElMessage.success(`${packageId} 已启动新的规范编译器 Session`)
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '工作包重新编译失败') }
  finally { busy.value = false }
}

async function redesignPackage(packageId: string) {
  if (!designerSession.value || store.usingDemo) return
  busy.value = true
  try {
    await api.redesignWorkPackage(designerSession.value.id, packageId)
    await refreshDesignerSession()
    startDesignerPolling()
    ElMessage.success(`${packageId} 已交给新的只读 Designer Session 重新设计`)
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '工作包重新设计失败') }
  finally { busy.value = false }
}
</script>

<template>
  <PageHeader :eyebrow="draft ? 'Designer / Plan' : 'Designer'" :title="draft ? 'Designer 与 LoopSpec' : '设计工作台'">
    <template v-if="draft" #actions><StatusBadge :status="draft.status === 'CONFIRMED' ? 'SUCCEEDED' : 'PENDING'" :label="draft.status" /><el-button v-if="designerSession?.requirementRevision !== undefined && draft.status !== 'CONFIRMED'" plain :disabled="busy || designerSession?.state === 'RUNNING'" @click="reopenRequirement"><Icon icon="lucide:message-circle-more" />重新讨论整体需求</el-button><el-button class="restart-designer-button" plain :disabled="busy" @click="restartDesigner"><Icon icon="lucide:rotate-ccw" />重新开始</el-button><el-button type="primary" :loading="busy" :disabled="!confirmationReady" @click="confirm"><Icon icon="lucide:circle-check-big" />确认设计并创建任务</el-button></template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <section v-if="!draft && !store.usingDemo && !store.loading && !store.projects.length" class="card designer-onboarding" aria-labelledby="designer-onboarding-title">
      <span class="designer-onboarding-icon"><Icon icon="lucide:folder-plus" aria-hidden="true" /></span>
      <div><p class="eyebrow">PROJECT REQUIRED</p><h2 id="designer-onboarding-title">先登记项目，再开始设计</h2><p>Designer 需要读取项目目录和约定，才能生成贴合代码库的 LoopSpec。登记项目不会启动 AI，也不会写入项目文件。</p></div>
      <el-button type="primary" size="large" @click="router.push('/projects')">前往登记项目<Icon icon="lucide:arrow-right" aria-hidden="true" /></el-button>
    </section>
    <section v-else-if="!draft" class="designer-start-page">
      <header class="designer-start-heading">
        <span class="designer-start-mark"><Icon icon="lucide:sparkles" /></span>
        <div>
          <p class="eyebrow">NEW DESIGN</p>
          <h2>今天想推进什么？</h2>
          <p>选择一个项目，描述你期望达成的结果。</p>
        </div>
      </header>

      <section v-if="designerRecoveryError" class="designer-recovery-notice" role="status">
        <Icon icon="lucide:cloud-off" />
        <div><strong>设计恢复暂时不可用</strong><p>{{ designerRecoveryError }}。本地恢复指针和服务端记录不会因短暂断线被删除，可刷新后重试。</p></div>
      </section>

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
              name="designer-goal"
              autocomplete="off"
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
            <div class="designer-auto-create">
              <span class="composer-boundary"><Icon icon="lucide:shield-check" />只读分析项目</span>
              <label><el-switch v-model="newAutoModeEnabled" :disabled="store.usingDemo" @change="changeNewAutoMode" /><span><strong>全自动模式</strong><small>默认关闭；自动完成设计并启动任务</small></span></label>
            </div>
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
              <span :class="['project-readiness', `is-${selectedProject.status.toLowerCase()}`]">{{ selectedProject.status === 'INVALID' ? '路径不可用' : selectedProject.executionMode === 'WORKTREE' ? 'Git 分支' : '直接执行' }}</span>
            </div>
            <dl class="context-details">
              <div><dt>路径</dt><dd class="mono">{{ selectedProject.rootPath }}</dd></div>
              <div><dt>执行位置</dt><dd class="mono">{{ selectedProject.executionMode === 'WORKTREE' ? '原项目目录（任务创建时切分支）' : selectedProject.executionMode === 'UNAVAILABLE' ? '不可用' : '原项目目录' }}</dd></div>
              <div><dt>历史任务</dt><dd>{{ selectedProject.taskCount }} 个</dd></div>
              <div><dt>待继续设计</dt><dd>{{ selectedProject.openDesignerSessionCount }} 个</dd></div>
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
        <nav class="designer-steps" aria-label="Designer 流程">
          <span v-for="(label, index) in designerSteps" :key="label" :class="{ active: index === workflowStep, completed: index < workflowStep }"><i>{{ index + 1 }}</i>{{ label }}</span>
        </nav>
        <div class="card-pad card-header"><div><p class="eyebrow">READ-ONLY DESIGNER</p><h2 class="card-title">{{ designerSession?.projectName ?? activeProjectName }}</h2></div><div class="designer-state-actions"><label class="designer-auto-switch"><span><strong>全自动</strong><small>{{ designerSession?.autoMode.state ?? 'DISABLED' }}</small></span><el-switch :model-value="designerSession?.autoMode.enabled === true" :loading="autoModeBusy" :disabled="store.usingDemo || designerSession?.autoMode.state === 'COMPLETED'" @change="changeAutoMode" /></label><el-button v-if="designerReconnecting || designerStreamState === 'reconnecting'" plain size="small" @click="reconnectDesigner"><Icon icon="lucide:refresh-cw" />立即重连</el-button><StatusBadge :status="designerBadgeStatus" :label="designerReconnecting || designerStreamState === 'reconnecting' ? 'RECONNECTING' : designerSession?.state ?? '等待 session'" /></div></div>
        <div class="designer-connection-strip" role="status" aria-live="polite">
          <span><i :class="['connection-dot', designerStreamState]" />{{ designerTransportLabel }}</span>
          <span><i :class="['connection-dot', { connected: designerRuntimeConnected, error: designerLiveError }]" />{{ designerRuntimeLabel }}</span>
          <span class="active-role"><Icon :icon="activeActorMeta.icon" />{{ activeActorMeta.label }} · {{ activeDetailedWorkflowLabel }}</span>
          <span v-if="designerSession?.activeWorkPackageId" class="mono">{{ designerSession.activeWorkPackageId }}/{{ designerSession.workPackages?.length ?? 0 }}</span>
          <span v-if="designerSession?.requirement" class="mono">模型调用 {{ designerSession.requirement.modelCallsUsed }}/{{ designerSession.requirement.maxModelCalls }}</span>
          <span v-if="designerSession?.compiler" class="mono">Compiler 格式修复 {{ designerSession.compiler.formatRepairCount ?? 0 }}/2 · 语义修复 {{ designerSession.compiler.semanticRepairCount ?? 0 }}/2<span v-if="designerSession.compiler.serverCompiled"> · 程序已编译</span></span>
          <span v-else-if="designerSession?.decomposition" class="mono">Decomposer 格式修复 {{ designerSession.decomposition.formatRepairCount ?? 0 }}/2 · 语义修复 {{ designerSession.decomposition.semanticRepairCount ?? 0 }}/2<span v-if="designerSession.decomposition.serverCompiled"> · 程序已编译</span></span>
          <span class="mono">远端 {{ designerRemoteState || 'WAITING' }}</span>
          <time :datetime="designerObservedAt">{{ formatObservedAt(designerObservedAt) }}</time>
        </div>
        <section v-if="designerSession?.taskProfile" class="task-profile-card" aria-label="任务画像与动态流程">
          <header><div><strong>任务画像 · {{ taskIntentLabel(designerSession.taskProfile.intent) }}</strong><span>{{ designerSession.taskProfile.confidence }}% · {{ designerSession.taskProfile.rolePackId }}@{{ designerSession.taskProfile.rolePackVersion }}</span></div><b :class="{ warning: designerSession.taskProfile.decisionRequired }">{{ designerSession.taskProfile.decisionRequired ? '需要确认' : profileResolutionLabel(designerSession.taskProfile.resolutionSource) }}</b></header>
          <p>流程 {{ workflowTemplateLabel(designerSession.taskProfile.workflowTemplate) }} · 执行 {{ executionStrategyLabel(designerSession.taskProfile.executionStrategy) }} · 测试 {{ testPolicyLabel(designerSession.taskProfile.testPolicy) }}</p>
          <div class="profile-evidence"><span v-for="item in designerSession.taskProfile.evidence" :key="item">{{ item }}</span></div>
          <div v-if="designerSession.taskProfile.state === 'PROVISIONAL'" class="profile-override">
            <el-select v-model="profileIntent" aria-label="覆盖任务类型"><el-option v-for="item in designerSession.availableProfileOverrides" :key="item" :label="taskIntentLabel(item)" :value="item" /></el-select>
            <el-select v-model="profileArtifact" aria-label="覆盖主要制品"><el-option v-for="item in designerSession.availableArtifactOverrides" :key="item" :label="artifactKindLabel(item)" :value="item" /></el-select>
            <el-button plain :loading="busy" @click="updateTaskProfile">应用覆盖</el-button>
          </div>
        </section>
        <section v-if="currentReport" class="task-profile-card report-card">
          <header><div><strong>独立 Reviewer 报告</strong><span>{{ currentReport.title }}<template v-if="reportDetail?.reviewerContractVersion"> · {{ reportDetail.reviewerContractVersion }}</template></span></div><b :class="{ warning: currentReport.stale }">{{ currentReport.stale ? '证据已过期' : '证据有效' }}</b></header>
          <p class="mono">SHA-256 {{ currentReport.contentSha256 }}</p>
          <div v-if="reportDetail?.evidence.length" class="profile-evidence"><span v-for="item in reportDetail.evidence" :key="`${item.path}:${item.line}`">{{ item.path }}:{{ item.line }} · {{ item.stale ? '已过期' : '有效' }}</span></div>
          <details v-if="reportDetail?.markdown"><summary>查看完整报告</summary><MarkdownDocument :content="reportDetail.markdown" /></details>
          <el-button v-if="currentReport.state === 'READY'" plain :loading="busy" @click="convertReportToDesign">转为修改任务</el-button>
          <small>该操作只创建关联 Designer 会话，不会直接创建 Task、分支或可写 Session。</small>
        </section>
        <section v-if="designerSession?.autoMode.state !== 'DISABLED'" :class="['designer-auto-status', { blocked: autoModeBlocked, completed: designerSession?.autoMode.state === 'COMPLETED' }]" role="status" aria-live="polite">
          <Icon :icon="autoModeBlocked ? 'lucide:octagon-alert' : designerSession?.autoMode.state === 'COMPLETED' ? 'lucide:circle-check-big' : 'lucide:bot'" />
          <div><strong>{{ autoModeBlocked ? '全自动模式已阻断' : designerSession?.autoMode.state === 'COMPLETED' ? '全自动设计已完成' : autoModeResolvingProfile ? '全自动模式正在确认画像' : '全自动模式正在推进' }}</strong><p v-if="autoModeBlocked">{{ designerSession?.autoMode.errorDetail }}；请先关闭后人工处理，完成后可重新授权。</p><p v-else-if="designerSession?.autoMode.state === 'COMPLETED'">任务已请求启动，正在打开任务详情；执行期决策保持人工处理。</p><p v-else-if="autoModeResolvingProfile">将采用 Router 当前推荐的任务类型和主要制品，无需人工覆盖；需求确认前仍可主动调整。</p><p v-else>每轮只推进一个权威动作；关闭不会撤销已完成动作或终止正在执行的模型调用。</p></div>
        </section>
        <section v-if="blockedWorkflowMessage" class="designer-session-alert" role="status" aria-live="polite"><Icon icon="lucide:refresh-cw" /><div><strong>{{ designerSession?.state === 'WAITING_INPUT' ? '设计工作流需要人工恢复' : '设计工作流已停止' }}</strong><p>{{ blockedWorkflowMessage.content }}</p><span class="tiny muted">最后有效需求快照、问题回答和候选 LoopSpec 均已保留；恢复不会重新执行已完成的拆包。</span><div class="recovery-actions"><el-button v-if="designerSession?.decomposition && !designerSession.activeWorkPackageId" plain size="small" :loading="busy" @click="retryDecomposition"><Icon icon="lucide:split" />重新拆解</el-button><el-button v-if="designerSession?.activeWorkPackageId" plain size="small" :loading="busy" @click="retryPackageCompiler(designerSession.activeWorkPackageId)"><Icon icon="lucide:braces" />重新编译当前包</el-button><el-button v-if="designerSession?.activeWorkPackageId" plain size="small" :loading="busy" @click="redesignPackage(designerSession.activeWorkPackageId)"><Icon icon="lucide:sparkles" />恢复当前包设计</el-button><template v-if="!designerSession?.decomposition"><el-button plain size="small" :loading="busy" @click="retryCompiler"><Icon icon="lucide:braces" />重新编译当前设计</el-button><el-button plain size="small" :loading="busy" @click="requestRedesign"><Icon icon="lucide:sparkles" />让 Designer 重新设计</el-button></template><el-button plain size="small" @click="restartDesigner"><Icon icon="lucide:rotate-ccw" />清理工作区</el-button></div></div></section>
        <section v-else-if="designerLiveError" class="designer-session-alert live-error" role="alert" aria-live="assertive"><Icon icon="lucide:triangle-alert" /><div><strong>OpenCode 实时错误</strong><p>{{ designerLiveError }}</p><span class="tiny muted">错误已从实时通道收到，正在同步持久化会话状态。</span></div></section>
        <div class="designer-conversation">
          <section v-if="designerSession?.workPackages?.length" class="work-package-rail" aria-label="工作包设计轨道">
            <article v-for="item in designerSession.workPackages ?? []" :key="item.id" :class="['work-package-chip', `package-${item.state.toLowerCase()}`, { active: item.id === designerSession.activeWorkPackageId, selected: item.id === selectedWorkPackageId }]" role="button" tabindex="0" @click="selectedWorkPackageId = item.id" @keydown.enter="selectedWorkPackageId = item.id">
              <header><b>{{ item.id }}</b><span>{{ item.state }}</span></header>
              <strong>{{ item.title }}</strong>
              <small v-if="item.rolePackId" class="mono">{{ item.rolePackId }}<template v-if="item.technologies?.length"> · {{ item.technologies.join('/') }}</template><template v-if="item.testPolicy"> · {{ item.testPolicy }}</template></small>
              <small v-if="item.state === 'STALE'">由 {{ item.invalidatedByPackageId }} 修订导致失效</small>
              <small v-else-if="item.state === 'PENDING'">依赖 {{ item.dependencies.join('、') || '无' }} · 等待上游接受</small>
              <small v-else>讨论 {{ item.discussionRoundCount }}/5 · 设计 R{{ item.designRevision }}<template v-if="item.approvedDesignRevision"> · 已接受 R{{ item.approvedDesignRevision }}</template></small>
              <el-button v-if="item.state === 'APPROVED'" text size="small" :disabled="busy || designerSession?.state === 'RUNNING'" @click.stop="reopenPackage(item.id)">重新讨论</el-button>
            </article>
          </section>
          <div class="chat-history">
          <template v-for="item in timelineItems" :key="item.key">
          <DesignerDiscussionHistory v-if="item.kind === 'discussion'" :entries="item.entries" />
          <DesignerValidatorHistory v-else-if="item.kind === 'validators'" :entries="item.entries" />
          <article v-else :class="['chat-message', `chat-${item.message.actor.toLowerCase()}`]">
            <header class="chat-message-header">
              <span class="chat-author">
                <span class="chat-avatar"><Icon :icon="actorMeta[item.message.actor].icon" /></span>
                <span><strong class="chat-role">{{ actorMeta[item.message.actor].label }}</strong><small>{{ actorMeta[item.message.actor].subtitle }}<template v-if="item.message.workPackageId"> · {{ item.message.workPackageId }}</template></small></span>
              </span>
              <time class="chat-message-time" :datetime="item.message.createdAt">{{ item.message.deliveryState ? `${statusLabel(item.message.deliveryState)} · ` : '' }}{{ formatDateTime(item.message.createdAt) }}</time>
            </header>
            <MarkdownDocument v-if="item.message.actor === 'DESIGNER'" :content="item.message.content" collapsible />
            <p v-else class="plain-message-content">{{ item.message.content }}</p>
          </article>
          </template>
          <article v-if="designerLiveResponse" class="chat-message chat-designer chat-live" aria-label="Designer 正在流式回复" aria-live="polite">
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
            mandatory
            :submitting="submittingDesignerQuestion === pending.id"
            :disabled="autoModeActive"
            @submit="(answers: string[][]) => answerDesignerQuestion(pending, answers)"
            @reject="rejectDesignerQuestion(pending)"
          />
          <article v-if="designerIsThinking" :class="['thinking-message', `thinking-${designerSession?.activeActor?.toLowerCase() ?? 'system'}`]" role="status" aria-live="polite" :aria-label="`${activeActorMeta.label}正在处理`">
            <span class="thinking-orbit" aria-hidden="true"><span /></span>
            <div class="thinking-copy">
              <strong>{{ activeActorMeta.label }}正在{{ activeDetailedWorkflowLabel }}<span class="thinking-dots" aria-hidden="true"><i /><i /><i /></span></strong>
              <p>{{ designerReconnecting || designerStreamState === 'reconnecting' ? '连接暂时中断，正在恢复并继续等待真实回复。' : designerLiveDetail || 'OpenCode 已收到请求，等待首段模型回复。' }}</p>
            </div>
          </article>
          </div>
          <div class="chat-compose">
            <div class="compose-heading"><label class="field-label" for="designer-message">当前作用域：{{ discussionScopeLabel }}</label><span class="tiny muted">完整快照 · R{{ designerSession?.discussionRevision ?? 0 }}</span></div>
            <el-input
              id="designer-message"
              v-model="userMessage"
              class="designer-message-input"
              type="textarea"
              :rows="10"
              maxlength="12000"
              name="designer-follow-up"
              autocomplete="off"
              resize="vertical"
              :disabled="!composerEnabled"
              :placeholder="hasPendingDesignerQuestion ? '请先回答上方设计问题…' : designerSession?.state === 'RUNNING' ? '当前角色正在处理上一条消息…' : isFinalReview ? '总体确认阶段请使用工作包“重新讨论”或整体需求重开…' : `继续讨论${discussionScopeLabel}；不会触发全局重新拆包…`"
              aria-label="发送给只读 OpenCode Designer 的消息"
              @keydown.meta.enter.prevent="sendMessage"
              @keydown.ctrl.enter.prevent="sendMessage"
            />
            <div class="compose-actions"><span class="tiny muted">{{ hasPendingDesignerQuestion ? '问题回答与普通输入不能同时提交' : '⌘ / Ctrl + Enter 发送；只修改当前作用域' }}</span><el-button type="primary" :loading="busy" :disabled="!composerEnabled || !userMessage.trim()" @click="sendMessage"><Icon icon="lucide:send" />发送</el-button></div>
            <div v-if="designerSession?.workflowPhase === 'DISCUSSING_REQUIREMENT' && designerSession.state === 'REVIEWING'" class="scope-primary-action"><el-button type="primary" :loading="busy" :disabled="autoModeActive || designerSession.taskProfile.decisionRequired" @click="confirmRequirement"><Icon :icon="designerSession.taskProfile.workflowTemplate === 'READ_ONLY_REPORT' ? 'lucide:file-search' : ['DIRECT_ARTIFACT', 'PACKAGED_ARTIFACT'].includes(designerSession.taskProfile.workflowTemplate) ? 'lucide:file-output' : designerSession.taskProfile.workflowTemplate === 'LOCAL_MAINTENANCE' ? 'lucide:wrench' : 'lucide:split'" />{{ autoModeActive ? '全自动模式将确认需求' : designerSession.taskProfile.workflowTemplate === 'READ_ONLY_REPORT' ? '需求已明确，生成只读报告' : designerSession.taskProfile.workflowTemplate === 'DIRECT_ARTIFACT' ? '需求已明确，规划制品' : designerSession.taskProfile.workflowTemplate === 'PACKAGED_ARTIFACT' ? '需求已明确，规划章节制品' : designerSession.taskProfile.workflowTemplate === 'LOCAL_MAINTENANCE' ? '需求已明确，规划安全维护' : '需求已明确，开始拆包' }}</el-button></div>
            <div v-else-if="designerSession?.workflowPhase === 'REVIEWING_PACKAGE' && currentPackage?.state === 'REVIEWING'" class="scope-primary-action"><span>候选已通过确定性校验，接受后才会进入下一个工作包。</span><el-button type="primary" :loading="busy" :disabled="autoModeActive" @click="approvePackage"><Icon icon="lucide:check-check" />{{ autoModeActive ? `全自动模式将接受 ${currentPackage.id}` : `接受 ${currentPackage.id} 并继续` }}</el-button></div>
          </div>
        </div>
      </article>
      <article class="card spec-panel">
        <div class="card-pad card-header"><div><p class="eyebrow">REVIEW GATE</p><h2 class="card-title">{{ isFinalReview ? '最终 LoopSpec' : '候选 LoopSpec' }}</h2></div><div class="review-actions"><span :class="['candidate-sync', `sync-${(designerSession?.candidate?.syncState ?? 'NONE').toLowerCase()}`]">{{ designerSession?.candidate?.syncState === 'SYNCING' ? '同步中' : designerSession?.candidate?.syncState === 'FAILED' ? '同步失败，保留上一版' : designerSession?.candidate?.syncState === 'SYNCED' ? `已同步 R${designerSession.candidate.discussionRevision}` : '等待候选' }}</span><template v-if="isFinalReview"><el-button v-if="draft.spec.schemaVersion === 'v1' && !store.usingDemo" plain size="small" :loading="busy" @click="copyLegacyDraftAsV2">复制为 v2</el-button><el-button plain size="small" :loading="busy" @click="saveDraft"><Icon icon="lucide:save" />保存</el-button></template></div></div>
        <div class="spec-meta"><span><Icon icon="lucide:folder-git-2" />{{ draft.spec.projectId }}</span><span><Icon icon="lucide:flag" />{{ designerSession?.candidate?.spec?.stages.length ?? draft.spec.stages.length }} 个阶段</span><span><Icon icon="lucide:timer" />{{ draft.spec.limits.maxDuration }}</span><span v-if="draft.spec.schemaVersion === 'v1'" class="legacy-contract">旧合同（兼容）</span></div>
        <section v-if="isFinalReview && artifactStage" class="artifact-preview" aria-label="服务端制品预览">
          <header><strong>{{ artifactStage.stageKind === 'TABULAR_CONVERSION' ? '表格转换预览' : artifactTarget.endsWith('.docx') ? 'DOCX 结构摘要' : 'Markdown 预览' }}</strong><span>{{ artifactTarget }}</span></header>
          <template v-if="artifactStage.stageKind === 'TABULAR_CONVERSION'"><p>输入 {{ artifactSource }} → 输出 {{ artifactTarget }}</p><small>公式使用缓存显示值；合并区域仅保留左上角；仅移除尾部全空行列。</small></template>
          <template v-else-if="artifactTarget.endsWith('.docx')"><p>{{ draft.spec.goal }} · {{ documentHeadingCount }} 个 1–4 级标题 · 服务端生成后执行 DOCUMENT_STRUCTURE 验收</p></template>
          <MarkdownDocument v-else :content="draft.spec.context" collapsible />
        </section>
        <section v-if="acceptanceAssessment && !acceptanceAssessment.legacy" class="acceptance-matrix" aria-label="双重验收计划矩阵">
          <header><strong>双重验收计划</strong><span :class="acceptanceAssessment.valid ? 'matrix-pass' : 'matrix-fail'">{{ acceptanceAssessment.valid ? '计划有效' : `${acceptanceAssessment.errors.length} 项阻断` }}</span></header>
          <div v-for="stage in acceptanceAssessment.stageAssessments" :key="stage.stageIndex" class="matrix-stage">
            <span>{{ draft.spec.stages[stage.stageIndex]?.workPackageId ? `${draft.spec.stages[stage.stageIndex]?.workPackageId} · ` : '' }}阶段 {{ stage.stageIndex + 1 }}</span>
            <div v-for="criterion in stage.criteria" :key="criterion.id">
              <code>{{ criterion.id }}</code><span>{{ criterion.description }}</span><em>{{ criterion.verificationMode }}</em>
              <b :class="criterion.machineCovered ? 'matrix-pass' : 'matrix-muted'">{{ criterion.machineCovered ? `机器：验收器 ${criterion.verifierIndexes.map(index => index + 1).join(', ')}` : '机器：不适用' }}</b>
              <b :class="criterion.judgePlanned ? 'matrix-planned' : 'matrix-muted'">{{ criterion.judgePlanned ? 'AI：计划评审' : 'AI：不适用' }}</b>
            </div>
            <div class="matrix-verifiers"><small v-for="verifier in stage.verifiers" :key="verifier.index">#{{ verifier.index + 1 }} {{ verifier.category }} · {{ verifier.reason }}</small></div>
          </div>
        </section>
        <div v-if="!isFinalReview" class="candidate-readonly" aria-label="只读候选 LoopSpec"><p>{{ designerSession?.candidate?.detail }}</p><pre v-if="candidateJson">{{ candidateJson }}</pre><div v-else class="candidate-empty"><Icon icon="lucide:braces" />完成当前包设计与校验后，这里会显示最后有效候选。</div></div>
        <LoopSpecEditor v-else v-model="editorValue" class="spec-editor" aria-label="LoopSpec 中文结构化编辑器">
          <template #after-stages><ExecutionAcceptancePanel :source="editorValue" /></template>
        </LoopSpecEditor>
        <LayeredErrorPanel v-if="fieldError" :error="fieldError" style="margin-top: 12px" />
        <div v-if="isFinalReview && draft.status !== 'CONFIRMED'" class="final-review-action">
          <div><strong>设计已进入总体确认</strong><span>{{ autoModeActive ? '全自动模式将确认设计、创建任务并请求开始执行。' : '确认后只创建一个待开始任务，仍需在任务页单独点击开始执行。' }}</span></div>
          <el-button type="primary" size="large" :loading="busy" :disabled="!confirmationReady || autoModeActive" @click="confirm"><Icon icon="lucide:circle-check-big" />{{ autoModeActive ? '等待全自动确认并启动' : '确认设计并创建任务' }}</el-button>
        </div>
        <div class="spec-footer"><span class="tiny muted"><Icon icon="lucide:git-branch" /> Git 项目切换原目录任务分支；无 HEAD 项目直接执行</span><time class="mono tiny" :datetime="draft.updatedAt">{{ formatDateTime(draft.updatedAt) }}</time></div>
      </article>
    </section>
  </main>
</template>

<style scoped>
.designer-onboarding { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 20px; width: min(900px, 100%); margin: 28px auto 0; padding: 28px; border-color: rgb(34 211 238 / 24%); background: linear-gradient(135deg, rgb(34 211 238 / 8%), rgb(139 92 246 / 7%)); }.designer-onboarding-icon { display: grid; width: 56px; height: 56px; place-items: center; border: 1px solid rgb(34 211 238 / 28%); border-radius: 15px; color: var(--color-accent-cyan); background: rgb(34 211 238 / 8%); }.designer-onboarding-icon svg { width: 25px; height: 25px; }.designer-onboarding h2 { margin: 4px 0 8px; font-size: 21px; text-wrap: balance; }.designer-onboarding p:last-child { max-width: 640px; margin: 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.7; }
.designer-start-page { width: min(1080px, 100%); margin: 10px auto 0; }
.designer-start-heading { display: flex; align-items: center; gap: 15px; margin: 0 0 18px 2px; }
.designer-start-mark { display: grid; flex: 0 0 auto; place-items: center; width: 46px; height: 46px; border: 1px solid rgb(34 211 238 / 24%); border-radius: 14px; color: var(--color-accent-cyan); background: linear-gradient(145deg, rgb(34 211 238 / 13%), rgb(139 92 246 / 12%)); box-shadow: 0 12px 34px rgb(0 0 0 / 22%); }
.designer-start-mark svg { width: 22px; height: 22px; }
.designer-start-heading h2 { margin: 0; color: var(--color-text-primary); font-size: 26px; font-weight: 720; letter-spacing: -.035em; }
.designer-start-heading > div > p:last-child { margin: 5px 0 0; color: var(--color-text-secondary); font-size: 12px; }
.designer-recovery-notice { display: flex; gap: 10px; margin-bottom: 14px; padding: 12px 14px; border: 1px solid rgb(245 158 11 / 36%); border-radius: 10px; color: #fbbf24; background: rgb(245 158 11 / 8%); }.designer-recovery-notice svg { flex: 0 0 auto; margin-top: 2px; }.designer-recovery-notice p { margin: 4px 0 0; color: var(--color-text-secondary); font-size: 11px; line-height: 1.55; }
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
.legacy-contract { color: #fbbf24 !important; }
.review-actions { display: flex; align-items: center; gap: 6px; }
.acceptance-matrix { margin: 12px 20px 0; padding: 12px; border: 1px solid rgb(34 211 238 / 22%); border-radius: 10px; background: rgb(8 47 73 / 12%); }
.acceptance-matrix > header { display: flex; justify-content: space-between; gap: 12px; font-size: 11px; }
.matrix-stage { display: grid; gap: 6px; margin-top: 10px; }
.matrix-stage > span { color: var(--color-text-muted); font: 9px var(--font-code); }
.matrix-stage > div:not(.matrix-verifiers) { display: grid; grid-template-columns: 60px minmax(0,1fr) 58px auto auto; gap: 8px; align-items: center; padding: 7px; border-radius: 7px; background: rgb(2 6 23 / 35%); font-size: 9px; }
.matrix-stage em { color: var(--color-accent-ai); font: 700 8px var(--font-code); font-style: normal; }
.matrix-stage b { font-weight: 650; }
.matrix-pass { color: #86efac; }
.matrix-fail { color: #fca5a5; }
.matrix-planned { color: #c4b5fd; }
.matrix-muted { color: var(--color-text-muted); }
.matrix-verifiers { display: flex; flex-wrap: wrap; gap: 6px; }
.matrix-verifiers small { padding: 4px 6px; border: 1px solid rgb(71 85 105 / 40%); border-radius: 6px; color: var(--color-text-muted); font: 8px var(--font-code); }
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
.designer-auto-create { display: flex; min-width: 0; align-items: center; gap: 18px; }.designer-auto-create > label { display: flex; min-width: 0; align-items: center; gap: 9px; color: var(--color-text-secondary); }.designer-auto-create > label > span { display: grid; gap: 2px; }.designer-auto-create strong { color: var(--color-text-primary); font-size: 10px; }.designer-auto-create small { color: var(--color-text-muted); font-size: 8px; }
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
.designer-layout { display: grid; align-items: start; grid-template-columns: minmax(0, 1.12fr) minmax(0, .88fr); gap: 18px; max-width: 100%; }
.designer-chat, .spec-panel { min-width: 0; }
.designer-chat { display: flex; align-self: start; flex-direction: column; min-height: 0; }
.spec-panel { min-height: 820px; }
.designer-steps { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; padding: 14px 20px 8px; }
.designer-steps span { display: flex; align-items: center; gap: 7px; min-width: 0; color: var(--color-text-muted); font-size: 9px; }
.designer-steps span::after { flex: 1; height: 1px; background: var(--color-border-default); content: ''; }
.designer-steps span:last-child::after { display: none; }
.designer-steps i { display: grid; flex: 0 0 auto; width: 20px; height: 20px; place-items: center; border: 1px solid var(--color-border-default); border-radius: 50%; font: 8px/1 var(--font-code); }
.designer-steps .active { color: var(--color-accent-cyan); }.designer-steps .active i { border-color: var(--color-accent-cyan); background: rgb(34 211 238 / 10%); }
.designer-steps .completed { color: var(--color-success); }.designer-steps .completed i { border-color: var(--color-success); background: rgb(34 197 94 / 10%); }
.designer-connection-strip { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 14px; margin: -4px 20px 10px; padding: 9px 11px; border: 1px solid rgb(71 85 105 / 42%); border-radius: 9px; color: var(--color-text-muted); background: rgb(2 6 23 / 30%); font: 9px/1.3 var(--font-code); }
.designer-connection-strip span { display: inline-flex; align-items: center; gap: 6px; }
.designer-connection-strip time { margin-left: auto; color: var(--color-text-muted); font-variant-numeric: tabular-nums; }
.designer-auto-status { display: flex; gap: 10px; margin: 0 20px 10px; padding: 10px 12px; border: 1px solid rgb(34 211 238 / 34%); border-radius: 9px; color: var(--color-accent-cyan); background: rgb(34 211 238 / 7%); }.designer-auto-status.blocked { border-color: rgb(245 158 11 / 42%); color: #fbbf24; background: rgb(245 158 11 / 8%); }.designer-auto-status.completed { border-color: rgb(34 197 94 / 38%); color: #4ade80; background: rgb(34 197 94 / 7%); }.designer-auto-status svg { flex: 0 0 auto; margin-top: 1px; }.designer-auto-status strong { font-size: 10px; }.designer-auto-status p { margin: 3px 0 0; color: var(--color-text-secondary); font-size: 9px; line-height: 1.5; }
.connection-dot { display: inline-block; flex: 0 0 auto; width: 6px; height: 6px; border-radius: 50%; background: var(--color-text-muted); }
.connection-dot.connecting { animation: live-pulse 1.2s ease-in-out infinite; }
.connection-dot.connected { background: var(--color-success); box-shadow: 0 0 9px rgb(34 197 94 / 60%); }
.connection-dot.reconnecting, .connection-dot.error { background: var(--color-session-warning); box-shadow: 0 0 9px rgb(245 158 11 / 45%); }
.designer-conversation { display: flex; flex-direction: column; min-height: 0; }
.work-package-rail { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; margin: 0 20px 8px; padding: 10px; border: 1px solid rgb(99 102 241 / 25%); border-radius: 10px; background: rgb(49 46 129 / 7%); }
.work-package-chip { min-width: 0; padding: 9px 10px; border: 1px solid rgb(71 85 105 / 45%); border-radius: 8px; background: rgb(2 6 23 / 35%); cursor: pointer; }
.work-package-chip.active { border-color: rgb(99 102 241 / 70%); box-shadow: inset 2px 0 #818cf8, 0 0 16px rgb(99 102 241 / 13%); }
.work-package-chip.selected { outline: 1px solid rgb(34 211 238 / 38%); }
.work-package-chip header { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: #a5b4fc; font: 8px/1.3 var(--font-code); }
.work-package-chip > strong { display: block; margin-top: 5px; overflow: hidden; color: var(--color-text-primary); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.work-package-chip > small { display: block; margin-top: 5px; color: var(--color-text-muted); font: 8px/1.5 var(--font-code); }
.work-package-chip.package-completed, .work-package-chip.package-succeeded { border-color: rgb(34 197 94 / 35%); }
.work-package-chip.package-approved { border-color: rgb(34 197 94 / 45%); background: rgb(34 197 94 / 6%); }
.work-package-chip.package-reviewing { border-color: rgb(34 211 238 / 45%); }
.work-package-chip.package-stale { border-color: rgb(245 158 11 / 42%); background: rgb(245 158 11 / 6%); }
.work-package-chip.package-failed { border-color: rgb(239 68 68 / 42%); }
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
.chat-decomposer { border-color: rgb(99 102 241 / 40%); background: rgb(99 102 241 / 8%); box-shadow: inset 2px 0 rgb(99 102 241 / 72%); }
.chat-decomposer .chat-role, .chat-decomposer .chat-avatar { color: #a5b4fc; }
.chat-designer { padding: clamp(16px, 2.2vw, 24px); border-color: rgb(139 92 246 / 28%); background: radial-gradient(circle at 8% 0, rgb(139 92 246 / 10%), transparent 32%), rgb(7 11 20 / 68%); box-shadow: inset 2px 0 rgb(139 92 246 / 55%), 0 14px 38px rgb(0 0 0 / 13%); }
.chat-designer .chat-message-header { margin-bottom: 18px; padding-bottom: 12px; border-bottom: 1px solid rgb(139 92 246 / 16%); }
.chat-designer .chat-role, .chat-designer .chat-avatar { color: #c4b5fd; }
.chat-compiler { border-color: rgb(34 211 238 / 34%); background: rgb(34 211 238 / 7%); box-shadow: inset 2px 0 rgb(34 211 238 / 62%); }
.chat-compiler .chat-role, .chat-compiler .chat-avatar { color: var(--color-accent-cyan); }
.chat-validator { border-color: rgb(34 197 94 / 30%); background: rgb(34 197 94 / 7%); box-shadow: inset 2px 0 rgb(34 197 94 / 58%); }
.chat-validator .chat-role, .chat-validator .chat-avatar { color: #86efac; }
.chat-validator.validator-retryable_error { border-color: rgb(245 158 11 / 38%); background: rgb(245 158 11 / 8%); box-shadow: inset 2px 0 rgb(245 158 11 / 65%); }
.chat-validator.validator-retryable_error .chat-role, .chat-validator.validator-retryable_error .chat-avatar { color: #fbbf24; }
.chat-validator.validator-normalized { border-color: rgb(34 211 238 / 34%); background: rgb(8 145 178 / 8%); box-shadow: inset 2px 0 rgb(34 211 238 / 62%); }
.chat-validator.validator-normalized .chat-role, .chat-validator.validator-normalized .chat-avatar { color: #67e8f9; }
.chat-validator.validator-terminal_error { border-color: rgb(239 68 68 / 42%); background: rgb(239 68 68 / 9%); box-shadow: inset 2px 0 rgb(239 68 68 / 68%); }
.chat-validator.validator-terminal_error .chat-role, .chat-validator.validator-terminal_error .chat-avatar { color: #fca5a5; }
.chat-live { position: relative; border-color: rgb(34 211 238 / 30%); box-shadow: 0 14px 38px rgb(0 0 0 / 13%), inset 2px 0 rgb(34 211 238 / 55%); }
.stream-caret { display: inline-block; width: 7px; height: 14px; margin: 4px 0 -2px 3px; background: var(--color-accent-cyan); animation: stream-blink .85s steps(1) infinite; box-shadow: 0 0 8px rgb(34 211 238 / 55%); }
.chat-system { padding-block: 10px; border-style: dashed; background: rgb(7 11 20 / 25%); }
.chat-system .chat-avatar { border-color: var(--color-border-default); color: var(--color-text-muted); background: transparent; }
.chat-system .chat-role { color: var(--color-text-secondary); }
.chat-compiler .plain-message-content, .chat-validator .plain-message-content { margin-top: 2px; }
.plain-message-content { margin: 7px 0 0; color: var(--color-text-primary); font-size: 12px; line-height: 1.65; white-space: pre-wrap; }
.thinking-message { position: relative; display: flex; align-items: center; gap: 14px; margin: 14px 0; padding: 17px 18px; overflow: hidden; border: 1px solid rgb(139 92 246 / 28%); border-radius: 12px; background: linear-gradient(100deg, rgb(139 92 246 / 9%), rgb(34 211 238 / 5%), rgb(139 92 246 / 9%)); background-size: 220% 100%; box-shadow: 0 12px 36px rgb(0 0 0 / 12%); animation: thinking-sheen 3s ease-in-out infinite; }
.thinking-message::after { position: absolute; inset: auto 16px 0; height: 1px; background: linear-gradient(90deg, transparent, rgb(34 211 238 / 55%), transparent); content: ""; animation: thinking-scan 2.2s ease-in-out infinite; }
.thinking-orbit { position: relative; display: grid; flex: 0 0 auto; place-items: center; width: 38px; height: 38px; border: 2px solid rgb(139 92 246 / 18%); border-top-color: #a78bfa; border-right-color: var(--color-accent-cyan); border-radius: 50%; box-shadow: 0 0 18px rgb(139 92 246 / 18%); animation: thinking-spin 1s linear infinite; }
.thinking-orbit span { width: 8px; height: 8px; border-radius: 50%; background: linear-gradient(135deg, #a78bfa, var(--color-accent-cyan)); box-shadow: 0 0 12px rgb(34 211 238 / 55%); }
.thinking-compiler { border-color: rgb(34 211 238 / 38%); background: linear-gradient(100deg, rgb(34 211 238 / 10%), rgb(8 47 73 / 5%), rgb(34 211 238 / 10%)); }
.thinking-decomposer { border-color: rgb(99 102 241 / 42%); background: linear-gradient(100deg, rgb(99 102 241 / 11%), rgb(49 46 129 / 5%), rgb(99 102 241 / 11%)); }
.thinking-validator { border-color: rgb(34 197 94 / 36%); background: linear-gradient(100deg, rgb(34 197 94 / 9%), rgb(20 83 45 / 5%), rgb(34 197 94 / 9%)); }
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
.scope-primary-action { display: flex; min-width: 0; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 12px; margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--color-border-default); }
.scope-primary-action span { margin-right: auto; color: var(--color-text-muted); font-size: 9px; }
.candidate-sync { padding: 5px 8px; border: 1px solid var(--color-border-default); border-radius: 999px; color: var(--color-text-muted); font: 8px/1 var(--font-code); }
.candidate-sync.sync-syncing { color: var(--color-accent-cyan); }.candidate-sync.sync-synced { color: var(--color-success); }.candidate-sync.sync-failed { color: var(--color-session-warning); }
.candidate-readonly { margin: 0 20px 20px; overflow: hidden; border: 1px solid var(--color-border-default); border-radius: 10px; background: rgb(2 6 23 / 48%); }
.candidate-readonly > p { margin: 0; padding: 10px 12px; border-bottom: 1px solid var(--color-border-default); color: var(--color-text-muted); font-size: 9px; }
.candidate-readonly pre { max-height: 660px; margin: 0; padding: 14px; overflow: auto; color: var(--color-text-secondary); font: 9px/1.65 var(--font-code); white-space: pre-wrap; }
.candidate-empty { display: grid; place-items: center; gap: 10px; min-height: 260px; padding: 30px; color: var(--color-text-muted); font-size: 10px; text-align: center; }
.candidate-empty svg { width: 24px; height: 24px; }
.artifact-preview { margin: 0 20px 16px; padding: 14px; border: 1px solid rgb(34 211 238 / 28%); border-radius: 10px; background: rgb(8 47 73 / 20%); }
.artifact-preview header { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 10px; color: var(--color-accent-cyan); }
.artifact-preview p, .artifact-preview small { color: var(--color-text-secondary); }
.spec-meta, .spec-footer { display: flex; align-items: center; gap: 12px; padding: 0 20px 14px; color: var(--color-text-secondary); font-family: var(--font-code); font-size: 10px; }
.spec-meta span { display: inline-flex; align-items: center; gap: 5px; }
.spec-editor { display: block; }
.spec-editor :deep(.acceptance-panel) { margin: 0; }
.spec-footer { justify-content: space-between; padding-top: 14px; border-top: 1px solid var(--color-border-default); }
.spec-footer span { display: inline-flex; align-items: center; gap: 5px; }
.final-review-action { display: flex; min-width: 0; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin: 16px 20px; padding: 14px; border: 1px solid rgb(34 197 94 / 35%); border-radius: 10px; background: rgb(34 197 94 / 7%); }
.final-review-action > div { display: grid; min-width: 0; flex: 1; gap: 4px; }.final-review-action strong { color: var(--color-text-primary); font-size: 11px; }.final-review-action span { color: var(--color-text-secondary); font-size: 9px; line-height: 1.5; }
.designer-session-alert { display: flex; gap: 10px; margin: 0 20px 8px; padding: 12px; border: 1px solid rgb(245 158 11 / 35%); border-radius: 10px; background: rgb(245 158 11 / 9%); color: var(--color-status-session); }
.designer-session-alert svg { flex: 0 0 auto; margin-top: 2px; }
.designer-session-alert p { margin: 5px 0; color: var(--color-text-primary); font-size: 11px; line-height: 1.5; }
.restart-designer-inline { display: flex; margin-top: 10px; }
.recovery-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.active-role { color: var(--color-text-secondary); }
.active-role svg { color: var(--color-accent-cyan); }
.designer-state-actions { display: flex; align-items: center; gap: 8px; }
.designer-auto-switch { display: flex; align-items: center; gap: 8px; padding-right: 8px; border-right: 1px solid var(--color-border-default); }.designer-auto-switch > span { display: grid; text-align: right; }.designer-auto-switch strong { color: var(--color-text-primary); font-size: 9px; }.designer-auto-switch small { color: var(--color-text-muted); font: 7px/1.2 var(--font-code); }
.task-profile-card { display: grid; gap: 8px; margin: 0 20px 10px; padding: 12px; border: 1px solid rgb(34 211 238 / 24%); border-radius: 10px; background: rgb(34 211 238 / 5%); }
.task-profile-card header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }.task-profile-card header > div { display: grid; gap: 3px; }.task-profile-card strong { color: var(--color-text-primary); font-size: 11px; }.task-profile-card header span, .task-profile-card p { margin: 0; color: var(--color-text-secondary); font: 8px/1.5 var(--font-code); }.task-profile-card b { color: var(--color-success); font: 8px/1 var(--font-code); }.task-profile-card b.warning { color: var(--color-session-warning); }
.profile-evidence { display: flex; flex-wrap: wrap; gap: 5px; }.profile-evidence span { padding: 3px 6px; border: 1px solid var(--color-border-default); border-radius: 999px; color: var(--color-text-muted); font: 7px/1 var(--font-code); }.profile-override { display: grid; grid-template-columns: 1fr 1fr auto; gap: 8px; }.report-card { border-color: rgb(34 197 94 / 28%); background: rgb(34 197 94 / 5%); }

@media (max-width: 1180px) {
  .designer-start-layout { grid-template-columns: 1fr; }
  .project-context-card { display: none; }
  .designer-layout { grid-template-columns: 1fr; }
  .spec-panel { min-height: 720px; }
}

@media (max-width: 680px) {
  .designer-onboarding { grid-template-columns: 1fr; margin-top: 8px; padding: 22px; }
  .designer-onboarding :deep(.el-button) { width: 100%; }
  .designer-start-heading { align-items: flex-start; }
  .composer-project-row { align-items: stretch; flex-direction: column; }
  .composer-project-row :deep(.el-select) { width: 100%; }
  .brief-editor { padding-inline: 16px; }
  .brief-template-row { padding-inline: 16px; }
  .draft-goal-input :deep(.el-textarea__inner) { min-height: 330px !important; }
  .draft-create-actions, .compose-actions { align-items: stretch; flex-direction: column; }
  .designer-auto-create { align-items: flex-start; flex-direction: column; gap: 8px; }
  .composer-submit { justify-content: space-between; width: 100%; }
  .create-draft-button, .compose-actions :deep(.el-button) { width: 100%; }
  .designer-message-input :deep(.el-textarea__inner) { min-height: 260px !important; }
  .final-review-action :deep(.el-button) { width: 100%; margin-left: 0; }
  .designer-connection-strip { align-items: flex-start; flex-direction: column; }
  .designer-connection-strip time { margin-left: 0; }
  .profile-override { grid-template-columns: 1fr; }
}
</style>
