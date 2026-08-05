<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import { api } from '@/api/client'
import type { LoopVerifierSpec, TaskDesignHistory } from '@/types/domain'

const route = useRoute()
const router = useRouter()
const id = computed(() => route.params.id as string)
const record = ref<TaskDesignHistory>()
const loading = ref(false)
const error = ref('')

const visibleMessages = computed(() => (record.value?.designerSession?.messages ?? []).filter((message) => !(
  message.role === 'SYSTEM'
  && message.deliveryState === 'PENDING_HANDOFF'
  && !message.content.startsWith('SYSTEM_ERROR')
)))
const rawSpec = computed(() => record.value ? JSON.stringify(record.value.draft.spec, null, 2) : '')

async function load() {
  loading.value = true
  error.value = ''
  try { record.value = await api.getTaskDesignHistory(id.value) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '无法读取历史设计记录' }
  finally { loading.value = false }
}

function verifierSummary(verifier: LoopVerifierSpec) {
  if (verifier.type === 'PROCESS') return verifier.command?.join(' ') || '未配置命令'
  if (verifier.type === 'FILE_EXISTS') return `文件必须存在：${verifier.path ?? '未配置路径'}`
  if (verifier.type === 'FILE_NOT_EXISTS') return `文件必须不存在：${verifier.path ?? '未配置路径'}`
  if (verifier.type === 'GIT_DIFF') return `差异范围：${verifier.allowedPaths?.join(', ') || '未限制路径'}`
  return verifier.type
}

function formatDate(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

watch(id, load, { immediate: true })
</script>

<template>
  <PageHeader eyebrow="任务 / 历史设计" :title="record?.taskTitle ?? '设计与 LoopSpec 历史'" :title-tooltip="record?.draft.spec.goal">
    <template #actions>
      <el-button plain @click="router.push(`/tasks/${id}`)"><Icon icon="lucide:activity" />任务检视</el-button>
      <el-button plain @click="router.push('/tasks')"><Icon icon="lucide:list" />全部任务</el-button>
    </template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <section v-if="loading" class="card card-pad" aria-live="polite"><div class="skeleton-block" style="height: 120px" /></section>
    <section v-else-if="error" class="card empty-state"><div><Icon icon="lucide:file-warning" width="30" /><strong>无法读取历史设计</strong><p>{{ error }}</p></div></section>
    <template v-else-if="record">
      <section class="history-overview card card-pad">
        <div><p class="eyebrow">只读历史快照</p><h2>{{ record.projectName }}</h2></div>
        <div class="history-meta">
          <span><b>LoopSpec</b>{{ record.draft.status }}</span>
          <span><b>Designer</b>{{ record.designerSession?.state ?? '无关联会话' }}</span>
          <span><b>更新于</b>{{ formatDate(record.draft.updatedAt) }}</span>
        </div>
      </section>

      <section class="history-grid">
        <article class="card history-conversation">
          <header class="history-card-header"><div><p class="eyebrow">DESIGN CONVERSATION</p><h2>历史设计对话</h2><p>共 {{ visibleMessages.length }} 条已持久化记录</p></div><Icon icon="lucide:messages-square" width="22" /></header>
          <div v-if="visibleMessages.length" class="message-list">
            <article v-for="message in visibleMessages" :key="message.id" :class="['history-message', `message-${message.role.toLowerCase()}`]">
              <header><span><Icon :icon="message.role === 'ASSISTANT' ? 'lucide:sparkles' : message.role === 'USER' ? 'lucide:user-round' : 'lucide:info'" />{{ message.role === 'ASSISTANT' ? 'Designer' : message.role === 'USER' ? '你' : '系统' }}</span><time>{{ formatDate(message.createdAt) }}</time></header>
              <MarkdownDocument v-if="message.role === 'ASSISTANT'" :content="message.content" collapsible />
              <p v-else>{{ message.content }}</p>
            </article>
          </div>
          <div v-else class="history-empty"><Icon icon="lucide:message-square-off" /><p>该任务保留了 LoopSpec，但没有可关联的历史 Designer 对话。</p></div>
        </article>

        <article class="card history-spec">
          <header class="history-card-header"><div><p class="eyebrow">CONFIRMED CONTRACT</p><h2>LoopSpec {{ record.draft.spec.schemaVersion }}</h2><p>{{ record.draft.id }}</p></div><Icon icon="lucide:file-lock-2" width="22" /></header>
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
                <ul v-if="stage.verifiers.length" class="verifier-list"><li v-for="(verifier, verifierIndex) in stage.verifiers" :key="verifierIndex"><code>{{ verifier.type }}</code><span>{{ verifierSummary(verifier) }}</span></li></ul>
                <p v-else class="tiny muted">该阶段未配置确定性验收器。</p>
              </article>
            </section>
            <details class="raw-spec"><summary>查看完整 LoopSpec JSON</summary><pre>{{ rawSpec }}</pre></details>
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
.history-meta { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 9px; }
.history-meta span { min-width: 120px; padding: 9px 11px; border: 1px solid var(--color-border-default); border-radius: 9px; color: var(--color-text-primary); background: rgb(2 6 23 / 28%); font: 10px/1.4 var(--font-code); }
.history-meta b { display: block; margin-bottom: 3px; color: var(--color-text-muted); font-size: 8px; letter-spacing: .08em; text-transform: uppercase; }
.history-grid { display: grid; align-items: start; grid-template-columns: minmax(420px, .92fr) minmax(500px, 1.08fr); gap: 16px; margin-top: 16px; }
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
.message-user { margin-left: 28px; border-color: rgb(34 211 238 / 24%); background: rgb(34 211 238 / 5%); }
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
