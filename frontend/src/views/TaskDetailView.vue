<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import StageRail from '@/components/StageRail.vue'
import AttemptTimeline from '@/components/AttemptTimeline.vue'
import LayeredErrorPanel from '@/components/LayeredErrorPanel.vue'
import SessionMonitorPanel from '@/components/SessionMonitorPanel.vue'
import SessionLifecyclePanel from '@/components/SessionLifecyclePanel.vue'
import JudgeReviewCard from '@/components/JudgeReviewCard.vue'
import TaskAuditEvidencePanel from '@/components/TaskAuditEvidencePanel.vue'
import TaskPublicationActions from '@/components/TaskPublicationActions.vue'
import { useTaskStore } from '@/stores/taskStore'
import type { Attempt, ErrorEvent } from '@/types/domain'

const route = useRoute()
const router = useRouter()
const store = useTaskStore()
const id = computed(() => route.params.id as string)
const task = computed(() => store.tasks.find((item) => item.id === id.value))
const isDirectExecution = computed(() => task.value?.branch === 'DIRECT')
const attempts = computed<Attempt[]>(() => task.value?.attempts ?? task.value?.stages?.flatMap((stage) => stage.attempts) ?? [])
const lifecycleSession = computed(() => [...attempts.value].reverse().find((attempt) => attempt.sessionId))
const sessionErrors = computed<ErrorEvent[]>(() => (task.value?.errors ?? attempts.value.flatMap((attempt) => attempt.errors)).filter((error) => error.layer === 'SESSION'))
const verifierErrors = computed<ErrorEvent[]>(() => (task.value?.errors ?? attempts.value.flatMap((attempt) => attempt.errors)).filter((error) => error.layer === 'VERIFICATION'))
const taskErrors = computed<ErrorEvent[]>(() => task.value?.errors?.filter((error) => error.layer === 'TASK') ?? [])
const judges = computed(() => task.value?.judges ?? [])
const artifacts = computed(() => task.value?.artifacts ?? store.artifacts.filter((artifact) => artifact.taskId === id.value))
const verificationRows = computed(() => attempts.value.flatMap((attempt) => attempt.verifiers))
const changedFiles = computed(() => new Set(verificationRows.value.flatMap((verifier) => {
  const paths = verifier.evidence?.changedPaths
  return Array.isArray(paths) ? paths.filter((path): path is string => typeof path === 'string') : []
})).size)
const passedVerifications = computed(() => verificationRows.value.filter((verifier) => verifier.status === 'PASS').length)
const passedJudges = computed(() => judges.value.filter((judge) => judge.verdict === 'PASS').length)
const nextAction = computed(() => {
  if (!task.value) return ''
  if (task.value.status === 'SUCCEEDED') return isDirectExecution.value ? '检查原项目目录中的变更并决定后续发布方式。' : '检查变更摘要，然后提交并发布当前分支。'
  if (task.value.status === 'FAILED') return '先查看最上方错误与失败验证，再根据证据重新设计或新建任务。'
  if (task.value.status === 'CANCELLED') return '任务已取消；执行目录和证据仍保留，可据此新建设计。'
  if (task.value.status === 'WAITING_INPUT') return '回答页面中的待处理问题，任务将从当前阶段继续。'
  if (task.value.status === 'PAUSED') return '确认当前状态后点击“继续”，任务会从原阶段恢复。'
  if (task.value.status === 'READY') return '执行目录已准备完成，可以开始任务。'
  return '任务正在推进；实时会话和阶段状态会自动更新。'
})

async function load() {
  await store.loadTask(id.value)
  if (!store.usingDemo) store.watchTask(id.value)
}
onMounted(load)
watch(id, load)
onBeforeUnmount(() => store.stopWatching())

async function confirmCancel() {
  if (!task.value) return
  try {
    await ElMessageBox.confirm('将中止当前会话、停止验证器，并保留执行目录和证据。此操作无法自动恢复。', '取消当前任务？', { type: 'warning', confirmButtonText: '取消任务', cancelButtonText: '继续执行' })
    await store.updateTask(id.value, 'cancel')
  } catch {
    // User kept the running task.
  }
}
</script>

