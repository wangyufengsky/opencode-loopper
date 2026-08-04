<script setup lang="ts">
import { ref } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { api } from '@/api/client'
import { useTaskStore } from '@/stores/taskStore'

const store = useTaskStore()
const dialogVisible = ref(false)
const saving = ref(false)
const pickingDirectory = ref(false)
const fieldError = ref('')
const form = ref({ name: '', rootPath: '', description: '' })

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
</script>

<template>
  <PageHeader eyebrow="Workspace / Projects" title="项目登记" subtitle="登记受控项目根目录；执行只会在独立 worktree 内进行。">
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
        <div class="project-footer"><span class="mono tiny">{{ project.branch ?? 'no git head' }}</span><span class="tiny muted">{{ project.taskCount }} Tasks</span></div>
      </article>
    </section>
    <section v-else class="card empty-state"><div><Icon icon="lucide:folder-plus" width="28" /><strong>尚未登记项目</strong><p>从一个本机目录开始。实际执行前平台会再检查 Git HEAD 和路径边界。</p></div></section>
  </main>

  <el-dialog v-model="dialogVisible" title="登记项目根目录" width="min(640px, calc(100vw - 32px))" :close-on-click-modal="false">
    <p class="card-description" style="margin-top: -6px">只记录本机绝对路径。Task 会创建到 data/worktrees，不会直接写入原项目目录。</p>
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
</template>

<style scoped>
.project-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.project-card { min-height: 216px; }.project-icon { display: grid; place-items: center; width: 32px; height: 32px; border: 1px solid rgb(139 92 246 / 42%); border-radius: 9px; color: var(--color-accent-ai); background: rgb(139 92 246 / 10%); }.project-path { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.project-footer { display: flex; justify-content: space-between; margin-top: 15px; color: var(--color-text-secondary); }
.path-picker-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px; width: 100%; }.path-input { min-width: 0; }.folder-picker-button { min-width: 126px; }.path-picker-help { display: inline-flex; align-items: center; gap: 6px; margin: 8px 0 0; color: var(--color-text-muted); font-size: 10px; }.path-picker-help svg { color: var(--color-accent-cyan); }
@media (max-width: 1320px) { .project-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 560px) { .path-picker-row { grid-template-columns: 1fr; }.folder-picker-button { width: 100%; } }
</style>
