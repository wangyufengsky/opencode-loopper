<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { api } from '@/api/client'
import { useTaskStore } from '@/stores/taskStore'
import type { Project, ProjectConventionDraft, ProjectConventionSnapshot } from '@/types/domain'

const store = useTaskStore()
const dialogVisible = ref(false)
const saving = ref(false)
const pickingDirectory = ref(false)
const fieldError = ref('')
const form = ref({ name: '', rootPath: '', description: '' })
const conventionVisible = ref(false)
const conventionProject = ref<Project>()
const conventionSnapshot = ref<ProjectConventionSnapshot>()
const conventionDraft = ref<ProjectConventionDraft>()
const conventionError = ref('')
const loadingConvention = ref(false)
const generatingProjectId = ref('')
const applyingConvention = ref(false)
const cancellingProjectId = ref('')
let conventionPollTimer: number | undefined

function openDialog() {
  form.value = { name: '', rootPath: '', description: '' }
  fieldError.value = ''
  dialogVisible.value = true
}

async function pickDirectory() {
  fieldError.value = ''
  pickingDirectory.value = true
  try {
    const selection = store.usingDemo
      ? { selected: true, path: store.projects[0]?.rootPath ?? '/Users/name/IdeaProjects/project', name: store.projects[0]?.name ?? 'project' }
      : await api.pickProjectDirectory()
    if (!selection.selected || !selection.path) return
    form.value.rootPath = selection.path
    if (!form.value.name.trim()) form.value.name = selection.name ?? ''
    ElMessage.success(store.usingDemo ? '演示模式：已回填示例目录' : '已选择项目根目录')
  } catch (error) {
    fieldError.value = error instanceof Error ? error.message : '无法打开文件夹选择器，请手工填写绝对路径。'
  } finally {
    pickingDirectory.value = false
  }
}

async function submit() {
  fieldError.value = ''
  if (!form.value.name.trim()) { fieldError.value = '请输入项目名称。'; return }
  if (!/^(\/|[A-Za-z]:[\\/])/.test(form.value.rootPath.trim())) { fieldError.value = '请输入绝对项目根路径；相对路径不允许登记。'; return }
  saving.value = true
  try {
    if (store.usingDemo) {
      store.projects.push({ id: `demo-${Date.now()}`, ...form.value, status: 'NEEDS_GIT', updatedAt: new Date().toISOString(), taskCount: 0 })
    } else {
      store.projects.push(await api.createProject(form.value))
    }
    dialogVisible.value = false
    ElMessage.success('项目已登记')
  } catch (error) {
    fieldError.value = error instanceof Error ? error.message : '项目登记失败'
  } finally { saving.value = false }
}

function clearConventionPoll() {
  if (conventionPollTimer !== undefined) window.clearTimeout(conventionPollTimer)
  conventionPollTimer = undefined
}

function scheduleConventionPoll(projectId: string, draftId: string) {
  clearConventionPoll()
  conventionPollTimer = window.setTimeout(async () => {
    if (!conventionVisible.value) return
    try {
      const draft = await api.getProjectConventionDraft(projectId, draftId)
      conventionDraft.value = draft
      if (draft.state === 'RUNNING') scheduleConventionPoll(projectId, draftId)
    } catch (error) {
      conventionError.value = error instanceof Error ? error.message : '无法获取 AGENTS.md 生成状态'
    }
  }, 1000)
}

async function openConvention(project: Project) {
  clearConventionPoll()
  conventionProject.value = project
  conventionSnapshot.value = undefined
  conventionDraft.value = undefined
  conventionError.value = ''
  conventionVisible.value = true
  if (store.usingDemo) {
    conventionSnapshot.value = { projectId: project.id, exists: false, loopperManaged: false, content: '' }
    return
  }
  loadingConvention.value = true
  try {
    conventionSnapshot.value = await api.getCurrentProjectConvention(project.id)
  } catch (error) {
    conventionError.value = error instanceof Error ? error.message : '无法读取当前项目公约'
  } finally {
    loadingConvention.value = false
  }
}

async function generateConvention() {
  const project = conventionProject.value
  if (!project) return
  if (store.usingDemo) { ElMessage.warning('演示模式不会调用 AI 或写入项目文件'); return }
  clearConventionPoll()
  conventionDraft.value = undefined
  conventionError.value = ''
  generatingProjectId.value = project.id
  try {
    const draft = await api.generateProjectConvention(project.id)
    conventionDraft.value = draft
    if (draft.state === 'RUNNING') scheduleConventionPoll(project.id, draft.id)
  } catch (error) {
    conventionError.value = error instanceof Error ? error.message : 'AGENTS.md 生成失败'
  } finally {
    generatingProjectId.value = ''
  }
}

