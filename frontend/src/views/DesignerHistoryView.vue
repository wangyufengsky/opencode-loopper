<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import { api } from '@/api/client'
import { useTaskStore } from '@/stores/taskStore'
import type { DesignerHistoryItem } from '@/types/domain'
import { formatDateTime } from '@/utils/dateTime'

type StatusFilter = 'ALL' | 'PROCESSING' | 'REVIEWING' | 'WAITING_INPUT' | 'SESSION_ERROR'
type ArchiveFilter = 'ACTIVE' | 'ARCHIVED' | 'ALL'

const store = useTaskStore()
const route = useRoute()
const router = useRouter()
const designs = ref<DesignerHistoryItem[]>([])
const loading = ref(false)
const error = ref('')
const projectFilter = ref('ALL')
const statusFilter = ref<StatusFilter>('ALL')
const archiveFilter = ref<ArchiveFilter>('ACTIVE')
const timeOrder = ref<'NEWEST' | 'OLDEST'>('NEWEST')
const search = ref('')
const archivingId = ref('')

const statusOptions: Array<{ value: StatusFilter; label: string }> = [
  { value: 'ALL', label: '全部状态' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'REVIEWING', label: '待确认' },
  { value: 'WAITING_INPUT', label: '等待输入' },
  { value: 'SESSION_ERROR', label: '已停止/异常' },
]

const projectOptions = computed(() => store.projects.slice()
  .sort((left, right) => left.name.localeCompare(right.name, 'zh-CN')))
const archivedCount = computed(() => designs.value.filter((item) => item.archived).length)

function statusKind(item: DesignerHistoryItem): Exclude<StatusFilter, 'ALL'> {
  if (item.state === 'WAITING_INPUT') return 'WAITING_INPUT'
  if (item.state === 'SESSION_ERROR' || item.workflowPhase === 'FAILED') return 'SESSION_ERROR'
  if (item.state === 'REVIEWING' || item.state === 'COMPLETED'
    || ['REVIEWING_PACKAGE', 'FINAL_REVIEW', 'COMPLETED'].includes(item.workflowPhase)) return 'REVIEWING'
  return 'PROCESSING'
}

function statusText(item: DesignerHistoryItem) {
  if (item.archived) return '已归档'
  return {
    PROCESSING: '处理中',
    REVIEWING: item.workflowPhase === 'FINAL_REVIEW' || item.workflowPhase === 'COMPLETED' ? '总体待确认' : '工作包待确认',
    WAITING_INPUT: '等待输入',
    SESSION_ERROR: '已停止/异常',
  }[statusKind(item)]
}

const visibleDesigns = computed(() => designs.value
  .filter((item) => projectFilter.value === 'ALL' || item.projectId === projectFilter.value)
  .filter((item) => statusFilter.value === 'ALL' || statusKind(item) === statusFilter.value)
  .filter((item) => archiveFilter.value === 'ALL'
    || (archiveFilter.value === 'ARCHIVED' ? item.archived : !item.archived))
  .filter((item) => {
    const query = search.value.trim().toLocaleLowerCase('zh-CN')
    return !query || [item.goal, item.projectName, item.activeWorkPackageId ?? '']
      .some((value) => value.toLocaleLowerCase('zh-CN').includes(query))
  })
  .slice()
  .sort((left, right) => {
    const difference = new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime()
    return timeOrder.value === 'NEWEST' ? difference : -difference
  }))

function queryValue(value: unknown) {
  return Array.isArray(value) ? value[0] : typeof value === 'string' ? value : ''
}

function applyRouteQuery() {
  const projectId = queryValue(route.query.projectId)
  projectFilter.value = projectId || 'ALL'
  const status = queryValue(route.query.status).toUpperCase() as StatusFilter
  statusFilter.value = statusOptions.some((item) => item.value === status) ? status : 'ALL'
  const archive = queryValue(route.query.archive)
  archiveFilter.value = archive === 'archived' ? 'ARCHIVED' : archive === 'all' ? 'ALL' : 'ACTIVE'
  timeOrder.value = queryValue(route.query.order) === 'oldest' ? 'OLDEST' : 'NEWEST'
  search.value = queryValue(route.query.q)
}

watch(() => route.query, applyRouteQuery, { immediate: true })
watch([projectFilter, statusFilter, archiveFilter, timeOrder, search], () => {
  const query: Record<string, string> = {}
  if (projectFilter.value !== 'ALL') query.projectId = projectFilter.value
  if (statusFilter.value !== 'ALL') query.status = statusFilter.value
  if (archiveFilter.value !== 'ACTIVE') query.archive = archiveFilter.value.toLowerCase()
  if (timeOrder.value === 'OLDEST') query.order = 'oldest'
  if (search.value.trim()) query.q = search.value.trim()
  const current = Object.fromEntries(Object.entries(route.query)
    .map(([key, value]) => [key, queryValue(value)]).filter(([, value]) => value))
  if (JSON.stringify(current) !== JSON.stringify(query)) void router.replace({ query })
})

