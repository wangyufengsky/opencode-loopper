<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Icon } from '@iconify/vue'
import { api } from '@/api/client'
import { knowledgeApi } from '@/api/knowledge'
import { useKnowledgeStore } from '@/stores/knowledgeStore'
import { knowledgeStateLabel } from '@/utils/displayLabels'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import KnowledgeSourcesPanel from '@/components/knowledge/KnowledgeSourcesPanel.vue'
import KnowledgeEvidence from '@/components/knowledge/KnowledgeEvidence.vue'
import type { KnowledgeSource, KnowledgeContent, KnowledgeCitation, KnowledgeRange, Project } from '@/types/domain'
import KnowledgeModelPicker from '@/components/knowledge/KnowledgeModelPicker.vue'
import KnowledgeUsage from '@/components/knowledge/KnowledgeUsage.vue'
import KnowledgeQuestion from '@/components/knowledge/KnowledgeQuestion.vue'
import KnowledgeThinking from '@/components/knowledge/KnowledgeThinking.vue'
import { useKnowledgeModels } from '@/composables/useKnowledgeModels'
import { useKnowledgeSplit } from '@/composables/useKnowledgeSplit'
import { splitThinkingContent } from '@/utils/thinkingContent'
import '@/styles/knowledge.css'
const route = useRoute(), router = useRouter(), store = useKnowledgeStore()
const projects = ref<Project[]>([]), project = ref('')
const modelOptions = useKnowledgeModels()
const { models, model, defaultModel, mode, ready: modelReady, settingsError, settingsLoading, catalogError, catalogLoading } = modelOptions
const sources = ref<KnowledgeSource[]>([]), selected = ref<string[]>([]), sourceCursor = ref<string | null>(null)
const citationRange = ref<KnowledgeRange>()
const text = ref(sessionStorage.getItem(`knowledge.draft.${route.fullPath}`) || ''), pageError = ref(''), newId = ref(crypto.randomUUID())
const left = ref(false), right = ref(false), width = ref(window.innerWidth), lastPanel = ref<'left' | 'right'>('left')
const workspace = ref<HTMLElement>()
const sourceRoot = ref<HTMLElement>(), citationRoot = ref<HTMLElement>(), timeline = ref<HTMLElement>(), composer = ref<HTMLTextAreaElement>()
const citation = ref<{ citation: KnowledgeCitation; body: KnowledgeContent }>(), citationError = ref(''), citationLoading = ref(false)
const referenceList = ref<KnowledgeCitation[]>([])
const returnFocus: Record<'left' | 'right', HTMLElement | null> = { left: null, right: null }
let draftOwner = route.fullPath
let citationSequence = 0, dataSequence = 0, routeSequence = 0, alive = true, timer: ReturnType<typeof setInterval> | undefined
const activeProject = computed(() => store.conversation?.projectId || project.value)
const activeSources = computed(() => store.conversation?.sources || sources.value)
const canSend = computed(() => text.value.trim() && activeProject.value && (store.conversation || (selected.value.length && modelReady.value)) && !store.sending && !store.active && mode.value === 'managed')
const usage = computed(() => store.conversation?.usage || [...store.messages].reverse().find(message => message.inputTokens !== null || message.outputTokens !== null))
const overlay = computed(() => width.value < 1100)
function notice(error: unknown) { return error instanceof Error && /[\u4e00-\u9fff]/.test(error.message) ? error.message : '加载失败，请重试' }
const split = useKnowledgeSplit(workspace, left, overlay)
const displayedMessages = computed(() => store.messages.map(message => {
  const segments = splitThinkingContent(message.answer)
  const embedded = segments.filter(part => part.type === 'thinking').map(part => part.content).join('\n\n')
  return { ...message, answerBody: segments.filter(part => part.type === 'content').map(part => part.content).join('\n\n'), thinkingBody: message.thinking || embedded }
}))
async function reload() {
  if (settingsLoading.value || store.sending) return
  pageError.value = ''; store.error = ''
  await modelOptions.loadSettings(); void modelOptions.loadCatalog(true)
  const id = typeof route.params.conversationId === 'string' ? route.params.conversationId : ''
  if (id) { await store.load(id); if (store.conversation) project.value = store.conversation.projectId }
  await loadSources()
}
async function loadSources(more = false) {
  if (!activeProject.value) return
  const current = activeProject.value, ticket = dataSequence
  try { const page = await knowledgeApi.sources(current, more ? sourceCursor.value || '' : ''); if (current !== activeProject.value || ticket !== dataSequence) return
    sources.value = more ? [...sources.value, ...page.items] : page.items; sourceCursor.value = page.nextCursor ?? null
    if (!store.conversation) selected.value = selected.value.filter(id => sources.value.some(s => s.id === id && s.state === 'READY'))
  } catch (error) { if (ticket === dataSequence) pageError.value = notice(error) }
}
async function changedProject() {
  ++dataSequence; sources.value = []; selected.value = []; sourceCursor.value = null; pageError.value = ''
  await loadSources(); if (!store.conversation) selected.value = sources.value.filter(s => s.state === 'READY').map(s => s.id)
}
async function loadRoute() {
  const ticket = ++routeSequence; ++dataSequence
  const id = typeof route.params.conversationId === 'string' ? route.params.conversationId : ''
  sessionStorage.setItem(`knowledge.draft.${draftOwner}`, text.value); draftOwner = route.fullPath
  citationSequence++; citation.value = undefined; right.value = false; text.value = ''
  if (id) { await store.load(id); if (!alive || ticket !== routeSequence) return; if (store.conversation) project.value = store.conversation.projectId; if (!text.value) text.value = store.pendingText }
  else { store.reset(); newId.value = crypto.randomUUID() }
  await changedProject()
  if (!text.value) text.value = sessionStorage.getItem(`knowledge.draft.${route.fullPath}`) || ''
}
watch(() => route.params.conversationId, () => { void loadRoute() })
async function fresh() { if (store.sending) return; await router.push('/knowledge'); if (!store.conversation && !route.params.conversationId) { newId.value = crypto.randomUUID(); text.value = ''; await changedProject() } }
async function send() {
  if (!canSend.value) return
  const originalRoute = route.fullPath; const question = text.value.trim(); const success = await store.send(question, { id: newId.value, projectId: project.value, model: model.value, sourceIds: [...selected.value], title: question.slice(0, 80) })
  if (route.fullPath !== originalRoute) return
  if (store.conversation && route.params.conversationId !== store.conversation.id) await router.replace(`/knowledge/${store.conversation.id}`)
  if (success) text.value = ''; else text.value = question
  await nextTick(); if (timeline.value) timeline.value.scrollTop = timeline.value.scrollHeight

}
async function openPanel(side: 'left' | 'right', trigger?: HTMLElement) {
  if (!(side === 'left' ? left.value : right.value)) returnFocus[side] = trigger || (document.activeElement instanceof HTMLElement ? document.activeElement : null)
  lastPanel.value = side
  if (side === 'left') { left.value = true; if (width.value < 1100) right.value = false }
  else { right.value = true; if (width.value < 1100) left.value = false }
  await nextTick(); (side === 'left' ? sourceRoot.value : citationRoot.value)?.querySelector<HTMLElement>('button')?.focus({ preventScroll: true })
}
async function closePanel(side: 'left' | 'right') { if (side === 'left') left.value = false; else right.value = false; await nextTick(); (returnFocus[side]?.getClientRects().length ? returnFocus[side] : composer.value)?.focus({ preventScroll: true }) }
function resize() { width.value = window.innerWidth; if (width.value < 1100 && left.value && right.value) { if (lastPanel.value === 'left') right.value = false; else left.value = false } }
function keyboard(event: KeyboardEvent) {
  if (!left.value && !right.value) return
  if (event.key === 'Escape') { event.preventDefault(); closePanel(left.value && right.value ? lastPanel.value : left.value ? 'left' : 'right'); return }
  if (event.key !== 'Tab' || !overlay.value) return
  const root = left.value ? sourceRoot.value : citationRoot.value
  const controls = [...(root?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),select:not(:disabled),a[href],textarea,summary,[tabindex="0"]') || [])].filter(el => el.getClientRects().length)
  const first = controls[0], last = controls.at(-1)
  if (!first) { event.preventDefault(); return }
  if (event.shiftKey && (document.activeElement === first || !root?.contains(document.activeElement))) { event.preventDefault(); last?.focus({ preventScroll: true }) }
  else if (!event.shiftKey && (document.activeElement === last || !root?.contains(document.activeElement))) { event.preventDefault(); first.focus() }
}
async function openCitation(target: string, refs?: KnowledgeCitation[]) {
  if (!store.conversation) return
  const [id, location] = target.split('#'); if (!id) return
  const range = location?.match(/^([LR])(\d+)-[LR](\d+)$/)
  citationRange.value = range ? { unit: range[1] as 'L' | 'R', first: Number(range[2]), last: Number(range[3]) } : undefined
  if (refs) referenceList.value = refs
  const ticket = ++citationSequence, owner = store.conversation.id; citationLoading.value = true; citationError.value = ''; citation.value = undefined
  await openPanel('right')
  try { const result = await knowledgeApi.citation(owner, id); if (ticket === citationSequence && owner === store.conversation?.id) citation.value = result }
  catch (error) { if (ticket === citationSequence) citationError.value = notice(error) }
  finally { if (ticket === citationSequence) citationLoading.value = false }
}
function markdown(answer: string) { return answer.replace(/\]\(knowledge:([a-f0-9-]{36}(?:#[LR]\d+-[LR]\d+)?)\)/g, '](#knowledge-citation-$1)') }
function referenceClick(event: MouseEvent, refs: KnowledgeCitation[]) {
  const anchor = event.target instanceof Element ? event.target.closest('a') : null
  const href = anchor?.getAttribute('href') || ''
  if (!href.startsWith('#knowledge-citation-')) return
  event.preventDefault(); void openCitation(href.slice('#knowledge-citation-'.length), refs)
}
watch(() => store.messages.at(-1)?.answer, async () => { const near = !right.value && timeline.value && timeline.value.scrollHeight - timeline.value.scrollTop - timeline.value.clientHeight < 160; await nextTick(); if (near && timeline.value) timeline.value.scrollTop = timeline.value.scrollHeight })
onMounted(async () => {
  window.addEventListener('resize', resize); document.addEventListener('keydown', keyboard)
  void modelOptions.loadSettings().then(() => { if (alive && !route.params.conversationId) void modelOptions.loadCatalog() })
  if (route.params.conversationId) void loadRoute()
  else { store.reset(); text.value = sessionStorage.getItem(`knowledge.draft.${route.fullPath}`) || '' }
  try {
    const result = await api.getProjects(); if (!alive) return
    projects.value = result
    if (!route.params.conversationId) { project.value = result[0]?.id || ''; await changedProject() }
  } catch (error) { if (alive) pageError.value = notice(error) }
  if (!alive) return
  timer = setInterval(() => { if (store.active || store.disconnected) void store.refresh() }, 5000)
})
onBeforeUnmount(() => { sessionStorage.setItem(`knowledge.draft.${draftOwner}`, text.value); alive = false; routeSequence++; store.close(); citationSequence++; dataSequence++; if (timer) clearInterval(timer); window.removeEventListener('resize', resize); document.removeEventListener('keydown', keyboard) })
</script>
<template>
  <main id="main-content" class="knowledge-page" aria-label="知识库">
    <header class="knowledge-topbar" :inert="overlay && (left || right)">
      <div class="knowledge-heading"><Icon icon="lucide:book-open" /><h1>知识库</h1></div>
      <label class="knowledge-project"><Icon icon="lucide:folder" /><select v-model="project" aria-label="选择项目" :disabled="!!store.conversation || store.sending" @change="changedProject"><option value="" disabled>选择项目</option><option v-for="item in projects" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
      <div class="knowledge-top-actions"><button :disabled="store.sending" @click="fresh"><Icon icon="lucide:plus" /><span>新建对话</span></button><RouterLink class="knowledge-link-button" :to="{ path: '/knowledge/history', query: { project: activeProject || undefined } }"><Icon icon="lucide:history" /><span>历史对话</span></RouterLink><button :disabled="!activeProject" :aria-expanded="left" @click="left ? closePanel('left') : openPanel('left')"><Icon icon="lucide:panel-left" /><span>来源</span><small>{{ store.conversation?.sources.length ?? selected.length }}</small></button><RouterLink class="knowledge-link-button" :to="{ path: '/projects', query: { project: activeProject || undefined } }"><Icon icon="lucide:folder-cog" /><span>项目管理</span></RouterLink></div>
    </header>
    <div ref="workspace" class="knowledge-workspace" :class="{ 'is-resizing': split.dragging.value }" :style="{ '--evidence-width': `${split.panelWidth.value}px` }">
      <button v-if="overlay && (left || right)" class="knowledge-backdrop" aria-label="关闭面板" @click="closePanel(left ? 'left' : 'right')" />
      <aside v-show="left" ref="sourceRoot" class="knowledge-panel knowledge-left" :role="overlay ? 'dialog' : 'complementary'" :aria-modal="overlay || undefined" aria-label="资料来源"><KnowledgeSourcesPanel v-if="activeProject" :project="activeProject" :conversation-id="store.conversation?.id" :sources="activeSources" :selected="selected" :next-cursor="sourceCursor" @select="selected = $event" @reload="loadSources()" @more="loadSources(true)" @close="closePanel('left')" /></aside>
      <section class="knowledge-chat" :inert="overlay && (left || right)">
        <div v-if="pageError || store.error || settingsError" class="knowledge-notice" role="alert">{{ pageError || store.error || settingsError }} <button :disabled="settingsLoading || store.sending" @click="reload">{{ settingsLoading ? '正在读取…' : '重新读取' }}</button></div>
        <div v-if="store.disconnected" class="knowledge-notice" role="status">实时连接中断，正在重新读取会话状态。</div>
        <div v-if="mode && mode !== 'managed'" class="knowledge-notice">发送问题需要受管 OpenCode。<RouterLink to="/settings">前往设置切换运行模式</RouterLink></div>
        <div ref="timeline" class="knowledge-timeline" aria-label="聊天内容">
          <p v-if="store.loading && !store.messages.length" role="status">正在加载会话…</p>
          <div v-else-if="!store.messages.length" class="knowledge-welcome"><span class="knowledge-orb"><Icon icon="lucide:sparkles" /></span><p class="knowledge-eyebrow">你的项目，随时问</p><h2>让项目知识，成为答案</h2><p>从代码、文档与数据库中寻找依据。<br>选择项目，开始一次有据可查的对话。</p><div class="knowledge-suggestions"><button @click="text = '这个项目的核心流程是什么？'; composer?.focus({ preventScroll: true })">梳理核心流程 <span>↗</span></button><button @click="text = '文档要求与当前代码实现有哪些差异？'; composer?.focus({ preventScroll: true })">对照文档与实现 <span>↗</span></button><button @click="text = '项目数据库有哪些主要业务表？'; composer?.focus({ preventScroll: true })">了解数据结构 <span>↗</span></button></div></div>
          <button v-if="store.nextCursor" :disabled="store.loading" @click="store.more()">加载更早消息</button>
          <article v-for="message in displayedMessages" :key="message.id" class="knowledge-turn"><div class="knowledge-user"><span>你</span><p>{{ message.userText }}</p></div><div class="knowledge-answer"><div class="knowledge-answer-label"><Icon icon="lucide:sparkles" /><strong>项目助手</strong><small>{{ message.questions?.some(q => q.state === 'PENDING') ? '等待回答' : knowledgeStateLabel(message.state) }}</small></div><KnowledgeThinking :message="message" :thinking="message.thinkingBody" /><div @click="referenceClick($event, message.citations)"><MarkdownDocument :allow-images="false" :content="markdown(message.answerBody)" /></div><KnowledgeQuestion v-for="question in message.questions" :key="question.id" :question="question" :conversation="store.conversation!.id" @answered="store.refresh" /><p v-if="message.detail" class="knowledge-muted">{{ message.detail }}</p><button v-if="message.citations.length" class="knowledge-citations-button" @click="openCitation(message.citations[0]!.id, message.citations)"><Icon icon="lucide:files" /> 查看 {{ message.citations.length }} 条来源 · 引用详情 <span>→</span></button></div></article>
        </div>
        <form class="knowledge-composer" @submit.prevent="send"><textarea ref="composer" v-model="text" aria-label="向项目提问" placeholder="关于这个项目，你想了解什么？" maxlength="24000" rows="3" :disabled="store.sending" @keydown.enter.exact="!$event.isComposing && ($event.preventDefault(), send())" /><div class="knowledge-compose-footer"><button type="button" class="knowledge-source-summary" :disabled="!activeProject" @click="openPanel('left')"><Icon icon="lucide:layers" /> {{ store.conversation?.sources.length ?? selected.length }} 项来源</button><div class="knowledge-model"><KnowledgeModelPicker v-if="!store.conversation" :model="model" :default-model="defaultModel" :models="models" :loading="catalogLoading" :error="catalogError" :disabled="store.sending" @load="modelOptions.loadCatalog()" @retry="modelOptions.loadCatalog(true)" @select="modelOptions.select" /><span v-else :title="store.conversation.model">{{ store.conversation.model }}</span></div><KnowledgeUsage :usage="usage" /><button v-if="store.active" type="button" class="knowledge-send" :disabled="store.sending || store.conversation?.state === 'STOPPING'" @click="store.stop">{{ store.conversation?.state === 'STOPPING' ? '确认停止中' : '停止生成' }}</button><button v-else class="knowledge-send" :disabled="!canSend">发送 <Icon icon="lucide:arrow-up" /></button></div></form>

      </section>
      <div v-if="right && !overlay" class="knowledge-resizer" role="separator" tabindex="0" aria-label="调整引用面板宽度" aria-orientation="vertical" aria-controls="knowledge-evidence-panel" :aria-valuenow="split.panelWidth.value" :aria-valuemin="320" :aria-valuemax="split.maximum.value" title="拖动调整宽度，双击恢复默认" @pointerdown="split.start" @pointermove="split.move" @pointerup="split.stop" @pointercancel="split.stop" @lostpointercapture="split.stop" @keydown="split.keyboard" @dblclick="split.reset" />
      <aside id="knowledge-evidence-panel" v-show="right" ref="citationRoot" class="knowledge-panel knowledge-right" :role="overlay ? 'dialog' : 'complementary'" :aria-modal="overlay || undefined" aria-label="引用详情"><header class="knowledge-panel-heading"><h2>引用详情</h2><button aria-label="关闭引用详情" @click="closePanel('right')">×</button></header><div class="knowledge-reference-tabs"><button v-for="(item, index) in referenceList" :key="item.id" :class="{ selected: citation?.citation.id === item.id }" :title="item.name" @click="openCitation(item.id)">{{ index + 1 }}</button></div><p v-if="citationLoading" role="status">读取已保存的证据…</p><p v-if="citationError" class="knowledge-notice" role="alert">{{ citationError }}</p><KnowledgeEvidence v-if="citation" :body="citation.body" :citation="citation.citation" :focus-range="citationRange" /></aside>
    </div>
  </main>
</template>
