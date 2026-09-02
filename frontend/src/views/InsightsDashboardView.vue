<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Icon } from '@iconify/vue'
import PageHeader from '@/components/PageHeader.vue'
import { api } from '@/api/client'
import type { TaskInsight, UsageAggregate, Project, InsightQuery } from '@/types/domain'
import { TASK_STATUSES } from '@/types/states'
import { statusLabel, userFacingError } from '@/utils/displayLabels'

const tasks = ref<TaskInsight[]>([]); const globalUsage = ref<UsageAggregate>(); const loading = ref(true); const error = ref('')
const nextCursor = ref<string>()
const projects = ref<Project[]>([])
const filters = reactive({ projectId: '', state: '', quality: '', archive: 'ACTIVE', query: '' })
let applied: InsightQuery = { archive: 'ACTIVE' }
let generation = 0
function apply() { applied = { ...filters }; nextCursor.value = undefined; void load() }
function reset() { Object.assign(filters, { projectId: '', state: '', quality: '', archive: 'ACTIVE', query: '' }); apply() }
const knownTasks = computed(() => tasks.value.filter(task => task.usage.totalTokens !== null).length)
const qualityMeta: Record<TaskInsight['quality']['state'], { label: string; icon: string }> = {
  PASS: { label: '质量通过', icon: 'lucide:shield-check' },
  REVIEW_REQUIRED: { label: '待评审', icon: 'lucide:scan-eye' },
  PENDING: { label: '待验收', icon: 'lucide:clock-3' },
}
function tokens(value: number | null) { return value === null ? '未知' : value.toLocaleString('zh-CN') }
function duration(value: number) { return value < 60_000 ? `${Math.round(value / 1000)} 秒` : `${Math.floor(value / 60_000)} 分 ${Math.round(value % 60_000 / 1000)} 秒` }
async function load(append = false) {
  const request = ++generation
  loading.value = true; error.value = ''
  try {
    const payload = await api.getInsightsPage({ ...applied, cursor: append ? nextCursor.value : undefined })
    if (request !== generation) return
    tasks.value = append ? [...tasks.value, ...payload.tasks] : payload.tasks
    globalUsage.value = payload.usage; nextCursor.value = payload.nextCursor
  } catch (cause) { if (request === generation) error.value = userFacingError(cause, '无法读取服务端洞察') }
  finally { if (request === generation) loading.value = false }
}
onMounted(() => { void load(); void api.getProjects().then(value => { projects.value = value }).catch(() => { /* Filters remain usable while project discovery is unavailable. */ }) })
</script>

<template>
  <PageHeader eyebrow="任务分析" title="用量与质量">
    <template #actions><el-button :loading="loading" @click="load()"><Icon icon="lucide:refresh-cw" />刷新</el-button></template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <form class="card insight-filters" aria-label="洞察筛选" @submit.prevent="apply">
      <el-select v-model="filters.projectId" aria-label="项目筛选" placeholder="全部项目" clearable><el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" /></el-select>
      <el-select v-model="filters.state" aria-label="任务状态筛选" placeholder="全部状态" clearable><el-option v-for="state in TASK_STATUSES" :key="state" :label="statusLabel(state)" :value="state" /></el-select>
      <el-select v-model="filters.quality" aria-label="质量筛选" placeholder="全部质量" clearable><el-option v-for="(meta, value) in qualityMeta" :key="value" :label="meta.label" :value="value" /></el-select>
      <el-select v-model="filters.archive" aria-label="归档筛选"><el-option label="未归档" value="ACTIVE" /><el-option label="已归档" value="ARCHIVED" /><el-option label="全部归档状态" value="ALL" /></el-select>
      <el-input v-model="filters.query" aria-label="搜索任务标题" placeholder="搜索任务标题" clearable maxlength="200" />
      <el-button native-type="submit" type="primary">筛选</el-button><el-button @click="reset">重置</el-button>
    </form>
    <section v-if="error" class="error-panel error-panel-task" role="status"><Icon class="error-panel-icon" icon="lucide:triangle-alert" /><div><h3>数据未更新</h3><p>{{ userFacingError(error, '无法读取用量与质量数据') }}</p></div></section>
    <section v-else-if="loading" class="card empty-state"><div><Icon icon="lucide:loader-circle" class="spin" /><strong>正在读取数据…</strong></div></section>
    <template v-else>
      <section class="insight-metrics" aria-label="筛选范围用量"><article class="card"><span>筛选范围总用量</span><strong>{{ tokens(globalUsage?.totalTokens ?? null) }}</strong><small>{{ globalUsage?.unknownUsageCount ?? 0 }} 条未知记录</small></article><article class="card"><span>已加载且有用量的任务</span><strong>{{ knownTasks }}</strong></article><article class="card"><span>成本（按币种）</span><strong class="costs">{{ Object.entries(globalUsage?.costByCurrency ?? {}).map(([currency, amount]) => `${currency} ${amount}`).join(' · ') || '未知' }}</strong></article></section>
      <section v-if="!tasks.length" class="card empty-state"><div><Icon icon="lucide:chart-no-axes-combined" /><strong>暂无可汇总数据</strong></div></section>
      <section v-else class="insight-list" aria-label="任务洞察"><article v-for="task in tasks" :key="task.taskId" class="card insight-row"><header><div><p class="eyebrow">{{ statusLabel(task.state) }}</p><h2>{{ task.title }}</h2></div><RouterLink :to="`/tasks/${task.taskId}#judge-review`" class="quality-link" :aria-label="`${qualityMeta[task.quality.state].label}，查看任务评审`"><span :class="['quality', `quality-${task.quality.state.toLowerCase()}`]" :title="qualityMeta[task.quality.state].label"><span class="quality-icon" aria-hidden="true"><Icon :icon="qualityMeta[task.quality.state].icon" /></span><span>{{ qualityMeta[task.quality.state].label }}</span></span></RouterLink></header><dl><div><dt>用量</dt><dd>{{ tokens(task.usage.totalTokens) }}<small v-if="task.usage.unknownUsageCount"> + {{ task.usage.unknownUsageCount }} 未知</small></dd></div><div><dt>耗时</dt><dd>{{ duration(task.durationMs) }}</dd></div><div><dt>重试</dt><dd>{{ task.retryCount }}</dd></div><div><dt>质量</dt><dd>{{ task.quality.deterministicPassed ? '验收通过' : '验收待定' }} · {{ task.quality.humanApproved ? '人工认定通过' : task.quality.requirementJudgePassed && task.quality.riskJudgePassed ? 'AI 双评审通过' : 'AI 评审供参考' }}</dd></div></dl></article></section>
      <div v-if="nextCursor" class="load-more"><el-button plain :loading="loading" @click="load(true)">加载更多</el-button></div>
    </template>
  </main>
