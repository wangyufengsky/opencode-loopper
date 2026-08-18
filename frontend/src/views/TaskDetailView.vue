<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import StageRail from '@/components/StageRail.vue'
import AttemptTimeline from '@/components/AttemptTimeline.vue'
import LayeredErrorPanel from '@/components/LayeredErrorPanel.vue'
import JudgeReviewCard from '@/components/JudgeReviewCard.vue'
import DirtyWorkspaceDialog from '@/components/DirtyWorkspaceDialog.vue'
import TaskDecisionPanel from '@/components/TaskDecisionPanel.vue'
import { api } from '@/api/client'
import { useTaskStore } from '@/stores/taskStore'
import type { Attempt, ErrorEvent, TaskPublicationStatus, TaskQueueStatus } from '@/types/domain'

const SessionMonitorPanel = defineAsyncComponent(() => import('@/components/SessionMonitorPanel.vue'))
const TaskAuditEvidencePanel = defineAsyncComponent(() => import('@/components/TaskAuditEvidencePanel.vue'))
const TaskPublicationActions = defineAsyncComponent(() => import('@/components/TaskPublicationActions.vue'))

const route = useRoute()
const router = useRouter()
const store = useTaskStore()
const id = computed(() => route.params.id as string)
const task = computed(() => store.tasks.find((item) => item.id === id.value))
const aiNotices = computed(() => store.taskNotices?.[id.value] ?? [])
const isDirectExecution = computed(() => task.value?.branch === 'DIRECT')
const attempts = computed<Attempt[]>(() => task.value?.attempts ?? task.value?.stages?.flatMap((stage) => stage.attempts) ?? [])
const waitingForWorkspaceCleanup = computed(() => task.value?.status === 'WAITING_INPUT'
  && task.value.waitingReasonCode === 'SOURCE_BRANCH_WORKSPACE_DIRTY')
const sessionErrors = computed<ErrorEvent[]>(() => (task.value?.errors ?? attempts.value.flatMap((attempt) => attempt.errors)).filter((error) => error.layer === 'SESSION'))
const taskErrors = computed<ErrorEvent[]>(() => (task.value?.errors ?? [])
  .filter((error) => error.layer === 'TASK')
  .filter((error) => error.code !== 'SOURCE_BRANCH_WORKSPACE_DIRTY' || waitingForWorkspaceCleanup.value))
const judges = computed(() => task.value?.judges ?? [])
const artifacts = computed(() => task.value?.artifacts ?? store.artifacts.filter((artifact) => artifact.taskId === id.value))
const verificationRows = computed(() => attempts.value.flatMap((attempt) => attempt.verifiers))
const changedFiles = computed(() => new Set(verificationRows.value.flatMap((verifier) => {
  const paths = verifier.evidence?.changedPaths
  return Array.isArray(paths) ? paths.filter((path): path is string => typeof path === 'string') : []
})).size)
const passedVerifications = computed(() => verificationRows.value.filter((verifier) => verifier.status === 'PASS').length)
const judgeRetrying = ref(false)
const loopRetrying = ref(false)
const reworking = ref(false)
const dirtyWorkspaceDialogOpen = ref(false)
const queueStatus = ref<TaskQueueStatus>()
const queueLoading = ref(false)
const queueReconciling = ref(false)
const queueError = ref('')
const publicationState = ref<TaskPublicationStatus['deliveryState']>('NOT_STARTED')
const publicationEligible = computed(() => task.value?.status === 'SUCCEEDED'
  || (['AWAITING_DECISION', 'COMPLETED'].includes(task.value?.status ?? '')
    && task.value?.executionResult === 'SUCCEEDED'))
const clock = ref(Date.now())
let clockTimer: ReturnType<typeof setInterval> | undefined
const retryRemainingSeconds = computed(() => task.value?.retryDueAt
  ? Math.max(0, Math.ceil((Date.parse(task.value.retryDueAt) - clock.value) / 1000)) : 0)
