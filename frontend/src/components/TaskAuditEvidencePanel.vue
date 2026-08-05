<script setup lang="ts">
import { computed, ref } from 'vue'
import { Icon } from '@iconify/vue'
import JudgeReviewCard from '@/components/JudgeReviewCard.vue'
import { api } from '@/api/client'
import type { Artifact, Attempt, JudgeRun, TaskDiffPreview, VerifierResult } from '@/types/domain'

const props = defineProps<{
  taskId: string
  attempts: Attempt[]
  artifacts: Artifact[]
  judges: JudgeRun[]
  directExecution: boolean
}>()

const activeTab = ref<'logs' | 'diff' | 'evidence' | 'judges'>('evidence')
const orderedAttempts = computed(() => [...props.attempts].sort((left, right) => right.ordinal - left.ordinal))
const verificationRows = computed(() => orderedAttempts.value.flatMap((attempt) => attempt.verifiers.map((verifier, index) => ({ attempt, verifier, index }))))
const logRows = computed(() => verificationRows.value.filter(({ verifier }) => Boolean(output(verifier))))
const diffArtifact = computed(() => props.artifacts.find((artifact) => artifact.kind === 'DIFF'))
const gitDiffRows = computed(() => verificationRows.value.filter(({ verifier }) => verifier.name.toUpperCase() === 'GIT_DIFF'))
const changedPaths = computed(() => unique(gitDiffRows.value.flatMap(({ verifier }) => strings(verifier.evidence?.changedPaths))))
const untrackedPaths = computed(() => new Set(gitDiffRows.value.flatMap(({ verifier }) => strings(verifier.evidence?.untrackedPaths))))
const violations = computed(() => unique(gitDiffRows.value.flatMap(({ verifier }) => strings(verifier.evidence?.violations))))
const sessionDiff = computed(() => parseSessionDiff(diffArtifact.value?.content))
const passedCount = computed(() => verificationRows.value.filter(({ verifier }) => verifier.status === 'PASS').length)
const previewOpen = ref(false)
const previewLoading = ref(false)
const previewError = ref('')
const previewPath = ref('')
const preview = ref<TaskDiffPreview>()
let previewRequest = 0

const previewLines = computed(() => (preview.value?.patch ?? '').split('\n').map((content, index) => ({
  content,
  number: index + 1,
  kind: diffLineKind(content),
})))

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

function unique(values: string[]) {
  return [...new Set(values)]
}

function output(verifier: VerifierResult) {
  return verifier.output ?? (typeof verifier.evidence?.output === 'string' ? verifier.evidence.output : '')
}

function argv(verifier: VerifierResult) {
  const command = strings(verifier.evidence?.argv)
  return command.length ? command.join(' ') : verifier.name
}

function exitCode(verifier: VerifierResult) {
  return typeof verifier.evidence?.exitCode === 'number' ? verifier.evidence.exitCode : undefined
}

function detail(verifier: VerifierResult) {
  const items: string[] = []
  const code = exitCode(verifier)
  if (code !== undefined) items.push(`退出码 ${code}`)
  if (verifier.elapsedMs) items.push(`${verifier.elapsedMs} ms`)
  if (verifier.evidence?.timedOut === true) items.push('已超时')
  if (verifier.evidence?.outputTruncated === true) items.push('输出已截断')
  return items.join(' · ')
}

function parseSessionDiff(content?: string) {
  if (!content?.trim()) return { empty: true, text: '' }
  try {
    const parsed = JSON.parse(content) as unknown
    if (Array.isArray(parsed)) return { empty: parsed.length === 0, text: parsed.length ? JSON.stringify(parsed, null, 2) : '' }
    if (parsed && typeof parsed === 'object') {
      const record = parsed as Record<string, unknown>
      if (record.available === false) return { empty: true, text: '', unavailable: typeof record.message === 'string' ? record.message : '会话差异不可用' }
      return { empty: false, text: JSON.stringify(parsed, null, 2) }
    }
  } catch {
    return { empty: false, text: content }
  }
  return { empty: false, text: content }
}