async function applyConvention() {
  const project = conventionProject.value
  const draft = conventionDraft.value
  if (!project || !draft || draft.state !== 'READY') return
  applyingConvention.value = true
  conventionError.value = ''
  try {
    conventionDraft.value = await api.applyProjectConvention(project.id, draft.id)
    conventionSnapshot.value = {
      projectId: project.id,
      exists: true,
      loopperManaged: true,
      content: conventionDraft.value.content ?? draft.content ?? '',
    }
    ElMessage.success(`${draft.operation === 'CREATE' ? '已创建' : '已更新'} AGENTS.md`)
  } catch (error) {
    conventionError.value = error instanceof Error ? error.message : 'AGENTS.md 写入失败'
  } finally {
    applyingConvention.value = false
  }
}

function closeConvention() {
  clearConventionPoll()
  conventionVisible.value = false
}

async function cancelManagement(project: Project) {
  if (store.usingDemo) {
    store.projects = store.projects.filter((item) => item.id !== project.id)
    ElMessage.success('演示项目已取消管理')
    return
  }
  try {
    await ElMessageBox.confirm(
      `取消管理“${project.name}”后，它会从项目登记列表消失；项目目录、历史任务、设计对话、LoopSpec 与执行证据都不会删除。`,
      '取消管理该项目？',
      { confirmButtonText: '确认取消管理', cancelButtonText: '返回', type: 'warning' },
    )
  } catch { return }
  cancellingProjectId.value = project.id
  try {
    await api.cancelProjectManagement(project.id)
    store.projects = store.projects.filter((item) => item.id !== project.id)
    ElMessage.success('已取消管理；项目文件与历史记录均已保留')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '取消管理失败')
  } finally {
    cancellingProjectId.value = ''
  }
}

onBeforeUnmount(clearConventionPoll)
</script>