const retryCauseLabel = computed(() => ({ RATE_LIMIT: '请求限流', SESSION: 'Session 错误', VERIFICATION: '验证失败' }[task.value?.retryCause ?? 'SESSION']))
watch(id, () => { publicationState.value = 'NOT_STARTED' })
const deterministicAccepted = computed(() => Boolean(task.value?.stages?.length) && task.value!.stages!.every((stage) => stage.status === 'SUCCEEDED'))
const latestJudge = (role: 'REQUIREMENT' | 'RISK') => judges.value
  .filter((judge) => judge.role === role)
  .sort((left, right) => right.ordinal - left.ordinal)[0]
const passedJudges = computed(() => ['REQUIREMENT', 'RISK'].filter((role) => latestJudge(role as 'REQUIREMENT' | 'RISK')?.verdict === 'PASS').length)
const doubleReviewApproved = computed(() => ['REQUIREMENT', 'RISK'].every((role) => latestJudge(role as 'REQUIREMENT' | 'RISK')?.verdict === 'PASS'))
const currentJudges = computed(() => ['REQUIREMENT', 'RISK']
  .map((role) => latestJudge(role as 'REQUIREMENT' | 'RISK'))
  .filter((judge) => judge !== undefined))
const verifierErrors = computed<ErrorEvent[]>(() => (task.value?.errors ?? attempts.value.flatMap((attempt) => attempt.errors))
  .filter((error) => error.layer === 'VERIFICATION')
  .filter((error) => !error.code.startsWith('JUDGE_') || (task.value?.status === 'WAITING_INPUT' && !doubleReviewApproved.value)))
const canRetryJudges = computed(() => deterministicAccepted.value
  && publicationState.value !== 'MERGED'
  && (task.value?.status === 'WAITING_INPUT' || (task.value?.status === 'SUCCEEDED' && !doubleReviewApproved.value)))
const canRetryLoop = computed(() => task.value?.status === 'WAITING_INPUT'
  && !deterministicAccepted.value && task.value.loopRetryAvailable === true)
const canCancelDirectly = computed(() => task.value?.status === 'PENDING_START'
  || task.value?.status === 'QUEUED'
  || task.value?.status === 'READY'
  || (task.value?.status === 'WAITING_INPUT' && !waitingForWorkspaceCleanup.value))
