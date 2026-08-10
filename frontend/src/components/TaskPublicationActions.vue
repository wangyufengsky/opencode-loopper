<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import { changedLineNumbers, countChangedGroups, languageForPath, parseMergeConflicts, resolveMergeConflict, type MergeSide } from '@/utils/mergeView'
import CodeMergeEditor from './CodeMergeEditor.vue'
import type { LocalSyncConflictContent, LocalSyncConflictFile, LocalSyncConflictSession, LocalSyncResolution, Task, TaskPublicationStatus } from '@/types/domain'

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
const conflictDialogOpen = ref(false)
const conflictSession = ref<LocalSyncConflictSession>()
const conflictFiles = ref<LocalSyncConflictFile[]>([])
const selectedConflictPath = ref('')
const conflictContent = ref<LocalSyncConflictContent>()
const mergeContent = ref('')
const conflictLoading = ref(false)
const conflictSaving = ref(false)
const conflictError = ref('')
const aiLoading = ref(false)
const activeConflictIndex = ref(0)
const baseReferenceOpen = ref(false)
const resultEditor = ref<InstanceType<typeof CodeMergeEditor>>()

const commitPreview = computed(() => `#${ticketNumber.value || '0000'}_${commitSubject.value.trim() || 'AI 生成提交信息'}`)
const commitValid = computed(() => /^\d{4}$/.test(ticketNumber.value) && commitSubject.value.trim().length > 0 && commitPreview.value.length <= 126)
const providerLabel = computed(() => publication.value?.provider === 'GITHUB' ? 'GitHub Pull Request' : 'GitLab Merge Request')
const localPublication = computed(() => Boolean(publication.value && !publication.value.remoteName))
const canApplyConflict = computed(() => Boolean(conflictSession.value
  && ['READY', 'ROLLED_BACK'].includes(conflictSession.value.state)
  && conflictSession.value.resolvedCount === conflictSession.value.conflictCount))
const manualHasConflictMarkers = computed(() => containsUnresolvedMergeMarkers(mergeContent.value))
const baseDisplayContent = computed(() => conflictContent.value?.baseContent ?? '（文件不存在）')
const sourceDisplayContent = computed(() => conflictContent.value?.sourceContent ?? '（文件不存在）')
const taskDisplayContent = computed(() => conflictContent.value?.taskContent ?? '（文件不存在）')
const mergeLanguage = computed(() => languageForPath(selectedConflictPath.value))
const sourceChangedLines = computed(() => changedLineNumbers(baseDisplayContent.value, sourceDisplayContent.value))
const taskChangedLines = computed(() => changedLineNumbers(baseDisplayContent.value, taskDisplayContent.value))
const resultChangedLines = computed(() => changedLineNumbers(baseDisplayContent.value, mergeContent.value))
const mergeConflicts = computed(() => parseMergeConflicts(mergeContent.value))
const resultConflictLines = computed(() => mergeConflicts.value.flatMap((conflict) =>
  Array.from({ length: conflict.endLine - conflict.startLine + 1 }, (_, index) => conflict.startLine + index)))
const activeConflictLines = computed(() => {
  const conflict = mergeConflicts.value[activeConflictIndex.value]
  return conflict ? Array.from({ length: conflict.endLine - conflict.startLine + 1 }, (_, index) => conflict.startLine + index) : []
})
const changedGroupCount = computed(() => countChangedGroups(sourceChangedLines.value) + countChangedGroups(taskChangedLines.value))
const failedVerificationChecks = computed(() => {
  const serialized = conflictSession.value?.verificationEvidence
  if (!serialized) return [] as Array<{ type: string; path: string; summary: string; output?: string }>
  try {
    const parsed = JSON.parse(serialized) as { checks?: unknown[] }
    if (!Array.isArray(parsed.checks)) return []
    return parsed.checks.flatMap((value) => {
      if (!value || typeof value !== 'object') return []
      const check = value as Record<string, unknown>
      if (check.passed !== false) return []
      const evidence = check.evidence && typeof check.evidence === 'object'
        ? check.evidence as Record<string, unknown> : {}
      return [{
        type: String(check.type ?? 'VERIFY'),
        path: String(check.path ?? ''),
        summary: String(check.summary ?? '验证失败'),
        output: typeof evidence.output === 'string' ? evidence.output : undefined,
      }]
    })
  } catch { return [] }
})

