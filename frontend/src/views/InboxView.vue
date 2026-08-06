<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Icon } from '@iconify/vue'
import PageHeader from '@/components/PageHeader.vue'
import PendingQuestionCard from '@/components/PendingQuestionCard.vue'
import { api } from '@/api/client'
import type { Interaction, InteractionAction, QuestionInteraction } from '@/types/domain'

const interactions = ref<Interaction[]>([])
const loading = ref(true)
const error = ref('')
const submittingId = ref('')
let timer: ReturnType<typeof setInterval> | undefined

const pendingCount = computed(() => interactions.value.filter((item) => item.state === 'PENDING').length)

async function refresh() {
  try {
    interactions.value = await api.getInteractions()
    error.value = ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '待处理中心暂时无法连接服务端'
  } finally {
    loading.value = false
  }
}

async function resolve(item: Interaction, action: InteractionAction, answers?: string[][]) {
  submittingId.value = item.id
  try {
    await api.resolveInteraction(item.id, { action, version: item.version, ...(answers ? { answers } : {}) })
    await refresh()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '交互提交失败，请刷新后重试'
    await refresh()
  } finally {
    submittingId.value = ''
  }
}

function pendingQuestion(item: QuestionInteraction) {
  return { id: item.externalRequestId, questions: item.payload.questions }
}

onMounted(async () => {
  await refresh()
  timer = setInterval(refresh, 1500)
})
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <PageHeader eyebrow="Work Queue / Interaction" title="待处理中心">
    <template #actions>
      <span class="inbox-count"><b>{{ pendingCount }}</b> 项等待处理</span>
      <el-button :loading="loading" @click="refresh"><Icon icon="lucide:refresh-cw" />刷新</el-button>
    </template>
  </PageHeader>
  <main id="main-content" class="content" tabindex="-1">
    <section v-if="error" class="error-panel error-panel-session" role="status">
      <Icon class="error-panel-icon" icon="lucide:triangle-alert" />
      <div><h3>待处理状态未同步</h3><p>{{ error }}</p></div>
    </section>

    <section v-if="loading && !interactions.length" class="card empty-state">
      <div><Icon icon="lucide:loader-circle" class="spin" width="30" /><strong>正在读取服务端待处理项</strong><p>Question、Permission 与版本状态均来自 OpenCode 和本地持久化记录。</p></div>
    </section>

    <section v-else-if="!interactions.length" class="card empty-state">
      <div><Icon icon="lucide:inbox" width="30" /><strong>目前没有待处理项</strong><p>新的 Question 或 Permission 到达后会自动显示在这里。</p></div>
    </section>

    <section v-else class="inbox-list" aria-live="polite">
      <article v-for="item in interactions" :key="item.id" class="card inbox-item" :class="{ 'hard-denied': item.state === 'HARD_DENIED' }">
        <header>
          <div class="inbox-kind">
            <Icon :icon="item.kind === 'QUESTION' ? 'lucide:message-square-more' : 'lucide:shield-question'" />
            <div><p>{{ item.kind === 'QUESTION' ? 'QUESTION' : 'PERMISSION' }}</p><h2>{{ item.kind === 'QUESTION' ? 'OpenCode 需要你的回答' : (item.payload.title || item.payload.permission || '权限请求') }}</h2></div>
          </div>
          <div class="inbox-meta"><span class="mono">v{{ item.version }}</span><span>{{ item.taskId ? `Task ${item.taskId.slice(0, 8)}` : 'Designer' }}</span></div>
        </header>

        <PendingQuestionCard v-if="item.kind === 'QUESTION' && item.state === 'PENDING'" :pending="pendingQuestion(item)" :submitting="submittingId === item.id" @submit="(answers) => resolve(item, 'REPLY', answers)" @reject="resolve(item, 'REJECT')" />

        <div v-else-if="item.kind === 'PERMISSION'" class="permission-body">
          <dl><div><dt>权限类型</dt><dd class="mono">{{ item.payload.permission }}</dd></div><div><dt>匹配范围</dt><dd class="mono">{{ item.payload.patterns.join(' · ') || '未提供' }}</dd></div></dl>
          <p v-if="item.payload.hardDenied" class="hard-deny-note"><Icon icon="lucide:shield-x" />{{ item.payload.hardDenyReason || '该请求已被不可覆盖的本地安全策略拒绝。' }}</p>
          <footer v-if="item.state === 'PENDING'">
            <el-button :disabled="submittingId === item.id" @click="resolve(item, 'REJECT')">拒绝</el-button>
            <el-button :disabled="submittingId === item.id" @click="resolve(item, 'ONCE')">仅本次允许</el-button>
            <el-button type="primary" :loading="submittingId === item.id" @click="resolve(item, 'SESSION')">本 Session 允许</el-button>
          </footer>
        </div>
      </article>
    </section>
  </main>
</template>

<style scoped>
.inbox-count { display: inline-flex; align-items: center; gap: 6px; color: var(--color-text-secondary); font-size: 12px; }.inbox-count b { color: var(--color-accent-cyan); font-family: var(--font-code); }.inbox-list { display: grid; gap: 14px; }.inbox-item { padding: 16px; background: linear-gradient(145deg, rgb(18 28 48 / 96%), rgb(13 20 36 / 92%)); }.inbox-item > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding-bottom: 13px; border-bottom: 1px solid var(--color-border-default); }.inbox-kind { display: flex; gap: 11px; }.inbox-kind > svg { margin-top: 2px; color: var(--color-accent-cyan); }.inbox-kind p { margin: 0 0 4px; color: var(--color-accent-cyan); font: 700 9px/1 var(--font-code); letter-spacing: .1em; }.inbox-kind h2 { margin: 0; font-size: 14px; }.inbox-meta { display: flex; gap: 8px; color: var(--color-text-muted); font-size: 10px; }.permission-body { padding-top: 14px; }.permission-body dl { display: grid; gap: 8px; margin: 0; }.permission-body dl div { display: grid; grid-template-columns: 90px 1fr; gap: 12px; }.permission-body dt { color: var(--color-text-muted); font-size: 11px; }.permission-body dd { margin: 0; color: var(--color-text-secondary); font-size: 11px; overflow-wrap: anywhere; }.permission-body footer { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }.hard-denied { border-color: rgb(239 68 68 / 48%); }.hard-deny-note { display: flex; align-items: center; gap: 8px; margin: 14px 0 0; padding: 11px; border: 1px solid rgb(239 68 68 / 35%); border-radius: var(--radius-control); color: #fca5a5; background: rgb(239 68 68 / 8%); font-size: 11px; }.spin { animation: spin 1s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
</style>