async function loadHistory() {
  loading.value = true
  error.value = ''
  try {
    designs.value = await api.listDesignerHistory()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法读取历史设计'
  } finally {
    loading.value = false
  }
}

function openDesign(item: DesignerHistoryItem, mode: 'continue' | 'edit') {
  if (item.archived) return
  void router.push({
    path: '/designer',
    query: { sessionId: item.id, projectId: item.projectId, ...(mode === 'edit' ? { mode: 'edit' } : {}) },
  })
}

function clearWorkspacePointer(item: DesignerHistoryItem) {
  try {
    const raw = sessionStorage.getItem('opencode-loopper.designer-workspace')
    if (raw && (JSON.parse(raw) as { sessionId?: string }).sessionId === item.id) {
      sessionStorage.removeItem('opencode-loopper.designer-workspace')
    }
  } catch { /* A malformed browser hint cannot block a server-side archive operation. */ }
}

async function toggleArchive(item: DesignerHistoryItem) {
  if (archivingId.value) return
  archivingId.value = item.id
  try {
    if (item.archived) {
      await api.restoreDesignerSession(item.id)
      item.archived = false
      item.archivedAt = undefined
      ElMessage.success('设计已恢复，可以继续或修改')
    } else {
      await api.archiveDesignerSession(item.id)
      item.archived = true
      item.archivedAt = new Date().toISOString()
      clearWorkspacePointer(item)
      ElMessage.success('设计已归档，完整记录仍然保留')
    }
    await store.loadOverview()
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '归档状态更新失败')
  } finally {
    archivingId.value = ''
  }
}

function resetFilters() {
  projectFilter.value = 'ALL'
  statusFilter.value = 'ALL'
  archiveFilter.value = 'ACTIVE'
  timeOrder.value = 'NEWEST'
  search.value = ''
}

onMounted(loadHistory)
</script>

<template>
  <PageHeader eyebrow="Designer / History" title="历史设计">
    <template #actions>
      <el-button plain :loading="loading" @click="loadHistory"><Icon icon="lucide:refresh-cw" />刷新</el-button>
      <el-button type="primary" @click="router.push('/designer')"><Icon icon="lucide:plus" />新建设计</el-button>
    </template>
  </PageHeader>
  <main id="main-content" class="content design-history-page" tabindex="-1">
    <section class="history-intro">
      <div><p class="eyebrow">PERSISTED DESIGN SESSIONS</p><h2>管理尚未确认成任务的设计</h2><p>继续会回到原讨论位置；修改会打开整体需求编辑入口；归档只收起记录，不删除设计快照。</p></div>
      <div class="history-counts"><span><b>{{ designs.filter(item => !item.archived).length }}</b>可继续</span><span><b>{{ archivedCount }}</b>已归档</span></div>
    </section>

    <section class="toolbar history-toolbar" aria-label="历史设计筛选">
      <el-input v-model="search" clearable class="history-search" aria-label="搜索历史设计" placeholder="搜索目标、项目或工作包…"><template #prefix><Icon icon="lucide:search" /></template></el-input>
      <el-select v-model="projectFilter" aria-label="按项目筛选设计">
        <el-option label="全部项目" value="ALL" />
        <el-option v-for="project in projectOptions" :key="project.id" :label="project.name" :value="project.id" />
      </el-select>
      <el-select v-model="statusFilter" aria-label="按状态筛选设计">
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-select v-model="archiveFilter" aria-label="选择设计归档范围">
        <el-option label="未归档" value="ACTIVE" />
        <el-option :label="`已归档（${archivedCount}）`" value="ARCHIVED" />
        <el-option label="全部设计" value="ALL" />
      </el-select>
      <el-select v-model="timeOrder" aria-label="按更新时间排序设计">
        <el-option label="最新更新优先" value="NEWEST" />
        <el-option label="最早更新优先" value="OLDEST" />
      </el-select>
      <span class="mono tiny muted">{{ visibleDesigns.length }} 条</span>
    </section>

    <section v-if="error" class="card history-error" role="status"><Icon icon="lucide:server-off" /><div><strong>历史设计加载失败</strong><p>{{ error }}</p></div><el-button plain size="small" @click="loadHistory">重试</el-button></section>
    <section v-else-if="loading" class="history-list" aria-label="正在加载历史设计">
      <article v-for="index in 5" :key="index" class="card history-card skeleton-block" />
    </section>
    <section v-else-if="visibleDesigns.length" class="history-list" aria-label="历史设计列表">
      <article v-for="item in visibleDesigns" :key="item.id" :class="['card', 'history-card', { archived: item.archived }]">
        <div class="history-card-main">
          <div class="history-card-heading">
            <span :class="['history-status', `status-${item.archived ? 'archived' : statusKind(item).toLowerCase()}`]">{{ statusText(item) }}</span>
            <span v-if="item.activeWorkPackageId" class="package-tag">{{ item.activeWorkPackageId }}</span>
          </div>
          <h3 :title="item.goal || '未命名设计'">{{ item.goal || '未命名设计' }}</h3>
          <div class="history-meta"><span><Icon icon="lucide:folder" />{{ item.projectName }}</span><span><Icon icon="lucide:clock-3" />更新于 {{ formatDateTime(item.updatedAt) }}</span><span v-if="item.archivedAt"><Icon icon="lucide:archive" />归档于 {{ formatDateTime(item.archivedAt) }}</span></div>
        </div>
        <div class="history-actions">
          <template v-if="!item.archived">
            <el-button size="small" type="primary" @click="openDesign(item, 'continue')"><Icon icon="lucide:play" />继续</el-button>
            <el-button size="small" plain @click="openDesign(item, 'edit')"><Icon icon="lucide:pencil" />修改</el-button>
          </template>
          <el-button size="small" plain :loading="archivingId === item.id" :disabled="Boolean(archivingId)" @click="toggleArchive(item)"><Icon :icon="item.archived ? 'lucide:archive-restore' : 'lucide:archive'" />{{ item.archived ? '恢复' : '归档' }}</el-button>
        </div>
      </article>
    </section>
    <section v-else class="card empty-state"><div><Icon icon="lucide:history" width="30" /><strong>{{ designs.length ? '没有匹配的历史设计' : '还没有历史设计' }}</strong><p>{{ designs.length ? '调整项目、状态、归档范围或时间排序后重试。' : '新建设计后，未确认成任务的会话会集中显示在这里。' }}</p><el-button v-if="designs.length" plain @click="resetFilters">清除筛选</el-button><el-button v-else type="primary" @click="router.push('/designer')">新建设计</el-button></div></section>
  </main>
