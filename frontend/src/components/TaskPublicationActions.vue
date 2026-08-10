<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import type { Task, TaskPublicationStatus } from '@/types/domain'

const props = withDefaults(defineProps<{ task: Task; demo?: boolean }>(), { demo: false })
const publication = ref<TaskPublicationStatus>()
const loading = ref(false)
const operationLoading = ref(false)
const commitDialogOpen = ref(false)
const suggestionLoading = ref(false)
const ticketNumber = ref('')
const commitSubject = ref('')
const commitError = ref('')
const mergeDialogOpen = ref(false)
const mergeError = ref('')
const mergeForm = ref({ targetBranch: '', title: '', description: '' })

const commitPreview = computed(() => `#${ticketNumber.value || '0000'}_${commitSubject.value.trim() || 'AI 生成提交信息'}`)
const commitValid = computed(() => /^\d{4}$/.test(ticketNumber.value) && commitSubject.value.trim().length > 0 && commitPreview.value.length <= 126)
const providerLabel = computed(() => publication.value?.provider === 'GITHUB' ? 'GitHub Pull Request' : 'GitLab Merge Request')
const localPublication = computed(() => Boolean(publication.value && !publication.value.remoteName))

async function loadPublication() {
  if (props.task.status !== 'SUCCEEDED') return
  if (props.demo) {
    publication.value = { state: 'READY', available: true, branch: props.task.branch, remoteName: 'origin', targetBranch: 'main', targetBranches: ['main'], provider: 'GITLAB', hasChanges: true }
    return
  }
  loading.value = true
  try {
    publication.value = await api.getTaskPublication(props.task.id)
  } catch (cause) {
    publication.value = { state: 'UNAVAILABLE', available: false, reason: cause instanceof Error ? cause.message : '无法读取任务发布状态', targetBranches: [], provider: 'UNKNOWN', hasChanges: false }
  } finally {
    loading.value = false
  }
}

watch(() => [props.task.id, props.task.status], loadPublication, { immediate: true })

function normalizeTicket(value: string) {
  ticketNumber.value = value.replace(/\D/g, '').slice(0, 4)
}

async function openCommitDialog() {
  ticketNumber.value = ''
  commitSubject.value = ''
  commitError.value = ''
  commitDialogOpen.value = true
  suggestionLoading.value = true
  try {
    if (props.demo) {
      commitSubject.value = '完善任务提交与合并请求流程'
    } else {
      const suggestion = await api.generateTaskCommitMessage(props.task.id)
      commitSubject.value = suggestion.subject
    }
  } catch (cause) {
    commitSubject.value = props.task.title.replace(/^#[0-9]{4}_/, '').slice(0, 80)
    commitError.value = cause instanceof Error ? `${cause.message}；已回填任务标题，可手工修改。` : 'AI 提交信息生成失败，可手工填写。'
  } finally {
    suggestionLoading.value = false
  }
}

async function submitCommit() {
  commitError.value = ''
  if (!commitValid.value) {
    commitError.value = /^\d{4}$/.test(ticketNumber.value) ? '请输入提交说明，并将完整提交信息控制在 126 个字符以内。' : '请输入 4 位数字工单号。'
    return
  }
  try {
    const local = localPublication.value
    await ElMessageBox.confirm(
      local
        ? '将提交任务 worktree 的全部已验收变更，并安全同步到源项目目录。若源目录存在冲突，操作会停止且不会覆盖现有改动。'
        : `将提交任务 worktree 的全部已验收变更，并推送分支 ${publication.value?.branch ?? props.task.branch}。`,
      local ? '确认提交并同步？' : '确认提交并推送？',
      { confirmButtonText: local ? '提交并同步' : '提交并推送', cancelButtonText: '返回编辑', type: 'warning' },
    )
  } catch { return }
  operationLoading.value = true
  try {
    if (props.demo) {
      publication.value = { ...(publication.value as TaskPublicationStatus), state: 'PUSHED', hasChanges: false, commitMessage: commitPreview.value, commitSha: '3f7a2c1', upstream: `origin/${props.task.branch}` }
    } else {
      publication.value = await api.publishTask(props.task.id, commitPreview.value)
    }
    commitDialogOpen.value = false
    ElMessage.success(localPublication.value ? '任务变更已同步到源项目' : '任务变更已提交并推送')
  } catch (cause) {
    commitError.value = cause instanceof Error ? cause.message : '提交或推送失败'
    await loadPublication()
  } finally {
    operationLoading.value = false
  }
}

async function retryPublication() {
  const local = localPublication.value
  try {
    await ElMessageBox.confirm(
      local
        ? `提交 ${publication.value?.commitSha?.slice(0, 8) ?? ''} 已保留，将再次检查冲突并同步到源项目目录。`
        : `提交 ${publication.value?.commitSha?.slice(0, 8) ?? ''} 已保留，将重新推送到 ${publication.value?.remoteName ?? 'origin'}。`,
      local ? '继续同步源代码？' : '继续推送任务分支？',
      { confirmButtonText: local ? '继续同步' : '继续推送', cancelButtonText: '取消', type: 'warning' },
    )
  } catch { return }
  operationLoading.value = true
  try {
    publication.value = props.demo ? { ...(publication.value as TaskPublicationStatus), state: 'PUSHED' } : await api.publishTask(props.task.id)
    ElMessage.success(local ? '任务变更已同步到源项目' : '任务分支已推送')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : local ? '同步失败' : '推送失败')
    await loadPublication()
  } finally {
    operationLoading.value = false
  }
}

