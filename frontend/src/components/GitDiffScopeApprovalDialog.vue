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
const allowedCount = computed(() => approval.value?.files.filter((file) => decisions.value[file.path] === 'ALLOW').length ?? 0)
const rejectedCount = computed(() => approval.value?.files.filter((file) => decisions.value[file.path] === 'REJECT').length ?? 0)
const pendingCount = computed(() => (approval.value?.files.length ?? 0) - decidedCount.value)
const selectedFile = computed(() => approval.value?.files.find((file) => file.path === selectedPath.value))
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
  <section v-if="approval" class="scope-approval-card card card-pad" role="status" aria-live="polite">
    <span class="scope-icon"><Icon icon="lucide:file-warning" aria-hidden="true" /></span>
    <div>
      <p class="eyebrow">需要你的决定</p>
      <h2 class="card-title">{{ approval.files.length }} 个既有文件需要确认</h2>
      <p>任务没有失败。请检查实际差异后决定是否接受。</p>
    </div>
    <el-button type="primary" @click="open = true">审阅文件</el-button>
  </section>

  <el-dialog v-model="open" class="scope-approval-dialog" width="min(1120px, 94vw)" append-to-body :close-on-click-modal="false">
    <template #header>
      <div class="dialog-title">
        <span><Icon icon="lucide:git-compare-arrows" aria-hidden="true" /></span>
        <div><strong>审阅范围外修改</strong><small>决定只对当前差异内容有效；文件再次变化会重新请求确认</small></div>
      </div>
    </template>

    <div v-if="loading && !approval" class="dialog-state"><Icon class="spin" icon="lucide:loader-circle" aria-hidden="true" />正在读取差异…</div>
    <div v-else-if="approval" class="approval-layout">
      <aside class="approval-files" aria-label="待决定文件">
        <div class="files-heading"><strong>既有文件</strong><span>{{ approval.files.length }}</span></div>
        <button
          v-for="file in approval.files"
          :key="file.path"
          type="button"
          :class="['file-open', { active: selectedPath === file.path }]"
          :aria-label="`查看差异 ${file.path}`"
          :aria-current="selectedPath === file.path ? 'true' : undefined"
          @click="loadPreview(file.path)"
        >
          <span>{{ typeLabel(file) }}</span>
          <code :title="file.path">{{ file.path }}</code>
          <Icon v-if="decisions[file.path] === 'ALLOW'" class="file-decision allowed" icon="lucide:check" aria-label="已接受" />
          <Icon v-else-if="decisions[file.path] === 'REJECT'" class="file-decision rejected" icon="lucide:x" aria-label="已拒绝" />
          <Icon v-else class="file-decision pending" icon="lucide:circle" aria-label="待决定" />
        </button>
        <p class="auto-policy"><Icon icon="lucide:badge-check" aria-hidden="true" /><span>范围外新增文件已自动接受，并会保留在审计记录中。</span></p>
      </aside>

      <section class="approval-preview" aria-label="文件修改前后差异">
        <header class="preview-heading">
          <div><span>{{ selectedFile ? typeLabel(selectedFile) : '文件' }}</span><code :title="selectedPath">{{ selectedPath }}</code></div>
          <div v-if="selectedFile" class="file-decisions" :aria-label="`${selectedPath} 接受决定`">
            <button type="button" data-decision="ALLOW" :class="{ selected: decisions[selectedPath] === 'ALLOW' }" @click="decide(selectedPath, 'ALLOW')"><Icon icon="lucide:check" aria-hidden="true" />接受此文件</button>
            <button type="button" data-decision="REJECT" :class="{ selected: decisions[selectedPath] === 'REJECT' }" @click="decide(selectedPath, 'REJECT')"><Icon icon="lucide:x" aria-hidden="true" />拒绝此文件</button>
          </div>
        </header>
        <div class="diff-legend"><span class="old">删除行</span><span class="new">新增行</span><span class="where">变更区块</span><b v-if="preview?.truncated">内容已安全截断</b></div>
        <div v-if="previewLoading" class="dialog-state"><Icon class="spin" icon="lucide:loader-circle" aria-hidden="true" />正在加载该文件的差异…</div>
        <div v-else-if="previewError" class="dialog-state error"><Icon icon="lucide:triangle-alert" aria-hidden="true" />{{ previewError }}</div>
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
        <span>已接受 {{ allowedCount }} · 已拒绝 {{ rejectedCount }} · 待决定 {{ pendingCount }}</span>
        <div><el-button @click="open = false">稍后处理</el-button><el-button type="primary" :disabled="!allDecided" :loading="submitting" @click="submit">确认决定并继续验证</el-button></div>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.scope-approval-card { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 14px; margin-top: 16px; border-color: rgb(72 91 120 / 70%); background: #101827; }
.scope-approval-card p:last-child { margin: 6px 0 0; color: var(--color-text-secondary); font-size: 11px; line-height: 1.55; }
.scope-icon { display: grid; width: 38px; height: 38px; place-items: center; border: 1px solid rgb(91 116 151 / 54%); border-radius: 10px; color: #9fb2cc; background: #162238; }
:global(.scope-approval-dialog) { overflow: hidden; overscroll-behavior: contain; border: 1px solid rgb(72 91 120 / 72%); border-radius: 14px; background: #0b1220; box-shadow: 0 28px 90px rgb(0 0 0 / 58%); }
:global(.scope-approval-dialog .el-dialog__header) { margin: 0; padding: 15px 18px; border-bottom: 1px solid var(--color-border-default); }
:global(.scope-approval-dialog .el-dialog__body) { padding: 0; }
:global(.scope-approval-dialog .el-dialog__footer) { padding: 12px 16px; border-top: 1px solid var(--color-border-default); }
.dialog-title { display: flex; align-items: center; gap: 11px; }
.dialog-title > span { display: grid; width: 34px; height: 34px; flex: 0 0 auto; place-items: center; border: 1px solid var(--color-border-default); border-radius: 9px; color: #aebed4; background: #131f32; }
.dialog-title > div { display: grid; gap: 3px; }
.dialog-title strong { color: var(--color-text-primary); font-size: 14px; line-height: 1.35; }
.dialog-title small { color: var(--color-text-muted); font-size: 10px; line-height: 1.45; }
.approval-layout { display: grid; grid-template-columns: 250px minmax(0, 1fr); min-height: min(68vh, 680px); max-height: min(74vh, 720px); overflow: hidden; }
.approval-files { overflow: auto; overscroll-behavior: contain; padding: 12px; border-right: 1px solid var(--color-border-default); background: #0d1523; }
.files-heading { display: flex; align-items: center; justify-content: space-between; margin: 1px 2px 10px; color: var(--color-text-primary); font-size: 11px; }
.files-heading span { display: grid; min-width: 22px; height: 22px; place-items: center; border-radius: 999px; color: var(--color-text-muted); background: #172338; font: 10px var(--font-code); }
.file-open { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 8px; width: 100%; min-height: 46px; margin-bottom: 6px; padding: 8px 9px; border: 1px solid transparent; border-radius: 8px; color: inherit; background: transparent; text-align: left; touch-action: manipulation; cursor: pointer; }
.file-open:hover { border-color: var(--color-border-default); background: #121d30; }
.file-open.active { border-color: rgb(34 211 238 / 34%); background: #142136; box-shadow: inset 2px 0 var(--color-accent-cyan); }
.file-open:focus-visible { outline: 2px solid var(--color-accent-cyan); outline-offset: 2px; }
.file-open > span { padding: 3px 5px; border-radius: 4px; color: #9fb2cc; background: #1a2940; font-size: 9px; }
.file-open code { min-width: 0; overflow: hidden; color: #d4deec; font: 10px/1.4 var(--font-code); text-overflow: ellipsis; white-space: nowrap; }
.file-decision { width: 15px; color: var(--color-text-muted); }
.file-decision.allowed { color: #4ade80; }
.file-decision.rejected { color: #fb7185; }
.file-decision.pending { opacity: .55; }
.auto-policy { display: flex; align-items: flex-start; gap: 7px; margin: 14px 3px 2px; color: var(--color-text-muted); font-size: 10px; line-height: 1.5; }
.auto-policy :deep(svg) { flex: 0 0 auto; margin-top: 1px; color: #6f829e; }
.approval-preview { display: grid; min-width: 0; min-height: 0; grid-template-rows: auto auto minmax(0, 1fr); background: #070c16; }
.preview-heading { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 16px; min-height: 58px; padding: 10px 14px; border-bottom: 1px solid var(--color-border-default); }
.preview-heading > div:first-child { display: flex; min-width: 0; align-items: center; gap: 8px; }
.preview-heading > div:first-child > span { flex: 0 0 auto; padding: 3px 6px; border-radius: 5px; color: #9fb2cc; background: #172338; font-size: 9px; }
.preview-heading code { min-width: 0; overflow: hidden; color: #d7e1ef; font: 10px var(--font-code); text-overflow: ellipsis; white-space: nowrap; }
.file-decisions { display: flex; flex: 0 0 auto; overflow: hidden; border: 1px solid var(--color-border-default); border-radius: 7px; background: #0d1523; }
.file-decisions button { display: inline-flex; align-items: center; justify-content: center; gap: 5px; min-height: 31px; padding: 6px 9px; border: 0; color: var(--color-text-secondary); background: transparent; font-size: 10px; touch-action: manipulation; cursor: pointer; }
.file-decisions button:hover { color: var(--color-text-primary); background: var(--color-bg-hover); }
.file-decisions button:focus-visible { position: relative; z-index: 1; outline: 2px solid var(--color-accent-cyan); outline-offset: -2px; }
.file-decisions button + button { border-left: 1px solid var(--color-border-default); }
.file-decisions button[data-decision="ALLOW"].selected { color: #86efac; background: rgb(34 197 94 / 16%); }
.file-decisions button[data-decision="REJECT"].selected { color: #fda4af; background: rgb(239 68 68 / 16%); }
.diff-legend { display: flex; align-items: center; gap: 7px; min-height: 36px; padding: 7px 14px; border-bottom: 1px solid var(--color-border-default); color: var(--color-text-muted); font-size: 9px; }
.diff-legend span { display: inline-flex; align-items: center; gap: 5px; }
.diff-legend span::before { width: 7px; height: 7px; border-radius: 2px; content: ''; }
.diff-legend .old::before { background: rgb(239 68 68 / 72%); }
.diff-legend .new::before { background: rgb(34 197 94 / 72%); }
.diff-legend .where::before { background: rgb(34 211 238 / 72%); }
.diff-legend b { margin-left: auto; color: var(--color-session-warning); font-weight: 600; }
.diff-table { min-height: 0; overflow: auto; overscroll-behavior: contain; }
.diff-columns, .diff-line { display: grid; grid-template-columns: 54px 54px max-content; min-width: 100%; width: max-content; }
.diff-columns { position: sticky; top: 0; z-index: 2; border-bottom: 1px solid var(--color-border-default); color: #65748c; background: #0b1322; font: 9px/1.8 var(--font-code); }
.diff-columns span { padding: 3px 9px; text-align: right; }
.diff-columns span:last-child { min-width: 700px; text-align: left; }
.diff-line { color: #b5c1d3; font: 11px/1.62 var(--font-code); }
.diff-line > span { position: sticky; left: 0; padding: 1px 9px; border-right: 1px solid rgb(130 147 173 / 9%); color: #65748c; background: #09101c; text-align: right; user-select: none; }
.diff-line > span:nth-child(2) { left: 54px; }
.diff-line > code { min-width: 700px; padding: 1px 12px; color: inherit; font: inherit; white-space: pre; }
.diff-line.added { color: #bbf7d0; background: rgb(34 197 94 / 13%); }
.diff-line.removed { color: #fecaca; background: rgb(239 68 68 / 13%); }
.diff-line.hunk { color: #67e8f9; background: rgb(34 211 238 / 9%); }
.diff-line.header { color: #7d8da8; }
.diff-line.added > span { color: #4ade80; background: #0d261e; }
.diff-line.removed > span { color: #fb7185; background: #2a1118; }
.diff-line.hunk > span { color: #22d3ee; background: #0b202a; }
.dialog-state { display: grid; min-height: 340px; place-content: center; justify-items: center; gap: 8px; color: var(--color-text-tertiary); font-size: 11px; }
.dialog-state.error, .approval-error { color: var(--color-danger); }
.approval-error { margin: 0; padding: 9px 14px; border-top: 1px solid rgb(239 68 68 / 24%); background: rgb(239 68 68 / 6%); font-size: 10px; }
.dialog-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.dialog-footer > span { color: var(--color-text-secondary); font: 10px var(--font-code); font-variant-numeric: tabular-nums; }
.spin { animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 820px) { .scope-approval-card { grid-template-columns: auto minmax(0, 1fr); }.scope-approval-card > button { grid-column: 1 / -1; }.approval-layout { grid-template-columns: 1fr; max-height: none; }.approval-files { max-height: 220px; border-right: 0; border-bottom: 1px solid var(--color-border-default); }.preview-heading { align-items: stretch; flex-direction: column; }.file-decisions { align-self: flex-start; }.dialog-footer { align-items: stretch; flex-direction: column; } }
@media (max-width: 560px) { .scope-approval-card { grid-template-columns: 1fr; }.scope-icon { display: none; }.file-decisions { width: 100%; }.file-decisions button { flex: 1; }.diff-legend { flex-wrap: wrap; }.diff-legend b { width: 100%; margin-left: 0; } }
@media (prefers-reduced-motion: reduce) { .spin { animation: none; } }
</style>
