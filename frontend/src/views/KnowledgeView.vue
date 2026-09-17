<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Icon } from '@iconify/vue'
import { api } from '@/api/client'
import { knowledgeApi } from '@/api/knowledge'
import { useKnowledgeStore } from '@/stores/knowledgeStore'
import { knowledgeStateLabel, knowledgeToolLabel } from '@/utils/displayLabels'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import KnowledgeSourcesPanel from '@/components/knowledge/KnowledgeSourcesPanel.vue'
import KnowledgeEvidence from '@/components/knowledge/KnowledgeEvidence.vue'
import type { AvailableModel, KnowledgeConversation, KnowledgeSource, KnowledgeContent, KnowledgeCitation, Project } from '@/types/domain'
import '@/styles/knowledge.css'
const route = useRoute(), router = useRouter(), store = useKnowledgeStore()
const projects = ref<Project[]>([]), models = ref<AvailableModel[]>([]), project = ref(''), model = ref(''), mode = ref('managed')
const modelError = ref(''), refreshingModels = ref(false), modelChosen = ref(false)
const sources = ref<KnowledgeSource[]>([]), selected = ref<string[]>([]), sourceCursor = ref<string | null>(null)
const history = ref<KnowledgeConversation[]>([]), historyCursor = ref<string | null>(null), historyOpen = ref(false)
const text = ref(''), pageError = ref(''), initialLoading = ref(true), newId = ref(crypto.randomUUID())
const left = ref(false), right = ref(false), width = ref(window.innerWidth), lastPanel = ref<'left' | 'right'>('left')
const sourceRoot = ref<HTMLElement>(), citationRoot = ref<HTMLElement>(), timeline = ref<HTMLElement>(), composer = ref<HTMLTextAreaElement>()
const citation = ref<{ citation: KnowledgeCitation; body: KnowledgeContent }>(), citationError = ref(''), citationLoading = ref(false)
const referenceList = ref<KnowledgeCitation[]>([])
const returnFocus: Record<'left' | 'right', HTMLElement | null> = { left: null, right: null }
let citationSequence = 0, dataSequence = 0, modelSequence = 0, timer: ReturnType<typeof setInterval> | undefined
const activeProject = computed(() => store.conversation?.projectId || project.value)
const activeSources = computed(() => store.conversation?.sources || sources.value)
const modelAvailable = computed(() => models.value.some(item => item.id === model.value))
const modelNotice = computed(() => modelError.value || (!store.conversation && model.value && !modelAvailable.value ? '所选模型不在可用列表中，请重新读取或选择其他模型' : ''))
const canSend = computed(() => text.value.trim() && activeProject.value && (store.conversation || (selected.value.length && modelAvailable.value && !modelError.value)) && !refreshingModels.value && !store.sending && !store.active && mode.value === 'managed')
const usage = computed(() => store.conversation?.usage || [...store.messages].reverse().find(message => message.inputTokens !== null || message.outputTokens !== null))
const overlay = computed(() => width.value < 1100)
function notice(error: unknown) { return error instanceof Error && /[\u4e00-\u9fff]/.test(error.message) ? error.message : '加载失败，请重试' }
async function refreshModels() {
  const ticket = ++modelSequence; refreshingModels.value = true
  try {
    const [settings, available] = await Promise.all([api.getSettings(), api.getSettingsModels()])
    if (ticket !== modelSequence) return
    const provider = settings.openCode.provider.trim(), name = settings.openCode.model.trim()
    models.value = available; mode.value = settings.openCode.mode; modelError.value = ''
    if (!modelChosen.value) model.value = provider && name ? `${provider}/${name}` : ''
  } catch (error) { if (ticket === modelSequence) modelError.value = `无法读取模型配置：${notice(error)}` }
  finally { if (ticket === modelSequence) refreshingModels.value = false }
}
async function reload() {
  if (refreshingModels.value || store.sending) return
  pageError.value = ''; store.error = ''
  await refreshModels()
  const id = typeof route.params.conversationId === 'string' ? route.params.conversationId : ''
  if (id) { await store.load(id); if (store.conversation) project.value = store.conversation.projectId }
  await Promise.all([loadSources(), loadHistory()])
}
async function loadSources(more = false) {
  if (!activeProject.value) return
  const current = activeProject.value, ticket = dataSequence
  try { const page = await knowledgeApi.sources(current, more ? sourceCursor.value || '' : ''); if (current !== activeProject.value || ticket !== dataSequence) return
    sources.value = more ? [...sources.value, ...page.items] : page.items; sourceCursor.value = page.nextCursor ?? null
    if (!store.conversation) selected.value = selected.value.filter(id => sources.value.some(s => s.id === id && s.state === 'READY'))
  } catch (error) { if (ticket === dataSequence) pageError.value = notice(error) }
}
async function loadHistory(more = false) {
  if (!activeProject.value) return
  const current = activeProject.value, ticket = dataSequence
  try { const page = await knowledgeApi.history(current, more ? historyCursor.value || '' : ''); if (current === activeProject.value && ticket === dataSequence) { history.value = more ? [...history.value, ...page.items] : page.items; historyCursor.value = page.nextCursor ?? null } }
  catch (error) { if (ticket === dataSequence) pageError.value = notice(error) }
}
async function changedProject() {
  ++dataSequence; sources.value = []; selected.value = []; history.value = []; sourceCursor.value = null; historyCursor.value = null; pageError.value = ''
  await Promise.all([loadSources(), loadHistory()]); if (!store.conversation) selected.value = sources.value.filter(s => s.state === 'READY').map(s => s.id)
}
async function loadRoute() {
  const id = typeof route.params.conversationId === 'string' ? route.params.conversationId : ''
  citationSequence++; citation.value = undefined; right.value = false; historyOpen.value = false; text.value = ''
  if (id) { await store.load(id); if (store.conversation) project.value = store.conversation.projectId; text.value = store.pendingText }
  else { store.reset(); newId.value = crypto.randomUUID() }
  await changedProject()
}
watch(() => route.params.conversationId, () => { if (!initialLoading.value) void loadRoute() })
async function fresh() { if (store.sending) return; await router.push('/knowledge'); if (!store.conversation && !route.params.conversationId) { newId.value = crypto.randomUUID(); text.value = ''; await changedProject() } }
async function send() {
  if (!canSend.value) return
  const originalRoute = route.fullPath; const question = text.value.trim(); const success = await store.send(question, { id: newId.value, projectId: project.value, model: model.value, sourceIds: [...selected.value], title: question.slice(0, 80) })
  if (route.fullPath !== originalRoute) return
  if (store.conversation && route.params.conversationId !== store.conversation.id) await router.replace(`/knowledge/${store.conversation.id}`)
  if (success) text.value = ''; else text.value = question
  await nextTick(); if (timeline.value) timeline.value.scrollTop = timeline.value.scrollHeight
  void loadHistory()
}
async function openPanel(side: 'left' | 'right', trigger?: HTMLElement) {
  if (!(side === 'left' ? left.value : right.value)) returnFocus[side] = trigger || (document.activeElement instanceof HTMLElement ? document.activeElement : null)
  lastPanel.value = side
  if (side === 'left') { left.value = true; if (width.value < 1600) right.value = false }
  else { right.value = true; if (width.value < 1600) left.value = false }
  await nextTick(); (side === 'left' ? sourceRoot.value : citationRoot.value)?.querySelector<HTMLElement>('button')?.focus()
}
async function closePanel(side: 'left' | 'right') { if (side === 'left') left.value = false; else right.value = false; await nextTick(); (returnFocus[side]?.getClientRects().length ? returnFocus[side] : composer.value)?.focus() }
function resize() { width.value = window.innerWidth; if (width.value < 1600 && left.value && right.value) { if (lastPanel.value === 'left') right.value = false; else left.value = false } }
function keyboard(event: KeyboardEvent) {
  if (!left.value && !right.value) return
  if (event.key === 'Escape') { event.preventDefault(); closePanel(left.value && right.value ? lastPanel.value : left.value ? 'left' : 'right'); return }
  if (event.key !== 'Tab' || !overlay.value) return
  const root = left.value ? sourceRoot.value : citationRoot.value
  const controls = [...(root?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),select:not(:disabled),a[href],textarea,summary,[tabindex="0"]') || [])].filter(el => el.getClientRects().length)
  const first = controls[0], last = controls.at(-1)
  if (!first) { event.preventDefault(); return }
  if (event.shiftKey && (document.activeElement === first || !root?.contains(document.activeElement))) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && (document.activeElement === last || !root?.contains(document.activeElement))) { event.preventDefault(); first.focus() }
}
async function openCitation(id: string, refs?: KnowledgeCitation[]) {
  if (!store.conversation) return
  if (refs) referenceList.value = refs
  const ticket = ++citationSequence, owner = store.conversation.id; citationLoading.value = true; citationError.value = ''; citation.value = undefined
  await openPanel('right')
  try { const result = await knowledgeApi.citation(owner, id); if (ticket === citationSequence && owner === store.conversation?.id) citation.value = result }
  catch (error) { if (ticket === citationSequence) citationError.value = notice(error) }
  finally { if (ticket === citationSequence) citationLoading.value = false }
}
function markdown(answer: string) { return answer.replace(/\]\(knowledge:([a-f0-9-]{36})\)/g, '](#knowledge-citation-$1)') }
function referenceClick(event: MouseEvent, refs: KnowledgeCitation[]) {
  const anchor = event.target instanceof Element ? event.target.closest('a') : null
  const href = anchor?.getAttribute('href') || ''
  if (!href.startsWith('#knowledge-citation-')) return
  event.preventDefault(); void openCitation(href.slice('#knowledge-citation-'.length), refs)
}
watch(() => store.messages.at(-1)?.answer, async () => { const near = timeline.value && timeline.value.scrollHeight - timeline.value.scrollTop - timeline.value.clientHeight < 160; await nextTick(); if (near && timeline.value) timeline.value.scrollTop = timeline.value.scrollHeight })
onMounted(async () => {
  window.addEventListener('resize', resize); document.addEventListener('keydown', keyboard)
  try { const [p] = await Promise.allSettled([api.getProjects(), refreshModels()])
    if (p.status === 'fulfilled') { projects.value = p.value; project.value = p.value[0]?.id || '' } else pageError.value = notice(p.reason)
    await loadRoute()
  } finally { initialLoading.value = false }
  timer = setInterval(() => { if (store.active || store.disconnected) void store.refresh() }, 5000)
})
onBeforeUnmount(() => { store.close(); citationSequence++; dataSequence++; modelSequence++; if (timer) clearInterval(timer); window.removeEventListener('resize', resize); document.removeEventListener('keydown', keyboard) })
</script>
<template>
  <main id="main-content" class="knowledge-page" aria-label="知识库">
    <header class="knowledge-topbar" :inert="overlay && (left || right)">
      <div class="knowledge-heading"><Icon icon="lucide:book-open" /><h1>知识库</h1><span>项目知识问答</span></div>
      <div class="knowledge-top-actions"><button :disabled="store.sending" @click="fresh"><Icon icon="lucide:plus" /> 新建对话</button><button :aria-expanded="historyOpen" @click="historyOpen = !historyOpen">历史对话</button></div>
      <div class="knowledge-context"><label><Icon icon="lucide:folder" /><select v-model="project" aria-label="选择项目" :disabled="!!store.conversation || store.sending" @change="changedProject"><option value="" disabled>选择项目</option><option v-for="item in projects" :key="item.id" :value="item.id">{{ item.name }}</option></select></label><button :disabled="!activeProject" :aria-expanded="left" @click="left ? closePanel('left') : openPanel('left')"><Icon icon="lucide:panel-left" /> 来源 <span>{{ store.conversation?.sources.length ?? selected.length }}</span></button></div>
    </header>
    <nav v-if="historyOpen" class="knowledge-history" aria-label="历史对话" :inert="overlay && (left || right)"><p>当前项目的历史对话</p><RouterLink v-for="item in history" :key="item.id" :to="`/knowledge/${item.id}`">{{ item.title }}<small>{{ new Date(item.createdAt).toLocaleDateString() }}</small></RouterLink><p v-if="!history.length">暂无历史对话</p><button v-if="historyCursor" @click="loadHistory(true)">加载更多对话</button></nav>
    <div class="knowledge-workspace">
      <button v-if="overlay && (left || right)" class="knowledge-backdrop" aria-label="关闭面板" @click="closePanel(left ? 'left' : 'right')" />
      <aside v-show="left" ref="sourceRoot" class="knowledge-panel knowledge-left" :role="overlay ? 'dialog' : 'complementary'" :aria-modal="overlay || undefined" aria-label="资料来源"><KnowledgeSourcesPanel v-if="activeProject" :project="activeProject" :conversation-id="store.conversation?.id" :sources="activeSources" :selected="selected" :next-cursor="sourceCursor" @select="selected = $event" @reload="loadSources()" @more="loadSources(true)" @close="closePanel('left')" /></aside>
      <section class="knowledge-chat" :inert="overlay && (left || right)">
        <div v-if="pageError || store.error || modelNotice" class="knowledge-notice" role="alert">{{ pageError || store.error || modelNotice }} <button :disabled="refreshingModels || store.sending" @click="reload">{{ refreshingModels ? '正在读取…' : '重新读取' }}</button></div>
        <div v-if="store.disconnected" class="knowledge-notice" role="status">实时连接中断，正在重新读取会话状态。</div>
        <div v-if="mode !== 'managed'" class="knowledge-notice">发送问题需要受管 OpenCode。<RouterLink to="/settings">前往设置切换运行模式</RouterLink></div>
        <div ref="timeline" class="knowledge-timeline" aria-label="聊天内容">
          <p v-if="initialLoading || store.loading" role="status">正在加载会话…</p>
          <div v-else-if="!store.messages.length" class="knowledge-welcome"><span class="knowledge-orb"><Icon icon="lucide:sparkles" /></span><p class="knowledge-eyebrow">你的项目，随时问</p><h2>让项目知识，成为答案</h2><p>从代码、文档与数据库中寻找依据。<br>选择项目，开始一次有据可查的对话。</p><div class="knowledge-suggestions"><button @click="text = '这个项目的核心流程是什么？'; composer?.focus()">梳理核心流程 <span>↗</span></button><button @click="text = '文档要求与当前代码实现有哪些差异？'; composer?.focus()">对照文档与实现 <span>↗</span></button><button @click="text = '项目数据库有哪些主要业务表？'; composer?.focus()">了解数据结构 <span>↗</span></button></div></div>
          <button v-if="store.nextCursor" :disabled="store.loading" @click="store.more()">加载更早消息</button>
          <article v-for="message in store.messages" :key="message.id" class="knowledge-turn"><div class="knowledge-user"><span>你</span><p>{{ message.userText }}</p></div><div class="knowledge-answer"><div class="knowledge-answer-label"><Icon icon="lucide:sparkles" /><strong>项目助手</strong><small>{{ knowledgeStateLabel(message.state) }}</small></div><div @click="referenceClick($event, message.citations)"><MarkdownDocument :allow-images="false" :content="markdown(message.answer)" /></div><p v-if="message.detail" class="knowledge-muted">{{ message.detail }}</p><details v-if="message.calls.length" class="knowledge-activities"><summary>{{ message.calls.length }} 项资料读取记录</summary><p v-for="call in message.calls" :key="call.id">{{ knowledgeToolLabel(call.tool) }} · {{ knowledgeStateLabel(call.state) }}<span v-if="call.detail"> — {{ call.detail }}</span></p></details><button v-if="message.citations.length" class="knowledge-citations-button" @click="openCitation(message.citations[0]!.id, message.citations)"><Icon icon="lucide:files" /> 查看 {{ message.citations.length }} 条来源 · 引用详情 <span>→</span></button></div></article>
        </div>
        <form class="knowledge-composer" @submit.prevent="send"><textarea ref="composer" v-model="text" aria-label="向项目提问" placeholder="关于这个项目，你想了解什么？" maxlength="24000" rows="3" :disabled="store.sending || initialLoading" @keydown.enter.exact="!$event.isComposing && ($event.preventDefault(), send())" /><div class="knowledge-compose-footer"><button type="button" class="knowledge-source-summary" :disabled="!activeProject" @click="openPanel('left')"><Icon icon="lucide:layers" /> {{ store.conversation?.sources.length ?? selected.length }} 项来源</button><label class="knowledge-model"><span class="sr-only">问答模型</span><select v-if="!store.conversation" v-model="model" aria-label="问答模型" :disabled="store.sending || refreshingModels" @change="modelChosen = true"><option v-if="!model" value="" disabled>选择问答模型</option><option v-else-if="!modelAvailable" :value="model" disabled>{{ model }}（不可用）</option><option v-for="item in models" :key="item.id" :value="item.id">{{ item.label || item.id }}</option></select><span v-else :title="store.conversation.model">{{ store.conversation.model }}</span></label><button v-if="store.active" type="button" class="knowledge-send" :disabled="store.sending || store.conversation?.state === 'STOPPING'" @click="store.stop">{{ store.conversation?.state === 'STOPPING' ? '确认停止中' : '停止生成' }}</button><button v-else class="knowledge-send" :disabled="!canSend">发送 <Icon icon="lucide:arrow-up" /></button></div></form>
        <footer class="knowledge-footnote"><span>{{ store.conversation ? '项目、模型和来源已固定 · 调整配置请新建对话' : 'Enter 发送 · Shift + Enter 换行' }}</span><span v-if="usage">累计用量：{{ usage.inputTokens ?? '—' }} 输入 / {{ usage.outputTokens ?? '—' }} 输出</span></footer>
      </section>
      <aside v-show="right" ref="citationRoot" class="knowledge-panel knowledge-right" :role="overlay ? 'dialog' : 'complementary'" :aria-modal="overlay || undefined" aria-label="引用详情"><header class="knowledge-panel-heading"><h2>引用详情</h2><button aria-label="关闭引用详情" @click="closePanel('right')">×</button></header><div class="knowledge-reference-tabs"><button v-for="(item, index) in referenceList" :key="item.id" :class="{ selected: citation?.citation.id === item.id }" :title="item.name" @click="openCitation(item.id)">{{ index + 1 }}</button></div><p v-if="citationLoading" role="status">读取已保存的证据…</p><p v-if="citationError" class="knowledge-notice" role="alert">{{ citationError }}</p><KnowledgeEvidence v-if="citation" :body="citation.body" :citation="citation.citation" /></aside>
    </div>
  </main>
</template>
