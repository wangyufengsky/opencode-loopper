<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import CodeMergeEditor from '@/components/CodeMergeEditor.vue'
import type { KnowledgeContent, KnowledgeCitation, KnowledgeRange } from '@/types/domain'
const props = defineProps<{ body: KnowledgeContent; citation?: KnowledgeCitation; focusRange?: KnowledgeRange }>()
const editor = ref<InstanceType<typeof CodeMergeEditor>>(), root = ref<HTMLElement>(), formatted = ref(false), showJson = ref(false)
const language = computed(() => props.body.name?.endsWith('.java') ? 'java' : props.body.name?.endsWith('.json') ? 'json' : 'plain')
const first = computed(() => props.body.startLine || 1)
const last = computed(() => props.body.endLine || first.value + (props.body.text || '').split('\n').length - 1)
const range = computed(() => props.focusRange || (props.citation ? { unit: props.body.kind === 'DATABASE' ? 'R' : 'L', first: props.body.kind === 'DATABASE' ? 1 : first.value, last: props.body.kind === 'DATABASE' ? rows.value.length : last.value } : undefined))
const lines = computed(() => range.value?.unit === 'L' ? Array.from({ length: Math.max(0, Math.min(last.value, range.value.last) - Math.max(first.value, range.value.first) + 1) }, (_, i) => Math.max(first.value, range.value!.first) - first.value + i + 1) : [])
const rows = computed<unknown[][]>(() => {
  if (Array.isArray(props.body.rows)) return props.body.rows as unknown[][]
  if (Array.isArray(props.body.items)) return (props.body.items as Record<string, unknown>[]).map(row => columns.value.map(column => row[column]))
  return []
})
const columns = computed<string[]>(() => {
  if (Array.isArray(props.body.columns)) return props.body.columns.map(c => typeof c === 'string' ? c : String(c.name))
  if (Array.isArray(props.body.items) && props.body.items.length) return Object.keys(props.body.items[0])
  return []
})
const limitations = computed(() => props.body.limitations?.filter(text => text !== '仅提供可提取内容，不还原版式、动画或图片中的文字；不执行公式或抓取外部资源') || [])
const gitLabels: Record<string, string> = { sha: '提交', commit: '提交', author: '作者', authorEmail: '作者邮箱', authorDate: '作者时间', authoredAt: '作者时间', committer: '提交者', committerEmail: '提交者邮箱', committerDate: '提交时间', committedAt: '提交时间', subject: '说明', name: '名称', email: '邮箱', commits: '提交数', count: '数量' }
const columnLabel = (column: string) => props.body.kind === 'GIT' ? gitLabels[column] || column : column
const cell = (value: unknown) => value == null ? 'NULL' : typeof value === 'object' ? JSON.stringify(value) : String(value)
const jsonBody = computed(() => JSON.stringify(props.body, null, 2))
const rowHighlighted = (index: number) => range.value?.unit === 'R' && index + 1 >= range.value.first && index + 1 <= range.value.last
watch(() => [props.body, props.focusRange], async () => {
  formatted.value = false; showJson.value = false; await nextTick()
  if (lines.value.length) editor.value?.scrollToLine(lines.value[0]!)
  const row = root.value?.querySelector<HTMLElement>('tr.is-highlighted')
  if (row) { const viewport = row.closest<HTMLElement>('.knowledge-result-table'); if (viewport) viewport.scrollTop = row.offsetTop - 50 }
}, { immediate: true })
</script>
<template>
  <section ref="root" class="knowledge-evidence">
    <h3>{{ citation?.name || body.name }}</h3><p class="knowledge-location">{{ citation?.location || body.location }}</p>
    <div class="knowledge-evidence-meta"><span v-if="citation">{{ new Date(citation.createdAt).toLocaleString() }}</span><span v-if="body.sha256" class="knowledge-hash" :title="body.sha256">{{ body.sha256.slice(0, 12) }}</span></div>
    <p v-if="body.changeNotice" class="knowledge-notice">{{ body.changeNotice }}</p><p v-for="limitation in limitations" :key="limitation" class="knowledge-notice">{{ limitation }}</p>
    <template v-if="body.kind === 'DATABASE' || (!body.text && body.kind === 'GIT')">
      <p v-if="body.truncated || body.incomplete" class="knowledge-notice">结果未覆盖全部资料，仅展示本次采集范围</p><p v-if="body.notice" class="knowledge-muted">{{ body.notice }}</p>
      <details v-if="body.sql" class="knowledge-sql"><summary>查询语句</summary><pre>{{ body.sql }}</pre></details>
      <div v-if="columns.length" class="knowledge-result-table"><table><thead><tr><th>行</th><th v-for="column in columns" :key="column">{{ columnLabel(column) }}</th></tr></thead><tbody><tr v-for="(row, index) in rows" :key="index" :class="{ 'is-highlighted': rowHighlighted(index) }"><th>{{ index + 1 }}</th><td v-for="(value, col) in row" :key="col">{{ cell(value) }}</td></tr></tbody></table></div>
      <p v-if="body.kind === 'DATABASE' && !rows.length" class="knowledge-muted">没有返回结果行</p><button @click="showJson = !showJson">{{ showJson ? '收起原始结果' : '原始结果' }}</button><CodeMergeEditor v-if="showJson || !columns.length" :model-value="jsonBody" readonly line-wrapping language="json" aria-label="数据库或 Git 读取结果" />
    </template>
    <template v-else-if="body.text">
      <div class="knowledge-evidence-toolbar"><span>{{ body.kind === 'DOCUMENT' ? body.lineBasis || '解析文本行号' : '原文行号' }} {{ first }}–{{ last }}</span><button v-if="body.kind === 'DOCUMENT'" @click="formatted = !formatted">{{ formatted ? '带行号原文' : '阅读视图' }}</button></div>
      <MarkdownDocument v-if="formatted" :content="body.text" :allow-images="false" :highlight-lines="lines" />
      <CodeMergeEditor v-else ref="editor" :key="`${citation?.id}:${body.sha256}:${first}:${language}`" :model-value="body.text" readonly line-wrapping :first-line-number="first" :highlighted-lines="lines" :language="language" aria-label="引用原文片段" />
      <details v-if="Array.isArray(body.authors)"><summary>最后修改记录</summary><div v-for="(author, index) in (body.authors as Record<string, unknown>[])" :key="index" class="knowledge-author">{{ author.number }} · {{ author.author }} · {{ String(author.commit).slice(0, 12) }}</div></details>
    </template>
  </section>
</template>
