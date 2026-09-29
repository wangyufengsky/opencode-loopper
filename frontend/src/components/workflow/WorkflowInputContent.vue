<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowInputs } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import CodeMergeEditor from '@/components/CodeMergeEditor.vue'
import WorkflowReviewReport from './WorkflowReviewReport.vue'
import WorkflowKnowledgeReport from './WorkflowKnowledgeReport.vue'
import { knowledgeBundle } from './knowledgeBundle'
const props = defineProps<{ requirement: string; node: string; attempt: string; input: WorkflowInputs['values'][number]; review?: boolean }>()
const text = ref(''), loaded = ref(false), next = ref<number | null>(0), total = ref(0), busy = ref(false), error = ref('')
const complete = computed(() => loaded.value && next.value === null)
const structured = computed(() => { if (!complete.value || props.input.kind === 'TEXT') return null; try { return JSON.parse(text.value) as unknown } catch { return null } })
let generation = 0, controller: AbortController | undefined
function reset() { generation++; controller?.abort(); text.value = ''; loaded.value = false; next.value = 0; total.value = 0; busy.value = false; error.value = '' }
watch(() => [props.requirement, props.node, props.attempt, props.input.name, props.input.sha256], reset)
async function load() {
  if (busy.value || next.value === null) return
  const ticket = generation, offset = next.value
  controller = new AbortController(); busy.value = true; error.value = ''
  try {
    const page = await workflowRuns.inputContent(props.requirement, props.node, props.attempt, props.input.name, offset, controller.signal)
    if (ticket !== generation) return
    const end = offset + page.text.length
    if (page.name !== props.input.name || page.kind !== props.input.kind || page.sha256 !== props.input.sha256 || page.offset !== offset
      || !Number.isInteger(page.totalLength) || page.totalLength < end || page.totalLength > 307200 || loaded.value && page.totalLength !== total.value
      || (page.nextOffset === null ? end !== page.totalLength : page.nextOffset !== end || end <= offset || end >= page.totalLength))
      throw new Error('返回的正文与固定输入不一致，请重试读取。')
    text.value += page.text; next.value = page.nextOffset; total.value = page.totalLength; loaded.value = true
  } catch (failure) { if (ticket === generation) error.value = userFacingError(failure, '固定输入正文暂时无法读取，请重试。') }
  finally { if (ticket === generation) busy.value = false }
}
onBeforeUnmount(() => { generation++; controller?.abort() })
</script>
<template>
  <div class="workflow-input-content" :aria-busy="busy">
    <button v-if="!loaded && !error" :disabled="busy" @click="load">{{ busy ? '读取正文…' : '查看固定版本正文' }}</button>
    <p v-if="error" class="workflow-error" role="alert">{{ error }}<button :disabled="busy" @click="load">重试读取</button></p>
    <template v-if="loaded">
      <p v-if="!complete" role="status">正文尚未读完，已读取 {{ text.length }} / {{ total }} 字符。</p>
      <MarkdownDocument v-if="complete && input.kind === 'TEXT'" :content="text" :allow-images="false" />
      <WorkflowReviewReport v-else-if="complete && review && structured" :content="structured" />
      <WorkflowKnowledgeReport v-else-if="complete && knowledgeBundle(structured)" :content="structured" />
      <CodeMergeEditor v-else-if="complete && structured" :model-value="JSON.stringify(structured, null, 2)" language="json" readonly />
      <pre v-else>{{ text }}</pre>
      <button v-if="!complete && !error" :disabled="busy" @click="load">{{ busy ? '读取正文…' : '继续读取正文' }}</button>
    </template>
  </div>
</template>
<style scoped>
.workflow-input-content { min-width: 0; }
pre { max-height: 24rem; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font-family: var(--font-mono); }
</style>