function pathState(path: string) {
  return untrackedPaths.value.has(path) ? '新增' : '修改'
}

function diffLineKind(line: string) {
  if (line.startsWith('@@')) return 'hunk'
  if (line.startsWith('+') && !line.startsWith('+++')) return 'added'
  if (line.startsWith('-') && !line.startsWith('---')) return 'removed'
  if (line.startsWith('diff ') || line.startsWith('index ') || line.startsWith('---') || line.startsWith('+++')) return 'header'
  return 'context'
}

async function showDiff(path: string) {
  const request = ++previewRequest
  previewPath.value = path
  preview.value = undefined
  previewError.value = ''
  previewLoading.value = true
  previewOpen.value = true
  try {
    const result = await api.getTaskDiffPreview(props.taskId, path)
    if (request === previewRequest) preview.value = result
  } catch (cause) {
    if (request === previewRequest) previewError.value = cause instanceof Error ? cause.message : '无法加载文件差异'
  } finally {
    if (request === previewRequest) previewLoading.value = false
  }
}
</script>

<template>
  <article class="card audit-panel">
    <div class="audit-header">
      <div><p class="eyebrow">审计证据</p><h2 class="card-title">验证、差异、评审与日志</h2><p class="card-description">先展示结构化结果；需要排障时再展开原始日志。</p></div>
      <el-tabs v-model="activeTab" class="audit-tabs">
        <el-tab-pane label="日志" name="logs" />
        <el-tab-pane label="差异" name="diff" />
        <el-tab-pane label="验证" name="evidence" />
        <el-tab-pane label="评审" name="judges" />
      </el-tabs>
    </div>

    <div class="audit-content">
      <template v-if="activeTab === 'logs'">
        <div class="source-note"><Icon icon="lucide:terminal-square" /><span><strong>确定性验证日志</strong>来自验证器真实进程输出；模型会话实时输出保留在上方会话面板。</span><b>{{ logRows.length }} 份</b></div>
        <div v-if="logRows.length" class="audit-stack">
          <details v-for="({ attempt, verifier }) in logRows" :key="verifier.id" class="audit-disclosure">
            <summary><span :class="['state-dot', verifier.status.toLowerCase()]" /><span class="summary-main"><strong>{{ argv(verifier) }}</strong><small>尝试 {{ attempt.ordinal }} · {{ verifier.summary }}<template v-if="detail(verifier)"> · {{ detail(verifier) }}</template></small></span><Icon icon="lucide:chevron-down" /></summary>
            <pre class="audit-log">{{ output(verifier) }}</pre>
          </details>
        </div>
        <div v-else class="audit-empty"><Icon icon="lucide:clock-3" /><strong>验证日志尚未产生</strong><p>验证器开始执行后，这里会显示命令的真实 stdout / stderr；不会生成模拟日志。</p></div>
      </template>

      <template v-else-if="activeTab === 'diff'">
        <div class="source-note"><Icon icon="lucide:git-compare-arrows" /><span><strong>不需要连接远端 Git。</strong>{{ directExecution ? '当前任务使用 Loopper 私有基线对比原项目目录。' : '当前任务使用本地 Git HEAD 与隔离 worktree 进行对比。' }}</span><b>{{ changedPaths.length }} 个文件</b></div>
        <div v-if="changedPaths.length" class="diff-list" aria-label="变更文件">
          <button v-for="path in changedPaths" :key="path" type="button" class="diff-row" :aria-label="`预览差异 ${path}`" @click="showDiff(path)"><span :class="['diff-state', { added: untrackedPaths.has(path) }]">{{ pathState(path) }}</span><code>{{ path }}</code><Icon icon="lucide:eye" /></button>
        </div>
        <div v-else class="audit-empty"><Icon icon="lucide:file-diff" /><strong>没有检测到文件变更</strong><p>确定性 GIT_DIFF 证据与会话补丁均未报告变更。</p></div>
        <div v-if="violations.length" class="violation-box"><strong><Icon icon="lucide:triangle-alert" />范围违规</strong><ul><li v-for="item in violations" :key="item">{{ item }}</li></ul></div>
        <details v-if="sessionDiff.text" class="audit-disclosure secondary"><summary><span class="summary-main"><strong>OpenCode 会话补丁</strong><small>{{ diffArtifact?.title }} · 补充证据</small></span><Icon icon="lucide:chevron-down" /></summary><pre class="audit-log">{{ sessionDiff.text }}</pre></details>
        <p v-else-if="sessionDiff.unavailable" class="secondary-note">OpenCode 会话补丁不可用：{{ sessionDiff.unavailable }}。上方确定性文件范围证据仍然有效。</p>
        <p v-else-if="sessionDiff.empty && changedPaths.length" class="secondary-note">OpenCode 会话接口返回了空补丁 `[]`；上方文件清单来自实际通过的 GIT_DIFF 验证器。</p>
      </template>

      <template v-else-if="activeTab === 'evidence'">
        <div class="source-note"><Icon icon="lucide:badge-check" /><span><strong>确定性验证</strong>按尝试和验证器拆分展示，原始输出可单独展开。</span><b>{{ passedCount }} / {{ verificationRows.length }} 通过</b></div>
        <div v-if="verificationRows.length" class="verification-grid">
          <article v-for="({ attempt, verifier }, index) in verificationRows" :key="verifier.id" class="verification-card">
            <header><span :class="['verification-icon', verifier.status.toLowerCase()]"><Icon :icon="verifier.status === 'PASS' ? 'lucide:check' : verifier.status === 'FAIL' ? 'lucide:x' : 'lucide:loader-circle'" /></span><div><strong>{{ verifier.name }}</strong><small>尝试 {{ attempt.ordinal }} · 验证 {{ index + 1 }}</small></div><span :class="['result-pill', verifier.status.toLowerCase()]">{{ verifier.status === 'PASS' ? '通过' : verifier.status === 'FAIL' ? '失败' : '等待' }}</span></header>
            <p>{{ verifier.summary }}</p>
            <code v-if="verifier.evidence?.argv">{{ argv(verifier) }}</code>
            <div v-if="detail(verifier)" class="verification-meta">{{ detail(verifier) }}</div>
            <details v-if="output(verifier)" class="inline-output"><summary>查看完整输出</summary><pre class="audit-log">{{ output(verifier) }}</pre></details>
            <div v-if="verifier.name.toUpperCase() === 'GIT_DIFF' && strings(verifier.evidence?.changedPaths).length" class="verification-meta">已检查 {{ strings(verifier.evidence?.changedPaths).length }} 个变更文件</div>
          </article>
        </div>
        <div v-else class="audit-empty"><Icon icon="lucide:badge-help" /><strong>尚无验证结果</strong><p>任务进入确定性验证阶段后会在此生成结构化记录。</p></div>
      </template>

      <template v-else>
        <div class="source-note"><Icon icon="lucide:scale" /><span><strong>独立双评审</strong>只展示解析后的结论与 Markdown 证据，原始协议 JSON 保留在后端审计记录中。</span><b>{{ judges.length }} 份</b></div>
        <div v-if="judges.length" class="judge-grid"><JudgeReviewCard v-for="judge in judges" :key="judge.id" :judge="judge" /></div>
        <div v-else class="audit-empty"><Icon icon="lucide:hourglass" /><strong>评审尚未开始</strong><p>最终阶段的确定性验证全部通过后，系统会启动需求与风险两个只读评审会话。</p></div>
      </template>
    </div>

    <el-dialog v-model="previewOpen" class="diff-preview-dialog" width="min(1040px, 92vw)" append-to-body destroy-on-close>
      <template #header>
        <div class="preview-title"><span :class="['diff-state', { added: preview?.changeType === 'NEW' || untrackedPaths.has(previewPath) }]">{{ preview?.changeType === 'NEW' || untrackedPaths.has(previewPath) ? '新增' : '修改' }}</span><div><strong>文件差异预览</strong><code>{{ previewPath }}</code></div></div>
      </template>
      <div class="preview-legend"><span class="added">新增行</span><span class="removed">删除行</span><span class="hunk">变更区块</span><b v-if="preview?.truncated">内容已安全截断</b></div>
      <div v-if="previewLoading" class="preview-state"><Icon icon="lucide:loader-circle" class="spin" /><strong>正在生成差异预览</strong></div>
      <div v-else-if="previewError" class="preview-state error"><Icon icon="lucide:triangle-alert" /><strong>预览加载失败</strong><p>{{ previewError }}</p></div>
      <div v-else-if="preview?.patch" class="diff-preview" role="region" :aria-label="`${previewPath} 统一差异`">
        <div v-for="line in previewLines" :key="line.number" :class="['preview-line', line.kind]"><span>{{ line.number }}</span><code>{{ line.content || ' ' }}</code></div>
      </div>
      <div v-else class="preview-state"><Icon icon="lucide:file-check-2" /><strong>当前文件没有可显示的文本差异</strong></div>
    </el-dialog>
  </article>