function containsUnresolvedMergeMarkers(content: string) {
  let opened = false
  let separated = false
  const isMarker = (line: string, marker: string) => line === marker || line.startsWith(`${marker} `) || line.startsWith(`${marker}\t`)
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trimStart()
    if (isMarker(line, '<<<<<<<')) { opened = true; separated = false }
    else if (opened && line.trimEnd() === '=======') separated = true
    else if (opened && separated && isMarker(line, '>>>>>>>')) return true
  }
  return opened
}

async function loadPublication() {
  if (props.task.status !== 'SUCCEEDED') return
  if (props.demo) {
    publication.value = { state: 'READY', available: true, branch: props.task.branch, remoteName: 'origin', targetBranch: 'main', targetBranches: ['main'], provider: 'GITLAB', hasChanges: true, conflictCount: 0, resolvedCount: 0 }
    return
  }
  loading.value = true
  try {
    publication.value = await api.getTaskPublication(props.task.id)
  } catch (cause) {
    publication.value = { state: 'UNAVAILABLE', available: false, reason: cause instanceof Error ? cause.message : '无法读取任务发布状态', targetBranches: [], provider: 'UNKNOWN', hasChanges: false, conflictCount: 0, resolvedCount: 0 }
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
    if (publication.value.state === 'LOCAL_SYNC_CONFLICT') {
      await openConflictCenter()
    } else {
      ElMessage.success(localPublication.value ? '任务变更已同步到源项目' : '任务变更已提交并推送')
    }
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
    if (publication.value.state === 'LOCAL_SYNC_CONFLICT') await openConflictCenter()
    else ElMessage.success(local ? '任务变更已同步到源项目' : '任务分支已推送')
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

async function openConflictCenter() {
  conflictDialogOpen.value = true
  conflictError.value = ''
  conflictLoading.value = true
  try {
    const sessionId = publication.value?.conflictSessionId
    conflictSession.value = sessionId
      ? await api.getLocalSyncConflictSession(props.task.id, sessionId)
      : await api.createLocalSyncConflictSession(props.task.id)
    await loadConflictFiles()
  } catch (cause) {
    conflictError.value = cause instanceof Error ? cause.message : '无法载入同步冲突会话'
  } finally {
    conflictLoading.value = false
  }
}

async function loadConflictFiles(preferredPath?: string) {
  const session = conflictSession.value
  if (!session) return
  conflictFiles.value = await api.getLocalSyncConflictFiles(props.task.id, session.id)
  const path = preferredPath && conflictFiles.value.some((file) => file.path === preferredPath)
    ? preferredPath : conflictFiles.value[0]?.path
  if (path) await selectConflictFile(path)
}

async function selectConflictFile(path: string) {
  const session = conflictSession.value
  if (!session) return
  selectedConflictPath.value = path
  conflictError.value = ''
  conflictContent.value = await api.getLocalSyncConflictContent(props.task.id, session.id, path)
  mergeContent.value = conflictContent.value.mergedContent ?? conflictContent.value.sourceContent ?? ''
  activeConflictIndex.value = 0
  baseReferenceOpen.value = false
}

watch(() => mergeConflicts.value.length, (count) => {
  if (!count) activeConflictIndex.value = 0
  else if (activeConflictIndex.value >= count) activeConflictIndex.value = count - 1
})

async function moveConflict(direction: number) {
  const count = mergeConflicts.value.length
  if (!count) return
  activeConflictIndex.value = (activeConflictIndex.value + direction + count) % count
  await nextTick()
  resultEditor.value?.scrollToLine(mergeConflicts.value[activeConflictIndex.value]?.startLine ?? 1)
}

async function acceptActiveConflict(side: MergeSide) {
  if (!mergeConflicts.value.length) return
  mergeContent.value = resolveMergeConflict(mergeContent.value, activeConflictIndex.value, side)
  await nextTick()
  const remaining = mergeConflicts.value.length
  if (remaining) {
    activeConflictIndex.value = Math.min(activeConflictIndex.value, remaining - 1)
    resultEditor.value?.scrollToLine(mergeConflicts.value[activeConflictIndex.value]?.startLine ?? 1)
  }
  ElMessage.info(side === 'source' ? '当前冲突块已采用源项目，保存手工合并后生效' : '当前冲突块已采用任务版本，保存手工合并后生效')
}

async function reloadConflictSession() {
  if (!conflictSession.value) return
  conflictSession.value = await api.getLocalSyncConflictSession(props.task.id, conflictSession.value.id)
}

async function saveResolution(resolution: Exclude<LocalSyncResolution, 'AUTO'>) {
  const session = conflictSession.value
  const content = conflictContent.value
  if (!session || !content) return
  if (resolution === 'MANUAL' && manualHasConflictMarkers.value) {
    conflictError.value = '合并结果仍包含 <<<<<<< / ======= / >>>>>>> 冲突标记。请保留需要的两侧代码并删除全部标记后再保存。'
    return
  }
  conflictSaving.value = true
  conflictError.value = ''
  try {
    conflictContent.value = await api.saveLocalSyncResolution(props.task.id, session.id, {
      path: content.path, resolution, expectedVersion: content.version,
      ...(resolution === 'MANUAL' ? { content: mergeContent.value } : {}),
    })
    await reloadConflictSession()
    await loadConflictFiles(content.path)
    ElMessage.success(resolution === 'MANUAL' ? '手工合并方案已保存' : '解决方式已保存')
  } catch (cause) {
    conflictError.value = cause instanceof Error ? cause.message : '保存解决方案失败'
  } finally {
    conflictSaving.value = false
  }
}

async function requestAiSuggestion() {
  const session = conflictSession.value
  const content = conflictContent.value
  if (!session || !content) return
  try {
    await ElMessageBox.confirm(
      '将把此文件受限大小的 Base、源项目、任务内容和任务目标发送给当前 OpenCode 模型。建议不会自动选中或应用。',
      '发送单文件内容给当前模型？',
      { type: 'warning', confirmButtonText: '请求 AI 建议', cancelButtonText: '取消' },
    )
  } catch { return }
  aiLoading.value = true
  conflictError.value = ''
  try {
    const suggestion = await api.suggestLocalSyncResolution(props.task.id, session.id, { path: content.path, expectedVersion: content.version })
    conflictContent.value = { ...content, aiSuggestion: suggestion.suggestion, version: suggestion.version }
    const file = conflictFiles.value.find((candidate) => candidate.path === content.path)
    if (file) { file.hasAiSuggestion = true; file.version = suggestion.version }
  } catch (cause) {
    conflictError.value = cause instanceof Error ? cause.message : 'AI 建议生成失败'
  } finally {
    aiLoading.value = false
  }
}

function loadAiIntoEditor() {
  if (!conflictContent.value?.aiSuggestion) return
  mergeContent.value = conflictContent.value.aiSuggestion
  ElMessage.info('AI 建议已载入编辑器，尚未保存或采用')
}

async function refreshConflictSession() {
  conflictLoading.value = true
  conflictError.value = ''
  try {
    conflictSession.value = await api.createLocalSyncConflictSession(props.task.id)
    publication.value = { ...(publication.value as TaskPublicationStatus), conflictSessionId: conflictSession.value.id,
      conflictCount: conflictSession.value.conflictCount, resolvedCount: conflictSession.value.resolvedCount }
    await loadConflictFiles()
  } catch (cause) {
    conflictError.value = cause instanceof Error ? cause.message : '刷新冲突会话失败'
  } finally {
    conflictLoading.value = false
  }
}

async function applyConflictSession() {
  const session = conflictSession.value
  if (!session || session.resolvedCount !== session.conflictCount) return
  try {
    await ElMessageBox.confirm(
      `将把 ${session.conflictCount} 个任务差异文件写入源项目（HEAD ${session.sourceHead.slice(0, 10)}），按原 LoopSpec 顺序验证；任何写入或验证失败都会自动恢复全部任务路径。`,
      '确认合并并同步？',
      { type: 'warning', confirmButtonText: '确认合并并同步', cancelButtonText: '继续检查' },
    )
  } catch { return }
  conflictSaving.value = true
  conflictError.value = ''
  try {
    conflictSession.value = await api.applyLocalSyncConflict(props.task.id, session.id, { confirmed: true, expectedVersion: session.version })
    if (conflictSession.value.state === 'APPLIED') {
      conflictDialogOpen.value = false
      await loadPublication()
      ElMessage.success('冲突方案已验证并同步到源项目')
    } else {
      conflictError.value = conflictSession.value.errorMessage ?? '同步未完成'
    }
  } catch (cause) {
    conflictError.value = cause instanceof Error ? cause.message : '应用冲突方案失败'
    await reloadConflictSession().catch(() => undefined)
  } finally {
    conflictSaving.value = false
  }
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
    <el-button v-else-if="publication.state === 'LOCAL_SYNC_CONFLICT'" type="danger" plain @click="openConflictCenter"><Icon icon="lucide:git-merge" />解决同步冲突（{{ publication.conflictCount }}）</el-button>
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

  <el-dialog v-model="conflictDialogOpen" class="local-sync-dialog" title="本地源代码同步冲突解决中心" width="min(1500px, 96vw)" append-to-body :close-on-click-modal="false" destroy-on-close>
    <div v-if="conflictSession" class="conflict-session-bar">
      <span><Icon icon="lucide:folder-git-2" />{{ conflictSession.sourceRoot }}</span>
      <code>source HEAD {{ conflictSession.sourceHead.slice(0, 12) }}</code>
      <strong>{{ conflictSession.resolvedCount }} / {{ conflictSession.conflictCount }} 已解决</strong>
    </div>
    <div v-if="conflictSession?.state === 'STALE'" class="conflict-state danger">
      <Icon icon="lucide:refresh-cw" /><span>源项目已变化，会话已过期。刷新后会重新计算三方内容，旧方案不会写入。</span>
      <el-button size="small" type="warning" :loading="conflictLoading" @click="refreshConflictSession">刷新预检</el-button>
    </div>
    <div v-else-if="conflictSession?.state === 'ROLLED_BACK'" class="conflict-state danger"><Icon icon="lucide:undo-2" /><span>上次验证或写入失败，全部任务路径已自动恢复。{{ conflictSession.errorMessage || '解决方案仍保留，可编辑后重试。' }}</span></div>
    <div v-else-if="conflictSession?.state === 'ROLLBACK_FAILED'" class="conflict-state danger"><Icon icon="lucide:triangle-alert" /><span>自动恢复失败，未标记同步成功。备份：{{ conflictSession.backupDir }}</span></div>
    <div v-else-if="conflictSession?.state === 'APPLYING' || conflictSession?.state === 'VERIFYING'" class="conflict-state"><Icon icon="lucide:loader-circle" class="spin" /><span>{{ conflictSession.state === 'APPLYING' ? '正在原子写入源项目' : '正在按 LoopSpec 验证，失败会自动恢复' }}</span></div>

    <div v-loading="conflictLoading" class="conflict-workbench">
      <aside class="conflict-files">
        <button v-for="file in conflictFiles" :key="file.path" type="button" :class="{ active: file.path === selectedConflictPath }" @click="selectConflictFile(file.path)">
          <Icon :icon="file.resolved ? 'lucide:circle-check' : 'lucide:circle-alert'" />
          <span><code>{{ file.path }}</code><small>{{ file.changeType }} · {{ file.contentType }}<template v-if="file.resolution"> · {{ file.resolution }}</template></small></span>
        </button>
      </aside>
      <section v-if="conflictContent" class="conflict-detail">
        <div class="resolution-toolbar">
          <div>
            <el-button size="small" :type="conflictContent.resolution === 'SOURCE' ? 'primary' : 'default'" :loading="conflictSaving" @click="saveResolution('SOURCE')">整文件采用源项目</el-button>
            <el-button size="small" :type="conflictContent.resolution === 'TASK' ? 'primary' : 'default'" :loading="conflictSaving" @click="saveResolution('TASK')">整文件采用任务</el-button>
            <el-button v-if="conflictContent.contentType === 'TEXT'" size="small" :type="conflictContent.resolution === 'MANUAL' ? 'success' : 'default'" :loading="conflictSaving" @click="saveResolution('MANUAL')">保存手工合并</el-button>
          </div>
          <el-button v-if="conflictContent.aiEligible" size="small" type="warning" plain :loading="aiLoading" @click="requestAiSuggestion"><Icon icon="lucide:sparkles" />AI 建议（内容将外发）</el-button>
        </div>
        <template v-if="conflictContent.contentType === 'TEXT'">
          <div class="merge-block-toolbar">
            <div class="merge-path"><Icon icon="lucide:file-code-2" /><code>{{ conflictContent.path }}</code><span v-if="mergeLanguage !== 'plain'">{{ mergeLanguage.toUpperCase() }}</span></div>
            <div class="merge-stats"><strong>{{ changedGroupCount }} 处变化</strong><span :class="{ danger: mergeConflicts.length }">{{ mergeConflicts.length }} 个未解决冲突</span></div>
            <div class="merge-nav">
              <el-button size="small" plain :disabled="!mergeConflicts.length" aria-label="上一个冲突" @click="moveConflict(-1)"><Icon icon="lucide:chevron-up" /></el-button>
              <code>{{ mergeConflicts.length ? `${activeConflictIndex + 1} / ${mergeConflicts.length}` : '0 / 0' }}</code>
              <el-button size="small" plain :disabled="!mergeConflicts.length" aria-label="下一个冲突" @click="moveConflict(1)"><Icon icon="lucide:chevron-down" /></el-button>
              <el-button size="small" plain @click="baseReferenceOpen = !baseReferenceOpen"><Icon icon="lucide:history" />{{ baseReferenceOpen ? '收起 Base' : '查看 Base' }}</el-button>
            </div>
          </div>
          <div class="merge-grid">
            <article class="merge-source">
              <header><span>源项目（左）</span><code>{{ conflictContent.sourceHash.slice(0, 10) }}</code></header>
              <CodeMergeEditor :key="`${conflictContent.path}:source`" :model-value="sourceDisplayContent" :language="mergeLanguage" :changed-lines="sourceChangedLines" readonly aria-label="源项目内容" />
            </article>
            <article class="merge-result">
              <header>
                <span>合并结果</span>
                <div class="block-actions">
                  <el-button size="small" :disabled="!mergeConflicts.length" @click="acceptActiveConflict('source')"><Icon icon="lucide:move-left" />本段采用源项目</el-button>
                  <el-button size="small" :disabled="!mergeConflicts.length" @click="acceptActiveConflict('task')">本段采用任务<Icon icon="lucide:move-right" /></el-button>
                </div>
              </header>
              <CodeMergeEditor ref="resultEditor" :key="`${conflictContent.path}:result`" v-model="mergeContent" :language="mergeLanguage" :changed-lines="resultChangedLines" :conflict-lines="resultConflictLines" :active-conflict-lines="activeConflictLines" aria-label="合并结果编辑器" />
            </article>
            <article class="merge-task">
              <header><span>任务版本（右）</span><code>{{ conflictContent.taskHash.slice(0, 10) }}</code></header>
              <CodeMergeEditor :key="`${conflictContent.path}:task`" :model-value="taskDisplayContent" :language="mergeLanguage" :changed-lines="taskChangedLines" readonly aria-label="任务内容" />
            </article>
          </div>
          <div v-if="baseReferenceOpen" class="base-reference"><header><span>Base 共同祖先（仅供参考）</span><code>{{ conflictContent.baseHash.slice(0, 10) }}</code></header><pre>{{ baseDisplayContent }}</pre></div>
          <div v-if="manualHasConflictMarkers" class="merge-marker-warning"><Icon icon="lucide:triangle-alert" /><span>合并结果仍有 Git 冲突标记，不能保存或同步。请合并两侧需要的代码并删除所有标记行。</span></div>
          <div v-if="conflictContent.aiSuggestion !== undefined" class="ai-suggestion">
            <div><strong>AI 建议（尚未采用）</strong><span>必须载入编辑器、复核并保存手工合并后才会生效。</span></div>
            <el-button size="small" @click="loadAiIntoEditor">载入编辑器</el-button>
            <pre>{{ conflictContent.aiSuggestion }}</pre>
          </div>
        </template>
        <div v-else class="non-text-resolution">
          <Icon icon="lucide:file-warning" />
          <strong>{{ conflictContent.contentType === 'BINARY' ? '二进制文件' : '超大文本文件' }}不在浏览器中打开</strong>
          <p>只能选择源项目或任务版本；哈希会在应用前重新核对。</p>
          <code>BASE {{ conflictContent.baseHash }}</code><code>SOURCE {{ conflictContent.sourceHash }}</code><code>TASK {{ conflictContent.taskHash }}</code>
        </div>
      </section>
      <section v-else class="conflict-empty">选择一个冲突文件查看三方内容</section>
    </div>
    <div v-if="failedVerificationChecks.length" class="verification-failures">
      <strong>上次发布验证失败详情</strong>
      <article v-for="check in failedVerificationChecks" :key="`${check.type}:${check.path}`">
        <header><code>{{ check.type }} {{ check.path }}</code><span>{{ check.summary }}</span></header>
        <pre v-if="check.output">{{ check.output }}</pre>
      </article>
    </div>
    <p v-if="conflictError" class="publication-error"><Icon icon="lucide:triangle-alert" />{{ conflictError }}</p>
    <template #footer>
      <el-button :disabled="conflictSaving" @click="conflictDialogOpen = false">稍后处理</el-button>
      <el-button v-if="conflictSession?.state === 'STALE'" type="warning" :loading="conflictLoading" @click="refreshConflictSession">刷新预检</el-button>
      <el-button v-else type="success" :loading="conflictSaving" :disabled="!canApplyConflict" @click="applyConflictSession">确认合并并同步</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.publication-intro { display: flex; gap: 11px; padding: 12px; border: 1px solid rgb(139 92 246 / 28%); border-radius: 9px; background: rgb(139 92 246 / 7%); }.publication-intro > svg { flex: 0 0 auto; margin-top: 2px; color: var(--color-accent-ai); }.publication-intro strong { color: var(--color-text-primary); font-size: 12px; }.publication-intro p { margin: 5px 0 0; color: var(--color-text-secondary); font-size: 11px; }
.generation-state { display: flex; align-items: center; gap: 6px; margin: 7px 0 0; color: var(--color-accent-ai); font-size: 10px; }.commit-preview { display: grid; gap: 7px; padding: 12px; border: 1px solid var(--color-border-default); border-radius: 8px; background: #080e1a; }.commit-preview span { color: var(--color-text-muted); font-size: 10px; }.commit-preview code { color: #dbeafe; font: 12px/1.55 var(--font-code); overflow-wrap: anywhere; }
.publication-error { display: flex; align-items: flex-start; gap: 6px; margin: 10px 0 0; color: var(--color-task-danger); font-size: 11px; line-height: 1.5; }.publication-error > svg { flex: 0 0 auto; margin-top: 2px; }.branch-flow { display: flex; align-items: center; gap: 10px; padding: 11px 12px; border: 1px solid rgb(34 211 238 / 18%); border-radius: 8px; background: rgb(34 211 238 / 4%); }.branch-flow code { min-width: 0; color: var(--color-accent-cyan); font: 10px/1.45 var(--font-code); overflow-wrap: anywhere; }.branch-flow > svg { flex: 0 0 auto; color: var(--color-text-muted); }.merge-note { display: flex; gap: 7px; margin: 0; color: var(--color-text-secondary); font-size: 10px; line-height: 1.55; }.merge-note > svg { flex: 0 0 auto; margin-top: 1px; }
.spin { animation: spin .8s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
:global(.publication-dialog) { border: 1px solid rgb(130 147 173 / 18%); border-radius: 12px; background: #0b1220; box-shadow: 0 24px 80px rgb(0 0 0 / 55%); }:global(.publication-dialog .el-dialog__header) { margin: 0; padding-bottom: 14px; border-bottom: 1px solid var(--color-border-default); }:global(.publication-dialog .el-dialog__body) { padding-top: 18px; }
.conflict-session-bar { display: flex; align-items: center; gap: 14px; min-width: 0; padding: 9px 12px; border: 1px solid var(--color-border-default); border-radius: 8px; background: #08111e; color: var(--color-text-secondary); font-size: 10px; }.conflict-session-bar span { display: flex; min-width: 0; align-items: center; gap: 6px; flex: 1; overflow-wrap: anywhere; }.conflict-session-bar code { color: var(--color-accent-cyan); }.conflict-session-bar strong { color: #86efac; }.conflict-state { display: flex; align-items: center; gap: 8px; margin-top: 10px; padding: 9px 11px; border: 1px solid rgb(251 191 36 / 30%); border-radius: 7px; background: rgb(251 191 36 / 7%); color: #fde68a; font-size: 10px; }.conflict-state span { flex: 1; }.conflict-state.danger { border-color: rgb(248 113 113 / 32%); background: rgb(248 113 113 / 8%); color: #fecaca; }
.conflict-workbench { display: grid; grid-template-columns: minmax(220px, 250px) minmax(0, 1fr); min-height: 590px; margin-top: 12px; overflow: hidden; border: 1px solid var(--color-border-default); border-radius: 9px; background: #07101b; }.conflict-files { overflow: auto; border-right: 1px solid var(--color-border-default); background: #091321; }.conflict-files button { display: flex; width: 100%; gap: 8px; align-items: flex-start; padding: 10px; border: 0; border-bottom: 1px solid rgb(130 147 173 / 10%); background: transparent; color: var(--color-text-secondary); text-align: left; cursor: pointer; }.conflict-files button.active { background: rgb(34 211 238 / 8%); color: #e0f2fe; }.conflict-files button > svg { flex: 0 0 auto; margin-top: 1px; }.conflict-files button span { min-width: 0; }.conflict-files code { display: block; color: inherit; font: 10px/1.4 var(--font-code); overflow-wrap: anywhere; }.conflict-files small { display: block; margin-top: 4px; color: var(--color-text-muted); font-size: 9px; }.conflict-detail { display: flex; min-width: 0; min-height: 0; overflow-x: auto; flex-direction: column; }.resolution-toolbar { display: flex; min-width: 900px; justify-content: space-between; gap: 10px; padding: 9px 10px; border-bottom: 1px solid var(--color-border-default); }.merge-block-toolbar { display: grid; min-width: 900px; grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; gap: 12px; min-height: 42px; padding: 7px 10px; border-bottom: 1px solid var(--color-border-default); background: #091321; }.merge-path { display: flex; min-width: 0; align-items: center; gap: 7px; color: var(--color-text-secondary); }.merge-path code { min-width: 0; overflow: hidden; color: #dbeafe; font: 10px var(--font-code); text-overflow: ellipsis; white-space: nowrap; }.merge-path span { padding: 2px 5px; border: 1px solid rgb(34 211 238 / 26%); border-radius: 4px; color: var(--color-accent-cyan); font: 8px var(--font-code); }.merge-stats { display: flex; gap: 9px; color: var(--color-text-muted); font-size: 9px; }.merge-stats strong { color: #86efac; }.merge-stats .danger { color: #fca5a5; }.merge-nav { display: flex; align-items: center; gap: 5px; }.merge-nav code { min-width: 42px; color: var(--color-text-secondary); font: 9px var(--font-code); text-align: center; }.merge-grid { display: grid; min-width: 900px; min-height: 470px; flex: 1; grid-template-columns: repeat(3, minmax(285px, 1fr)); gap: 1px; background: var(--color-border-default); }.conflict-detail > .merge-grid { overflow: hidden; }.merge-grid article { display: grid; min-width: 0; min-height: 0; grid-template-rows: auto 1fr; background: #07101d; }.merge-grid header { display: flex; min-height: 34px; align-items: center; justify-content: space-between; gap: 8px; padding: 5px 9px; background: #0b1727; color: var(--color-text-secondary); font-size: 9px; }.merge-grid header code { color: var(--color-text-muted); }.merge-grid .merge-result header { border-bottom: 1px solid rgb(74 222 128 / 24%); color: #86efac; }.merge-grid header > span { color: inherit; font-weight: 700; }.block-actions { display: flex; gap: 4px; }.block-actions :deep(.el-button) { padding: 4px 5px; font-size: 8px; }.base-reference { min-width: 900px; border-top: 1px solid var(--color-border-default); background: #070e19; }.base-reference header { display: flex; justify-content: space-between; padding: 6px 10px; color: var(--color-text-muted); font-size: 9px; }.base-reference pre { max-height: 180px; margin: 0; padding: 10px 14px; overflow: auto; border-top: 1px solid rgb(130 147 173 / 12%); white-space: pre; color: #94a3b8; font: 10px/1.55 var(--font-code); }.non-text-resolution { display: flex; flex: 1; flex-direction: column; align-items: center; justify-content: center; gap: 8px; color: var(--color-text-secondary); }.non-text-resolution > svg { width: 32px; height: 32px; color: #fbbf24; }.non-text-resolution p { margin: 0; }.non-text-resolution code { max-width: 80%; overflow-wrap: anywhere; color: var(--color-text-muted); font-size: 9px; }.ai-suggestion { display: grid; min-width: 900px; grid-template-columns: 1fr auto; gap: 7px; padding: 9px; border-top: 1px solid rgb(139 92 246 / 28%); background: rgb(139 92 246 / 6%); }.ai-suggestion div { display: grid; gap: 3px; }.ai-suggestion strong { color: #ddd6fe; font-size: 10px; }.ai-suggestion span { color: var(--color-text-muted); font-size: 9px; }.ai-suggestion pre { grid-column: 1 / -1; max-height: 110px; margin: 0; overflow: auto; white-space: pre-wrap; color: #c4b5fd; font: 9px/1.45 var(--font-code); }.conflict-empty { display: grid; flex: 1; place-items: center; color: var(--color-text-muted); font-size: 11px; }
.merge-marker-warning { display: flex; align-items: flex-start; gap: 7px; padding: 9px 11px; border-top: 1px solid rgb(248 113 113 / 30%); background: rgb(248 113 113 / 8%); color: #fecaca; font-size: 10px; line-height: 1.5; }.merge-marker-warning > svg { flex: 0 0 auto; margin-top: 1px; }
.verification-failures { display: grid; gap: 7px; margin-top: 10px; padding: 10px; border: 1px solid rgb(248 113 113 / 28%); border-radius: 8px; background: rgb(248 113 113 / 6%); }.verification-failures > strong { color: #fecaca; font-size: 10px; }.verification-failures article { overflow: hidden; border: 1px solid rgb(130 147 173 / 16%); border-radius: 6px; background: #07101b; }.verification-failures header { display: flex; gap: 10px; justify-content: space-between; padding: 7px 9px; color: #fca5a5; font-size: 9px; }.verification-failures pre { max-height: 180px; margin: 0; padding: 9px; overflow: auto; border-top: 1px solid rgb(130 147 173 / 14%); white-space: pre-wrap; color: #cbd5e1; font: 9px/1.45 var(--font-code); }
:global(.local-sync-dialog) { border: 1px solid rgb(130 147 173 / 22%); border-radius: 12px; background: #08111e; box-shadow: 0 30px 100px rgb(0 0 0 / 65%); }:global(.local-sync-dialog .el-dialog__header) { margin: 0; padding-bottom: 12px; border-bottom: 1px solid var(--color-border-default); }:global(.local-sync-dialog .el-dialog__body) { padding: 14px 16px 8px; }
@media (max-width: 1200px) { .conflict-workbench { grid-template-columns: 190px minmax(0, 1fr); } }
</style>
