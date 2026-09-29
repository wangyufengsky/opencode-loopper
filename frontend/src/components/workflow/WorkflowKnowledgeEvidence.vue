<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { workflowRuns } from '@/api/workflowRuns'
import type { KnowledgeContent, WorkflowKnowledgeBody, WorkflowKnowledgeEntry } from '@/types/domain'
import KnowledgeEvidence from '@/components/knowledge/KnowledgeEvidence.vue'
import { knowledgeToolLabel, userFacingError } from '@/utils/displayLabels'
import { knowledgeBody } from './knowledgeBundle'
const props = defineProps<{ requirement: string; node: string; attempt: string }>()
const rows = ref<WorkflowKnowledgeEntry[]>([]), cursor = ref<string | null>(null), loaded = ref(false), busy = ref(false), error = ref('')
const selected = ref(''), body = ref<WorkflowKnowledgeBody | null>(null), bodyBusy = ref(false), bodyError = ref('')
const cache = new Map<string, WorkflowKnowledgeBody>()
let alive = true, generation = 0, failedMore = false
const content = computed<KnowledgeContent | null>(() => body.value ? knowledgeBody(body.value.content, knowledgeToolLabel(body.value.toolName)) : null)
async function load(more = false) {
  if (busy.value) return
  busy.value = true; error.value = ''; failedMore = more
  try {
    const page = await workflowRuns.knowledgeEvidence(props.requirement, props.node, props.attempt, more ? cursor.value || '' : '')
    if (alive) { rows.value = more ? [...rows.value, ...page.items] : page.items; cursor.value = page.nextCursor || null; loaded.value = true }
  } catch (failure) { if (alive) error.value = userFacingError(failure, '检索证据暂时无法读取，请重试。') }
  finally { if (alive) busy.value = false }
}
async function read(id: string) {
  const ticket = ++generation
  selected.value = id; body.value = cache.get(id) || null; bodyError.value = ''; bodyBusy.value = !body.value
  if (body.value) return
  try {
    const saved = await workflowRuns.knowledgeEvidenceBody(props.requirement, props.node, props.attempt, id)
    if (alive && ticket === generation) {
      cache.set(id, saved); body.value = saved
      if (cache.size > 3) cache.delete(cache.keys().next().value!)
    }
  } catch (failure) { if (alive && ticket === generation) bodyError.value = userFacingError(failure, '证据正文暂时无法读取，请重试。') }
  finally { if (alive && ticket === generation) bodyBusy.value = false }
}
onMounted(() => { void load() })
onBeforeUnmount(() => { alive = false; generation++ })
</script>
<template>
  <section class="workflow-knowledge-evidence" aria-label="本次检索证据">
    <h3>本次检索证据</h3><p class="evidence-note">这里保存本次执行实际读取的资料。采集之后的文件变化不会改写这些记录。</p>
    <button type="button" :disabled="busy" @click="load()">{{ busy ? '读取证据…' : '刷新证据' }}</button>
    <p v-if="error" role="alert">{{ error }} <button type="button" :disabled="busy" @click="load(failedMore)">重试读取证据</button></p>
    <p v-if="loaded && !rows.length">本次执行尚无保存的检索证据。</p>
    <ul>
      <li v-for="entry in rows" :key="entry.id"><button type="button" :aria-pressed="selected === entry.id" @click="read(entry.id)">{{ knowledgeToolLabel(entry.toolName) }}<time>{{ new Date(entry.createdAt).toLocaleString('zh-CN') }}</time></button></li>
    </ul>
    <button v-if="cursor && !error" type="button" :disabled="busy" @click="load(true)">更多证据</button>
    <p v-if="bodyBusy" role="status">读取保存的正文…</p>
    <p v-if="bodyError" role="alert">{{ bodyError }} <button type="button" @click="read(selected)">重试读取正文</button></p>
    <KnowledgeEvidence v-if="content" :body="content" />
  </section>
</template>
<style scoped>
.evidence-note, time { color: var(--color-text-secondary); font-size: .85rem; }
ul { padding: 0; list-style: none; max-height: 20rem; overflow-y: auto; }
li { margin-block: .5rem; }
li button { display: block; width: 100%; text-align: left; overflow-wrap: anywhere; }
li button[aria-pressed="true"] { border-color: var(--color-primary); }
time { display: block; }
.workflow-knowledge-evidence { min-width: 0; overflow-wrap: anywhere; }
</style>
