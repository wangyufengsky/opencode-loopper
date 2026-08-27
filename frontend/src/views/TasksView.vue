<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import MetricCard from '@/components/MetricCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { useTaskStore } from '@/stores/taskStore'
import { formatCompactDateTime } from '@/utils/dateTime'
import { userFacingError } from '@/utils/displayLabels'
import type { Task, TaskStatus } from '@/types/domain'
import { demoTaskStatusGroups } from '@/mock/demoData'

type StatusFilter = 'ALL' | 'ACTIVE' | 'SUCCESSFUL' | 'TERMINATED' | TaskStatus
type ArchiveFilter = 'ACTIVE' | 'ARCHIVED' | 'ALL'

const store = useTaskStore()
const route = useRoute()
const router = useRouter()
const filter = ref<StatusFilter>('ALL')
const projectFilter = ref('ALL')
const timeOrder = ref<'NEWEST' | 'OLDEST'>('NEWEST')
const archiveFilter = ref<ArchiveFilter>('ACTIVE')
const search = ref('')
const groupByProject = ref(false)
const archivingTaskId = ref('')
const deletingTaskId = ref('')
const clock = ref(Date.now())
let clockTimer: ReturnType<typeof setInterval> | undefined
let reloadTimer: number | undefined
const ready = ref(false)
onMounted(async () => {
  clockTimer = setInterval(() => { clock.value = Date.now() }, 1000)
  if (store.usingDemo) {
    ready.value = true
    return
  }
  await store.loadProjects()
  ready.value = true
  await reloadTasks()
})
onBeforeUnmount(() => {
  if (clockTimer) clearInterval(clockTimer)
  if (reloadTimer) window.clearTimeout(reloadTimer)
})
function retryRemaining(task: Task) {
  return task.retryDueAt ? Math.max(0, Math.ceil((Date.parse(task.retryDueAt) - clock.value) / 1000)) : 0
}

const statusOptions: Array<{ value: StatusFilter; label: string }> = [
  { value: 'ALL', label: '全部' },
  { value: 'ACTIVE', label: '处理中' },
  { value: 'SUCCESSFUL', label: '已成功' },
  { value: 'WAITING_INPUT', label: '等待输入' },
  { value: 'PAUSED', label: '已暂停' },
  { value: 'JUDGING', label: '评审中' },
  { value: 'AWAITING_DECISION', label: '等待处置' },
  { value: 'COMPLETED', label: '已确认完成' },
  { value: 'SUPERSEDED', label: '已由新任务接续' },
  { value: 'SUCCEEDED', label: '已成功' },
  { value: 'FAILED', label: '已失败' },
  { value: 'CANCELLED', label: '已取消' },
]
const validStatuses = new Set(statusOptions.map((item) => item.value))
validStatuses.add('TERMINATED')
const terminalStatuses: TaskStatus[] = ['COMPLETED', 'SUPERSEDED', 'SUCCEEDED', 'FAILED', 'CANCELLED']

const projectOptions = computed(() => (store.usingDemo
  ? Array.from(new Map(store.tasks.map((task) => [task.projectId, task.projectName])).entries())
    .map(([id, name]) => ({ id, name }))
  : store.projects).slice().sort((left, right) => left.name.localeCompare(right.name, 'zh-CN')))
const visibleTasks = computed(() => {
  if (!store.usingDemo) return store.tasks
  return store.tasks
    .filter((task) => archiveFilter.value === 'ALL'
      || (archiveFilter.value === 'ARCHIVED' ? task.archived : !task.archived))
    .filter((task) => filter.value === 'ALL'
      || (filter.value === 'ACTIVE' ? demoTaskStatusGroups[task.status] === 'PROCESSING'
        : filter.value === 'SUCCESSFUL' ? demoTaskStatusGroups[task.status] === 'SUCCESSFUL'
        : filter.value === 'TERMINATED' ? demoTaskStatusGroups[task.status] === 'TERMINATED' : task.status === filter.value))
    .filter((task) => projectFilter.value === 'ALL' || task.projectId === projectFilter.value)
    .filter((task) => !search.value.trim() || [task.title, task.goal, task.projectName, task.branch]
      .some((value) => value.toLocaleLowerCase('zh-CN').includes(search.value.trim().toLocaleLowerCase('zh-CN'))))
    .slice().sort((left, right) => (timeOrder.value === 'NEWEST' ? -1 : 1)
      * (new Date(left.updatedAt).getTime() - new Date(right.updatedAt).getTime()))
})
const taskGroups = computed(() => {
  if (!groupByProject.value) return [{ id: 'all', name: '', tasks: visibleTasks.value }]
  const groups = new Map<string, { id: string; name: string; tasks: Task[] }>()
  for (const task of visibleTasks.value) {
    const group = groups.get(task.projectId) ?? { id: task.projectId, name: task.projectName, tasks: [] }
    group.tasks.push(task)
    groups.set(task.projectId, group)
  }
  return [...groups.values()].sort((left, right) => left.name.localeCompare(right.name, 'zh-CN'))
})
const finished = computed(() => store.taskFacets.SUCCESSFUL ?? 0)
const terminated = computed(() => store.taskFacets.TERMINATED ?? 0)
const waitingInput = computed(() => store.taskFacets.WAITING_INPUT ?? 0)
const activeCount = computed(() => store.taskFacets.PROCESSING ?? 0)
const archivedCount = computed(() => store.taskFacets.ARCHIVED_TOTAL ?? 0)
const noRegisteredProject = computed(() => !store.usingDemo && store.projects.length === 0)

