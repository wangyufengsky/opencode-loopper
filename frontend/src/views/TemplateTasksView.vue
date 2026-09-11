<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Icon } from '@iconify/vue'
import { ElAlert, ElDatePicker } from 'element-plus'
import { api } from '@/api/client'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import StoryBindingSetup from '@/components/StoryBindingSetup.vue'
import { useTemplateTaskStore } from '@/stores/templateTaskStore'
import { userFacingError } from '@/utils/displayLabels'
import type { StoryBindingConfiguration, TemplateBranchChoice, TemplateProjectChoice, TemplateTaskDefinition } from '@/types/domain'

const router = useRouter()
const store = useTemplateTaskStore()
const selected = ref<TemplateTaskDefinition['id']>('CODE_REVIEW')
const projectId = ref('')
const branchId = ref('')
const startDate = ref('')
const endDate = ref('')
const story = ref<StoryBindingConfiguration>({ enabled: false })
const projects = ref<TemplateProjectChoice[]>([])
const branches = ref<TemplateBranchChoice[]>([])
const projectCursor = ref<string | null>()
const branchCursor = ref<string | null>()
const projectQuery = ref('')
const branchQuery = ref('')
const loadingProjects = ref(false)
const loadingBranches = ref(false)
const remoteAvailable = ref(true)
const error = ref('')
const branchError = ref('')
let projectGeneration = 0
let branchGeneration = 0
let timer: ReturnType<typeof setInterval> | undefined
const definition = computed(() => store.catalog?.templates.find(item => item.id === selected.value))
const dateError = computed(() => startDate.value && endDate.value && endDate.value < startDate.value ? '结束日期不能早于开始日期' : '')
const valid = computed(() => definition.value && projectId.value && branchId.value && startDate.value && endDate.value
  && !dateError.value && !loadingBranches.value && (!story.value.enabled || (story.value.systemCode?.trim() && story.value.storyCode?.trim())))

