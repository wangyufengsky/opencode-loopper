<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Icon } from '@iconify/vue'
import { ElAlert, ElDatePicker } from 'element-plus'
import { api } from '@/api/client'
import PageHeader from '@/components/PageHeader.vue'
import DocumentFilePicker from '@/components/DocumentFilePicker.vue'
import DirectoryPathInput from '@/components/DirectoryPathInput.vue'
import SourceTemplateFields from '@/components/SourceTemplateFields.vue'
import '@/styles/template-task-form.css'
import { documentUploadError, useDocumentTemplateStore } from '@/stores/documentTemplateStore'
import { useTemplateTaskStore } from '@/stores/templateTaskStore'
import { userFacingError } from '@/utils/displayLabels'
import type { TemplateBranchChoice, TemplateProjectChoice, TemplateTaskDefinition } from '@/types/domain'

const router = useRouter()
const route = useRoute()
const documents = useDocumentTemplateStore()
const files = ref<File[]>([])
const fileError = computed(() => files.value.length ? documentUploadError(files.value) : '')
const sourceFields = ref<InstanceType<typeof SourceTemplateFields>>()
const busy = computed(() => store.submitting || documents.submitting || sourceFields.value?.submitting)
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
const reviewMode = ref<'DATE_INCREMENTAL' | 'FULL'>('DATE_INCREMENTAL')
const projects = ref<TemplateProjectChoice[]>([])
const branches = ref<TemplateBranchChoice[]>([])
const projectCursor = ref<string | null>()
const branchCursor = ref<string | null>()
const projectQuery = ref('')
const branchQuery = ref('')
const loadingProjects = ref(false)
const loadingBranches = ref(false)
const remoteAvailable = ref(true)
const remoteProblems = ref<string[]>([])
const error = ref('')
const branchError = ref('')
let projectGeneration = 0
let branchGeneration = 0
const definition = computed(() => store.catalog?.templates.find(item => item.id === selected.value))
const isDocument = computed(() => definition.value?.inputs?.documents === true)
const isSource = computed(() => definition.value?.inputs?.sourcePath === true)
const needsBranch = computed(() => definition.value?.inputs?.branch ?? true)
const isSnapshot = computed(() => definition.value?.workflow === 'SNAPSHOT_CODE_REVIEW')
const needsDates = computed(() => isSnapshot.value ? reviewMode.value === 'DATE_INCREMENTAL' : definition.value?.inputs?.dates ?? true)
const dateError = computed(() => startDate.value && endDate.value && endDate.value < startDate.value ? '结束日期不能早于开始日期' : '')
const valid = computed(() => definition.value && projectId.value && !busy.value
  && (!needsBranch.value || (branchId.value && !loadingBranches.value))
  && (!needsDates.value || (startDate.value && endDate.value && !dateError.value))
  && (isSource.value ? sourceFields.value?.valid : isDocument.value ? files.value.length > 0 && !fileError.value : !pickingDocumentPath.value))

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
    remoteProblems.value = result.remoteProblems ?? []
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
  if (projectId.value && needsBranch.value) void searchBranches('', false, true)
})
watch(needsBranch, value => {
  ++branchGeneration; loadingBranches.value = false; branchError.value = ''; branches.value = []; branchId.value = ''
  if (value && projectId.value) void searchBranches('', false, true)
})
function calendarDate(date: Date) { return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}` }
function disableEnd(date: Date) { return !!startDate.value && calendarDate(date) < startDate.value }
async function submit() {
  if (!valid.value || !definition.value) return
  error.value = ''
  try {
    if (isSource.value) {
      const id = await sourceFields.value?.submit()
      if (id) await router.push(`/template-tasks/source-runs/${id}`)
      return
    }
    if (isDocument.value) {
      const id = await documents.start({ templateId: selected.value, templateVersion: definition.value.version,
        projectId: projectId.value, ...(needsBranch.value ? { branchId: branchId.value } : {}) }, files.value)
      if (id) await router.push(`/template-tasks/document-runs/${id}`)
      return
    }
    const id = await store.start({ templateId: selected.value, templateVersion: definition.value.version, projectId: projectId.value,
      branchId: branchId.value, ...(needsDates.value ? { startDate: startDate.value, endDate: endDate.value } : {}),
      ...(isSnapshot.value ? { reviewMode: reviewMode.value } : {}), documentPath: documentPath.value.trim() || undefined })
    if (id) await router.push(`/tasks/${id}`)
  } catch (failure) { error.value = userFacingError(failure, '未能开始执行，请重试；已确认的任务会继续复用') }
}
onMounted(async () => {
  await Promise.all([searchProjects(), store.loadCatalog().then(() => {
    selected.value = store.catalog?.templates[0]?.id ?? ''
    startDate.value = store.catalog?.defaultStartDate ?? ''; endDate.value = store.catalog?.defaultEndDate ?? ''
  }).catch(failure => { error.value = userFacingError(failure, '模板目录加载失败，请刷新页面') })])
  if (typeof route.query.projectId === 'string') {
    try {
      const inherited = await api.templateProject(route.query.projectId)
      if (!projects.value.some(project => project.id === inherited.id)) projects.value.unshift(inherited)
      projectId.value = inherited.id
    } catch (failure) { error.value = userFacingError(failure, '无法读取入口项目，请重新选择') }
  }
  try { await documents.restore() } catch (failure) { error.value = userFacingError(failure, '上次上传状态读取失败，可从历史任务查看') }
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
          :class="{ selected: selected === item.id }" :aria-pressed="selected === item.id" :disabled="busy" @click="selected = item.id">
          <Icon :icon="item.icon?.startsWith('lucide:') ? item.icon : 'lucide:workflow'" width="22" />
          <span><strong>{{ item.title }}</strong><span class="muted tiny">{{ item.description }}</span></span>
          <Icon v-if="selected === item.id" icon="lucide:check" width="18" />
        </button>
        <p v-if="store.catalog && !visibleTemplates.length" class="muted">没有匹配的模板</p>
        <p v-else-if="!store.catalog && !error" class="muted">正在加载模板…</p>
      </section>
    </aside>
    <div class="template-configuration">
    <form v-if="definition" class="card task-parameters" aria-label="模板任务参数" @submit.prevent="submit">
      <header class="parameter-heading">
        <span class="parameter-icon" aria-hidden="true"><Icon :icon="definition.icon?.startsWith('lucide:') ? definition.icon : 'lucide:workflow'" width="26" /></span>
        <div class="parameter-intro"><span class="parameter-kicker">{{ definition.category || '任务配置' }}</span><h2>{{ definition.title }}</h2><p class="muted">{{ definition.description }}</p>
          <div v-if="isSource" class="output-tags" aria-label="任务产出">
            <template v-if="definition.inputs?.testOutputPath"><span><Icon icon="lucide:flask-conical" />单元测试</span><span><Icon icon="lucide:check-check" />测试验证与评审</span></template>
            <template v-else><span><Icon icon="lucide:file-text" />Markdown 文档</span><span><Icon icon="lucide:git-branch" />流程图</span><span><Icon icon="lucide:list-checks" />源码覆盖清单</span></template>
          </div>
        </div>
      </header>
      <div class="parameter-body">
      <section class="parameter-section" aria-label="项目与范围">
      <div class="section-heading"><span class="section-number">01</span><h3>{{ isSource ? '所属项目' : '项目与范围' }}</h3><span v-if="isSource" class="section-note">使用项目当前目录</span></div>
      <div v-if="isSnapshot" class="parameter-grid">
        <label>审查范围<el-select v-model="reviewMode" aria-label="审查范围" :disabled="busy"><el-option value="DATE_INCREMENTAL" label="日期增量审查" /><el-option value="FULL" label="全面审查" /></el-select></label>
        <p class="muted">{{ reviewMode === 'FULL' ? '审查所选分支的冻结目标版本。' : '对比开始日期 00:00 与结束日期 24:00 前的主线版本，审查最终差异。' }}</p>
      </div>
      <div class="parameter-grid" :class="{ 'single-column': !needsBranch && !needsDates }">
        <label>项目<el-select v-model="projectId" aria-label="项目" filterable remote :remote-method="searchProjects" :loading="loadingProjects" :disabled="busy" placeholder="搜索并选择项目">
          <el-option v-for="project in projects" :key="project.id" :value="project.id" :label="project.name" />
          <template v-if="projectCursor" #footer><el-button text :loading="loadingProjects" @click="searchProjects(projectQuery, true)">加载更多项目</el-button></template>
        </el-select></label>
        <label v-if="needsBranch">分支<el-select v-model="branchId" aria-label="分支" filterable remote :remote-method="searchBranches" :loading="loadingBranches" :disabled="!projectId || busy" placeholder="默认主分支，可搜索切换">
          <el-option v-for="branch in branches" :key="branch.id" :value="branch.id" :label="branch.label" />
          <template v-if="branchCursor" #footer><el-button text :loading="loadingBranches" @click="searchBranches(branchQuery, true)">加载更多分支</el-button></template>
        </el-select></label>
        <label v-if="needsDates">开始日期<el-date-picker v-model="startDate" aria-label="开始日期" type="date" value-format="YYYY-MM-DD" format="YYYY-MM-DD" :disabled="busy" :clearable="false" /></label>
        <label v-if="needsDates">结束日期<el-date-picker v-model="endDate" aria-label="结束日期" type="date" value-format="YYYY-MM-DD" format="YYYY-MM-DD" :disabled="busy" :disabled-date="disableEnd" :clearable="false" /></label>
      </div>
      </section>
      <SourceTemplateFields v-if="isSource" ref="sourceFields" :definition="definition" :project-id="projectId" :document-path="documentPath" />
      <div v-if="!isDocument && !isSource" class="parameter-section document-path"><div class="section-heading"><span class="section-number">02</span><h3>报告输出</h3><span class="section-note">可选</span></div><span>文档生成路径</span><DirectoryPathInput v-model="documentPath" v-model:picking="pickingDocumentPath" label="文档生成路径" :scope-key="projectId" :disabled="busy" placeholder="项目相对路径或绝对路径；留空使用默认目录" /></div>
      <div v-if="isDocument" class="parameter-section document-path">
        <div class="section-heading"><span class="section-number">02</span><h3>需求文档</h3></div>
        <DocumentFilePicker v-model="files" input-id="requirement-files" :disabled="busy" />
        <p class="muted tiny">图片和流程图的提取局限会在结果中列出。</p>
        <el-alert v-if="fileError" :title="fileError" type="error" :closable="false" />
        <p v-if="definition.id === 'REQUIREMENT_DEVELOPMENT'" class="muted">在项目当前目录开发，按需求自动设计、编码与测试；业务待决事项会暂停等待处理。</p>
        <p v-else class="muted">评审冻结分支的相关代码；本次不修改代码、不执行构建或测试。</p>
        <RouterLink v-if="documents.previousRun" :to="`/template-tasks/document-runs/${documents.previousRun.id}`">查看上次上传：{{ documents.previousRun.title }}</RouterLink>
      </div>
      <el-alert v-if="needsDates && dateError" :title="dateError" type="error" :closable="false" />
      <el-alert v-if="needsBranch && branchError" :title="branchError" type="error" :closable="false"><el-button text @click="searchBranches('', false, true)">重新读取分支</el-button></el-alert>
      <el-alert v-else-if="needsBranch && !remoteAvailable" :title="remoteProblems.length ? `${remoteProblems.join('；')}；也可明确选择可用的本地分支` : '部分远程分支暂不可访问，请检查连接后重新读取，或明确选择可用的本地分支'" type="warning" :closable="false" />
      </div>
      <footer class="run-action"><p v-if="isSource" class="action-hint"><Icon :icon="valid ? 'lucide:circle-check' : 'lucide:info'" />{{ valid ? '范围已检查，创建后在详情页开始执行' : '先检查处理范围，再创建任务' }}</p><el-button type="primary" native-type="submit" :loading="busy" :disabled="!valid">{{ isSource ? '创建任务' : isDocument ? (definition.id === 'REQUIREMENT_DEVELOPMENT' ? '开始开发' : '开始评审') : '开始执行' }}<Icon icon="lucide:arrow-right" /></el-button></footer>
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
.template-tasks { display: grid; gap: 20px; width: 100%; }
.template-workspace { display: grid; grid-template-columns: minmax(260px, 320px) minmax(0, 1fr); gap: 24px; align-items: start; }
.template-catalog, .template-configuration { display: grid; align-content: start; gap: 16px; min-width: 0; }
.catalog-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
h2 { margin: 0; font-size: 18px; }
.template-choices { display: grid; gap: 8px; }
.template-choice { display: grid; grid-template-columns: 22px minmax(0, 1fr) 16px; gap: 10px; align-items: start; padding: 12px 10px; text-align: left; color: var(--color-text-primary); background: transparent; border: 1px solid var(--color-border-default); border-radius: var(--radius-control); cursor: pointer; font: inherit; }
.template-choice > span { display: grid; gap: 4px; }
.template-choice strong { font-size: 14px; }
.template-choice strong, .template-choice span { overflow-wrap: anywhere; line-height: 1.6; }
.template-choice.selected { border-color: var(--color-action-primary); background: var(--color-bg-elevated); box-shadow: inset 3px 0 var(--color-action-primary); }
.template-choice:hover { background: var(--color-bg-elevated); }
.template-choice:focus-visible { outline: 2px solid var(--color-action-primary); outline-offset: 2px; }
.template-choice > svg { margin-top: 2px; color: var(--color-action-primary); }
.task-parameters { min-width: 0; overflow: hidden; }
.parameter-heading { display: flex; align-items: flex-start; gap: 16px; padding: 24px; border-bottom: 1px solid var(--color-border-default); background: var(--shade-fill-lighter); }
.parameter-icon { display: grid; place-items: center; flex: 0 0 48px; height: 48px; color: var(--color-action-primary); border: 1px solid var(--color-border-default); border-radius: var(--radius-card); background: var(--color-bg-surface); }
.parameter-intro { min-width: 0; }
.parameter-kicker { display: block; margin-bottom: 6px; color: var(--color-text-secondary); font-size: 11px; }
.parameter-heading h2 { font-size: 22px; line-height: 1.4; }
.parameter-heading p { margin: 8px 0 0; font-size: 13px; line-height: 1.7; }
.output-tags { display: flex; flex-wrap: wrap; gap: 8px 16px; margin-top: 14px; }
.output-tags span { display: inline-flex; align-items: center; gap: 6px; color: var(--color-text-secondary); font-size: 12px; }
.output-tags svg { color: var(--color-action-primary); }
.parameter-body { display: grid; align-content: start; gap: 24px; padding: 24px; }
.document-path { display: grid; gap: 10px; min-width: 0; }
.document-path > p { margin: 0; font-size: 12px; line-height: 1.7; }
.parameter-grid :deep(.el-date-editor), .parameter-grid :deep(.el-select) { width: 100%; }
.run-action { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 12px 20px; padding: 18px 24px; border-top: 1px solid var(--color-border-default); background: var(--shade-fill-lighter); }
.run-action > .el-button { min-height: 40px; min-width: 140px; margin: 0; }
.run-action :deep(.el-button > span) { gap: 10px; }
.action-hint { display: flex; align-items: center; gap: 8px; flex: 1; margin: 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.6; }
.action-hint svg { flex-shrink: 0; }
.rubric { font-size: 13px; line-height: 1.8; }
.rubric summary { cursor: pointer; }
@media (max-width: 1100px) { .template-workspace { grid-template-columns: 1fr; }.template-choices { grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); } }
@media (max-width: 700px) { .parameter-heading, .parameter-body { padding: 20px 16px; }.parameter-heading { gap: 12px; }.parameter-heading h2 { font-size: 20px; }.parameter-icon { flex-basis: 40px; height: 40px; }.run-action { padding: 16px; }.run-action > * { flex-basis: 100%; }.run-action > .el-button { width: 100%; } }
</style>