function queryValue(value: unknown) {
  return Array.isArray(value) ? value[0] : typeof value === 'string' ? value : ''
}

function applyRouteQuery() {
  const status = queryValue(route.query.status).toUpperCase()
  filter.value = validStatuses.has(status as StatusFilter) ? status as StatusFilter : 'ALL'
  const project = queryValue(route.query.project)
  projectFilter.value = project || 'ALL'
  timeOrder.value = queryValue(route.query.order) === 'oldest' ? 'OLDEST' : 'NEWEST'
  const archive = queryValue(route.query.archive)
  archiveFilter.value = archive === 'archived' ? 'ARCHIVED' : archive === 'all' ? 'ALL' : 'ACTIVE'
  search.value = queryValue(route.query.q)
  groupByProject.value = queryValue(route.query.group) === 'project'
}

watch(() => route.query, applyRouteQuery, { immediate: true })
watch([filter, projectFilter, timeOrder, archiveFilter, search, groupByProject], () => {
  const query: Record<string, string> = {}
  if (filter.value !== 'ALL') query.status = filter.value
  if (projectFilter.value !== 'ALL') query.project = projectFilter.value
  if (timeOrder.value === 'OLDEST') query.order = 'oldest'
  if (archiveFilter.value !== 'ACTIVE') query.archive = archiveFilter.value.toLowerCase()
  if (search.value.trim()) query.q = search.value.trim()
  if (groupByProject.value) query.group = 'project'
  const current = Object.fromEntries(Object.entries(route.query).map(([key, value]) => [key, queryValue(value)]).filter(([, value]) => value))
  if (JSON.stringify(current) !== JSON.stringify(query)) void router.replace({ query })
})
watch([filter, projectFilter, timeOrder, archiveFilter, search], () => {
  if (!ready.value || store.usingDemo) return
  if (reloadTimer) window.clearTimeout(reloadTimer)
  reloadTimer = window.setTimeout(() => { void reloadTasks() }, 180)
})

function selectedStatusQuery(): { status?: string[]; statusGroup?: 'PROCESSING' | 'SUCCESSFUL' | 'TERMINATED' } {
  if (filter.value === 'ACTIVE') return { statusGroup: 'PROCESSING' }
  if (filter.value === 'SUCCESSFUL') return { statusGroup: 'SUCCESSFUL' }
  if (filter.value === 'TERMINATED') return { statusGroup: 'TERMINATED' }
  return filter.value === 'ALL' ? {} : { status: [filter.value] }
}

async function reloadTasks(append = false) {
  await store.loadTaskSummaries({
    projectId: projectFilter.value === 'ALL' ? undefined : projectFilter.value,
    ...selectedStatusQuery(),
    archive: archiveFilter.value,
    q: search.value.trim() || undefined,
    order: timeOrder.value === 'OLDEST' ? 'oldest' : 'newest',
  }, append)
}

async function refreshAll() {
  await Promise.all([store.loadProjects(true), reloadTasks()])
}

function selectMetric(status: StatusFilter) {
  filter.value = filter.value === status ? 'ALL' : status
  archiveFilter.value = 'ACTIVE'
}

function resetFilters() {
  filter.value = 'ALL'
  projectFilter.value = 'ALL'
  timeOrder.value = 'NEWEST'
  archiveFilter.value = 'ACTIVE'
  search.value = ''
  groupByProject.value = false
}

function canArchive(task: Task) {
  return terminalStatuses.includes(task.status)
}