async function searchProjects(query = '', append = false) {
  const generation = ++projectGeneration
  projectQuery.value = query
  loadingProjects.value = true
  try {
    const page = await api.templateProjects(query, append ? projectCursor.value ?? undefined : undefined)
    if (generation !== projectGeneration) return
    projects.value = append ? [...projects.value, ...page.items] : page.items
    projectCursor.value = page.nextCursor
  } catch (failure) { if (generation === projectGeneration) error.value = userFacingError(failure, '项目列表加载失败，请重试') }
  finally { if (generation === projectGeneration) loadingProjects.value = false }
}
async function searchBranches(query = '', append = false, chooseDefault = false) {
  const generation = ++branchGeneration
  if (!projectId.value) return
  branchQuery.value = query
  loadingBranches.value = true
  branchError.value = ''
  try {
    const result = await api.templateBranches(projectId.value, query, append ? branchCursor.value ?? undefined : undefined)
    if (generation !== branchGeneration) return
    branches.value = append ? [...branches.value, ...result.page.items] : result.page.items
    branchCursor.value = result.page.nextCursor
    remoteAvailable.value = result.remoteAvailable
    if (chooseDefault && result.defaultBranch) {
      if (!branches.value.some(item => item.id === result.defaultBranchId)) branches.value.unshift(result.defaultBranch)
      branchId.value = result.defaultBranch.id
    }
  } catch (failure) { if (generation === branchGeneration) branchError.value = userFacingError(failure, '分支读取失败，请检查仓库连接后重试') }
  finally { if (generation === branchGeneration) loadingBranches.value = false }
}
watch(projectId, () => {
  ++branchGeneration
  branches.value = []; branchId.value = ''; branchCursor.value = null; branchQuery.value = ''
  story.value = { enabled: false }
  if (projectId.value) void searchBranches('', false, true)
})
function calendarDate(date: Date) { return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}` }
function disableEnd(date: Date) { return !!startDate.value && calendarDate(date) < startDate.value }
async function submit() {
  if (!valid.value || !definition.value) return
  error.value = ''
  try {
    const id = await store.start({ templateId: selected.value, templateVersion: definition.value.version, projectId: projectId.value,
      branchId: branchId.value, startDate: startDate.value, endDate: endDate.value, story: story.value })
    if (id) await router.push(`/tasks/${id}`)
  } catch (failure) { error.value = userFacingError(failure, '未能开始执行，请重试；已确认的任务会继续复用') }
}
async function refresh() {
  try { await store.loadRuns() } catch (failure) { error.value = userFacingError(failure, '执行历史加载失败，请重试') }
}
onMounted(async () => {
  await Promise.all([searchProjects(), refresh(), store.loadCatalog().then(() => {
    startDate.value = store.catalog?.defaultStartDate ?? ''; endDate.value = store.catalog?.defaultEndDate ?? ''
  }).catch(failure => { error.value = userFacingError(failure, '模板目录加载失败，请刷新页面') })])
  timer = setInterval(() => {
    if (!document.hidden && store.runs.some(run => ['QUEUED', 'PREPARING', 'READY', 'RUNNING', 'VERIFYING', 'JUDGING', 'STOPPING'].includes(run.state))) void refresh()
  }, 5000)
})
onBeforeUnmount(() => { ++projectGeneration; ++branchGeneration; if (timer) clearInterval(timer) })
</script>

<template>
  <PageHeader eyebrow="公共任务" title="模板任务" />
  <main id="main-content" class="content template-tasks" tabindex="-1">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <section class="template-choices" aria-label="选择模板任务">
      <button v-for="item in store.catalog?.templates" :key="item.id" type="button" class="card template-choice"
        :class="{ selected: selected === item.id }" :aria-pressed="selected === item.id" :disabled="store.submitting" @click="selected = item.id">
        <Icon :icon="item.id === 'CODE_REVIEW' ? 'lucide:scan-search' : 'lucide:chart-no-axes-combined'" width="24" />
        <strong>{{ item.title }}</strong><span class="muted">{{ item.description }}</span>
      </button>
    </section>
    <form class="card card-pad task-parameters" aria-label="模板任务参数" @submit.prevent="submit">
      <div class="parameter-grid">
        <label>项目<el-select v-model="projectId" aria-label="项目" filterable remote :remote-method="searchProjects" :loading="loadingProjects" :disabled="store.submitting" placeholder="搜索并选择项目">
          <el-option v-for="project in projects" :key="project.id" :value="project.id" :label="project.name" />
          <template v-if="projectCursor" #footer><el-button text :loading="loadingProjects" @click="searchProjects(projectQuery, true)">加载更多项目</el-button></template>
        </el-select></label>
        <label>分支<el-select v-model="branchId" aria-label="分支" filterable remote :remote-method="searchBranches" :loading="loadingBranches" :disabled="!projectId || store.submitting" placeholder="默认主分支，可搜索切换">
          <el-option v-for="branch in branches" :key="branch.id" :value="branch.id" :label="branch.label" />
          <template v-if="branchCursor" #footer><el-button text :loading="loadingBranches" @click="searchBranches(branchQuery, true)">加载更多分支</el-button></template>
        </el-select></label>
        <label>开始日期<el-date-picker v-model="startDate" aria-label="开始日期" type="date" value-format="YYYY-MM-DD" format="YYYY-MM-DD" :disabled="store.submitting" :clearable="false" /></label>
        <label>结束日期<el-date-picker v-model="endDate" aria-label="结束日期" type="date" value-format="YYYY-MM-DD" format="YYYY-MM-DD" :disabled="store.submitting" :disabled-date="disableEnd" :clearable="false" /></label>
      </div>
      <p class="muted tiny">北京时间 · 包含开始和结束当天 · 统计所选分支可达提交的提交时间</p>
      <el-alert v-if="dateError" :title="dateError" type="error" :closable="false" />
      <el-alert v-if="branchError" :title="branchError" type="error" :closable="false"><el-button text @click="searchBranches('', false, true)">重新读取分支</el-button></el-alert>
      <el-alert v-else-if="!remoteAvailable" title="部分远程分支暂不可访问，请检查连接后重新读取，或明确选择可用的本地分支" type="warning" :closable="false" />
      <StoryBindingSetup v-model="story" :project-id="projectId" :disabled="store.submitting" template-task />
      <div class="run-action"><span class="muted">{{ definition?.stages.join(' → ') }} → 双评审</span><el-button type="primary" native-type="submit" :loading="store.submitting" :disabled="!valid">开始执行</el-button></div>
    </form>
    <details v-if="selected === 'CONTRIBUTION_REPORT' && store.catalog" class="card card-pad rubric">
      <summary>内置评分标准 · 满分 100</summary>
      <p>数量 30 分，以有效增删行进行对数归一化；价值 25、难度 15、质量 20、维护 10 分。模型给出有证据支持的等级，系统计算总分与排名。同分同名次。</p>
      <div v-for="dimension in store.catalog.dimensions" :key="dimension.title"><strong>{{ dimension.title }} · {{ dimension.weight }} 分</strong><p>{{ dimension.levels.map((level, index) => `${index}级：${level}`).join('；') }}</p></div>
      <p class="muted">这是本项目的贡献观察标准。机器人单列，共同作者等分；生成代码、依赖及重复补丁等去噪均有记录。Git 贡献不能代表个人全部工作价值。</p>
    </details>
    <section class="card card-pad run-history" aria-labelledby="template-history-title">
      <div class="section-heading"><h2 id="template-history-title">执行历史</h2><el-button text :loading="store.loading" @click="refresh">刷新</el-button></div>
      <p v-if="!store.runs.length" class="muted">{{ store.loading ? '正在加载执行记录…' : '还没有模板任务' }}</p>
      <router-link v-for="run in store.runs" :key="run.id" :to="`/tasks/${run.id}`" class="run-row">
        <div><strong>{{ run.title }}</strong><p class="muted">{{ run.projectName }} · {{ run.branchLabel }}</p></div><StatusBadge :status="run.state" />
      </router-link>
      <el-button v-if="store.nextCursor" :loading="store.loading" @click="store.loadRuns('', true).catch(failure => error = userFacingError(failure, '加载失败，请重试'))">加载更多</el-button>
    </section>
  </main>
</template>

<style scoped>
.template-tasks { display: grid; gap: 20px; }
.template-choices, .parameter-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
.template-choice { padding: 24px; text-align: left; display: grid; gap: 12px; color: var(--color-text-primary); cursor: pointer; font: inherit; }
.template-choice.selected { border-color: var(--color-action-primary); background: var(--color-bg-elevated); }
.template-choice strong { font-size: 17px; }
.template-choice span { line-height: 1.65; }
.task-parameters { display: grid; gap: 18px; }
.parameter-grid label { display: grid; gap: 10px; min-width: 0; }
.parameter-grid :deep(.el-date-editor), .parameter-grid :deep(.el-select) { width: 100%; }
.run-action, .section-heading, .run-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.run-row { color: inherit; text-decoration: none; padding: 18px 0; border-bottom: 1px solid var(--color-border-default); }
.run-row strong { overflow-wrap: anywhere; }
.run-row p { margin: 8px 0 0; }
.run-history h2 { font-size: 18px; margin: 0; }
.rubric { line-height: 1.8; }
.rubric summary { cursor: pointer; }
@media (max-width: 700px) { .template-choices, .parameter-grid { grid-template-columns: 1fr; } .run-action { align-items: stretch; flex-direction: column; } }
</style>
