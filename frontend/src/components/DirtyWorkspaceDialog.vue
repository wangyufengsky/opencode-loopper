<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import { useTaskStore } from '@/stores/taskStore'
import type { DirtyWorkspaceAction, DirtyWorkspaceFile, DirtyWorkspaceState } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'

const props = defineProps<{ taskId: string; modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const store = useTaskStore()
const workspace = ref<DirtyWorkspaceState>()
const actions = ref<Record<string, DirtyWorkspaceAction | undefined>>({})
const commitMessage = ref('chore: 保存任务开始前的本地改动')
const loading = ref(false)
const applying = ref(false)
const cancelling = ref(false)
const error = ref('')

const allSelected = computed(() => Boolean(workspace.value)
  && workspace.value!.files.every((file) => Boolean(actions.value[file.path])))
const hasCommit = computed(() => workspace.value?.files.some((file) => actions.value[file.path] === 'COMMIT') === true)
const removeCount = computed(() => workspace.value?.files.filter((file) => actions.value[file.path] === 'REMOVE').length ?? 0)

function statusLabel(file: DirtyWorkspaceFile) {
  if (file.untracked) return '未跟踪'
  const status = `${file.indexStatus}${file.workTreeStatus}`
  if (status.includes('U')) return '存在冲突'
  if (status.includes('R')) return '重命名'
  if (status.includes('C')) return '复制'
  if (status.includes('D')) return '已删除'
  if (status.includes('A')) return '新增'
  return '已修改'
}

async function loadWorkspace() {
  if (!props.modelValue) return
  loading.value = true
  error.value = ''
  try {
    const latest = await api.getDirtyWorkspace(props.taskId)
    workspace.value = latest
    actions.value = Object.fromEntries(latest.files.map((file) => [file.path, actions.value[file.path]]))
  } catch (cause) {
    error.value = userFacingError(cause, '无法读取未提交文件列表')
  } finally {
    loading.value = false
  }
}

watch(() => [props.modelValue, props.taskId], ([open]) => {
  if (open) void loadWorkspace()
}, { immediate: true })

async function recheckAndContinue() {
  const current = workspace.value
  if (!current || applying.value) return
  error.value = ''
  if (!current.clean && !allSelected.value) {
    error.value = '请为每个文件选择提交、暂存或移除。'
    return
  }
  if (hasCommit.value && (!commitMessage.value.trim() || commitMessage.value.trim().length > 160)) {
    error.value = '提交说明需为 1–160 个字符。'
    return
  }
  if (removeCount.value > 0) {
    try {
      await ElMessageBox.confirm(
        `将永久丢弃 ${removeCount.value} 个文件的本地改动；未跟踪文件会被删除。该操作不能由 Loopper 自动恢复。`,
        '确认移除所选文件？',
        { type: 'error', confirmButtonText: '确认移除并继续', cancelButtonText: '返回检查' },
      )
    } catch { return }
  }
  applying.value = true
  try {
    const result = await store.resolveDirtyWorkspace(props.taskId, {
      snapshotId: current.snapshotId,
      resolutions: current.files.map((file) => ({ path: file.path, action: actions.value[file.path]! })),
      ...(hasCommit.value ? { commitMessage: commitMessage.value.trim() } : {}),
    })
    workspace.value = result.workspace
    if (result.task.status !== 'WAITING_INPUT') {
      emit('update:modelValue', false)
      ElMessage.success('工作区已清理，任务分支准备完成')
    } else {
      actions.value = {}
      error.value = '工作区仍有新的或未处理的改动，请重新选择后继续。'
    }
  } catch (cause) {
    error.value = userFacingError(cause, '处理失败，服务器未确认工作区已经干净')
    await loadWorkspace()
  } finally {
    applying.value = false
  }
}

async function cancelAsFailure() {
  if (cancelling.value) return
  try {
    await ElMessageBox.confirm(
      '任务分支尚未创建。取消后该任务将直接标记为失败，现有本地文件保持原样。',
      '取消工作区处理并终止任务？',
      { type: 'warning', confirmButtonText: '标记任务失败', cancelButtonText: '继续处理' },
    )
  } catch { return }
  cancelling.value = true
  error.value = ''
  try {
    await store.failDirtyWorkspace(props.taskId)
    emit('update:modelValue', false)
    ElMessage.warning('任务已因取消工作区处理而失败')
  } catch (cause) {
    error.value = userFacingError(cause, '无法终止任务')
  } finally {
    cancelling.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="发现未提交文件"
    width="min(980px, 94vw)"
    append-to-body
    :show-close="false"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
  >
    <div class="dirty-intro">
      <Icon icon="lucide:git-branch" width="24" />
      <div>
        <strong>创建任务分支前，需要先明确处理当前工作区中的每个文件。</strong>
        <p>分支：<span class="mono">{{ workspace?.branch || '读取中' }}</span>。Loopper 不会自动混入、隐藏或删除这些改动。</p>
      </div>
      <el-button plain :loading="loading" @click="loadWorkspace"><Icon icon="lucide:refresh-cw" />刷新文件列表</el-button>
    </div>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-skeleton v-if="loading && !workspace" :rows="4" animated />
    <template v-else-if="workspace">
      <el-table v-if="workspace.files.length" :data="workspace.files" max-height="440" class="dirty-table">
        <el-table-column label="状态" width="108">
          <template #default="scope"><span class="status-chip">{{ statusLabel(scope.row) }}</span></template>
        </el-table-column>
        <el-table-column label="文件" min-width="390">
          <template #default="scope">
            <div class="file-path mono">{{ scope.row.path }}</div>
            <div v-if="scope.row.originalPath" class="original-path mono">来自 {{ scope.row.originalPath }}</div>
          </template>
        </el-table-column>
        <el-table-column label="处理方式" width="220">
          <template #default="scope">
            <el-select v-model="actions[scope.row.path]" placeholder="请选择" style="width: 100%">
              <el-option label="提交到当前源分支" value="COMMIT" />
              <el-option label="暂存到 Git stash" value="STASH" />
              <el-option label="移除 / 丢弃改动" value="REMOVE" />
            </el-select>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="当前工作区已经干净，可以重新检查并继续。" :image-size="72" />

      <div v-if="hasCommit" class="commit-message">
        <label for="dirty-commit-message">保护提交说明</label>
        <el-input id="dirty-commit-message" v-model="commitMessage" maxlength="160" show-word-limit />
        <p>所有选择“提交”的文件会合并为当前源分支上的一个本地提交；不会自动推送。</p>
      </div>
      <div class="action-notes">
        <span><b>提交</b>：保留在当前源分支历史中</span>
        <span><b>暂存</b>：仅暂存所选路径并包含未跟踪文件</span>
        <span class="danger"><b>移除</b>：丢弃跟踪文件改动或删除未跟踪文件</span>
      </div>
    </template>

    <template #footer>
      <el-button type="danger" plain :loading="cancelling" @click="cancelAsFailure">取消并标记任务失败</el-button>
      <el-button type="primary" :loading="applying" :disabled="loading || !workspace" @click="recheckAndContinue">
        重新检查并继续
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.dirty-intro { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: start; gap: 12px; margin-bottom: 16px; color: var(--color-text-secondary); }
.dirty-intro strong { color: var(--color-text-primary); font-size: 14px; }.dirty-intro p { margin: 6px 0 0; font-size: 12px; }
.dirty-table { margin-top: 14px; }.file-path { color: var(--color-text-primary); font-size: 12px; word-break: break-all; }.original-path { margin-top: 4px; color: var(--color-text-tertiary); font-size: 10px; word-break: break-all; }
.status-chip { display: inline-flex; padding: 3px 7px; border: 1px solid rgb(245 158 11 / 28%); border-radius: 999px; background: rgb(245 158 11 / 8%); color: var(--color-session-warning); font-size: 10px; }
.commit-message { margin-top: 16px; padding: 14px; border: 1px solid var(--color-border-default); border-radius: 10px; background: rgb(7 12 22 / 48%); }.commit-message label { display: block; margin-bottom: 8px; color: var(--color-text-primary); font-size: 12px; }.commit-message p { margin: 7px 0 0; color: var(--color-text-tertiary); font-size: 10px; }
.action-notes { display: flex; flex-wrap: wrap; gap: 8px 16px; margin-top: 14px; color: var(--color-text-tertiary); font-size: 10px; }.action-notes b { color: var(--color-text-secondary); }.action-notes .danger, .action-notes .danger b { color: var(--color-task-error); }
@media (max-width: 680px) { .dirty-intro { grid-template-columns: auto 1fr; }.dirty-intro > button { grid-column: 1 / -1; } }
</style>
