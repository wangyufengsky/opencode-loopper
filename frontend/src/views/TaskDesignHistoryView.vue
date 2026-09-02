<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import DesignerDiscussionHistory from '@/components/DesignerDiscussionHistory.vue'
import { frozenDesignTimeline } from '@/utils/frozenDesignTimeline'
import { api } from '@/api/client'
import type { LoopVerifierSpec, TaskDesignHistory } from '@/types/domain'
import { displayLabel, statusLabel, userFacingError } from '@/utils/displayLabels'

const route = useRoute()
const router = useRouter()
const id = computed(() => route.params.id as string)
const record = ref<TaskDesignHistory>()
const loading = ref(false)
const error = ref('')
const attachmentPreviews = ref<Record<string, string>>({})
const attachmentPreviewBusy = ref('')

const timeline = computed(() => frozenDesignTimeline(record.value?.designerSession?.messages ?? [],
  record.value?.designerSession?.answeredQuestions ?? []))
const actorLabels = { USER: '你', ROUTER: '需求分析师', DECOMPOSER: '任务规划师', DESIGNER: '设计师', COMPILER: '规范工程师', REVIEWER: '评审员', VALIDATOR: '验收工程师', SYSTEM: '系统' } as const
const actorIcons = { USER: 'lucide:user-round', ROUTER: 'lucide:route', DECOMPOSER: 'lucide:split', DESIGNER: 'lucide:sparkles', COMPILER: 'lucide:braces', REVIEWER: 'lucide:file-search', VALIDATOR: 'lucide:badge-check', SYSTEM: 'lucide:info' } as const

async function load() {
  loading.value = true
  error.value = ''
  try { record.value = await api.getTaskDesignHistory(id.value) }
  catch (cause) { error.value = userFacingError(cause, '无法读取历史设计记录') }
  finally { loading.value = false }
}

function verifierSummary(verifier: LoopVerifierSpec) {
  if (verifier.type === 'PROCESS') return verifier.command?.join(' ') || '未配置命令'
  if (verifier.type === 'FILE_EXISTS') return `文件必须存在：${verifier.path ?? '未配置路径'}`
  if (verifier.type === 'FILE_NOT_EXISTS') return `文件必须不存在：${verifier.path ?? '未配置路径'}`
  if (verifier.type === 'GIT_DIFF') return `差异范围：${verifier.allowedPaths?.join(', ') || '未限制路径'}`
  return displayLabel(verifier.type)
}

function formatDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