async function toggleArchive(task: Task) {
  if (archivingTaskId.value || deletingTaskId.value) return
  archivingTaskId.value = task.id
  try {
    await store.setTaskArchived(task.id, !task.archived)
    if (!store.usingDemo) await reloadTasks()
    ElMessage.success(task.archived ? '任务已恢复到活动列表' : '任务已归档，可随时恢复')
  } catch (cause) {
    ElMessage.error(userFacingError(cause, '任务归档状态更新失败'))
  } finally {
    archivingTaskId.value = ''
  }
}

async function confirmDelete(task: Task) {
  if (!task.archived || archivingTaskId.value || deletingTaskId.value) return
  try {
    await ElMessageBox.confirm(
      `将永久删除“${task.title}”的任务记录、验证、评审和关联设计历史。项目文件、Git 分支与 worktree 不会删除。此操作无法恢复。`,
      '永久删除历史任务？',
      { type: 'error', confirmButtonText: '永久删除', cancelButtonText: '取消' },
    )
    deletingTaskId.value = task.id
    await store.deleteArchivedTask(task.id)
    if (!store.usingDemo) await reloadTasks()
    ElMessage.success('历史任务已永久删除')
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') {
      ElMessage.error(userFacingError(cause, '历史任务删除失败'))
    }
  } finally {
    deletingTaskId.value = ''
  }
}
</script>

