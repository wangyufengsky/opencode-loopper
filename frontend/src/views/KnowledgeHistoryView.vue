<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Icon } from '@iconify/vue'
import { api } from '@/api/client'
import { knowledgeApi } from '@/api/knowledge'
import { knowledgeStateLabel } from '@/utils/displayLabels'
import type { KnowledgeConversation, Project } from '@/types/domain'
import '@/styles/knowledge.css'
const route = useRoute(), router = useRouter(), items = ref<KnowledgeConversation[]>([]), projects = ref<Project[]>([])
const project = ref(String(route.query.project || '')), query = ref(String(route.query.query || '')), archive = ref(String(route.query.archive || 'active')), state = ref(String(route.query.state || '')), period = ref(String(route.query.period || ''))
const cursor = ref<string | null>(null), busy = ref(false), mutation = ref(''), error = ref(''), list = ref<HTMLElement>()
let pagesLoaded = 0
let since = period.value ? new Date(Date.now() - Number(period.value) * 86400000).toISOString() : ''
let sequence = 0, timer: ReturnType<typeof setTimeout> | undefined
const names = computed(() => new Map(projects.value.map(p => [p.id, p.name])))
function activity(item: KnowledgeConversation) { return item.options?.lastActivityAt || item.updatedAt || item.createdAt }
function group(item: KnowledgeConversation) {
  const day = new Date(activity(item)), today = new Date(); today.setHours(0,0,0,0)
  if (day >= today) return '今天'
  today.setDate(today.getDate() - 1); return day >= today ? '昨天' : '更早'
}
const groups = computed(() => ['今天', '昨天', '更早'].map(label => ({ label, items: items.value.filter(item => group(item) === label) })).filter(group => group.items.length))
async function load(more = false) {
  const ticket = ++sequence; busy.value = true; error.value = ''
  try {
    const page = await knowledgeApi.history(project.value, more ? cursor.value || '' : '', { query: query.value.trim(), archive: archive.value, state: state.value, since })
    if (ticket !== sequence) return
    pagesLoaded = more ? pagesLoaded + 1 : 1
    items.value = more ? [...new Map([...items.value, ...page.items].map(i => [i.id, i])).values()] : page.items; cursor.value = page.nextCursor ?? null
    await nextTick()

  } catch (failure) { if (ticket === sequence) error.value = failure instanceof Error && /[\u4e00-\u9fff]/.test(failure.message) ? failure.message : '历史对话读取失败，请重试' }
  finally { if (ticket === sequence) busy.value = false }
}
watch([project, query, archive, state, period], () => {
  if (timer) clearTimeout(timer)
  since = period.value ? new Date(Date.now() - Number(period.value) * 86400000).toISOString() : ''
  timer = setTimeout(async () => { await router.replace({ path: '/knowledge/history', query: { project: project.value || undefined, query: query.value || undefined, archive: archive.value, state: state.value || undefined, period: period.value || undefined } }); void load() }, 220)
})
async function organize(item: KnowledgeConversation) {
  if (mutation.value) return
  mutation.value = item.id; error.value = ''
  try { await knowledgeApi.archive(item.id, !item.options?.archivedAt, item.options?.version || 0); await load() }
  catch (failure) { error.value = failure instanceof Error && /[\u4e00-\u9fff]/.test(failure.message) ? failure.message : '归档状态更新失败，请刷新后重试' }
  finally { mutation.value = '' }
}
function enter(item: KnowledgeConversation) { sessionStorage.setItem(`knowledge.history.pages.${route.fullPath}`, String(pagesLoaded)); sessionStorage.setItem(`knowledge.history.scroll.${route.fullPath}`, String(list.value?.scrollTop || 0)); void router.push(`/knowledge/${item.id}`) }
async function restoreList() {
  const path = route.fullPath, depth = Math.min(20, Number(sessionStorage.getItem(`knowledge.history.pages.${path}`) || 1))
  await load()
  while (route.fullPath === path && cursor.value && pagesLoaded < depth && !error.value) await load(true)
  await nextTick()
  if (list.value && route.fullPath === path) list.value.scrollTop = Number(sessionStorage.getItem(`knowledge.history.scroll.${path}`) || 0)
}
onMounted(async () => { void restoreList(); try { projects.value = await api.getProjects() } catch { error.value = '项目列表暂不可用，仍可查看全部对话' } })
onBeforeUnmount(() => { sequence++; if (timer) clearTimeout(timer) })
</script>
<template>
  <main id="main-content" class="knowledge-page knowledge-history-page">
    <header class="knowledge-topbar"><div class="knowledge-heading"><Icon icon="lucide:history" /><h1>历史对话</h1></div><div class="knowledge-top-actions"><RouterLink class="knowledge-link-button" to="/knowledge"><Icon icon="lucide:plus" />新建对话</RouterLink></div></header>
    <div class="knowledge-history-filters"><label class="knowledge-history-search"><Icon icon="lucide:search" /><input v-model="query" placeholder="搜索标题或对话内容" aria-label="搜索历史对话" maxlength="200"></label><select v-model="project" aria-label="按项目筛选"><option value="">全部项目</option><option v-for="item in projects" :key="item.id" :value="item.id">{{ item.name }}</option></select><select v-model="period" aria-label="按时间筛选"><option value="">全部时间</option><option value="7">最近 7 天</option><option value="30">最近 30 天</option></select><select v-model="state" aria-label="按状态筛选"><option value="">全部状态</option><option value="IDLE">可继续对话</option><option value="RUNNING">进行中</option><option value="WAITING_INPUT">等待回答</option><option value="STOPPING">正在停止</option></select><select v-model="archive" aria-label="归档筛选"><option value="active">未归档</option><option value="archived">已归档</option><option value="all">全部对话</option></select></div>
    <p v-if="error" class="knowledge-notice" role="alert">{{ error }} <button @click="load()">重试</button></p>
    <div ref="list" class="knowledge-history-list" :aria-busy="busy"><p v-if="busy && !items.length" role="status">正在读取对话…</p><div v-else-if="!items.length" class="knowledge-history-empty"><Icon icon="lucide:messages-square" /><h2>{{ query || state || period ? '没有匹配的对话' : archive === 'archived' ? '还没有归档对话' : '从一次提问开始' }}</h2><p>{{ query || state || period ? '试试调整关键词或筛选条件' : '你的项目讨论会保存在这里' }}</p></div>
      <section v-for="section in groups" :key="section.label"><h2>{{ section.label }}</h2><article v-for="item in section.items" :key="item.id" class="knowledge-history-card"><button class="knowledge-history-entry" @click="enter(item)"><span class="knowledge-history-symbol"><Icon icon="lucide:message-square-text" /></span><span class="knowledge-history-title"><strong>{{ item.title }}</strong><small>{{ names.get(item.projectId) || '项目' }} · {{ item.model }}</small></span><span class="knowledge-history-meta"><small>{{ item.options?.awaitingAnswer ? '等待回答' : knowledgeStateLabel(item.state) }}</small><time>{{ new Date(activity(item)).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }}</time></span></button><button :disabled="!!mutation" :aria-label="`${item.options?.archivedAt ? '恢复' : '归档'}对话 ${item.title}`" :title="item.options?.archivedAt ? '恢复对话' : '归档对话'" @click="organize(item)"><Icon :icon="item.options?.archivedAt ? 'lucide:archive-restore' : 'lucide:archive'" /></button></article></section>
      <button v-if="cursor" :disabled="busy" @click="load(true)">{{ busy ? '正在读取…' : '加载更多' }}</button>
    </div>
  </main>
</template>
