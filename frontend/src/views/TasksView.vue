<script setup lang="ts">
import { computed, ref } from 'vue'
import { Icon } from '@iconify/vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import MetricCard from '@/components/MetricCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { useTaskStore } from '@/stores/taskStore'
import type { TaskStatus } from '@/types/domain'

const store = useTaskStore()
const router = useRouter()
const filter = ref<'ALL' | TaskStatus>('ALL')
const visibleTasks = computed(() => filter.value === 'ALL' ? store.tasks : store.tasks.filter((task) => task.status === filter.value))
const finished = computed(() => store.tasks.filter((task) => task.status === 'SUCCEEDED').length)
const terminal = computed(() => store.tasks.filter((task) => ['FAILED', 'CANCELLED'].includes(task.status)).length)
const waitingInput = computed(() => store.tasks.filter((task) => task.status === 'WAITING_INPUT').length)
const formatDate = (value: string) => new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', month: 'numeric', day: 'numeric' }).format(new Date(value))
function openTask(task: { id: string }) { router.push(`/tasks/${task.id}`) }
</script>

<template>
  <PageHeader eyebrow="Control Plane / Tasks" title="任务控制台" subtitle="每个 Task 都有隔离分支、worktree、执行证据与可恢复的 Session 边界。">
    <template #actions><el-button plain @click="store.loadOverview"><Icon icon="lucide:refresh-cw" />刷新状态</el-button><el-button type="primary" @click="router.push('/designer')"><Icon icon="lucide:sparkles" />新建 Loop</el-button></template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <section v-if="store.error && !store.usingDemo" class="error-panel error-panel-verification" style="margin-bottom: 16px"><Icon class="error-panel-icon" icon="lucide:server-off" /><div><h3>无法读取本地控制面</h3><p>{{ store.error }}。真实状态不会被演示数据覆盖。</p><el-button size="small" plain type="primary" style="margin-top: 9px" @click="store.activateDemo()">载入交互演示</el-button></div></section>
    <section class="metric-grid">
      <MetricCard label="运行中" :value="store.activeTasks.length" detail="包括验证、重试与 Judge" icon="lucide:orbit" accent="var(--color-accent-cyan)" />
      <MetricCard label="已完成" :value="finished" detail="确定性验证与 Judge 均通过" icon="lucide:badge-check" accent="var(--color-success)" />
      <MetricCard label="需要输入" :value="waitingInput" detail="需要用户决策后继续" icon="lucide:message-square-warning" accent="var(--color-accent-ai)" />
      <MetricCard label="终止任务" :value="terminal" detail="保留 worktree 与全部证据" icon="lucide:shield-x" accent="var(--color-task-danger)" />
    </section>
    <section class="toolbar"><div class="toolbar-group"><el-button-group><el-button v-for="item in ['ALL', 'RUNNING', 'RETRY_WAIT', 'WAITING_INPUT', 'FAILED']" :key="item" :type="filter === item ? 'primary' : undefined" size="small" @click="filter = item as typeof filter">{{ item === 'ALL' ? '全部' : item.replace(/_/g, ' ') }}</el-button></el-button-group></div><p class="mono tiny muted">{{ visibleTasks.length }} tasks · {{ store.usingDemo ? 'demo' : 'live' }}</p></section>
    <section v-if="store.loading" class="card card-pad"><div v-for="n in 5" :key="n" class="skeleton-block" style="height: 48px; margin-bottom: 8px" /></section>
    <section v-else-if="visibleTasks.length" class="task-table"><el-table :data="visibleTasks" row-key="id" :height="430"><el-table-column label="Task" min-width="285"><template #default="{ row }"><RouterLink class="task-link" :to="`/tasks/${row.id}`">{{ row.title }}</RouterLink><p class="mono tiny muted" style="margin: 5px 0 0">{{ row.branch }}</p></template></el-table-column><el-table-column label="状态" width="144"><template #default="{ row }"><StatusBadge :status="row.status" /></template></el-table-column><el-table-column label="进度" width="110"><template #default="{ row }"><span class="mono">{{ row.attemptCount }}/{{ row.maxAttempts }}</span></template></el-table-column><el-table-column label="项目" min-width="150" prop="projectName" /><el-table-column label="更新于" width="130"><template #default="{ row }"><span class="muted tiny">{{ formatDate(row.updatedAt) }}</span></template></el-table-column><el-table-column width="64"><template #default="{ row }"><el-button text circle aria-label="打开任务" @click="openTask(row)"><Icon icon="lucide:arrow-up-right" /></el-button></template></el-table-column></el-table></section>
    <section v-else class="card empty-state"><div><Icon icon="lucide:orbit" width="30" /><strong>没有匹配的 Task</strong><p>切换筛选条件，或在 Designer 中创建第一条 LoopSpec。</p></div></section>
  </main>
</template>