</template>

<style scoped>
.insight-filters{display:flex;flex-wrap:wrap;gap:10px;padding:16px;margin-bottom:16px}.insight-filters .el-select{width:160px}.insight-filters .el-input{width:220px}
.insight-metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin-bottom:16px}.insight-metrics article,.insight-row{padding:16px;background:linear-gradient(145deg,rgb(18 28 48 / 96%),rgb(8 14 28 / 92%))}.insight-metrics span,.insight-metrics small,dt{color:var(--color-text-muted);font:700 10px/1.4 var(--font-code)}.insight-metrics strong{display:block;margin:10px 0 5px;color:var(--color-text-primary);font-size:24px}.insight-metrics .costs{font-size:13px;line-height:1.55}.insight-list{display:grid;gap:12px}.insight-row header{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.insight-row h2{margin:0;font-size:15px}.quality{--quality-accent:var(--color-session-warning);--quality-border:rgb(245 158 11 / 24%);--quality-surface:rgb(245 158 11 / 7%);display:inline-flex;align-items:center;flex:0 0 auto;gap:7px;min-height:28px;padding:3px 10px 3px 5px;border:1px solid var(--quality-border);border-radius:7px;color:var(--quality-accent);background:linear-gradient(135deg,var(--quality-surface),rgb(7 11 20 / 38%));box-shadow:inset 0 1px rgb(255 255 255 / 3%),0 5px 18px rgb(0 0 0 / 12%);font:650 10px/1 var(--font-ui);letter-spacing:.03em;white-space:nowrap}.quality-icon{display:grid;place-items:center;width:18px;height:18px;border:1px solid var(--quality-border);border-radius:5px;background:var(--quality-surface);font-size:12px}.quality-pass{--quality-accent:#5fd384;--quality-border:rgb(34 197 94 / 25%);--quality-surface:rgb(34 197 94 / 8%)}.quality-review_required{--quality-accent:#d6a74b;--quality-border:rgb(245 158 11 / 24%);--quality-surface:rgb(245 158 11 / 7%)}.quality-pending{--quality-accent:#8d9bb0;--quality-border:rgb(154 168 189 / 20%);--quality-surface:rgb(154 168 189 / 6%)}.insight-row dl{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:1px;margin:15px 0 0;background:var(--color-border-default)}.insight-row dl div{padding:10px;background:rgb(2 6 23 / 40%)}dd{margin:6px 0 0;color:var(--color-text-secondary);font-size:11px}dd small{color:var(--color-session-warning)}.spin{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}@media(max-width:800px){.insight-metrics,.insight-row dl{grid-template-columns:1fr}}
.quality-link{display:inline-flex;flex:0 0 auto;text-decoration:none}
.load-more{display:flex;justify-content:center;padding:16px 0}
</style>
