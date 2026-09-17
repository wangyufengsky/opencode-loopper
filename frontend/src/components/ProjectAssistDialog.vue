<script setup lang="ts">
import { ref, watch } from 'vue'
import { api } from '@/api/client'
import type { Project, ProjectAssistConfig, AssistEvidenceSource } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import DirectoryPathInput from './DirectoryPathInput.vue'
import GitCredentialForm from './GitCredentialForm.vue'
const props = defineProps<{ project?: Project; demo?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const activeTab = ref('account')
const credentialBusy = ref(false)
const view = ref<ProjectAssistConfig>()
const repository = ref('')
const sources = ref<AssistEvidenceSource[]>([])
const loading = ref(false)
const error = ref('')
const dirty = ref(false)
const picking = ref(false)
let generation = 0
function accept(value: ProjectAssistConfig) {
  view.value = value; repository.value = value.config.repository ?? ''
  sources.value = value.config.sources.map(row => ({ ...row })); dirty.value = false
}
watch(() => props.project?.id, async id => {
  const request = ++generation; error.value = ''; view.value = undefined; activeTab.value = 'account'
  if (!id) return
  loading.value = true
  try {
    const value = props.demo ? { version: -1, credentialConfigured: false, config: { sources: [] } } : await api.projectAssistConfig(id)
    if (request === generation) accept(value)
  } catch (cause) { if (request === generation) error.value = userFacingError(cause, '配置读取失败，请重新打开') }
  finally { if (request === generation) loading.value = false }
}, { immediate: true })
async function action(kind: 'save' | 'check' | 'discover') {
  if (!props.project || !view.value || loading.value || picking.value) return
  const id = props.project.id, request = generation
  loading.value = true; error.value = ''
  try {
    if (props.demo) throw new Error('请在实际项目中配置 GitLab 与证据来源')
    if (kind === 'discover') {
      const found = await api.discoverProjectGitLab(id)
      if (request === generation) { repository.value = found.repository; dirty.value = true }
    } else {
      const value = kind === 'save' ? await api.saveProjectAssistConfig(id, view.value.version, repository.value, sources.value)
        : await api.checkProjectGitLab(id, view.value.version)
      if (request === generation) accept(value)
    }
  } catch (cause) { if (request === generation) error.value = userFacingError(cause, '操作失败，请检查配置后重试') }
  finally { if (request === generation) loading.value = false }
}
function addSource() { sources.value.push({ kind: 'LOG', root: '', pattern: 'logs/*.log' }); dirty.value = true }
</script>
<template>
  <el-dialog :model-value="!!project" :title="`${project?.name ?? ''} · Git 与 GitLab`" destroy-on-close width="min(760px, calc(100vw - 32px))" :close-on-click-modal="false" :show-close="!loading && !credentialBusy" :close-on-press-escape="!loading && !credentialBusy" @close="emit('close')">
    <el-tabs v-model="activeTab" :before-leave="() => !loading && !credentialBusy"><el-tab-pane label="Git 账号" name="account" /><el-tab-pane label="GitLab 与证据" name="gitlab" /></el-tabs>
    <GitCredentialForm v-if="project" v-show="activeTab === 'account'" :key="project.id" :project-id="project.id" :demo="demo" @busy="credentialBusy = $event" />
    <section v-show="activeTab === 'gitlab'">
    <p class="muted">支持 HTTP 与 HTTPS。<router-link to="/settings" @click="emit('close')">在设置中维护 GitLab 接口地址</router-link></p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form v-if="view" label-position="top" :disabled="loading || picking" @submit.prevent="action('save')">
      <el-form-item label="GitLab 仓库"><el-input v-model="repository" placeholder="group/subgroup/repository" @input="dirty = true" /></el-form-item>
      <div class="actions"><el-button @click="action('discover')">从 origin 识别</el-button><el-button :disabled="dirty || !view.config.repository" @click="action('check')">检查已保存绑定</el-button></div>
      <p class="muted">凭证：{{ view.credentialConfigured ? '环境变量已配置' : '未配置，请设置环境变量并重启服务' }} · {{ dirty ? '修改待保存' : view.config.projectId ? '绑定已核验' : '绑定待核验' }}</p>
      <p v-if="view.config.name">{{ view.config.name }} · {{ view.config.checkedAt }}</p>
      <el-divider>证据来源</el-divider>
      <div v-for="(source, index) in sources" :key="index" class="source-row">
        <select v-model="source.kind" aria-label="证据类型" :disabled="loading" @change="source.root = ''; dirty = true"><option value="LOG">应用日志</option><option value="JUNIT">JUnit 报告</option></select>
        <el-input v-model="source.pattern" aria-label="文件规则" placeholder="例如 target/surefire-reports/*.xml" @input="dirty = true" />
        <DirectoryPathInput v-if="source.kind === 'LOG'" v-model="source.root" v-model:picking="picking" :scope-key="project?.id" :demo="demo" label="允许读取的外部日志目录" placeholder="留空使用任务工作目录；外部目录须填写绝对路径" @update:model-value="dirty = true" />
        <el-button @click="sources.splice(index, 1); dirty = true">移除此规则</el-button>
      </div>
      <el-button :disabled="sources.length >= 16" @click="addSource">添加来源</el-button>
      <p class="muted tiny">配置仅供新任务使用。日志记录正式验证期间的新增内容；工具开关在辅助 MCP 工具设置中逐项启用。</p>
    </el-form>
    </section>
    <template #footer><el-button :disabled="loading || credentialBusy" @click="emit('close')">关闭</el-button><el-button v-if="activeTab === 'gitlab'" type="primary" :loading="loading" :disabled="!view || picking" @click="action('save')">保存配置</el-button></template>
  </el-dialog>
</template>
<style scoped>
.actions { display: flex; flex-wrap: wrap; gap: 8px; }
.source-row { display: grid; gap: 10px; margin-bottom: 18px; padding: 12px; border: 1px solid var(--color-border-default); border-radius: 8px; }
select { background: var(--color-bg-surface); color: var(--color-text-primary); border: 1px solid var(--color-border-default); border-radius: var(--radius-control); padding: 8px; font: inherit; }
</style>