<template>
  <PageHeader eyebrow="任务" title="任务控制台">
    <template #actions>
      <el-button plain @click="refreshAll"><Icon icon="lucide:refresh-cw" aria-hidden="true" />刷新状态</el-button>
      <el-button v-if="noRegisteredProject" type="primary" @click="router.push('/projects')"><Icon icon="lucide:folder-plus" aria-hidden="true" />登记项目</el-button>
      <el-button v-else type="primary" @click="router.push('/designer')"><Icon icon="lucide:sparkles" aria-hidden="true" />新建设计</el-button>
    </template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <section v-if="store.error && !store.usingDemo" class="error-panel error-panel-verification" role="status" aria-live="polite" style="margin-bottom: 16px"><Icon class="error-panel-icon" icon="lucide:server-off" aria-hidden="true" /><div><h3>任务加载失败</h3><p>{{ userFacingError(store.error) }}</p><el-button size="small" plain type="primary" style="margin-top: 9px" @click="store.activateDemo()">载入演示</el-button></div></section>

    <section v-if="!store.loading && noRegisteredProject" class="card onboarding-card" aria-labelledby="task-onboarding-title">
      <span class="onboarding-icon"><Icon icon="lucide:folder-kanban" aria-hidden="true" /></span>
      <div><p class="eyebrow">开始使用</p><h2 id="task-onboarding-title">先登记一个项目</h2></div>
      <el-button type="primary" size="large" @click="router.push('/projects')">登记本机项目<Icon icon="lucide:arrow-right" aria-hidden="true" /></el-button>
    </section>

    <template v-if="!noRegisteredProject || store.tasks.length">
      <section class="metric-grid" aria-label="任务概览与快速筛选">
        <MetricCard label="处理中" :value="activeCount" icon="lucide:orbit" accent="var(--color-accent-cyan)" interactive :active="filter === 'ACTIVE'" @select="selectMetric('ACTIVE')" />
        <MetricCard label="已成功" :value="finished" icon="lucide:badge-check" accent="var(--color-success)" interactive :active="filter === 'SUCCESSFUL'" @select="selectMetric('SUCCESSFUL')" />
        <MetricCard label="需要输入" :value="waitingInput" icon="lucide:message-square-warning" accent="var(--color-accent-ai)" interactive :active="filter === 'WAITING_INPUT'" @select="selectMetric('WAITING_INPUT')" />
        <MetricCard label="已终止" :value="terminated" icon="lucide:shield-x" accent="var(--color-task-danger)" interactive :active="filter === 'TERMINATED'" @select="selectMetric('TERMINATED')" />
      </section>

      <section class="toolbar task-toolbar" aria-label="任务筛选">
        <div class="task-filter-stack">
          <div class="toolbar-group task-filters" aria-label="按状态筛选">
            <el-button-group><el-button v-for="item in statusOptions" :key="item.value" :type="filter === item.value ? 'primary' : undefined" size="small" :aria-pressed="filter === item.value" @click="filter = item.value">{{ item.label }}</el-button></el-button-group>
          </div>
          <div class="task-query-row">
            <el-input v-model="search" class="task-search" name="task-search" autocomplete="off" clearable aria-label="搜索任务" placeholder="搜索标题、目标或项目…"><template #prefix><Icon icon="lucide:search" aria-hidden="true" /></template></el-input>
            <el-select v-model="projectFilter" class="project-filter" size="small" aria-label="按项目筛选任务">
              <el-option label="全部项目" value="ALL" />
              <el-option v-for="project in projectOptions" :key="project.id" :label="project.name" :value="project.id" />
            </el-select>
            <el-select v-model="archiveFilter" class="archive-filter" size="small" aria-label="选择归档范围">
              <el-option label="活动任务" value="ACTIVE" />
              <el-option :label="`已归档（${archivedCount}）`" value="ARCHIVED" />
              <el-option label="全部任务" value="ALL" />
            </el-select>
            <el-select v-model="timeOrder" class="time-sort" size="small" aria-label="按更新时间排序">
              <el-option label="最新更新优先" value="NEWEST" />
              <el-option label="最早更新优先" value="OLDEST" />
            </el-select>
            <el-button size="small" :type="groupByProject ? 'primary' : undefined" :aria-pressed="groupByProject" @click="groupByProject = !groupByProject"><Icon icon="lucide:layout-list" aria-hidden="true" />按项目分组</el-button>
          </div>
        </div>
        <p class="mono tiny muted" aria-live="polite">{{ visibleTasks.length }} 个任务 · {{ store.usingDemo ? '演示数据' : '实时数据' }}</p>
      </section>

      <section v-if="store.loading" class="card card-pad"><div v-for="n in 5" :key="n" class="skeleton-block" style="height: 48px; margin-bottom: 8px" /></section>
      <div v-else-if="visibleTasks.length" class="task-groups">
        <section v-for="group in taskGroups" :key="group.id" class="task-group">
          <header v-if="groupByProject" class="task-group-header"><div><Icon icon="lucide:folder" aria-hidden="true" /><h2>{{ group.name }}</h2></div><span>{{ group.tasks.length }} 个任务</span></header>
          <div class="task-table"><el-table :data="group.tasks" row-key="id" :height="groupByProject ? undefined : 430"><el-table-column label="任务" min-width="285"><template #default="{ row }"><RouterLink class="task-link" :title="row.goal || row.title" :to="`/tasks/${row.id}`">{{ row.title }}</RouterLink><p class="mono tiny muted task-branch" translate="no">{{ row.branch }}</p></template></el-table-column><el-table-column label="状态" width="132"><template #default="{ row }"><StatusBadge :status="row.status" /><p v-if="row.status === 'RETRY_WAIT'" class="mono tiny muted">{{ retryRemaining(row) }}s</p></template></el-table-column><el-table-column label="进度" width="90"><template #default="{ row }"><span class="mono numeric">{{ row.attemptCount }}/{{ row.maxAttempts }}</span></template></el-table-column><el-table-column v-if="!groupByProject" label="项目" min-width="140" prop="projectName" /><el-table-column label="更新于" width="120"><template #default="{ row }"><time class="muted tiny" :datetime="row.updatedAt">{{ formatCompactDateTime(row.updatedAt) }}</time></template></el-table-column><el-table-column label="设计" width="96"><template #default="{ row }"><RouterLink v-if="row.hasDesignHistory" class="design-history-link" :to="`/tasks/${row.id}/design`"><Icon icon="lucide:messages-square" aria-hidden="true" />查看</RouterLink><span v-else class="tiny muted">无</span></template></el-table-column><el-table-column width="132"><template #default="{ row }"><div class="row-actions"><button v-if="row.archived" type="button" class="icon-action danger" :disabled="Boolean(archivingTaskId || deletingTaskId)" :aria-label="`永久删除任务 ${row.title}`" title="永久删除" @click="confirmDelete(row)"><Icon :icon="deletingTaskId === row.id ? 'lucide:loader-circle' : 'lucide:trash-2'" :class="{ spin: deletingTaskId === row.id }" aria-hidden="true" /></button><button v-if="row.archived || canArchive(row)" type="button" class="icon-action" :disabled="Boolean(archivingTaskId || deletingTaskId)" :aria-label="row.archived ? `恢复任务 ${row.title}` : `归档任务 ${row.title}`" :title="row.archived ? '恢复任务' : '归档任务'" @click="toggleArchive(row)"><Icon :icon="archivingTaskId === row.id ? 'lucide:loader-circle' : row.archived ? 'lucide:archive-restore' : 'lucide:archive'" :class="{ spin: archivingTaskId === row.id }" aria-hidden="true" /></button><RouterLink class="icon-action" :to="`/tasks/${row.id}`" :aria-label="`打开任务 ${row.title}`" title="打开任务"><Icon icon="lucide:arrow-up-right" aria-hidden="true" /></RouterLink></div></template></el-table-column></el-table></div>
        </section>
      </div>
      <div v-if="store.taskNextCursor" class="load-more-row"><el-button plain :loading="store.loading" @click="reloadTasks(true)">加载更多</el-button></div>
      <section v-if="!store.loading && !visibleTasks.length" class="card empty-state"><div><Icon icon="lucide:search-x" width="30" aria-hidden="true" /><strong>{{ store.tasks.length ? '没有匹配的任务' : '还没有任务' }}</strong><el-button v-if="store.tasks.length" plain @click="resetFilters">清除筛选</el-button><el-button v-else type="primary" @click="router.push('/designer')">开始设计</el-button></div></section>
    </template>
  </main>