const judgeActionLabel = computed(() => judges.value.length ? '重新发起双评审' : '启动双评审')
const canRework = computed(() => !isDirectExecution.value
  && !waitingForWorkspaceCleanup.value
  && ['WAITING_INPUT', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(task.value?.status ?? ''))
const nextAction = computed(() => {
  if (!task.value) return ''
  if (task.value.status === 'AWAITING_DECISION') return task.value.executionResult === 'SUCCEEDED'
    ? '本轮执行已成功，但任务尚未终结。发布、继续优化、派生任务、只读审计或取消都需要你的明确选择。'
    : '本轮执行已失败，但任务尚未终结。半成品已冻结，可继续当前任务、派生继承、全部重做、只读审计或取消。'
  if (task.value.status === 'COMPLETED') return '结果已由用户确认，任务进入终态。'
  if (task.value.status === 'SUPERSEDED') return '当前任务已由新的接续任务替代，后续实现、验证和发布聚焦新任务。'
  if (task.value.status === 'SUCCEEDED') return publicationState.value === 'MERGED' ? '代码已推送并由 GitLab 确认合并，原任务交付状态不可再改变。' : isDirectExecution.value ? '检查原项目目录中的变更并决定后续发布方式。' : '检查变更摘要，然后提交并发布当前分支。'
  if (task.value.status === 'FAILED') return '先查看最上方错误与失败验证，再根据证据重新设计或新建任务。'
  if (task.value.status === 'CANCELLED') return '任务已取消；执行目录和证据仍保留，可据此新建设计。'
  if (task.value.status === 'PENDING_START') return '计划已经确认，但尚未申请项目写租约或创建任务分支。点击“开始执行”后才会进入队列并准备执行环境。'
  if (task.value.status === 'QUEUED') return '任务正在等待项目写租约；Loopper 会自动检查终态持有者，也可在下方手动重新检查。取消只会移除此任务自己的队列项。'
  if (task.value.status === 'RETRY_WAIT') return `${retryCauseLabel.value}，将在约 ${retryRemainingSeconds.value} 秒后自动创建全新 Session。`
  if (waitingForWorkspaceCleanup.value) return '逐项处理源分支中的未提交文件；重新检查确认干净后，Loopper 才会创建任务分支。'
  if (task.value.status === 'WAITING_INPUT' && canRetryJudges.value) return '查看未通过或异常的评审证据；补齐条件后可重新发起需求 / 风险双评审。'
  if (task.value.status === 'WAITING_INPUT' && canRetryLoop.value) return '循环已因重复失败或重试策略暂停。检查结构化交接后，可明确确认再执行一轮全新 Session。'
  if (task.value.status === 'WAITING_INPUT') return '回答页面中的待处理问题，任务将从当前阶段继续。'
  if (task.value.status === 'PAUSED') return '确认当前状态后点击“继续”，任务会从原阶段恢复。'
  if (task.value.status === 'READY') return '执行目录已准备完成，Loopper 正在自动启动任务；若不再执行，也可直接取消并保留任务证据。'
  return '任务正在推进；实时会话和阶段状态会自动更新。'
})

async function load() {
  await store.loadTask(id.value)
  await loadQueue()
  if (!store.usingDemo) store.watchTask(id.value)
}
onMounted(() => { clockTimer = setInterval(() => { clock.value = Date.now() }, 1000); void load() })
watch(id, load)
watch(() => task.value?.status, () => { void loadQueue() })
watch(waitingForWorkspaceCleanup, (waiting) => { dirtyWorkspaceDialogOpen.value = waiting }, { immediate: true })
onBeforeUnmount(() => { if (clockTimer) clearInterval(clockTimer); store.stopWatching() })

const queueReleaseDetail = computed(() => {
  const reason = queueStatus.value?.releaseReason
  if (!reason) return '等待当前持有者完成安全释放检查。'
  const reasons: Record<string, string> = {
    SOURCE_BRANCH_WORKSPACE_DIRTY: '当前持有者的工作区有未提交或未跟踪文件。',
    SESSION_WRITER_UNCONFIRMED: '当前写入 Session 尚未确认停止。',
    DIRECT_ROOT_FINGERPRINT_MISMATCH: '项目目录指纹已变化，系统拒绝自动转移。',
    DIRECT_WORKSPACE_UNAVAILABLE: '项目工作区当前不可访问。',
    TASK_SOURCE_BRANCH_UNAVAILABLE: '任务开始前的源分支已不可用。',
    TASK_SOURCE_BRANCH_RESTORE_MISMATCH: '登记目录当前不在该任务的任务分支上。',
    TASK_SOURCE_BRANCH_RESTORE_DIRTY: '任务分支仍有未提交文件。',
    TASK_SOURCE_BRANCH_RESTORE_FAILED: '当前分支无法安全恢复到任务开始前的源分支。',
    TASK_SOURCE_BRANCH_RESTORE_UNCONFIRMED: '分支切换结果无法确认。',
  }
  return reasons[reason] ?? reason
})

async function loadQueue() {
  queueError.value = ''
  if (store.usingDemo || task.value?.status !== 'QUEUED') {
    queueStatus.value = undefined
    return
  }
  queueLoading.value = true
  try {
    queueStatus.value = await api.getTaskQueue(id.value)
  } catch (error) {
    queueError.value = error instanceof Error ? error.message : '队列状态加载失败'
  } finally {
    queueLoading.value = false
  }
}

async function reconcileQueue() {
  if (!queueStatus.value?.reconcileAvailable || queueReconciling.value) return
  queueReconciling.value = true
  queueError.value = ''
  try {
    queueStatus.value = await api.reconcileTaskQueue(id.value)
    await store.loadTask(id.value)
    await loadQueue()
  } catch (error) {
    const message = error instanceof Error ? error.message : '安全释放检查失败'
    try { await store.loadTask(id.value) } catch { /* Keep the last known Task projection. */ }
    if (task.value?.status === 'QUEUED') {
      try { queueStatus.value = await api.getTaskQueue(id.value) } catch { /* Keep the last known queue projection. */ }
    }
    queueError.value = message
  } finally {
    queueReconciling.value = false
  }
}

async function confirmCancel() {
  if (!task.value) return
  const pending = task.value.status === 'PENDING_START'
  const queued = task.value.status === 'QUEUED'
  const ready = task.value.status === 'READY'
  try {
    await ElMessageBox.confirm(
      pending
        ? '任务尚未请求执行，也没有申请项目写租约、创建任务分支或切换工作区。取消只会终止这条待开始任务。'
        : queued
        ? '将从等待队列中移除此任务并标记为已取消；当前正在执行的任务和项目写租约不会受影响。取消后可从任务列表归档或删除。'
        : ready
          ? '任务尚未开始执行。取消后会保留任务分支、执行目录和已有证据，并按现有安全规则释放项目写租约。'
        : '将中止当前会话、停止验证器，并保留执行目录和证据。此操作无法自动恢复。',
      pending ? '取消待开始任务？' : queued ? '取消排队任务？' : ready ? '取消待执行任务？' : '取消当前任务？',
      { type: 'warning', confirmButtonText: '取消任务', cancelButtonText: pending ? '保留待开始' : queued ? '继续排队' : ready ? '保留待执行' : '继续执行' },
    )
    await store.updateTask(id.value, 'cancel')
  } catch {
    // User kept the running task.
  }
}


async function confirmRetryJudges() {
  if (!canRetryJudges.value || judgeRetrying.value) return
  try {
    await ElMessageBox.confirm('将启动两个新的只读 OpenCode 评审 Session，可能产生模型用量；历史评审证据会完整保留。', `${judgeActionLabel.value}？`, { type: 'warning', confirmButtonText: judgeActionLabel.value, cancelButtonText: '暂不评审' })
    judgeRetrying.value = true
    await store.retryJudges(id.value)
  } catch {
    // User cancelled, or the store has exposed the backend error in its error state.
  } finally {
    judgeRetrying.value = false
  }
}

async function confirmRetryLoop() {
  if (!canRetryLoop.value || loopRetrying.value) return
  try {
    await ElMessageBox.confirm(
      '将依据最新结构化 Attempt 交接启动一个全新的可写 OpenCode Session。历史失败和工作区证据会保留；若结果仍无变化，Loopper 会再次暂停。',
      '确认继续一轮？',
      { type: 'warning', confirmButtonText: '继续一轮', cancelButtonText: '暂不继续' },
    )
    loopRetrying.value = true
    await store.retryWaitingLoop(id.value)
  } catch {
    // User cancelled, or the store exposed the backend error.
  } finally {
    loopRetrying.value = false
  }
}

async function confirmRework() {
  if (!canRework.value || reworking.value || !task.value) return
  try {
    await ElMessageBox.confirm(
      '将从此任务创建时的 Git 基线创建全新任务分支，并把登记的原项目目录切换到该分支后重新执行全部阶段。父任务、父分支和历史证据不会被修改。',
      '新分支重做任务？',
      { type: 'warning', confirmButtonText: '创建新分支并重做', cancelButtonText: '取消' },
    )
    reworking.value = true
    const childId = await store.reworkTask(id.value)
    if (childId) await router.push(`/tasks/${childId}`)
  } catch {
    // User cancelled, or the store exposed the backend error.
  } finally {
    reworking.value = false
  }
}
</script>

<template>
  <PageHeader eyebrow="任务 / 检视" :title="task?.title ?? '加载任务'" :title-tooltip="task?.goal || task?.title">
    <template #actions>
      <StatusBadge v-if="task" :status="task.status" />
      <el-button v-if="task?.hasDesignHistory" plain @click="router.push(`/tasks/${id}/design`)"><Icon icon="lucide:messages-square" />设计</el-button>
      <el-button v-if="task?.status === 'FAILED' || task?.status === 'CANCELLED'" type="primary" @click="router.push(`/tasks/${id}/recovery`)"><Icon icon="lucide:git-fork" />恢复</el-button>
      <el-button v-if="canRework" type="warning" plain :loading="reworking" @click="confirmRework"><Icon icon="lucide:git-branch-plus" />新分支重做</el-button>
      <el-button plain @click="router.push('/tasks')"><Icon icon="lucide:list" />全部任务</el-button>
      <el-button v-if="task?.status === 'PENDING_START'" type="primary" @click="store.updateTask(id, 'start')"><Icon icon="lucide:play" />开始执行</el-button>
      <template v-else-if="task?.status === 'RUNNING' || task?.status === 'VERIFYING' || task?.status === 'RETRY_WAIT'"><el-button plain @click="store.updateTask(id, 'pause')"><Icon icon="lucide:pause" />暂停</el-button><el-button plain type="danger" @click="confirmCancel"><Icon icon="lucide:square" />取消</el-button></template>
      <el-button v-else-if="task?.status === 'PAUSED'" type="primary" @click="store.updateTask(id, 'resume')"><Icon icon="lucide:play" />继续</el-button>
      <el-button v-if="canCancelDirectly" plain type="danger" @click="confirmCancel"><Icon icon="lucide:square" />取消任务</el-button>
      <el-button v-if="canRetryLoop" type="warning" :loading="loopRetrying" @click="confirmRetryLoop"><Icon icon="lucide:rotate-ccw" />继续一轮</el-button>
      <el-button v-if="canRetryJudges" type="warning" :loading="judgeRetrying" @click="confirmRetryJudges"><Icon icon="lucide:scan-eye" />{{ judgeActionLabel }}</el-button>
      <TaskPublicationActions v-if="publicationEligible && task" :task="task" :demo="store.usingDemo" @delivery-state="publicationState = $event" />
    </template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <section v-if="!task" class="card empty-state"><div><Icon icon="lucide:search-x" width="30" /><strong>未找到此任务</strong><p>它可能已被清理，或当前接口尚未返回该条记录。</p></div></section>
    <template v-else>
      <section class="task-overview card card-pad">
        <div v-if="task.status === 'PENDING_START'"><p class="eyebrow">执行环境尚未申请</p><span class="mono tiny muted">确认计划不会占用写租约或切换项目分支</span></div>
        <div v-else><p class="eyebrow">{{ isDirectExecution ? '直接执行' : '原项目任务分支' }}</p><span class="mono tiny muted">{{ isDirectExecution ? '原项目目录' : task.branch }} · {{ task.worktreePath }}</span></div>
        <div class="overview-meta"><span><b>{{ task.attemptCount }}</b> / {{ task.maxAttempts }} 次尝试</span><span v-if="store.streamState !== 'idle'" :class="['stream-state', store.streamState]">{{ store.streamState === 'connected' ? 'SSE 已连接' : 'SSE 重连中' }}</span></div>
      </section>
      <TaskDecisionPanel v-if="task.status === 'AWAITING_DECISION'" :task-id="task.id" @reload="load" @open-task="(taskId) => router.push(`/tasks/${taskId}`)" />
      <section v-if="task.status === 'SUPERSEDED' && task.successorTaskId" class="decision-successor card card-pad">
        <div><p class="eyebrow">SUCCESSOR TASK</p><h2 class="card-title">后续工作已转移到新任务</h2></div>
        <el-button type="primary" @click="router.push(`/tasks/${task.successorTaskId}`)"><Icon icon="lucide:arrow-up-right" />打开新任务</el-button>
      </section>
      <section v-if="task.status === 'RETRY_WAIT'" class="retry-wait-card card card-pad" role="status">
        <div><p class="eyebrow">RETRY_WAIT</p><h2 class="card-title">{{ retryCauseLabel }}</h2><p>第 {{ task.retryOrdinal || 1 }} 次退避，计划等待 {{ task.retryDelaySeconds || 0 }} 秒。</p></div>
        <strong class="retry-countdown mono">{{ retryRemainingSeconds }}s</strong>
      </section>
      <section class="result-summary card card-pad" aria-labelledby="result-summary-heading">
        <div class="result-copy"><p class="eyebrow">RESULT / NEXT STEP</p><h2 id="result-summary-heading" class="card-title">结果与下一步</h2><p>{{ nextAction }}</p></div>
        <dl class="result-metrics">
          <div><dt>文件变更</dt><dd>{{ changedFiles }}</dd></div>
          <div><dt>验证通过</dt><dd>{{ passedVerifications }} / {{ verificationRows.length }}</dd></div>
          <div><dt>评审通过</dt><dd>{{ passedJudges }} / 2</dd></div>
        </dl>
      </section>
      <div v-for="notice in aiNotices" :key="notice" class="ai-output-notice" role="status">
        <Icon icon="lucide:info" />{{ notice }}
      </div>
      <section v-if="task.status === 'QUEUED'" class="queue-blocker card card-pad" aria-labelledby="queue-blocker-heading">
        <div class="card-header">
          <div><p class="eyebrow">WORKSPACE LEASE</p><h2 id="queue-blocker-heading" class="card-title">当前在排谁</h2></div>
          <span v-if="queueStatus?.queuePosition" class="mono tiny muted">队列第 {{ queueStatus.queuePosition }} 位</span>
        </div>
        <p v-if="queueLoading" class="queue-copy muted">正在读取权威队列与租约状态…</p>
        <template v-else-if="queueStatus">
          <div class="queue-holder">
            <div>
              <span class="tiny muted">当前写租约持有者</span>
              <button v-if="queueStatus.holderTaskId" class="queue-holder-link" type="button" @click="router.push(`/tasks/${queueStatus.holderTaskId}`)">
                {{ queueStatus.holderTaskTitle || queueStatus.holderTaskId }}
              </button>
              <strong v-else>未找到持有者</strong>
            </div>
            <dl>
              <div><dt>任务状态</dt><dd>{{ queueStatus.holderTaskState || '未知' }}</dd></div>
              <div><dt>是否归档</dt><dd>{{ queueStatus.holderArchived ? '是' : '否' }}</dd></div>
              <div><dt>租约状态</dt><dd>{{ queueStatus.leaseState }}</dd></div>
            </dl>
          </div>
          <p class="queue-copy">{{ queueReleaseDetail }}</p>
          <el-button v-if="queueStatus.reconcileAvailable" type="primary" plain :loading="queueReconciling" @click="reconcileQueue">
            <Icon icon="lucide:refresh-cw" />重新检查并释放
          </el-button>
        </template>
        <p v-if="queueError" class="queue-error" role="alert">{{ queueError }}</p>
      </section>
      <section v-if="task.workPackages?.length" class="card card-pad package-progress" aria-labelledby="package-progress-heading">
        <div class="card-header"><div><p class="eyebrow">WORK PACKAGE EXECUTION</p><h2 id="package-progress-heading" class="card-title">工作包与独立尝试池</h2></div><span class="tiny muted">全部包完成后仅运行一组 Requirement / Risk Judge</span></div>
        <div class="package-progress-grid">
          <article v-for="item in task.workPackages" :key="item.id" :class="['package-progress-card', `is-${item.status.toLowerCase()}`]">
            <header><strong>{{ item.id }}</strong><StatusBadge :status="item.status" /></header>
            <p>{{ item.completedStages }} / {{ item.stageCount }} 个阶段完成</p>
            <footer><span>尝试池</span><b>{{ item.attemptCount }} / {{ item.attemptLimit }}</b></footer>
          </article>
        </div>
      </section>
      <section v-if="task.stages?.length" class="card card-pad" style="margin-top: 16px"><div class="card-header"><div><h2 class="card-title">阶段进度</h2></div></div><StageRail :stages="task.stages" /></section>
      <section v-for="error in verifierErrors" :key="error.id" style="margin-top: 16px"><LayeredErrorPanel :error="error" :judges="currentJudges" /></section>
      <section v-for="error in sessionErrors" :key="error.id" style="margin-top: 16px"><LayeredErrorPanel :error="error" /></section>
      <section v-for="error in taskErrors" :key="error.id" style="margin-top: 16px"><LayeredErrorPanel :error="error" /></section>
      <SessionMonitorPanel :task-id="task.id" />
      <section v-if="store.auditErrors?.[id]" class="error-panel error-panel-verification" role="status">
        <Icon class="error-panel-icon" icon="lucide:database-zap" /><div><h3>审计信息暂未加载</h3><p>{{ store.auditErrors?.[id] }}。任务核心状态仍可正常查看。</p><el-button size="small" plain @click="store.loadTaskAudit?.(id)">重试审计加载</el-button></div>
      </section>
      <section v-if="judges.length || task.status === 'JUDGING' || task.status === 'WAITING_INPUT' || canRetryJudges" id="judge-review" class="card card-pad judge-section" style="margin-top: 16px" aria-labelledby="judge-heading">
        <div class="card-header"><div><p class="eyebrow">独立只读评审</p><h2 id="judge-heading" class="card-title">需求 / 风险双评审</h2></div><StatusBadge :status="task.status" /></div>
        <p v-if="!judges.length" class="judge-empty">确定性验收已通过，但尚无需求 / 风险评审记录。可从页面顶部启动双评审。</p>
        <div class="judge-grid">
          <JudgeReviewCard v-for="judge in judges" :key="judge.id" :judge="judge" />
        </div>
      </section>
      <section class="task-detail-grid" style="margin-top: 16px">
        <article class="card card-pad"><div class="card-header"><div><p class="eyebrow">尝试历史</p><h2 class="card-title">尝试与会话</h2></div><span class="mono tiny muted">{{ attempts.length }} 条记录</span></div><AttemptTimeline :attempts="attempts" /></article>
        <TaskAuditEvidencePanel :task-id="task.id" :attempts="attempts" :artifacts="artifacts" :direct-execution="isDirectExecution" />
      </section>
    </template>
  </main>
  <DirtyWorkspaceDialog v-if="task" v-model="dirtyWorkspaceDialogOpen" :task-id="task.id" />
</template>

<style scoped>
.task-overview { display: flex; align-items: center; justify-content: space-between; gap: 18px; min-width: 0; }.task-overview > div:first-child { min-width: 0; }.task-overview > div:first-child > span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.overview-meta { display: flex; flex: 0 0 auto; align-items: center; gap: 15px; color: var(--color-text-secondary); font-family: var(--font-code); font-size: 11px; }.overview-meta b { color: var(--color-text-primary); }.stream-state { display: inline-flex; align-items: center; gap: 6px; }.stream-state::before { width: 7px; height: 7px; border-radius: 50%; background: currentColor; content: ""; }.stream-state.connected { color: var(--color-success); }.stream-state.reconnecting { color: var(--color-session-warning); }.task-detail-grid { display: grid; grid-template-columns: minmax(300px, .77fr) minmax(500px, 1.23fr); gap: 16px; }@media (max-width: 1320px) { .task-detail-grid { grid-template-columns: minmax(290px, .7fr) minmax(470px, 1.3fr); } }@media (max-width: 1050px) { .task-detail-grid { grid-template-columns: 1fr; } }
.result-summary { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(360px, .8fr); align-items: center; gap: 22px; margin-top: 16px; border-color: rgb(34 211 238 / 19%); background: linear-gradient(125deg, rgb(34 211 238 / 6%), rgb(139 92 246 / 4%)); }.result-copy p:last-child { max-width: 720px; margin: 8px 0 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.65; }.result-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin: 0; }.result-metrics div { padding: 12px; border: 1px solid var(--color-border-default); border-radius: 9px; background: rgb(7 12 22 / 50%); }.result-metrics dt { color: var(--color-text-tertiary); font-size: 9px; }.result-metrics dd { margin: 6px 0 0; color: var(--color-text-primary); font: 700 14px/1 var(--font-code); font-variant-numeric: tabular-nums; }
.ai-output-notice { display: flex; align-items: center; gap: 8px; margin-top: 10px; padding: 9px 12px; border: 1px solid rgb(34 211 238 / 28%); border-radius: 8px; color: #a5f3fc; background: rgb(8 145 178 / 8%); font-size: 11px; }
.retry-wait-card { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-top: 16px; border-color: rgb(245 158 11 / 36%); background: linear-gradient(120deg, rgb(245 158 11 / 8%), rgb(15 23 42 / 25%)); }.retry-wait-card p:last-child { margin: 8px 0 0; color: var(--color-text-secondary); font-size: 11px; }.retry-countdown { color: var(--color-session-warning); font-size: 24px; font-variant-numeric: tabular-nums; }
.decision-successor { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 16px; border-color: rgb(34 211 238 / 28%); }
.queue-blocker { margin-top: 16px; border-color: rgb(245 158 11 / 30%); background: linear-gradient(120deg, rgb(245 158 11 / 7%), rgb(15 23 42 / 25%)); }.queue-holder { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 20px; margin-top: 12px; }.queue-holder > div:first-child { display: grid; align-content: start; gap: 7px; min-width: 0; }.queue-holder-link { width: fit-content; max-width: 100%; padding: 0; overflow: hidden; border: 0; background: transparent; color: var(--color-accent); font: inherit; font-weight: 700; text-align: left; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; }.queue-holder dl { display: grid; grid-template-columns: repeat(3, minmax(82px, 1fr)); gap: 8px; margin: 0; }.queue-holder dl div { padding: 9px 11px; border: 1px solid var(--color-border-default); border-radius: 8px; background: rgb(2 6 23 / 35%); }.queue-holder dt { color: var(--color-text-tertiary); font-size: 9px; }.queue-holder dd { margin: 5px 0 0; color: var(--color-text-primary); font: 650 10px/1.2 var(--font-code); }.queue-copy { margin: 12px 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.6; }.queue-error { margin: 12px 0 0; color: var(--color-danger); font-size: 12px; }
.judge-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }@media (max-width: 960px) { .judge-grid { grid-template-columns: 1fr; } }
.judge-empty { margin: 0 0 12px; color: var(--color-text-secondary); font-size: 12px; line-height: 1.6; }
.package-progress { margin-top: 16px; border-color: rgb(99 102 241 / 22%); }
.package-progress-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 10px; margin-top: 12px; }
.package-progress-card { padding: 12px; border: 1px solid var(--color-border-default); border-radius: 10px; background: rgb(2 6 23 / 30%); }
.package-progress-card header, .package-progress-card footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.package-progress-card header > strong { color: #a5b4fc; font: 750 10px/1 var(--font-code); }
.package-progress-card p { margin: 11px 0; color: var(--color-text-secondary); font-size: 10px; }
.package-progress-card footer { padding-top: 9px; border-top: 1px solid var(--color-border-default); color: var(--color-text-muted); font: 9px/1 var(--font-code); }
.package-progress-card footer b { color: var(--color-text-primary); }
.package-progress-card.is-running { border-color: rgb(34 211 238 / 38%); }
.package-progress-card.is-succeeded { border-color: rgb(34 197 94 / 32%); }
.package-progress-card.is-failed { border-color: rgb(239 68 68 / 38%); }
@media (max-width: 640px) { .task-overview { align-items: flex-start; flex-direction: column; }.overview-meta { width: 100%; justify-content: space-between; } }
@media (max-width: 720px) { .queue-holder { grid-template-columns: 1fr; }.queue-holder dl { grid-template-columns: 1fr; } }
@media (max-width: 900px) { .result-summary { grid-template-columns: 1fr; }.result-metrics { max-width: 520px; } }
@media (max-width: 520px) { .result-metrics { grid-template-columns: 1fr; } }
</style>