<template>
  <PageHeader eyebrow="Workspace / Projects" title="项目登记" subtitle="登记受控项目根目录；有 Git HEAD 时隔离执行，否则直接修改原目录。">
    <template #actions><el-button type="primary" :icon="Icon" @click="openDialog"><Icon icon="lucide:plus" />登记项目</el-button></template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <section v-if="store.error && !store.usingDemo" class="card card-pad" style="margin-bottom: 16px">
      <div class="error-panel error-panel-verification"><Icon class="error-panel-icon" icon="lucide:plug-zap" /><div><h3>本地 API 不可用</h3><p>{{ store.error }}。你可以启动 Spring 服务，或者查看不发送请求的演示数据。</p><el-button size="small" plain type="primary" style="margin-top: 10px" @click="store.activateDemo()">查看演示数据</el-button></div></div>
    </section>
    <section class="toolbar"><div><p class="eyebrow">{{ store.usingDemo ? 'DEMO DATA' : 'REGISTERED ROOTS' }}</p><span class="muted tiny">{{ store.projects.length }} 个项目 · 路径在后端 canonicalize 后保存</span></div><el-button text :icon="Icon" @click="store.loadOverview"><Icon icon="lucide:refresh-cw" />刷新</el-button></section>
    <section v-if="store.loading" class="metric-grid"><div v-for="n in 4" :key="n" class="skeleton-block" style="height: 150px" /></section>
    <section v-else-if="store.projects.length" class="project-grid">
      <article v-for="project in store.projects" :key="project.id" class="card card-pad project-card">
        <div class="card-header"><div><div class="project-icon"><Icon icon="lucide:folder-git-2" /></div><h2 class="card-title" style="margin-top: 12px">{{ project.name }}</h2></div><StatusBadge :status="project.status === 'READY' ? 'SUCCEEDED' : project.status === 'INVALID' ? 'FAILED' : 'PENDING'" :label="project.status" /></div>
        <p class="card-description">{{ project.description || '尚未添加说明' }}</p>
        <div class="divider" /><p class="mono tiny muted project-path">{{ project.rootPath }}</p>
        <div class="project-footer">
          <div class="project-stats"><span class="mono tiny">{{ project.branch ?? 'no git head' }}</span><span class="tiny muted">{{ project.taskCount }} Tasks</span></div>
          <div class="project-actions">
            <button type="button" class="convention-action" aria-label="查看 AGENTS.md 项目公约" title="查看 AGENTS.md 项目公约" @click="openConvention(project)">
              <Icon icon="lucide:file-text" aria-hidden="true" /><span>项目公约</span>
            </button>
            <button type="button" class="convention-action danger-action" :disabled="Boolean(cancellingProjectId)" :aria-busy="cancellingProjectId === project.id" aria-label="取消管理该项目" title="取消管理该项目" @click="cancelManagement(project)">
              <Icon :class="{ spin: cancellingProjectId === project.id }" :icon="cancellingProjectId === project.id ? 'lucide:loader-circle' : 'lucide:folder-x'" aria-hidden="true" /><span>{{ cancellingProjectId === project.id ? '处理中…' : '取消管理' }}</span>
            </button>
          </div>
        </div>
      </article>
    </section>
    <section v-else class="card empty-state"><div><Icon icon="lucide:folder-plus" width="28" /><strong>尚未登记项目</strong><p>从一个本机目录开始。实际执行前平台会再检查 Git HEAD 和路径边界。</p></div></section>
  </main>

  <el-dialog v-model="dialogVisible" title="登记项目根目录" width="min(640px, calc(100vw - 32px))" :close-on-click-modal="false">
    <p class="card-description" style="margin-top: -6px">只记录本机绝对路径。有 Git HEAD 时 Task 创建到 data/worktrees；否则直接在登记目录执行。</p>
    <el-form label-position="top" style="margin-top: 18px" @submit.prevent="submit">
      <el-form-item label="项目名称"><el-input v-model="form.name" placeholder="例如 OpenCode Loopper" /></el-form-item>
      <el-form-item label="项目根路径">
        <div class="path-picker-row">
          <el-input v-model="form.rootPath" class="mono path-input" placeholder="/Users/name/IdeaProjects/project" aria-label="项目根路径" />
          <el-button class="folder-picker-button" :loading="pickingDirectory" :disabled="saving" aria-label="选择项目文件夹" @click="pickDirectory"><Icon icon="lucide:folder-open" />选择文件夹</el-button>
        </div>
        <p class="path-picker-help"><Icon icon="lucide:mouse-pointer-click" />打开本机目录面板；也可以直接粘贴绝对路径。</p>
        <p v-if="fieldError" class="inline-field-error"><Icon icon="lucide:circle-alert" /> {{ fieldError }}</p>
      </el-form-item>
      <el-form-item label="说明（可选）"><el-input v-model="form.description" type="textarea" :rows="3" placeholder="说明该项目的用途与约束" /></el-form-item>
    </el-form>
    <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">验证并登记</el-button></template>
  </el-dialog>

  <el-dialog v-model="conventionVisible" title="项目公约 AGENTS.md" width="min(820px, calc(100vw - 32px))" :close-on-click-modal="false" @closed="closeConvention">
    <p class="card-description" style="margin-top: -6px">
      先查看 {{ conventionProject?.name }} 当前的 AGENTS.md；只有点击新增或更新后，才会启动只读 AI 设计流程，确认前不会写入项目。
    </p>
    <div v-if="loadingConvention" class="convention-progress">
      <Icon icon="lucide:loader-circle" class="spin" /><strong>正在读取项目公约…</strong>
    </div>
    <div v-else-if="conventionDraft?.state === 'RUNNING'" class="convention-progress">
      <Icon icon="lucide:loader-circle" class="spin" /><div><strong>AI 正在分析项目</strong><p class="muted tiny">只读生成；完成后将在这里显示完整预览。</p></div>
    </div>
    <div v-if="conventionError || conventionDraft?.error" class="inline-field-error convention-error">
      <Icon icon="lucide:circle-alert" />{{ conventionError || conventionDraft?.error }}
    </div>
    <template v-if="!conventionDraft && conventionSnapshot?.exists">
      <div class="convention-meta">
        <span class="eyebrow">CURRENT AGENTS.MD</span>
        <span class="tiny muted">{{ conventionSnapshot.loopperManaged ? '包含 Loopper 管理区块' : '现有人工公约' }}</span>
      </div>
      <el-input class="convention-preview mono" type="textarea" :autosize="{ minRows: 16, maxRows: 26 }" :model-value="conventionSnapshot.content" readonly aria-label="当前 AGENTS.md 项目公约" />
    </template>
    <div v-else-if="!loadingConvention && !conventionDraft && conventionSnapshot && !conventionSnapshot.exists" class="convention-empty">
      <Icon icon="lucide:file-question" width="30" /><strong>暂时没有</strong><p>项目根目录中还没有 AGENTS.md 项目公约。</p>
    </div>
    <template v-if="conventionDraft?.content">
      <div class="convention-meta">
        <span class="eyebrow">{{ conventionDraft.operation === 'CREATE' ? 'CREATE NEW FILE' : 'UPDATE MANAGED BLOCK' }}</span>
        <span class="tiny muted">人工内容保留 · 应用时校验源文件哈希</span>
      </div>
      <el-input class="convention-preview mono" type="textarea" :autosize="{ minRows: 16, maxRows: 26 }" :model-value="conventionDraft.content" readonly aria-label="AGENTS.md 完整预览" />
    </template>
    <template #footer>
      <el-button @click="closeConvention">{{ conventionDraft?.state === 'APPLIED' ? '关闭' : '取消' }}</el-button>
      <el-button v-if="!conventionDraft && conventionSnapshot" type="primary" :loading="Boolean(generatingProjectId)" @click="generateConvention">
        {{ conventionSnapshot.exists ? 'AI 更新 Loopper 公约' : '新增 Loopper 公约' }}
      </el-button>
      <el-button v-else-if="conventionDraft?.state === 'FAILED'" type="primary" :loading="Boolean(generatingProjectId)" @click="generateConvention">重新进行 AI 设计</el-button>
      <el-button v-if="conventionDraft?.state === 'READY'" type="primary" :loading="applyingConvention" @click="applyConvention">确认写入 AGENTS.md</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.project-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.project-card { min-height: 224px; }.project-icon { display: grid; place-items: center; width: 32px; height: 32px; border: 1px solid rgb(139 92 246 / 42%); border-radius: 9px; color: var(--color-accent-ai); background: rgb(139 92 246 / 10%); }.project-path { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.project-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 13px; color: var(--color-text-secondary); }.project-stats { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-width: 0; flex: 1; }.project-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 7px; }
