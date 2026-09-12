<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Icon } from '@iconify/vue'
import { ElAlert, ElDatePicker } from 'element-plus'
import { api } from '@/api/client'
import PageHeader from '@/components/PageHeader.vue'
import DirectoryPathInput from '@/components/DirectoryPathInput.vue'
import { useTemplateTaskStore } from '@/stores/templateTaskStore'
import { userFacingError } from '@/utils/displayLabels'
import type { TemplateBranchChoice, TemplateProjectChoice, TemplateTaskDefinition } from '@/types/domain'

const router = useRouter()
const store = useTemplateTaskStore()
const selected = ref<TemplateTaskDefinition['id']>('')
const templateQuery = ref('')
const category = ref('全部')
const documentPath = ref('')
const pickingDocumentPath = ref(false)
const categories = computed(() => ['全部', ...new Set(store.catalog?.templates.map(item => item.category || '通用') ?? [])])
const visibleTemplates = computed(() => (store.catalog?.templates ?? []).filter(item =>
  (category.value === '全部' || (item.category || '通用') === category.value)
  && (!templateQuery.value.trim() || `${item.title} ${item.description}`.toLocaleLowerCase().includes(templateQuery.value.trim().toLocaleLowerCase()))))
const projectId = ref('')
const branchId = ref('')
const startDate = ref('')
const endDate = ref('')
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
const definition = computed(() => store.catalog?.templates.find(item => item.id === selected.value))
const dateError = computed(() => startDate.value && endDate.value && endDate.value < startDate.value ? '结束日期不能早于开始日期' : '')
const valid = computed(() => definition.value && projectId.value && branchId.value && startDate.value && endDate.value
  && !dateError.value && !loadingBranches.value && !pickingDocumentPath.value)

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
  documentPath.value = projects.value.find(project => project.id === projectId.value)?.documentPath ?? ''
  if (projectId.value) void searchBranches('', false, true)
})
function calendarDate(date: Date) { return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}` }
function disableEnd(date: Date) { return !!startDate.value && calendarDate(date) < startDate.value }
async function submit() {
  if (!valid.value || !definition.value) return
  error.value = ''
  try {
    const id = await store.start({ templateId: selected.value, templateVersion: definition.value.version, projectId: projectId.value,
      branchId: branchId.value, startDate: startDate.value, endDate: endDate.value, documentPath: documentPath.value.trim() || undefined })
    if (id) await router.push(`/tasks/${id}`)
  } catch (failure) { error.value = userFacingError(failure, '未能开始执行，请重试；已确认的任务会继续复用') }
}
onMounted(async () => {
  await Promise.all([searchProjects(), store.loadCatalog().then(() => {
    selected.value = store.catalog?.templates[0]?.id ?? ''
    startDate.value = store.catalog?.defaultStartDate ?? ''; endDate.value = store.catalog?.defaultEndDate ?? ''
  }).catch(failure => { error.value = userFacingError(failure, '模板目录加载失败，请刷新页面') })])
})
onBeforeUnmount(() => { ++projectGeneration; ++branchGeneration })
</script>

<template>
  <PageHeader eyebrow="任务" title="模板任务">
    <template #actions><el-button plain @click="router.push({ path: '/tasks', query: { type: 'template' } })"><Icon icon="lucide:history" />历史任务</el-button></template>
  </PageHeader>
  <main id="main-content" class="content template-tasks" tabindex="-1">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <div class="template-workspace">
    <aside class="card card-pad template-catalog" aria-label="模板目录">
      <div class="catalog-heading"><h2>选择模板</h2><span class="muted tiny">{{ store.catalog?.templates.length ?? 0 }} 个</span></div>
      <el-input v-model="templateQuery" clearable aria-label="搜索模板" placeholder="搜索模板"><template #prefix><Icon icon="lucide:search" /></template></el-input>
      <el-select v-if="categories.length > 2" v-model="category" aria-label="模板分类"><el-option v-for="item in categories" :key="item" :value="item" :label="item === '全部' ? '全部分类' : item" /></el-select>
      <section class="template-choices" aria-label="选择模板任务">
        <button v-for="item in visibleTemplates" :key="item.id" type="button" class="template-choice"
          :class="{ selected: selected === item.id }" :aria-pressed="selected === item.id" :disabled="store.submitting" @click="selected = item.id">
          <Icon :icon="item.icon?.startsWith('lucide:') ? item.icon : 'lucide:workflow'" width="22" />
          <span><strong>{{ item.title }}</strong><span class="muted tiny">{{ item.description }}</span></span>
          <Icon v-if="selected === item.id" icon="lucide:check" width="18" />
        </button>
        <p v-if="store.catalog && !visibleTemplates.length" class="muted">没有匹配的模板</p>
        <p v-else-if="!store.catalog && !error" class="muted">正在加载模板…</p>
      </section>
    </aside>
    <div class="template-configuration">
    <form v-if="definition" class="card card-pad task-parameters" aria-label="模板任务参数" @submit.prevent="submit">
      <header class="parameter-heading"><h2>{{ definition.title }}</h2><p class="muted">{{ definition.description }}</p></header>
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
      <div class="document-path">文档生成路径<DirectoryPathInput v-model="documentPath" v-model:picking="pickingDocumentPath" label="文档生成路径" :scope-key="projectId" :disabled="store.submitting" placeholder="项目相对路径或绝对路径；留空使用默认目录" /></div>
      <el-alert v-if="dateError" :title="dateError" type="error" :closable="false" />
      <el-alert v-if="branchError" :title="branchError" type="error" :closable="false"><el-button text @click="searchBranches('', false, true)">重新读取分支</el-button></el-alert>
      <el-alert v-else-if="!remoteAvailable" title="部分远程分支暂不可访问，请检查连接后重新读取，或明确选择可用的本地分支" type="warning" :closable="false" />
      <div class="run-action"><el-button type="primary" native-type="submit" :loading="store.submitting" :disabled="!valid">开始执行</el-button></div>
    </form>
    <details v-if="definition?.scoringVersion && store.catalog" class="card card-pad rubric">
      <summary>内置评分标准 · 满分 100</summary>
      <p>数量 30 分，以有效增删行进行对数归一化；价值 25、难度 15、质量 20、维护 10 分。模型给出有证据支持的等级，系统计算总分与排名。同分同名次。</p>
      <div v-for="dimension in store.catalog.dimensions" :key="dimension.title"><strong>{{ dimension.title }} · {{ dimension.weight }} 分</strong><p>{{ dimension.levels.map((level, index) => `${index}级：${level}`).join('；') }}</p></div>
      <p class="muted">这是本项目的贡献观察标准。机器人单列，共同作者等分；生成代码、依赖及重复补丁等去噪均有记录。Git 贡献不能代表个人全部工作价值。</p>
    </details>
    </div>
    </div>
  </main>
</template>

<style scoped>
.template-tasks { display: grid; gap: 20px; }
.template-workspace { display: grid; grid-template-columns: minmax(260px, 340px) minmax(0, 1fr); gap: 24px; align-items: start; }
.template-catalog, .template-configuration, .task-parameters { display: grid; gap: 20px; min-width: 0; }
.catalog-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
h2 { margin: 0; font-size: 18px; }
.template-choices { display: grid; gap: 10px; max-height: 560px; overflow: auto; }
.template-choice { display: grid; grid-template-columns: 24px minmax(0, 1fr) 18px; gap: 12px; align-items: start; padding: 16px 12px; text-align: left; color: var(--color-text-primary); background: transparent; border: 1px solid var(--color-border-default); border-radius: 10px; cursor: pointer; font: inherit; }
.template-choice > span { display: grid; gap: 8px; }
.template-choice strong, .template-choice span { overflow-wrap: anywhere; line-height: 1.6; }
.template-choice.selected { border-color: var(--color-action-primary); background: var(--color-bg-elevated); }
.template-choice:focus-visible { outline: 2px solid var(--color-action-primary); outline-offset: 2px; }
.template-choice > svg { margin-top: 3px; color: var(--color-action-primary); }
.parameter-heading p { margin: 10px 0 0; line-height: 1.7; }
.parameter-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }
.parameter-grid label, .document-path { display: grid; gap: 10px; min-width: 0; }
.parameter-grid :deep(.el-date-editor), .parameter-grid :deep(.el-select) { width: 100%; }
.run-action { display: flex; justify-content: flex-end; }
.rubric { line-height: 1.8; }
.rubric summary { cursor: pointer; }
@media (max-width: 1000px) { .template-workspace { grid-template-columns: 1fr; }.template-choices { grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); max-height: 380px; } }
@media (max-width: 700px) { .parameter-grid { grid-template-columns: 1fr; }.run-action > * { width: 100%; } }
</style>