function formatFileSize(bytes: number) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.ceil(bytes / 1024))} KiB` : `${(bytes / 1024 / 1024).toFixed(1)} MiB`
}

async function loadAttachmentPreview(attachmentId: string) {
  if (attachmentPreviews.value[attachmentId] !== undefined) return
  attachmentPreviewBusy.value = attachmentId
  try {
    const preview = await api.getTaskDesignAttachmentPreview(id.value, attachmentId)
    attachmentPreviews.value = { ...attachmentPreviews.value,
      [attachmentId]: preview.text ?? '该文件使用经过验证的原始内容预览。' }
  } catch (cause) { error.value = userFacingError(cause, '无法读取冻结附件预览') }
  finally { attachmentPreviewBusy.value = '' }
}

watch(id, load, { immediate: true })
</script>

<template>
  <PageHeader eyebrow="任务设计" :title="record?.taskTitle ?? '历史设计'" :title-tooltip="record?.draft.spec.goal">
    <template #actions>
      <el-button plain @click="router.push(`/tasks/${id}`)"><Icon icon="lucide:activity" />任务检视</el-button>
      <el-button plain @click="router.push('/tasks')"><Icon icon="lucide:list" />全部任务</el-button>
    </template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <section v-if="loading" class="card card-pad" aria-live="polite"><div class="skeleton-block" style="height: 120px" /></section>
    <section v-else-if="error" class="card empty-state"><div><Icon icon="lucide:file-warning" width="30" /><strong>无法读取历史设计</strong><p>{{ userFacingError(error, '历史设计读取失败') }}</p></div></section>
    <template v-else-if="record">
      <section class="history-overview card card-pad">
        <div><p class="eyebrow">只读历史快照</p><h2>{{ record.projectName }}</h2></div>
        <div class="history-meta">
          <span><b>执行规范</b>{{ statusLabel(record.draft.status) }}</span>
          <span><b>设计会话</b>{{ record.designerSession?.state ? statusLabel(record.designerSession.state) : '无关联会话' }}</span>
          <span><b>更新于</b>{{ formatDate(record.draft.updatedAt) }}</span>
        </div>
      </section>
      <el-alert
        v-if="record.inheritedConversation"
        class="inherited-conversation"
        type="info"
        :closable="false"
        show-icon
        title="该重做/恢复任务沿用父任务冻结时的设计对话；下方执行规范仍是当前任务自己的冻结副本。"
      />

      <section v-if="record.frozenAttachments?.length" class="card frozen-attachments card-pad" aria-label="冻结附件清单">
        <div class="card-header"><div><p class="eyebrow">冻结附件清单</p><h2 class="card-title">设计与开发上下文</h2></div><span class="mono tiny">{{ record.frozenAttachments.length }} 个不可变文件</span></div>
        <div class="frozen-attachment-grid">
          <article v-for="attachment in record.frozenAttachments" :key="attachment.id">
            <Icon icon="lucide:file-lock-2" /><span><b>{{ attachment.filename }}</b><small>{{ formatFileSize(attachment.sizeBytes) }} · {{ attachment.scopeKey === 'REQUIREMENT' ? '整体需求' : attachment.scopeKey }}</small><code>SHA-256 {{ attachment.sha256 }}</code>
              <span class="frozen-preview-actions"><el-button text size="small" :loading="attachmentPreviewBusy === attachment.id" @click="loadAttachmentPreview(attachment.id)">安全预览</el-button><a v-if="attachment.mediaType.startsWith('image/') || attachment.mediaType === 'application/pdf'" :href="api.taskDesignAttachmentContentUrl(id, attachment.id)" target="_blank" rel="noopener">打开原文件</a></span>
              <pre v-if="attachmentPreviews[attachment.id]">{{ attachmentPreviews[attachment.id] }}</pre>
            </span>
          </article>
        </div>
      </section>

      <section v-if="record.requirement || record.decomposition || record.workPackages?.length" class="card package-history card-pad">
        <div class="card-header"><div><p class="eyebrow">已确认设计</p><h2 class="card-title">需求与工作包</h2></div><span v-if="record.requirement" class="mono tiny">第 {{ record.requirement.revision }} 版 · 模型调用 {{ record.requirement.modelCallsUsed }}/{{ record.requirement.maxModelCalls }}</span></div>
        <p v-if="record.requirement" class="frozen-requirement">{{ record.requirement.requirementText }}</p>
        <div v-if="record.workPackages?.length" class="package-history-grid">
          <article v-for="item in record.workPackages ?? []" :key="item.id">
            <header><b>工作包 {{ item.ordinal + 1 }}</b><span>{{ statusLabel(item.state) }}</span></header>
            <h3>{{ item.title }}</h3><p>{{ item.objective }}</p>
            <small v-if="item.compilerSummary">编译摘要：{{ item.compilerSummary }}</small>
            <small v-if="item.handoffSummary">交接摘要：{{ item.handoffSummary }}</small>
          </article>
        </div>
      </section>

      <section class="history-grid">
        <article class="card history-conversation">
          <header class="history-card-header"><div><p class="eyebrow">设计记录</p><h2>历史设计对话</h2><p>{{ timeline.length }} 条记录</p></div><Icon icon="lucide:messages-square" width="22" /></header>
          <div v-if="timeline.length" class="message-list">
            <template v-for="item in timeline" :key="item.key">
            <DesignerDiscussionHistory v-if="item.kind === 'discussion'" :entries="item.entries" />
            <article v-for="message in item.kind === 'message' ? [item.message] : []" :key="message.id" :class="['history-message', `message-${message.actor.toLowerCase()}`]">
              <header><span><Icon :icon="actorIcons[message.actor]" />{{ actorLabels[message.actor] }}</span><time>{{ formatDate(message.createdAt) }}</time></header>
              <MarkdownDocument v-if="message.actor === 'DESIGNER'" :content="message.content" collapsible />
              <p v-else>{{ message.actor === 'SYSTEM' ? userFacingError(message.content) : message.content }}</p>
              <div v-if="message.attachments?.length" class="history-message-attachments">
                <span v-for="attachment in message.attachments" :key="attachment.id"><Icon icon="lucide:paperclip" /><b>{{ attachment.filename }}</b><small>{{ formatFileSize(attachment.sizeBytes) }} · {{ attachment.scopeKey === 'REQUIREMENT' ? '整体需求' : attachment.scopeKey }} · {{ attachment.state }}</small></span>
              </div>
            </article>
            </template>
          </div>
          <div v-else class="history-empty"><Icon icon="lucide:message-square-off" /><p>暂无历史设计对话。</p></div>
        </article>

        <article class="card history-spec">
          <header class="history-card-header"><div><p class="eyebrow">已确认规范</p><h2>执行规范 {{ record.draft.spec.schemaVersion }}</h2></div><Icon icon="lucide:file-lock-2" width="22" /></header>
          <div class="spec-content">
            <section class="spec-section"><span>任务目标</span><p>{{ record.draft.spec.goal }}</p></section>
            <section class="spec-section"><span>执行上下文</span><p>{{ record.draft.spec.context || '未补充执行上下文' }}</p></section>
            <section class="spec-stages">
              <article v-for="(stage, stageIndex) in record.draft.spec.stages" :key="stageIndex" class="history-stage">
                <header><i>{{ stageIndex + 1 }}</i><div><span>阶段 {{ stageIndex + 1 }}</span><h3>{{ stage.objective }}</h3></div></header>
                <div class="stage-facts">
                  <div><b>建议修改</b><p>{{ stage.allowedPaths.join('、') || '未限定' }}</p></div>
                  <div><b>建议避让</b><p>{{ stage.forbiddenPaths.join('、') || '无' }}</p></div>
                  <div><b>交付物</b><p>{{ stage.deliverables.join('、') || '未填写' }}</p></div>
                </div>
                <ul v-if="stage.verifiers.length" class="verifier-list"><li v-for="(verifier, verifierIndex) in stage.verifiers" :key="verifierIndex"><code>{{ displayLabel(verifier.type) }}</code><span>{{ verifierSummary(verifier) }}</span></li></ul>
                <p v-else class="tiny muted">该阶段未配置确定性验收器。</p>
              </article>
            </section>
          </div>
        </article>
      </section>
    </template>
  </main>
</template>

<style scoped>
.history-overview { display: flex; align-items: center; justify-content: space-between; gap: 24px; }
.history-overview h2 { margin: 5px 0 7px; font-size: 17px; }
.history-overview p:not(.eyebrow) { max-width: 680px; margin: 0; color: var(--color-text-secondary); font-size: 11px; line-height: 1.6; }
.inherited-conversation { margin-top: 16px; }
.frozen-attachments { margin-top: 16px; }
.frozen-attachment-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 10px; margin-top: 12px; }
.frozen-attachment-grid article { display: flex; align-items: flex-start; gap: 10px; padding: 12px; border: 1px solid rgb(34 211 238 / 24%); border-radius: 10px; background: rgb(34 211 238 / 5%); }
.frozen-attachment-grid article > span { display: grid; min-width: 0; gap: 3px; }
.frozen-attachment-grid small, .frozen-attachment-grid code { color: var(--color-text-muted); font: 9px/1.45 var(--font-code); overflow-wrap: anywhere; }
.frozen-preview-actions { display: flex; align-items: center; gap: 8px; }
.frozen-preview-actions a { color: var(--color-accent-cyan); font-size: 9px; text-decoration: none; }
.frozen-attachment-grid pre { max-height: 220px; margin: 4px 0 0; padding: 9px; overflow: auto; border-radius: 7px; background: rgb(2 6 23 / 70%); color: var(--color-text-secondary); font: 10px/1.55 var(--font-code); white-space: pre-wrap; }
.history-meta { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 9px; }
.history-meta span { min-width: 120px; padding: 9px 11px; border: 1px solid var(--color-border-default); border-radius: 9px; color: var(--color-text-primary); background: rgb(2 6 23 / 28%); font: 10px/1.4 var(--font-code); }
.history-meta b { display: block; margin-bottom: 3px; color: var(--color-text-muted); font-size: 8px; letter-spacing: .08em; text-transform: uppercase; }
.history-grid { display: grid; align-items: start; grid-template-columns: minmax(420px, .92fr) minmax(500px, 1.08fr); gap: 16px; margin-top: 16px; }
.package-history { margin-top: 16px; }
.frozen-requirement { max-height: 150px; margin: 12px 0 0; padding: 12px; overflow: auto; border: 1px solid var(--color-border-default); border-radius: 9px; color: var(--color-text-secondary); background: rgb(2 6 23 / 30%); font-size: 10px; line-height: 1.65; white-space: pre-wrap; }
.package-history-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 10px; margin-top: 12px; }
.package-history-grid article { padding: 12px; border: 1px solid rgb(99 102 241 / 26%); border-radius: 10px; background: rgb(49 46 129 / 7%); }
.package-history-grid header { display: flex; justify-content: space-between; color: #a5b4fc; font: 8px/1.3 var(--font-code); }
.package-history-grid h3 { margin: 8px 0 5px; font-size: 11px; }
.package-history-grid p, .package-history-grid small { display: block; margin: 0; color: var(--color-text-secondary); font-size: 9px; line-height: 1.55; }
.package-history-grid small { margin-top: 6px; color: var(--color-text-muted); }
.history-conversation, .history-spec { min-width: 0; overflow: hidden; }
.history-card-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 20px; border-bottom: 1px solid var(--color-border-default); }
.history-card-header h2 { margin: 4px 0; font-size: 15px; }
.history-card-header p:not(.eyebrow) { margin: 0; color: var(--color-text-muted); font: 9px/1.5 var(--font-code); }
.history-card-header > svg { color: var(--color-accent-cyan); }
.message-list, .spec-content { padding: 6px 20px 20px; }
.history-message { margin-top: 14px; padding: 14px; border: 1px solid var(--color-border-default); border-radius: 11px; background: rgb(7 11 20 / 45%); }
.history-message > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; color: var(--color-text-muted); font: 9px/1.4 var(--font-code); }
.history-message > header span { display: inline-flex; align-items: center; gap: 7px; color: var(--color-text-primary); font-weight: 700; }
.history-message > p { margin: 0; color: var(--color-text-primary); font-size: 12px; line-height: 1.65; white-space: pre-wrap; }
.history-message-attachments { display: grid; gap: 6px; margin-top: 10px; }
.history-message-attachments span { display: flex; align-items: center; gap: 7px; padding: 7px 9px; border: 1px solid var(--color-border-default); border-radius: 8px; color: var(--color-text-secondary); font-size: 9px; }
.history-message-attachments small { color: var(--color-text-muted); }
.message-user { margin-left: 28px; border-color: rgb(34 211 238 / 24%); background: rgb(34 211 238 / 5%); }
.message-decomposer { border-color: rgb(99 102 241 / 38%); background: rgb(99 102 241 / 7%); }
.message-designer { border-color: rgb(139 92 246 / 28%); background: rgb(139 92 246 / 6%); }
.message-compiler { border-color: rgb(34 211 238 / 28%); background: rgb(34 211 238 / 5%); }
.message-validator { border-color: rgb(34 197 94 / 28%); background: rgb(34 197 94 / 5%); }
.message-assistant { border-color: rgb(139 92 246 / 24%); background: rgb(139 92 246 / 5%); }
.history-empty { display: grid; place-items: center; min-height: 240px; padding: 28px; color: var(--color-text-muted); text-align: center; }
.spec-section { margin-top: 14px; padding: 14px; border: 1px solid var(--color-border-default); border-radius: 11px; background: rgb(2 6 23 / 24%); }
.spec-section > span { color: var(--color-accent-cyan); font: 9px/1.4 var(--font-code); letter-spacing: .08em; }
.spec-section p { margin: 7px 0 0; color: var(--color-text-primary); font-size: 12px; line-height: 1.65; white-space: pre-wrap; }
.history-stage { margin-top: 14px; overflow: hidden; border: 1px solid var(--color-border-default); border-radius: 12px; background: rgb(8 13 24 / 60%); }
.history-stage > header { display: flex; align-items: center; gap: 10px; padding: 13px 14px; border-bottom: 1px solid var(--color-border-default); }
.history-stage i { display: grid; flex: 0 0 auto; place-items: center; width: 28px; height: 28px; border-radius: 8px; color: var(--color-accent-cyan); background: rgb(34 211 238 / 8%); font: normal 700 11px var(--font-code); }
.history-stage header span { color: var(--color-text-muted); font: 8px/1.3 var(--font-code); }
.history-stage h3 { margin: 2px 0 0; font-size: 12px; }
.stage-facts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1px; background: var(--color-border-default); }
.stage-facts div { min-width: 0; padding: 10px 11px; background: rgb(9 15 27 / 96%); }
.stage-facts b { color: var(--color-text-muted); font-size: 8px; }
.stage-facts p { margin: 4px 0 0; overflow-wrap: anywhere; color: var(--color-text-secondary); font-size: 9px; line-height: 1.5; }
.verifier-list { display: grid; gap: 7px; margin: 0; padding: 12px 14px; list-style: none; }
.verifier-list li { display: flex; align-items: flex-start; gap: 8px; color: var(--color-text-secondary); font-size: 9px; line-height: 1.5; }
.verifier-list code { flex: 0 0 auto; color: #c4b5fd; }
.raw-spec { margin-top: 14px; border: 1px solid var(--color-border-default); border-radius: 10px; }
.raw-spec summary { padding: 12px 14px; color: var(--color-text-secondary); cursor: pointer; font: 10px/1.4 var(--font-code); }
.raw-spec pre { max-height: 480px; margin: 0; padding: 14px; overflow: auto; border-top: 1px solid var(--color-border-default); color: var(--color-text-secondary); background: rgb(2 6 23 / 62%); font: 10px/1.55 var(--font-code); white-space: pre-wrap; }
@media (max-width: 1120px) { .history-grid { grid-template-columns: 1fr; } }
@media (max-width: 760px) { .history-overview { align-items: flex-start; flex-direction: column; }.history-meta { justify-content: flex-start; }.stage-facts { grid-template-columns: 1fr; } }
</style>