.convention-action { display: inline-flex; flex: 0 0 auto; align-items: center; justify-content: center; gap: 6px; min-height: 30px; padding: 0 10px; border: 1px solid rgb(139 92 246 / 34%); border-radius: 7px; color: #c4b5fd; background: rgb(139 92 246 / 8%); font-size: 10px; font-weight: 680; line-height: 1; cursor: pointer; transition: color .16s ease, background-color .16s ease, border-color .16s ease, box-shadow .16s ease, transform .08s ease; touch-action: manipulation; }.convention-action svg { width: 13px; height: 13px; }.convention-action:hover:not(:disabled) { border-color: rgb(139 92 246 / 62%); color: #ede9fe; background: rgb(139 92 246 / 17%); box-shadow: 0 0 18px rgb(139 92 246 / 13%); }.convention-action:active:not(:disabled) { transform: translateY(1px); }.convention-action:focus-visible { outline: 2px solid var(--color-accent-cyan); outline-offset: 3px; }.convention-action:disabled { opacity: .5; cursor: wait; }
.danger-action { border-color: rgb(248 113 113 / 28%); color: #fca5a5; background: rgb(127 29 29 / 8%); }.danger-action:hover:not(:disabled) { border-color: rgb(248 113 113 / 55%); color: #fecaca; background: rgb(127 29 29 / 20%); box-shadow: 0 0 18px rgb(248 113 113 / 10%); }
.convention-progress { display: flex; align-items: center; justify-content: center; gap: 10px; min-height: 180px; }.convention-progress p { margin: 4px 0 0; }.convention-empty { display: grid; place-items: center; gap: 9px; min-height: 210px; margin-top: 16px; border: 1px dashed var(--color-border); border-radius: 10px; color: var(--color-text-secondary); background: rgb(15 23 42 / 30%); text-align: center; }.convention-empty svg { color: var(--color-accent-ai); }.convention-empty p { margin: 0; color: var(--color-text-muted); font-size: 12px; }.convention-error { margin: 16px 0; }.convention-meta { display: flex; justify-content: space-between; gap: 12px; margin: 18px 0 9px; }.convention-preview :deep(textarea) { line-height: 1.55; }.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.path-picker-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px; width: 100%; }.path-input { min-width: 0; }.folder-picker-button { min-width: 126px; }.path-picker-help { display: inline-flex; align-items: center; gap: 6px; margin: 8px 0 0; color: var(--color-text-muted); font-size: 10px; }.path-picker-help svg { color: var(--color-accent-cyan); }
@media (max-width: 1320px) { .project-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 560px) { .path-picker-row { grid-template-columns: 1fr; }.folder-picker-button { width: 100%; } }
</style>