function openMergeDialog() {
  const current = publication.value
  if (!current) return
  mergeError.value = ''
  mergeForm.value = {
    targetBranch: current.targetBranch ?? current.targetBranches[0] ?? '',
    title: current.commitMessage ?? props.task.title,
    description: `## 任务目标\n\n${props.task.goal}\n\n## 来源\n\nOpenCode Loopper 任务 ${props.task.id}`,
  }
  mergeDialogOpen.value = true
}

async function createMergeRequest() {
  mergeError.value = ''
  if (!mergeForm.value.targetBranch) { mergeError.value = '请选择目标分支。'; return }
  if (!mergeForm.value.title.trim()) { mergeError.value = '请输入合并请求标题。'; return }
  operationLoading.value = true
  try {
    const draft = props.demo
      ? { creationUrl: 'https://gitlab.example/group/project/-/merge_requests/new', provider: 'GITLAB' as const, sourceBranch: props.task.branch, targetBranch: mergeForm.value.targetBranch, title: mergeForm.value.title, description: mergeForm.value.description }
      : await api.createTaskMergeRequestDraft(props.task.id, mergeForm.value)
    const opened = window.open(draft.creationUrl, '_blank', 'noopener,noreferrer')
    if (!opened) {
      await navigator.clipboard?.writeText(draft.creationUrl)
      mergeError.value = '浏览器阻止了新窗口，创建地址已复制到剪贴板。'
      return
    }
    mergeDialogOpen.value = false
    ElMessage.success(`已打开 ${draft.provider === 'GITHUB' ? 'Pull Request' : 'Merge Request'} 创建页`)
  } catch (cause) {
    mergeError.value = cause instanceof Error ? cause.message : '无法创建合并请求入口'
  } finally {
    operationLoading.value = false
  }
}
</script>

