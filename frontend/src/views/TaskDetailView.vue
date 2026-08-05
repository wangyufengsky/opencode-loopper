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
import JudgeReviewCard from '@/components/JudgeReviewCard.vue'
import TaskAuditEvidencePanel from '@/components/TaskAuditEvidencePanel.vue'
import { useTaskStore } from '@/stores/taskStore'
import type { Attempt, ErrorEvent } from '@/types/domain'

const route = useRoute()
const router = useRouter()
const store = useTaskStore()
const id = computed(() => route.params.id as string)
const task = computed(() => store.tasks.find((item) => item.id === id.value))
const isDirectExecution = computed(() => task.value?.branch === 'DIRECT')
const attempts = computed<Attempt[]>(() => task.value?.attempts ?? task.value?.stages?.flatMap((stage) => stage.attempts) ?? [])
const sessionErrors = computed<ErrorEvent[]>(() => (task.value?.errors ?? attempts.value.flatMap((attempt) => attempt.errors)).filter((error) => error.layer === 'SESSION'))
const verifierErrors = computed<ErrorEvent[]>(() => (task.value?.errors ?? attempts.value.flatMap((attempt) => attempt.errors)).filter((error) => error.layer === 'VERIFICATION'))
const taskErrors = computed<ErrorEvent[]>(() => task.value?.errors?.filter((error) => error.layer === 'TASK') ?? [])
const judges = computed(() => task.value?.judges ?? [])
const artifacts = computed(() => task.value?.artifacts ?? store.artifacts.filter((artifact) => artifact.taskId === id.value))

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
    <template #actions><StatusBadge v-if="task" :status="task.status" /><el-button v-if="task?.hasDesignHistory" plain @click="router.push(`/tasks/${id}/design`)"><Icon icon="lucide:messages-square" />设计</el-button><el-button plain @click="router.push('/tasks')"><Icon icon="lucide:list" />全部任务</el-button><el-button v-if="task?.status === 'READY'" type="primary" @click="store.updateTask(id, 'start')"><Icon icon="lucide:play" />开始执行</el-button><template v-else-if="task?.status === 'RUNNING' || task?.status === 'VERIFYING'"><el-button plain @click="store.updateTask(id, 'pause')"><Icon icon="lucide:pause" />暂停</el-button><el-button plain type="danger" @click="confirmCancel"><Icon icon="lucide:square" />取消</el-button></template><el-button v-else-if="task?.status === 'PAUSED'" type="primary" @click="store.updateTask(id, 'resume')"><Icon icon="lucide:play" />继续</el-button></template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <section v-if="!task" class="card empty-state"><div><Icon icon="lucide:search-x" width="30" /><strong>未找到此任务</strong><p>它可能已被清理，或当前接口尚未返回该条记录。</p></div></section>
    <template v-else>
      <section class="task-overview card card-pad">
        <div><p class="eyebrow">{{ isDirectExecution ? '直接执行' : '隔离执行' }}</p><span class="mono tiny muted">{{ isDirectExecution ? '原项目目录' : task.branch }} · {{ task.worktreePath }}</span></div>
        <div class="overview-meta"><span><b>{{ task.attemptCount }}</b> / {{ task.maxAttempts }} 次尝试</span><span v-if="store.streamState !== 'idle'" :class="['stream-state', store.streamState]">{{ store.streamState === 'connected' ? 'SSE 已连接' : 'SSE 重连中' }}</span></div>
      </section>
      <section v-if="task.stages?.length" class="card card-pad" style="margin-top: 16px"><div class="card-header"><div><h2 class="card-title">阶段进度</h2><p class="card-description">一个会话只执行当前阶段；会话失败不会直接终止任务。</p></div></div><StageRail :stages="task.stages" /></section>
      <section v-for="error in verifierErrors" :key="error.id" style="margin-top: 16px"><LayeredErrorPanel :error="error" /></section>
      <section v-for="error in sessionErrors" :key="error.id" style="margin-top: 16px"><LayeredErrorPanel :error="error" /></section>
      <section v-for="error in taskErrors" :key="error.id" style="margin-top: 16px"><LayeredErrorPanel :error="error" /></section>
      <SessionMonitorPanel :task-id="task.id" />
      <section v-if="judges.length || task.status === 'JUDGING' || task.status === 'WAITING_INPUT'" class="card card-pad judge-section" style="margin-top: 16px" aria-labelledby="judge-heading">
        <div class="card-header"><div><p class="eyebrow">独立只读评审</p><h2 id="judge-heading" class="card-title">需求 / 风险双评审</h2><p class="card-description">两个会话独立审阅；只有双方明确通过，任务才能成功。</p></div><StatusBadge :status="task.status" /></div>
        <div class="judge-grid">
          <JudgeReviewCard v-for="judge in judges" :key="judge.id" :judge="judge" />
        </div>
      </section>
      <section class="task-detail-grid" style="margin-top: 16px">
        <article class="card card-pad"><div class="card-header"><div><p class="eyebrow">尝试历史</p><h2 class="card-title">尝试与会话</h2></div><span class="mono tiny muted">{{ attempts.length }} 条记录</span></div><AttemptTimeline :attempts="attempts" /></article>
        <TaskAuditEvidencePanel :attempts="attempts" :artifacts="artifacts" :judges="judges" :direct-execution="isDirectExecution" />
      </section>
    </template>
  </main>
</template>

<style scoped>
.task-overview { display: flex; align-items: center; justify-content: space-between; gap: 18px; }.overview-meta { display: flex; align-items: center; gap: 15px; color: var(--color-text-secondary); font-family: var(--font-code); font-size: 11px; }.overview-meta b { color: var(--color-text-primary); }.stream-state { display: inline-flex; align-items: center; gap: 6px; }.stream-state::before { width: 7px; height: 7px; border-radius: 50%; background: currentColor; content: ""; }.stream-state.connected { color: var(--color-success); }.stream-state.reconnecting { color: var(--color-session-warning); }.task-detail-grid { display: grid; grid-template-columns: minmax(300px, .77fr) minmax(500px, 1.23fr); gap: 16px; }@media (max-width: 1320px) { .task-detail-grid { grid-template-columns: minmax(290px, .7fr) minmax(470px, 1.3fr); } }
.judge-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }@media (max-width: 960px) { .judge-grid { grid-template-columns: 1fr; } }
</style>
