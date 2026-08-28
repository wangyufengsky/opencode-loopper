<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { api } from '@/api/client'
import type { GitDiffScopeApproval, GitDiffScopeApprovalFile, GitDiffScopeDecisionAction, Task, TaskDiffPreview } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'

const props = defineProps<{ taskId: string; active: boolean }>()
const emit = defineEmits<{ resolved: [task: Task] }>()

const approval = ref<GitDiffScopeApproval>()
const open = ref(false)
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const selectedPath = ref('')
const preview = ref<TaskDiffPreview>()
const previewLoading = ref(false)
const previewError = ref('')
const decisions = ref<Record<string, GitDiffScopeDecisionAction | undefined>>({})
let autoOpenedRequest = ''
let previewRequest = 0

const decidedCount = computed(() => approval.value?.files.filter((file) => decisions.value[file.path]).length ?? 0)
const allDecided = computed(() => Boolean(approval.value?.files.length)
  && decidedCount.value === approval.value!.files.length)

type DiffLineKind = 'header' | 'hunk' | 'added' | 'removed' | 'context'
interface DiffLine { content: string; kind: DiffLineKind; oldLine?: number; newLine?: number }

const previewLines = computed<DiffLine[]>(() => parsePatch(preview.value?.patch ?? ''))

function parsePatch(patch: string): DiffLine[] {
  let oldLine: number | undefined
  let newLine: number | undefined
  return patch.split('\n').map((content) => {
    const hunk = content.match(/^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/)
    if (hunk) {
      oldLine = Number(hunk[1])
      newLine = Number(hunk[2])
      return { content, kind: 'hunk' }
    }
    if (content.startsWith('diff ') || content.startsWith('index ')
      || content.startsWith('---') || content.startsWith('+++')) return { content, kind: 'header' }
    if (content.startsWith('-')) {
      const row = { content, kind: 'removed' as const, oldLine }
      if (oldLine !== undefined) oldLine += 1
      return row
    }
    if (content.startsWith('+')) {
      const row = { content, kind: 'added' as const, newLine }
      if (newLine !== undefined) newLine += 1
      return row
    }
    if (content.startsWith('\\ No newline')) return { content, kind: 'context' }
    const row = { content, kind: 'context' as const, oldLine, newLine }
    if (oldLine !== undefined) oldLine += 1
    if (newLine !== undefined) newLine += 1
    return row
  })
}

function typeLabel(file: GitDiffScopeApprovalFile) {
  return ({ MODIFIED: '修改', DELETED: '删除', RENAMED_FROM: '重命名前', RENAMED_TO: '重命名后' } as const)[file.changeType]
}

function decide(path: string, action: GitDiffScopeDecisionAction) {
  decisions.value = { ...decisions.value, [path]: action }
}

async function loadPreview(path: string) {
  if (!approval.value) return
  const request = ++previewRequest
  selectedPath.value = path
  preview.value = undefined
  previewError.value = ''
  previewLoading.value = true
  try {
    const result = await api.getGitDiffScopeApprovalPreview(props.taskId, approval.value.requestId, path)
    if (request === previewRequest) preview.value = result
  } catch (cause) {
    if (request === previewRequest) previewError.value = userFacingError(cause, '无法加载本次文件差异')
  } finally {
    if (request === previewRequest) previewLoading.value = false
  }
}

async function refresh() {
  if (!props.active) {
    approval.value = undefined
    open.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    const latest = await api.getGitDiffScopeApproval(props.taskId)
    if (!latest) {
      approval.value = undefined
      open.value = false
      return
    }
    const changedRequest = approval.value?.requestId !== latest.requestId
    approval.value = latest
    if (changedRequest) {
      decisions.value = {}
      selectedPath.value = latest.files[0]?.path ?? ''
      if (autoOpenedRequest !== latest.requestId) {
        autoOpenedRequest = latest.requestId
        open.value = true
      }
    }
    if (selectedPath.value) await loadPreview(selectedPath.value)
  } catch (cause) {
    error.value = userFacingError(cause, '无法读取待确认的越界修改')
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!approval.value || !allDecided.value || submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    const current = approval.value
    const task = await api.resolveGitDiffScopeApproval(props.taskId, current.requestId, {
      expectedTaskVersion: current.taskVersion,
      decisions: current.files.map((file) => ({
        path: file.path,
        action: decisions.value[file.path]!,
        patchSha256: file.patchSha256,
      })),
    })
    emit('resolved', task)
    await refresh()
  } catch (cause) {
    error.value = userFacingError(cause, '未能应用文件范围决定')
  } finally {
    submitting.value = false
  }
}

watch(() => [props.taskId, props.active] as const, () => { void refresh() })
onMounted(() => { void refresh() })
</script>