</template>

<style scoped>
.audit-panel { min-width: 0; overflow: hidden; }
.audit-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; padding: 20px 20px 0; border-bottom: 1px solid rgb(130 147 173 / 10%); }
.audit-header > div { min-width: 0; }.audit-header .card-description { margin-bottom: 14px; }
.audit-tabs { flex: 0 0 auto; }.audit-tabs :deep(.el-tabs__header) { margin: 0; }.audit-tabs :deep(.el-tabs__nav-wrap::after) { display: none; }
.audit-content { min-height: 360px; padding: 16px 20px 20px; }
.source-note { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 9px; padding: 10px 12px; border: 1px solid rgb(34 211 238 / 15%); border-radius: 8px; background: rgb(34 211 238 / 4%); color: var(--color-text-secondary); font-size: 11px; line-height: 1.55; }
.source-note > svg { color: var(--color-accent-cyan); }.source-note strong { color: var(--color-text-primary); }.source-note > b { color: var(--color-accent-cyan); font: 700 10px/1 var(--font-code); white-space: nowrap; }
.audit-stack { display: grid; gap: 9px; margin-top: 12px; }.audit-disclosure { overflow: hidden; border: 1px solid var(--color-border-default); border-radius: 9px; background: rgb(7 12 22 / 56%); }.audit-disclosure.secondary { margin-top: 12px; }
.audit-disclosure summary { display: flex; align-items: center; gap: 9px; padding: 11px 12px; color: var(--color-text-secondary); cursor: pointer; list-style: none; }.audit-disclosure summary::-webkit-details-marker { display: none; }.audit-disclosure summary > svg { flex: 0 0 auto; transition: transform .16s ease; }.audit-disclosure[open] summary > svg { transform: rotate(180deg); }
.state-dot { flex: 0 0 7px; width: 7px; height: 7px; border-radius: 50%; background: var(--color-text-muted); }.state-dot.pass { background: var(--color-success); }.state-dot.fail { background: var(--color-task-danger); }
.summary-main { display: grid; min-width: 0; flex: 1; gap: 3px; }.summary-main strong { overflow: hidden; color: var(--color-text-primary); font: 620 11px/1.4 var(--font-code); text-overflow: ellipsis; white-space: nowrap; }.summary-main small { color: var(--color-text-tertiary); font-size: 9px; }
.audit-log { max-height: 390px; margin: 0; padding: 13px 14px; overflow: auto; border-top: 1px solid var(--color-border-default); background: #070c16; color: #b8c8de; font: 10px/1.62 var(--font-code); white-space: pre-wrap; overflow-wrap: anywhere; }
.audit-empty { display: grid; justify-items: center; gap: 6px; min-height: 260px; place-content: center; color: var(--color-text-tertiary); text-align: center; }.audit-empty > svg { margin-bottom: 4px; color: var(--color-text-muted); }.audit-empty strong { color: var(--color-text-secondary); font-size: 12px; }.audit-empty p { max-width: 380px; margin: 0; font-size: 10px; line-height: 1.6; }
.diff-list { display: grid; gap: 1px; margin-top: 12px; overflow: hidden; border: 1px solid var(--color-border-default); border-radius: 9px; background: var(--color-border-default); }.diff-row { display: grid; grid-template-columns: 48px minmax(0, 1fr) auto; align-items: center; gap: 9px; width: 100%; padding: 8px 11px; border: 0; background: #0a1120; color: inherit; text-align: left; cursor: pointer; transition: background .15s ease; }.diff-row:hover, .diff-row:focus-visible { background: #101b30; outline: none; }.diff-row > svg { color: var(--color-text-muted); }.diff-row:hover > svg { color: var(--color-accent-cyan); }.diff-row code { min-width: 0; color: var(--color-text-secondary); font: 10px/1.45 var(--font-code); overflow-wrap: anywhere; }.diff-state { color: #fbbf24; font-size: 9px; font-weight: 700; }.diff-state.added { color: var(--color-success); }
.violation-box { margin-top: 12px; padding: 11px 12px; border: 1px solid rgb(239 68 68 / 30%); border-radius: 8px; background: rgb(239 68 68 / 7%); color: var(--color-task-danger); font-size: 10px; }.violation-box strong { display: flex; align-items: center; gap: 6px; }.violation-box ul { margin: 7px 0 0; padding-left: 20px; }.secondary-note { margin: 11px 2px 0; color: var(--color-text-tertiary); font-size: 9px; line-height: 1.55; }
.verification-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin-top: 12px; }.verification-card { min-width: 0; padding: 12px; border: 1px solid var(--color-border-default); border-radius: 9px; background: rgb(8 14 25 / 65%); }.verification-card header { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 9px; }.verification-card header > div { display: grid; min-width: 0; gap: 2px; }.verification-card header strong { color: var(--color-text-primary); font: 700 11px/1.4 var(--font-code); }.verification-card header small { color: var(--color-text-tertiary); font-size: 9px; }.verification-icon { display: grid; width: 25px; height: 25px; place-items: center; border-radius: 50%; background: rgb(101 115 138 / 12%); color: var(--color-text-muted); }.verification-icon.pass { background: rgb(34 197 94 / 10%); color: var(--color-success); }.verification-icon.fail { background: rgb(239 68 68 / 10%); color: var(--color-task-danger); }.result-pill { padding: 4px 7px; border-radius: 999px; background: rgb(101 115 138 / 10%); color: var(--color-text-tertiary); font-size: 9px; font-weight: 700; }.result-pill.pass { background: rgb(34 197 94 / 9%); color: var(--color-success); }.result-pill.fail { background: rgb(239 68 68 / 9%); color: var(--color-task-danger); }.verification-card > p { margin: 10px 0 0; color: var(--color-text-secondary); font-size: 10px; }.verification-card > code { display: block; margin-top: 8px; padding: 7px 8px; overflow: hidden; border-radius: 6px; background: #070c16; color: #bae6fd; font: 9px/1.45 var(--font-code); text-overflow: ellipsis; white-space: nowrap; }.verification-meta { margin-top: 7px; color: var(--color-text-tertiary); font: 9px/1.45 var(--font-code); }.inline-output { margin-top: 9px; }.inline-output > summary { color: var(--color-accent-cyan); font-size: 9px; cursor: pointer; }.inline-output .audit-log { max-height: 250px; margin: 8px -12px -12px; }
.judge-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin-top: 12px; }
:global(.diff-preview-dialog) { overflow: hidden; border: 1px solid rgb(130 147 173 / 18%); border-radius: 12px; background: #0b1220; box-shadow: 0 24px 80px rgb(0 0 0 / 55%); }
:global(.diff-preview-dialog .el-dialog__header) { margin: 0; padding: 16px 18px 13px; border-bottom: 1px solid var(--color-border-default); }
:global(.diff-preview-dialog .el-dialog__body) { padding: 0; }
.preview-title { display: flex; align-items: center; gap: 11px; padding-right: 30px; }.preview-title > .diff-state { flex: 0 0 auto; padding: 5px 8px; border-radius: 999px; background: rgb(245 158 11 / 10%); }.preview-title > .diff-state.added { background: rgb(34 197 94 / 10%); }.preview-title > div { display: grid; min-width: 0; gap: 3px; }.preview-title strong { color: var(--color-text-primary); font-size: 13px; }.preview-title code { overflow: hidden; color: var(--color-text-secondary); font: 10px/1.4 var(--font-code); text-overflow: ellipsis; white-space: nowrap; }
.preview-legend { display: flex; align-items: center; gap: 8px; padding: 9px 16px; border-bottom: 1px solid var(--color-border-default); background: rgb(6 11 20 / 60%); color: var(--color-text-tertiary); font-size: 9px; }.preview-legend span { padding: 3px 6px; border-radius: 4px; }.preview-legend .added { background: rgb(34 197 94 / 13%); color: #86efac; }.preview-legend .removed { background: rgb(239 68 68 / 13%); color: #fca5a5; }.preview-legend .hunk { background: rgb(34 211 238 / 11%); color: #67e8f9; }.preview-legend b { margin-left: auto; color: #fbbf24; }
.preview-state { display: grid; min-height: 380px; place-content: center; justify-items: center; gap: 8px; color: var(--color-text-tertiary); text-align: center; }.preview-state strong { color: var(--color-text-secondary); font-size: 12px; }.preview-state p { max-width: 520px; margin: 0; font-size: 10px; }.preview-state.error > svg, .preview-state.error strong { color: var(--color-task-danger); }.spin { animation: spin .8s linear infinite; }
.diff-preview { max-height: min(68vh, 720px); overflow: auto; background: #070c16; }.preview-line { display: grid; grid-template-columns: 52px max-content; min-width: 100%; width: max-content; color: #a8b5c9; font: 10px/1.58 var(--font-code); }.preview-line > span { position: sticky; left: 0; padding: 1px 10px; border-right: 1px solid rgb(130 147 173 / 10%); background: #09101c; color: #526079; text-align: right; user-select: none; }.preview-line > code { min-width: calc(min(92vw, 1040px) - 54px); padding: 1px 12px; color: inherit; font: inherit; white-space: pre; }.preview-line.added { background: rgb(34 197 94 / 13%); color: #bbf7d0; }.preview-line.removed { background: rgb(239 68 68 / 13%); color: #fecaca; }.preview-line.hunk { background: rgb(34 211 238 / 9%); color: #67e8f9; }.preview-line.header { color: #7d8da8; }.preview-line.added > span { background: #0d261e; color: #4ade80; }.preview-line.removed > span { background: #2a1118; color: #fb7185; }.preview-line.hunk > span { background: #0b202a; color: #22d3ee; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 1080px) { .verification-grid, .judge-grid { grid-template-columns: 1fr; } }
@media (max-width: 720px) { .audit-header { display: block; }.audit-tabs { margin-top: 5px; }.source-note { grid-template-columns: auto minmax(0, 1fr); }.source-note > b { grid-column: 2; }.verification-grid { grid-template-columns: 1fr; } }
</style>
