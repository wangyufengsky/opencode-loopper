<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { knowledgeApi as api } from '@/api/knowledge'
import type { KnowledgeSource, KnowledgeContent, KnowledgeListing, KnowledgeSearch } from '@/types/domain'
import { knowledgeStateLabel } from '@/utils/displayLabels'
import KnowledgeEvidence from './KnowledgeEvidence.vue'
import KnowledgeGitBrowser from './KnowledgeGitBrowser.vue'
import { Icon } from '@iconify/vue'
const props = defineProps<{ project: string; conversationId?: string; sources: KnowledgeSource[]; selected: string[]; nextCursor?: string | null }>()
const emit = defineEmits<{ select: [ids: string[]]; reload: []; more: []; close: [] }>()
const filter = ref('ALL')
const query = ref(''), error = ref(''), busy = ref(false), external = ref(''), showExternal = ref(false)
const schemas = ref<string[]>([]), schema = ref(''), table = ref('')
const active = ref<KnowledgeSource>(), path = ref(''), listing = ref<KnowledgeListing>(), results = ref<KnowledgeSearch>(), content = ref<KnowledgeContent>()
let generation = 0
const groups = computed(() => [
  { label: '代码', icon: 'lucide:code-xml', type: 'CODE', items: props.sources.filter(s => s.kind === 'CODE') },
  { label: '文档', icon: 'lucide:files', type: 'DOCUMENTS', items: props.sources.filter(s => ['DOCUMENTS', 'DIRECTORY', 'UPLOAD'].includes(s.kind)) },
  { label: 'Git', icon: 'lucide:git-branch', type: 'GIT', items: props.sources.filter(s => s.kind === 'GIT') },
  { label: '数据库', icon: 'lucide:database', type: 'DATABASE', items: props.sources.filter(s => s.kind === 'DATABASE') },
].filter(group => filter.value === 'ALL' || group.type === filter.value))
watch(() => [props.project, props.conversationId], () => { generation++; active.value = undefined; listing.value = undefined; results.value = undefined; content.value = undefined; error.value = ''; busy.value = false })
async function run(action: () => Promise<void>) {
  if (busy.value) return
  const ticket = generation; busy.value = true; error.value = ''
  try { await action() } catch (failure) { if (ticket === generation) error.value = failure instanceof Error && /[\u4e00-\u9fff]/.test(failure.message) ? failure.message : '资料读取失败，请刷新重试' }
  finally { if (ticket === generation) busy.value = false }
}
function select(source: KnowledgeSource, checked: boolean) { emit('select', checked ? [...props.selected, source.id] : props.selected.filter(id => id !== source.id)) }
async function database(source: KnowledgeSource, selectedSchema = '', offset = 0) {
  const ticket = generation; const result = await api.database(props.project, source.id, { conversationId: props.conversationId, schema: selectedSchema, table: table.value || undefined, kind: table.value ? 'columns' : 'tables', offset })
  if (ticket !== generation) return
  active.value = source; listing.value = undefined; results.value = undefined; content.value = result
  if (Array.isArray(result.schemas)) schemas.value = result.schemas.filter((s): s is string => typeof s === 'string')
}
async function browse(source: KnowledgeSource, relative = '', cursor = '') {
  if (source.kind === 'GIT') { active.value = source; content.value = undefined; listing.value = undefined; results.value = undefined; return }
  const ticket = generation; const page = await api.browse(props.project, source.id, { conversationId: props.conversationId, path: relative, cursor })
  if (ticket !== generation) return
  active.value = source; path.value = relative; listing.value = cursor && listing.value ? { ...page, items: [...listing.value.items, ...page.items] } : page; results.value = undefined; content.value = undefined
}
async function read(relative: string, section = -1, startLine = 1, expectedSha?: string, offset = 0) {
  if (!active.value) return
  const ticket = generation; const body = await api.read(props.project, active.value.id, { conversationId: props.conversationId, path: relative, section, startLine, expectedSha, offset })
  if (ticket === generation) content.value = body
}
async function search(cursor = '') {
  if (!query.value.trim()) return
  const ticket = generation
  if (active.value && !['GIT', 'DATABASE'].includes(active.value.kind)) {
    const page = await api.search(props.project, active.value.id, { conversationId: props.conversationId, query: query.value.trim(), cursor })
    if (ticket === generation) { results.value = cursor && results.value ? { ...page, matches: [...results.value.matches, ...page.matches] } : page; content.value = undefined }
  } else {
    const selected = props.sources.filter(source => source.state === 'READY' && !['DATABASE', 'GIT'].includes(source.kind) && (props.conversationId || props.selected.includes(source.id)))
    const pages = []
    for (let i = 0; i < selected.length; i += 3) {
      pages.push(...await Promise.all(selected.slice(i, i + 3).map(source => api.search(props.project, source.id, { conversationId: props.conversationId, query: query.value.trim() }).then(page => ({ ...page, matches: page.matches.map(match => ({ ...match, sourceId: source.id })) })))));
      if (ticket !== generation) return
    }
    results.value = { matches: [...new Map(pages.flatMap(page => page.matches).map(match => [`${match.resourceKey || `${match.sourceId}:${match.path}`}:${match.section ?? match.startLine ?? 0}`, match])).values()], nextCursor: null, incomplete: pages.some(page => page.incomplete || page.nextCursor), limitations: pages.flatMap(page => page.limitations), detail: '' }; content.value = undefined
  }
}
async function readMatch(match: KnowledgeSearch['matches'][number]) {
  if (match.sourceId) active.value = props.sources.find(source => source.id === match.sourceId)
  await read(match.path, match.section ?? -1, match.startLine ?? 1, match.sha256)
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement; const files = [...(input.files || [])]; input.value = ''
  if (!files.length) return
  await run(async () => { const uploaded = await api.upload(props.project, files); emit('reload'); const failed = uploaded.filter(s => s.state !== 'READY'); if (failed.length) error.value = failed.map(s => `${s.name}：${s.detail}`).join('；') })
}
async function remove(source: KnowledgeSource) {
  if (!window.confirm(`移除“${source.name}”的资料绑定？原始文件和历史引用将保留。`)) return
  await run(async () => { await api.remove(props.project, source.id, source.version); emit('reload') })
}
</script>
<template>
  <header class="knowledge-panel-heading"><h2>资料来源</h2><button aria-label="关闭来源" @click="emit('close')">×</button></header>
  <div class="knowledge-source-filters"><button v-for="item in [{ id: 'ALL', label: '全部' }, { id: 'CODE', label: '代码' }, { id: 'DOCUMENTS', label: '文档' }, { id: 'GIT', label: 'Git' }, { id: 'DATABASE', label: '数据库' }]" :key="item.id" :class="{ selected: filter === item.id }" @click="filter = item.id">{{ item.label }}</button></div>
  <form class="knowledge-search" @submit.prevent="run(() => search())"><input v-model="query" aria-label="来源搜索" placeholder="搜索文件内容…" maxlength="200"><button :disabled="busy || !query.trim()">搜索</button></form>
  <div v-if="error" class="knowledge-notice" role="alert">{{ error }}</div>
  <div v-if="busy" class="knowledge-muted" role="status">正在读取…</div>
  <section v-for="group in groups" :key="group.label" class="knowledge-source-group">
    <h3><Icon :icon="group.icon" />{{ group.label }}<small>{{ group.items.length }}</small></h3><p v-if="!group.items.length" class="knowledge-muted">尚未绑定</p>
    <div v-for="source in group.items" :key="source.id" class="knowledge-source-row">
      <input v-if="!conversationId" type="checkbox" :aria-label="`使用${source.name}`" :checked="selected.includes(source.id)" :disabled="source.state !== 'READY'" @change="select(source, ($event.target as HTMLInputElement).checked)">
      <button class="knowledge-source-title" :class="{ selected: active?.id === source.id }" :disabled="busy || source.state !== 'READY'" @click="run(() => source.kind === 'DATABASE' ? database(source) : browse(source))"><span>{{ source.name }}<small v-if="source.state === 'READY' && source.detail">{{ source.detail }}</small></span><i :class="{ ready: source.state === 'READY' }" :title="knowledgeStateLabel(source.state)" /></button>
      <details v-if="!conversationId && ['DIRECTORY', 'UPLOAD'].includes(source.kind)"><summary aria-label="资料操作">⋯</summary><button :disabled="busy" @click="run(async () => { await api.refresh(project, source.id, source.version); emit('reload') })">刷新</button><button :disabled="busy" @click="remove(source)">移除绑定</button></details>
      <p v-if="source.detail && source.state !== 'READY'" class="knowledge-notice">{{ source.detail }}</p>
    </div>
  </section>
  <button v-if="nextCursor && !conversationId" :disabled="busy" @click="emit('more')">加载更多资料</button>
  <div v-if="!conversationId" class="knowledge-source-actions"><button :disabled="busy" @click="showExternal = !showExternal">＋ 外部目录</button><label class="knowledge-upload">上传文档<input type="file" multiple accept=".md,.markdown,.docx,.xlsx,.pptx,.pdf" :disabled="busy" @change="upload"></label></div>
  <form v-if="showExternal && !conversationId" @submit.prevent="run(async () => { await api.directory(project, external); external = ''; showExternal = false; emit('reload') })"><label>文档目录的绝对路径<input v-model="external" required placeholder="/path/to/documents"></label><button :disabled="busy || !external.trim()">登记目录</button></form>
    <template v-if="results"><button v-for="(match, index) in results.matches" :key="index" class="knowledge-match" :disabled="busy" @click="run(() => readMatch(match))"><strong>{{ match.name }}</strong><span>{{ match.snippet }}</span></button><p v-if="!results.matches.length">本页未找到匹配内容</p><p v-for="item in results.limitations" :key="item" class="knowledge-notice">{{ item }}</p><p v-if="results.incomplete" class="knowledge-muted">结果未覆盖全部资料</p><button v-if="results.nextCursor" :disabled="busy" @click="run(() => search(results!.nextCursor!))">继续检索</button></template>
  <section v-if="active" class="knowledge-browser">
    <div class="knowledge-browser-heading"><button aria-label="返回来源列表" @click="active = undefined; results = undefined; content = undefined; listing = undefined">←</button><h3>{{ active.name }}</h3></div><KnowledgeGitBrowser v-if="active.kind === 'GIT'" :key="`${project}:${conversationId}:${active.id}`" :project="project" :source="active.id" :conversation-id="conversationId" />
    <form v-if="active.kind === 'DATABASE'" @submit.prevent="run(() => database(active!, schema))"><select v-model="schema" aria-label="数据库结构"><option value="">选择结构</option><option v-for="name in schemas" :key="name" :value="name">{{ name }}</option></select><input v-model="table" aria-label="数据表名称" placeholder="表名（可选）"><button :disabled="busy || !schema">查看结构</button><button v-if="typeof content?.nextOffset === 'number'" type="button" :disabled="busy" @click="run(() => database(active!, schema, content!.nextOffset as number))">下一页结构</button></form><p v-if="active.kind === 'DATABASE'" class="knowledge-muted">{{ active.detail || '使用本项目已授权的连接。可在聊天中询问表结构或只读数据。' }}</p>
    <template v-if="listing"><button v-if="path" :disabled="busy" @click="run(() => browse(active!, path.split('/').slice(0, -1).join('/')))">返回上级</button><p class="knowledge-path">{{ path || '根目录' }}</p>
      <button v-for="entry in listing.items" :key="entry.path" class="knowledge-file" :disabled="busy" @click="run(() => entry.directory ? browse(active!, entry.path) : read(entry.path))">{{ entry.directory ? '▸ ' : '· ' }}{{ entry.name }}</button>
      <p v-if="!listing.items.length" class="knowledge-muted">此目录没有可读取资料</p><p v-if="listing.incomplete" class="knowledge-notice">{{ listing.detail || '目录未完整读取，请缩小范围或继续翻页' }}</p><button v-if="listing.nextCursor" :disabled="busy" @click="run(() => browse(active!, path, listing!.nextCursor!))">下一页目录</button>
    </template>

    <template v-if="content"><button v-for="section in content.sections" :key="section.section" class="knowledge-file" :disabled="busy" @click="run(() => read(content!.path, section.section, 1, content!.sha256))">{{ section.title }}</button><button v-if="content.kind === 'DOCUMENT' && typeof content.nextOffset === 'number' && content.nextOffset >= 0" :disabled="busy" @click="run(() => read(content!.path, -1, 1, content!.sha256, content!.nextOffset as number))">下一页章节</button><KnowledgeEvidence :body="content" /><button v-if="(content.nextSection ?? -1) >= 0 || (content.nextLine ?? -1) > 0" :disabled="busy" @click="run(() => read(content!.path, content!.nextSection ?? -1, content!.nextLine ?? 1, content!.sha256))">下一段原文</button></template>
  </section>
</template>