<template>
  <section v-if="approval" class="scope-approval-card card card-pad" role="status">
    <span class="scope-icon"><Icon icon="lucide:file-warning" /></span>
    <div>
      <p class="eyebrow">需要你的决定</p>
      <h2 class="card-title">{{ approval.files.length }} 个既有文件超出允许范围</h2>
      <p>任务未被判定失败。请查看每个文件的具体位置与修改前后内容，再决定放行或拒绝。</p>
    </div>
    <el-button type="warning" plain @click="open = true">查看差异并决定</el-button>
  </section>

  <el-dialog v-model="open" class="scope-approval-dialog" width="min(1180px, 95vw)" append-to-body :close-on-click-modal="false">
    <template #header>
      <div class="dialog-title">
        <span><Icon icon="lucide:shield-question" /></span>
        <div><p class="eyebrow">GIT_DIFF 范围确认</p><strong>决定是否接受既有文件的越界修改</strong></div>
      </div>
    </template>

    <div v-if="loading && !approval" class="dialog-state"><Icon class="spin" icon="lucide:loader-circle" />正在读取差异…</div>
    <div v-else-if="approval" class="approval-layout">
      <aside class="approval-files" aria-label="待决定文件">
        <div class="files-heading"><strong>既有文件</strong><span>{{ decidedCount }} / {{ approval.files.length }} 已决定</span></div>
        <article v-for="file in approval.files" :key="file.path" :class="['file-card', { active: selectedPath === file.path }]">
          <button class="file-open" type="button" :aria-label="`查看差异 ${file.path}`" @click="loadPreview(file.path)">
            <span>{{ typeLabel(file) }}</span><code>{{ file.path }}</code><Icon icon="lucide:chevron-right" />
          </button>
          <div class="file-decisions" :aria-label="`${file.path} 放行决定`">
            <button type="button" data-decision="ALLOW" :class="{ selected: decisions[file.path] === 'ALLOW' }" @click="decide(file.path, 'ALLOW')"><Icon icon="lucide:check" />放行</button>
            <button type="button" data-decision="REJECT" :class="{ selected: decisions[file.path] === 'REJECT' }" @click="decide(file.path, 'REJECT')"><Icon icon="lucide:x" />拒绝</button>
          </div>
        </article>
        <p class="auto-policy"><Icon icon="lucide:badge-check" />允许范围外的新增文件已自动放行；禁止路径和删除限制仍是硬性拦截。</p>
      </aside>

      <section class="approval-preview" aria-label="文件修改前后差异">
        <header>
          <div><span class="tiny muted">当前文件</span><code>{{ selectedPath }}</code></div>
          <div class="diff-legend"><span class="old">− 修改前</span><span class="new">+ 修改后</span><span class="where">@@ 修改位置</span></div>
        </header>
        <div v-if="previewLoading" class="dialog-state"><Icon class="spin" icon="lucide:loader-circle" />正在加载该文件的差异…</div>
        <div v-else-if="previewError" class="dialog-state error"><Icon icon="lucide:triangle-alert" />{{ previewError }}</div>
        <div v-else-if="preview?.patch" class="diff-table" role="region" :aria-label="`${selectedPath} 修改前后`">
          <div class="diff-columns"><span>旧行</span><span>新行</span><span>内容</span></div>
          <div v-for="(line, index) in previewLines" :key="index" :class="['diff-line', line.kind]">
            <span>{{ line.oldLine ?? '' }}</span><span>{{ line.newLine ?? '' }}</span><code>{{ line.content || ' ' }}</code>
          </div>
        </div>
        <div v-else class="dialog-state">该文件没有可展示的文本差异。</div>
      </section>
    </div>

    <p v-if="error" class="approval-error" role="alert">{{ error }}</p>
    <template #footer>
      <div class="dialog-footer">
        <span>决定与当前展示的差异内容绑定；文件再次变化时必须重新确认。</span>
        <div><el-button @click="open = false">稍后处理</el-button><el-button type="primary" :disabled="!allDecided" :loading="submitting" @click="submit">按以上决定继续验证</el-button></div>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.scope-approval-card { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 14px; margin-top: 16px; border-color: rgb(245 158 11 / 42%); background: linear-gradient(120deg, rgb(245 158 11 / 10%), rgb(15 23 42 / 24%)); }
