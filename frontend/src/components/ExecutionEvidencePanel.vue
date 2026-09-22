<script setup lang="ts">
import { ref, watch } from 'vue'
import { api } from '@/api/client'
import type { EvidencePage, EvidenceFailurePage, EvidenceBody, EvidenceSearch, EvidenceFailureDetail } from '@/types/domain'
import { userFacingError, evidenceCaptureLabel } from '@/utils/displayLabels'
const props = defineProps<{ taskId: string }>()
const opened = ref(false), busy = ref(false), error = ref(''), query = ref('')
const page = ref<EvidencePage>(), failures = ref<EvidenceFailurePage>(), body = ref<EvidenceBody>()
const detail = ref<EvidenceFailureDetail>(), search = ref<EvidenceSearch>()
let generation = 0
watch(() => props.taskId, () => { generation++; opened.value = false; busy.value = false; page.value = undefined; failures.value = undefined; body.value = undefined; detail.value = undefined; search.value = undefined; error.value = ''; query.value = '' })
async function run(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''; const current = generation
  try { await action() } catch (cause) { if (current === generation) error.value = userFacingError(cause, '证据读取失败，请重试') }
  finally { if (current === generation) busy.value = false }
}
async function load(cursor = '', failureCursor = '') {
  const current = generation, task = props.taskId
  await run(async () => {
    const [p, f] = await Promise.all([api.executionEvidence(task, cursor), api.evidenceFailures(task, failureCursor)])
    if (current === generation) { page.value = p; failures.value = f }
  })
}
function toggle(event: Event) {
  opened.value = (event.target as HTMLDetailsElement).open
  if (opened.value && !page.value) void load()
}
async function read(reference: string, offset = 0) {
  const current = generation, task = props.taskId
  await run(async () => { const value = await api.executionEvidenceBody(task, reference, offset); if (current === generation) { body.value = value; detail.value = undefined } })
}
async function failure(id: string) {
  const current = generation, task = props.taskId
  await run(async () => { const value = await api.evidenceFailure(task, id); if (current === generation) { detail.value = value; body.value = undefined } })
}
async function find(cursor = '') {
  const current = generation, task = props.taskId, term = query.value
  await run(async () => { const value = await api.searchExecutionEvidence(task, term, cursor); if (current === generation && query.value === term) search.value = value })
}
</script>
<template>
  <details class="execution-evidence" :open="opened" @toggle="toggle">
    <summary>日志与测试快照</summary>
    <div v-if="opened" class="evidence-content">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-button :loading="busy" @click="load()">刷新证据</el-button>
      <p v-if="page && !page.items.length" class="muted">暂无已采集快照。历史任务不会从当前文件补造证据。</p>
      <ul><li v-for="item in page?.items" :key="item.id"><button class="evidence-link" :disabled="busy" @click="read(item.reference)">{{ item.source }}</button> · {{ evidenceCaptureLabel(item.status) }} · {{ item.createdAt }}</li></ul>
      <el-button v-if="page?.nextCursor" :disabled="busy" @click="load(page.nextCursor)">下一页来源</el-button>
      <p class="muted">{{ failures?.detail }}</p>
      <ul><li v-for="item in failures?.items" :key="item.id"><button class="evidence-link" :disabled="busy" @click="failure(item.id)">{{ item.className }} · {{ item.name }}</button></li></ul>
      <el-button v-if="failures?.nextCursor" :disabled="busy" @click="load('', failures.nextCursor)">下一页失败用例</el-button>
      <form class="search" @submit.prevent="find()"><el-input v-model="query" placeholder="搜索已保存证据" :maxlength="256" @input="search = undefined" /><el-button native-type="submit" :disabled="busy || !query.trim()">搜索</el-button></form>
      <template v-if="search"><p v-if="!search.complete" class="muted">本次搜索范围不完整，请缩小范围或继续读取来源。</p><ul><li v-for="hit in search.items" :key="`${hit.reference}:${hit.offset}`"><button class="evidence-link" :disabled="busy" @click="read(hit.reference, Math.max(0, hit.offset - 100))">{{ hit.source }}</button><pre>{{ hit.excerpt }}</pre></li></ul><el-button v-if="search.nextCursor" :disabled="busy" @click="find(search.nextCursor)">继续搜索</el-button></template>
      <template v-if="body"><p>{{ body.source }} · {{ evidenceCaptureLabel(body.status) }}</p><pre>{{ body.content }}</pre><el-button v-if="body.nextOffset >= 0" :disabled="busy" @click="read(body.reference, body.nextOffset)">读取下一段</el-button></template>
      <template v-if="detail"><p>{{ detail.failure.name }} · {{ evidenceCaptureLabel(detail.source.status) }}</p><pre>{{ detail.failure.message }}\n{{ detail.failure.stack }}\n{{ detail.failure.output }}</pre></template>
    </div>
  </details>
</template>
<style scoped>
.execution-evidence { margin-bottom: 16px; padding: 12px; border: 1px solid var(--color-border-default); border-radius: calc(var(--radius-control) + 2px); }
summary { cursor: pointer; } .evidence-content { display: grid; gap: 12px; padding-top: 12px; }
.search { display: flex; gap: 8px; } pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 440px; overflow: auto; }
li { overflow-wrap: anywhere; margin: 8px 0; }.evidence-link { color: var(--color-text-primary); background: transparent; border: 0; text-decoration: underline; cursor: pointer; text-align: left; overflow-wrap: anywhere; }
</style>