</template>

<style scoped>
.onboarding-card { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 18px; margin-bottom: 18px; padding: 24px; border-color: rgb(34 211 238 / 24%); background: linear-gradient(135deg, rgb(34 211 238 / 8%), rgb(139 92 246 / 7%)); }
.onboarding-icon { display: grid; width: 52px; height: 52px; place-items: center; border: 1px solid rgb(34 211 238 / 28%); border-radius: 14px; color: var(--color-accent-cyan); background: rgb(34 211 238 / 8%); }.onboarding-icon svg { width: 24px; height: 24px; }.onboarding-card h2 { margin: 3px 0 7px; font-size: 19px; text-wrap: balance; }.onboarding-card p:last-child { max-width: 680px; margin: 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.65; }
.task-filter-stack { display: grid; min-width: 0; flex: 1; gap: 10px; }.task-filters { display: flex; align-items: center; flex-wrap: wrap; gap: 9px; }.task-query-row { display: flex; min-width: 0; align-items: center; flex-wrap: wrap; gap: 9px; }.task-search { width: min(320px, 100%); }.project-filter { width: 175px; }.archive-filter { width: 150px; }.time-sort { width: 155px; }
.task-groups { display: grid; gap: 16px; }.task-group { min-width: 0; }.task-group-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0 2px 8px; }.task-group-header > div { display: flex; min-width: 0; align-items: center; gap: 8px; }.task-group-header svg { color: var(--color-accent-cyan); }.task-group-header h2 { margin: 0; overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }.task-group-header span { color: var(--color-text-tertiary); font-size: 10px; }
.load-more-row { display: flex; justify-content: center; padding: 16px 0 2px; }
.task-link { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.task-branch { margin: 5px 0 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.numeric { font-variant-numeric: tabular-nums; }.design-history-link { display: inline-flex; align-items: center; gap: 5px; padding: 5px 8px; border: 1px solid var(--color-border-default); border-radius: 6px; color: var(--color-text-secondary); font-size: 10px; text-decoration: none; }.design-history-link:hover, .design-history-link:focus-visible { border-color: var(--color-accent-cyan); color: var(--color-accent-cyan); outline: none; }.row-actions { display: flex; justify-content: flex-end; gap: 4px; }.icon-action { display: inline-grid; width: 30px; height: 30px; padding: 0; place-items: center; border: 0; border-radius: 7px; background: transparent; color: var(--color-text-secondary); cursor: pointer; text-decoration: none; }.icon-action:hover, .icon-action:focus-visible { background: rgb(34 211 238 / 9%); color: var(--color-accent-cyan); outline: 2px solid transparent; }.icon-action.danger:hover, .icon-action.danger:focus-visible { background: rgb(239 68 68 / 10%); color: var(--color-task-danger); }.icon-action:focus-visible { outline-color: var(--color-accent-cyan); outline-offset: 1px; }.icon-action.danger:focus-visible { outline-color: var(--color-task-danger); }.icon-action:disabled { cursor: wait; opacity: .55; }.spin { animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 980px) { .task-toolbar { align-items: flex-start; flex-direction: column; }.onboarding-card { grid-template-columns: auto minmax(0, 1fr); }.onboarding-card > :last-child { grid-column: 2; justify-self: start; } }
@media (max-width: 640px) { .task-filters, .task-query-row { width: 100%; }.task-filters :deep(.el-button-group) { display: flex; max-width: 100%; overflow-x: auto; }.task-search, .project-filter, .archive-filter, .time-sort { width: 100%; }.onboarding-card { grid-template-columns: 1fr; }.onboarding-card > :last-child { grid-column: 1; }.onboarding-icon { width: 44px; height: 44px; } }
</style>