<template>
  <PageHeader eyebrow="任务 / 检视" :title="task?.title ?? '加载任务'" :title-tooltip="task?.goal || task?.title">
    <template #actions><StatusBadge v-if="task" :status="task.status" /><el-button v-if="task?.hasDesignHistory" plain @click="router.push(`/tasks/${id}/design`)"><Icon icon="lucide:messages-square" />设计</el-button><el-button v-if="task?.status === 'FAILED' || task?.status === 'CANCELLED'" type="primary" @click="router.push(`/tasks/${id}/recovery`)"><Icon icon="lucide:git-fork" />恢复</el-button><el-button plain @click="router.push('/tasks')"><Icon icon="lucide:list" />全部任务</el-button><el-button v-if="task?.status === 'READY'" type="primary" @click="store.updateTask(id, 'start')"><Icon icon="lucide:play" />开始执行</el-button><template v-else-if="task?.status === 'RUNNING' || task?.status === 'VERIFYING'"><el-button plain @click="store.updateTask(id, 'pause')"><Icon icon="lucide:pause" />暂停</el-button><el-button plain type="danger" @click="confirmCancel"><Icon icon="lucide:square" />取消</el-button></template><el-button v-else-if="task?.status === 'PAUSED'" type="primary" @click="store.updateTask(id, 'resume')"><Icon icon="lucide:play" />继续</el-button><TaskPublicationActions v-if="task?.status === 'SUCCEEDED'" :task="task" :demo="store.usingDemo" /></template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <section v-if="!task" class="card empty-state"><div><Icon icon="lucide:search-x" width="30" /><strong>未找到此任务</strong><p>它可能已被清理，或当前接口尚未返回该条记录。</p></div></section>
    <template v-else>
      <section class="task-overview card card-pad">
        <div><p class="eyebrow">{{ isDirectExecution ? '直接执行' : '隔离执行' }}</p><span class="mono tiny muted">{{ isDirectExecution ? '原项目目录' : task.branch }} · {{ task.worktreePath }}</span></div>
        <div class="overview-meta"><span><b>{{ task.attemptCount }}</b> / {{ task.maxAttempts }} 次尝试</span><span v-if="store.streamState !== 'idle'" :class="['stream-state', store.streamState]">{{ store.streamState === 'connected' ? 'SSE 已连接' : 'SSE 重连中' }}</span></div>
      </section>
      <section class="result-summary card card-pad" aria-labelledby="result-summary-heading">
        <div class="result-copy"><p class="eyebrow">RESULT / NEXT STEP</p><h2 id="result-summary-heading" class="card-title">结果与下一步</h2><p>{{ nextAction }}</p></div>
        <dl class="result-metrics">
          <div><dt>文件变更</dt><dd>{{ changedFiles }}</dd></div>
          <div><dt>验证通过</dt><dd>{{ passedVerifications }} / {{ verificationRows.length }}</dd></div>
          <div><dt>评审通过</dt><dd>{{ passedJudges }} / {{ Math.max(judges.length, 2) }}</dd></div>
        </dl>
      </section>
      <section v-if="task.stages?.length" class="card card-pad" style="margin-top: 16px"><div class="card-header"><div><h2 class="card-title">阶段进度</h2></div></div><StageRail :stages="task.stages" /></section>
      <section v-for="error in verifierErrors" :key="error.id" style="margin-top: 16px"><LayeredErrorPanel :error="error" /></section>
      <section v-for="error in sessionErrors" :key="error.id" style="margin-top: 16px"><LayeredErrorPanel :error="error" /></section>
      <section v-for="error in taskErrors" :key="error.id" style="margin-top: 16px"><LayeredErrorPanel :error="error" /></section>
      <SessionMonitorPanel :task-id="task.id" />
      <SessionLifecyclePanel v-if="lifecycleSession?.sessionId" :task-id="task.id" :session-id="lifecycleSession.sessionId" :session-state="lifecycleSession.status" :task-state="task.status" :direct-execution="isDirectExecution" />
      <section v-if="judges.length || task.status === 'JUDGING' || task.status === 'WAITING_INPUT'" class="card card-pad judge-section" style="margin-top: 16px" aria-labelledby="judge-heading">
        <div class="card-header"><div><p class="eyebrow">独立只读评审</p><h2 id="judge-heading" class="card-title">需求 / 风险双评审</h2></div><StatusBadge :status="task.status" /></div>
        <div class="judge-grid">
          <JudgeReviewCard v-for="judge in judges" :key="judge.id" :judge="judge" />
        </div>
      </section>
      <section class="task-detail-grid" style="margin-top: 16px">
        <article class="card card-pad"><div class="card-header"><div><p class="eyebrow">尝试历史</p><h2 class="card-title">尝试与会话</h2></div><span class="mono tiny muted">{{ attempts.length }} 条记录</span></div><AttemptTimeline :attempts="attempts" /></article>
        <TaskAuditEvidencePanel :task-id="task.id" :attempts="attempts" :artifacts="artifacts" :judges="judges" :direct-execution="isDirectExecution" />
      </section>
    </template>
  </main>
</template>

<style scoped>
.task-overview { display: flex; align-items: center; justify-content: space-between; gap: 18px; min-width: 0; }.task-overview > div:first-child { min-width: 0; }.task-overview > div:first-child > span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.overview-meta { display: flex; flex: 0 0 auto; align-items: center; gap: 15px; color: var(--color-text-secondary); font-family: var(--font-code); font-size: 11px; }.overview-meta b { color: var(--color-text-primary); }.stream-state { display: inline-flex; align-items: center; gap: 6px; }.stream-state::before { width: 7px; height: 7px; border-radius: 50%; background: currentColor; content: ""; }.stream-state.connected { color: var(--color-success); }.stream-state.reconnecting { color: var(--color-session-warning); }.task-detail-grid { display: grid; grid-template-columns: minmax(300px, .77fr) minmax(500px, 1.23fr); gap: 16px; }@media (max-width: 1320px) { .task-detail-grid { grid-template-columns: minmax(290px, .7fr) minmax(470px, 1.3fr); } }@media (max-width: 1050px) { .task-detail-grid { grid-template-columns: 1fr; } }
.result-summary { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(360px, .8fr); align-items: center; gap: 22px; margin-top: 16px; border-color: rgb(34 211 238 / 19%); background: linear-gradient(125deg, rgb(34 211 238 / 6%), rgb(139 92 246 / 4%)); }.result-copy p:last-child { max-width: 720px; margin: 8px 0 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.65; }.result-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin: 0; }.result-metrics div { padding: 12px; border: 1px solid var(--color-border-default); border-radius: 9px; background: rgb(7 12 22 / 50%); }.result-metrics dt { color: var(--color-text-tertiary); font-size: 9px; }.result-metrics dd { margin: 6px 0 0; color: var(--color-text-primary); font: 700 14px/1 var(--font-code); font-variant-numeric: tabular-nums; }
.judge-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }@media (max-width: 960px) { .judge-grid { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .task-overview { align-items: flex-start; flex-direction: column; }.overview-meta { width: 100%; justify-content: space-between; } }
@media (max-width: 900px) { .result-summary { grid-template-columns: 1fr; }.result-metrics { max-width: 520px; } }
@media (max-width: 520px) { .result-metrics { grid-template-columns: 1fr; } }
</style>