</template>

<style scoped>
.design-history-page { min-width: 0; }
.history-intro { display: flex; align-items: end; justify-content: space-between; gap: 24px; margin-bottom: 18px; }
.history-intro h2 { margin: 4px 0 7px; font-size: 20px; }
.history-intro p:last-child { margin: 0; color: var(--color-text-secondary); font-size: 11px; line-height: 1.65; }
.history-counts { display: flex; flex: 0 0 auto; gap: 10px; }
.history-counts span { display: grid; min-width: 76px; gap: 3px; padding: 10px 12px; border: 1px solid var(--color-border-default); border-radius: 10px; color: var(--color-text-muted); background: rgb(7 11 20 / 35%); font-size: 9px; text-align: right; }
.history-counts b { color: var(--color-text-primary); font: 17px/1 var(--font-code); }
.history-toolbar { display: grid; grid-template-columns: minmax(220px, 1.5fr) repeat(4, minmax(132px, .7fr)) auto; align-items: center; gap: 9px; }
.history-toolbar :deep(.el-select) { width: 100%; }
.history-search { min-width: 0; }
.history-list { display: grid; gap: 10px; min-width: 0; }
.history-card { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 20px; min-width: 0; min-height: 104px; padding: 16px 18px; overflow: hidden; }
.history-card.archived { opacity: .72; }
.history-card-main { min-width: 0; }
.history-card-heading { display: flex; align-items: center; gap: 7px; }
.history-status, .package-tag { display: inline-flex; align-items: center; min-height: 22px; padding: 0 8px; border: 1px solid currentcolor; border-radius: 999px; font: 8px/1 var(--font-code); }
.status-processing { color: var(--color-accent-cyan); }.status-reviewing { color: var(--color-success); }.status-waiting_input { color: var(--color-session-warning); }.status-session_error { color: var(--color-task-danger); }.status-archived { color: var(--color-text-muted); }
.package-tag { color: #a5b4fc; }
.history-card h3 { display: -webkit-box; margin: 9px 0 8px; overflow: hidden; color: var(--color-text-primary); font-size: 13px; line-height: 1.45; overflow-wrap: anywhere; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.history-meta { display: flex; min-width: 0; flex-wrap: wrap; gap: 7px 15px; color: var(--color-text-muted); font: 9px/1.4 var(--font-code); }
.history-meta span { display: inline-flex; min-width: 0; align-items: center; gap: 5px; }
.history-meta svg { flex: 0 0 auto; }
.history-actions { display: flex; flex: 0 0 auto; flex-wrap: wrap; justify-content: flex-end; gap: 8px; max-width: 260px; }
.history-error { display: flex; align-items: center; gap: 12px; padding: 16px; color: var(--color-task-danger); }.history-error div { min-width: 0; flex: 1; }.history-error p { margin: 4px 0 0; color: var(--color-text-secondary); font-size: 11px; }
.skeleton-block { height: 104px; }
@media (max-width: 1180px) { .history-toolbar { grid-template-columns: repeat(2, minmax(0, 1fr)); }.history-search { grid-column: 1 / -1; } }
@media (max-width: 760px) { .history-intro { align-items: stretch; flex-direction: column; }.history-counts { align-self: stretch; }.history-counts span { flex: 1; text-align: left; }.history-toolbar { grid-template-columns: 1fr; }.history-search { grid-column: auto; }.history-card { align-items: stretch; grid-template-columns: 1fr; }.history-actions { justify-content: flex-start; max-width: none; }.history-actions :deep(.el-button) { flex: 1; margin-left: 0; }.history-meta { display: grid; } }
</style>