<template>
  <template v-if="task.status === 'SUCCEEDED'">
    <el-button v-if="loading || !publication" plain disabled :loading="loading">读取提交状态</el-button>
    <el-button v-else-if="publication.state === 'READY'" type="success" :loading="operationLoading" @click="openCommitDialog"><Icon :icon="localPublication ? 'lucide:folder-sync' : 'lucide:git-commit-horizontal'" />{{ localPublication ? '同步源代码' : '提交' }}</el-button>
    <el-button v-else-if="publication.state === 'COMMITTED'" type="warning" :loading="operationLoading" @click="retryPublication"><Icon :icon="localPublication ? 'lucide:folder-sync' : 'lucide:cloud-upload'" />{{ localPublication ? '继续同步源代码' : '继续推送' }}</el-button>
    <el-button v-else-if="publication.state === 'SYNCED_LOCAL'" type="success" plain disabled><Icon icon="lucide:circle-check" />已同步源代码</el-button>
    <el-dropdown v-else-if="publication.state === 'PUSHED'" trigger="click" @command="openMergeDialog">
      <el-button type="primary"><Icon icon="lucide:git-merge" />合并分支<Icon icon="lucide:chevron-down" /></el-button>
      <template #dropdown><el-dropdown-menu><el-dropdown-item command="merge-request"><Icon icon="lucide:git-pull-request-create" />创建合并请求</el-dropdown-item></el-dropdown-menu></template>
    </el-dropdown>
    <el-tooltip v-else :content="publication.reason ?? '当前任务不可提交'" placement="bottom">
      <span><el-button plain disabled><Icon icon="lucide:git-commit-horizontal" />提交</el-button></span>
    </el-tooltip>
  </template>

  <el-dialog v-model="commitDialogOpen" class="publication-dialog" :title="localPublication ? '同步任务变更到源项目' : '提交任务变更'" width="min(660px, 92vw)" append-to-body :close-on-click-modal="false">
    <div class="publication-intro"><Icon icon="lucide:sparkles" /><div><strong>AI 已根据任务目标和实际差异生成默认说明</strong><p>你只需输入 4 位数字工单号；提交前仍可编辑说明。</p></div></div>
    <el-form label-position="top" style="margin-top: 18px" @submit.prevent="submitCommit">
      <el-form-item label="4 位数字工单号">
        <el-input :model-value="ticketNumber" maxlength="4" inputmode="numeric" placeholder="例如 3032" aria-label="4 位数字工单号" @update:model-value="normalizeTicket" />
      </el-form-item>
      <el-form-item label="AI 提交说明">
        <el-input v-model="commitSubject" type="textarea" :rows="3" maxlength="120" show-word-limit :disabled="suggestionLoading" placeholder="正在生成…" aria-label="AI 提交说明" />
        <p v-if="suggestionLoading" class="generation-state"><Icon icon="lucide:loader-circle" class="spin" />正在读取任务差异并生成提交说明</p>
      </el-form-item>
      <div class="commit-preview"><span>最终提交信息</span><code>{{ commitPreview }}</code></div>
      <p v-if="commitError" class="publication-error"><Icon icon="lucide:triangle-alert" />{{ commitError }}</p>
    </el-form>
    <template #footer><el-button :disabled="operationLoading" @click="commitDialogOpen = false">取消</el-button><el-button type="success" :loading="operationLoading" :disabled="suggestionLoading" @click="submitCommit">{{ localPublication ? '确认提交并同步' : '确认提交并推送' }}</el-button></template>
  </el-dialog>

  <el-dialog v-model="mergeDialogOpen" class="publication-dialog" title="创建合并请求" width="min(700px, 92vw)" append-to-body :close-on-click-modal="false">
    <div class="branch-flow"><code>{{ publication?.branch }}</code><Icon icon="lucide:arrow-right" /><code>{{ mergeForm.targetBranch || '选择目标分支' }}</code></div>
    <el-form label-position="top" style="margin-top: 18px" @submit.prevent="createMergeRequest">
      <el-form-item label="目标分支"><el-select v-model="mergeForm.targetBranch" placeholder="选择目标分支" style="width:100%"><el-option v-for="branch in publication?.targetBranches ?? []" :key="branch" :label="branch" :value="branch" /></el-select></el-form-item>
      <el-form-item label="合并请求标题"><el-input v-model="mergeForm.title" maxlength="160" /></el-form-item>
      <el-form-item label="说明"><el-input v-model="mergeForm.description" type="textarea" :rows="6" maxlength="8000" /></el-form-item>
      <p class="merge-note"><Icon icon="lucide:external-link" />确认后将打开 {{ providerLabel }} 创建页，由你复核并完成创建。</p>
      <p v-if="mergeError" class="publication-error"><Icon icon="lucide:triangle-alert" />{{ mergeError }}</p>
    </el-form>
    <template #footer><el-button :disabled="operationLoading" @click="mergeDialogOpen = false">取消</el-button><el-button type="primary" :loading="operationLoading" @click="createMergeRequest">前往创建合并请求</el-button></template>
  </el-dialog>
</template>

<style scoped>
.publication-intro { display: flex; gap: 11px; padding: 12px; border: 1px solid rgb(139 92 246 / 28%); border-radius: 9px; background: rgb(139 92 246 / 7%); }.publication-intro > svg { flex: 0 0 auto; margin-top: 2px; color: var(--color-accent-ai); }.publication-intro strong { color: var(--color-text-primary); font-size: 12px; }.publication-intro p { margin: 5px 0 0; color: var(--color-text-secondary); font-size: 11px; }
.generation-state { display: flex; align-items: center; gap: 6px; margin: 7px 0 0; color: var(--color-accent-ai); font-size: 10px; }.commit-preview { display: grid; gap: 7px; padding: 12px; border: 1px solid var(--color-border-default); border-radius: 8px; background: #080e1a; }.commit-preview span { color: var(--color-text-muted); font-size: 10px; }.commit-preview code { color: #dbeafe; font: 12px/1.55 var(--font-code); overflow-wrap: anywhere; }
.publication-error { display: flex; align-items: flex-start; gap: 6px; margin: 10px 0 0; color: var(--color-task-danger); font-size: 11px; line-height: 1.5; }.publication-error > svg { flex: 0 0 auto; margin-top: 2px; }.branch-flow { display: flex; align-items: center; gap: 10px; padding: 11px 12px; border: 1px solid rgb(34 211 238 / 18%); border-radius: 8px; background: rgb(34 211 238 / 4%); }.branch-flow code { min-width: 0; color: var(--color-accent-cyan); font: 10px/1.45 var(--font-code); overflow-wrap: anywhere; }.branch-flow > svg { flex: 0 0 auto; color: var(--color-text-muted); }.merge-note { display: flex; gap: 7px; margin: 0; color: var(--color-text-secondary); font-size: 10px; line-height: 1.55; }.merge-note > svg { flex: 0 0 auto; margin-top: 1px; }
.spin { animation: spin .8s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
:global(.publication-dialog) { border: 1px solid rgb(130 147 173 / 18%); border-radius: 12px; background: #0b1220; box-shadow: 0 24px 80px rgb(0 0 0 / 55%); }:global(.publication-dialog .el-dialog__header) { margin: 0; padding-bottom: 14px; border-bottom: 1px solid var(--color-border-default); }:global(.publication-dialog .el-dialog__body) { padding-top: 18px; }
</style>