.scope-approval-card p:last-child { margin: 7px 0 0; color: var(--color-text-secondary); font-size: 11px; line-height: 1.6; }.scope-icon { display: grid; width: 36px; height: 36px; place-items: center; border: 1px solid rgb(245 158 11 / 35%); border-radius: 9px; color: #fbbf24; background: rgb(245 158 11 / 9%); }
:global(.scope-approval-dialog) { overflow: hidden; border: 1px solid rgb(245 158 11 / 24%); border-radius: 13px; background: #0b1220; box-shadow: 0 28px 90px rgb(0 0 0 / 62%); }
:global(.scope-approval-dialog .el-dialog__header) { margin: 0; padding: 15px 18px; border-bottom: 1px solid var(--color-border-default); }
:global(.scope-approval-dialog .el-dialog__body) { padding: 0; }
:global(.scope-approval-dialog .el-dialog__footer) { padding: 12px 16px; border-top: 1px solid var(--color-border-default); }
.dialog-title { display: flex; align-items: center; gap: 10px; }.dialog-title > span { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 8px; color: #fbbf24; background: rgb(245 158 11 / 10%); }.dialog-title strong { display: block; margin-top: 4px; color: var(--color-text-primary); font-size: 13px; }
.approval-layout { display: grid; grid-template-columns: minmax(270px, 32%) minmax(0, 1fr); min-height: min(68vh, 680px); }.approval-files { overflow: auto; padding: 14px; border-right: 1px solid var(--color-border-default); background: rgb(2 6 23 / 26%); }.files-heading { display: flex; justify-content: space-between; margin-bottom: 10px; color: var(--color-text-primary); font-size: 11px; }.files-heading span { color: var(--color-text-tertiary); font: 10px var(--font-code); }
.file-card { margin-bottom: 8px; overflow: hidden; border: 1px solid var(--color-border-default); border-radius: 9px; background: rgb(15 23 42 / 46%); }.file-card.active { border-color: rgb(34 211 238 / 42%); }.file-open { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 8px; width: 100%; padding: 10px; border: 0; background: transparent; color: inherit; text-align: left; cursor: pointer; }.file-open > span { padding: 3px 5px; border-radius: 4px; color: #fbbf24; background: rgb(245 158 11 / 10%); font-size: 8px; }.file-open code { overflow: hidden; color: #dbeafe; font: 9px/1.4 var(--font-code); text-overflow: ellipsis; white-space: nowrap; }
.file-decisions { display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid var(--color-border-default); }.file-decisions button { display: flex; align-items: center; justify-content: center; gap: 5px; padding: 7px; border: 0; background: transparent; color: var(--color-text-tertiary); font-size: 9px; cursor: pointer; }.file-decisions button + button { border-left: 1px solid var(--color-border-default); }.file-decisions button[data-decision="ALLOW"].selected { color: #86efac; background: rgb(34 197 94 / 13%); }.file-decisions button[data-decision="REJECT"].selected { color: #fca5a5; background: rgb(239 68 68 / 13%); }.auto-policy { display: flex; gap: 7px; margin: 13px 2px 0; color: var(--color-text-tertiary); font-size: 9px; line-height: 1.55; }
.approval-preview { min-width: 0; background: #070c16; }.approval-preview > header { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 10px 14px; border-bottom: 1px solid var(--color-border-default); }.approval-preview header code { display: block; margin-top: 3px; color: #c7d2fe; font: 10px var(--font-code); }.diff-legend { display: flex; gap: 6px; }.diff-legend span { padding: 4px 7px; border-radius: 5px; font: 8px var(--font-code); }.diff-legend .old { color: #fca5a5; background: rgb(239 68 68 / 13%); }.diff-legend .new { color: #86efac; background: rgb(34 197 94 / 13%); }.diff-legend .where { color: #67e8f9; background: rgb(34 211 238 / 11%); }
.diff-table { max-height: min(62vh, 620px); overflow: auto; }.diff-columns, .diff-line { display: grid; grid-template-columns: 50px 50px max-content; min-width: 100%; width: max-content; }.diff-columns { position: sticky; top: 0; z-index: 2; border-bottom: 1px solid var(--color-border-default); background: #0b1322; color: #526079; font: 8px/1.8 var(--font-code); }.diff-columns span { padding: 2px 8px; text-align: right; }.diff-columns span:last-child { min-width: 700px; text-align: left; }.diff-line { color: #a8b5c9; font: 10px/1.58 var(--font-code); }.diff-line > span { position: sticky; left: 0; padding: 1px 8px; border-right: 1px solid rgb(130 147 173 / 9%); background: #09101c; color: #526079; text-align: right; user-select: none; }.diff-line > span:nth-child(2) { left: 50px; }.diff-line > code { min-width: 700px; padding: 1px 11px; color: inherit; font: inherit; white-space: pre; }.diff-line.added { background: rgb(34 197 94 / 13%); color: #bbf7d0; }.diff-line.removed { background: rgb(239 68 68 / 13%); color: #fecaca; }.diff-line.hunk { background: rgb(34 211 238 / 9%); color: #67e8f9; }.diff-line.header { color: #7d8da8; }.diff-line.added > span { background: #0d261e; color: #4ade80; }.diff-line.removed > span { background: #2a1118; color: #fb7185; }.diff-line.hunk > span { background: #0b202a; color: #22d3ee; }
.dialog-state { display: grid; min-height: 340px; place-content: center; justify-items: center; gap: 8px; color: var(--color-text-tertiary); font-size: 11px; }.dialog-state.error, .approval-error { color: var(--color-danger); }.approval-error { margin: 0; padding: 9px 14px; border-top: 1px solid rgb(239 68 68 / 24%); background: rgb(239 68 68 / 6%); font-size: 10px; }.dialog-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; }.dialog-footer > span { color: var(--color-text-tertiary); font-size: 9px; }.spin { animation: spin .8s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 820px) { .scope-approval-card { grid-template-columns: auto minmax(0, 1fr); }.scope-approval-card > button { grid-column: 1 / -1; }.approval-layout { grid-template-columns: 1fr; }.approval-files { max-height: 240px; border-right: 0; border-bottom: 1px solid var(--color-border-default); }.dialog-footer { align-items: stretch; flex-direction: column; } }
</style>
