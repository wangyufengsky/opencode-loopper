<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { workflowDocuments } from '@/api/workflowDocuments'
import type { WorkflowFile, WorkflowUpload, WorkflowUploadRequest } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
const props = defineProps<{ requirement: string; version: number; revision: number; value: string; title: string; disabled?: boolean }>()
const emit = defineEmits<{ change: [value: string]; busy: [value: boolean]; pending: [value: boolean] }>()
const chosen = ref<WorkflowUpload | null>(null), history = ref<WorkflowUpload[]>([]), cursor = ref<string | null>(null)
const busy = ref(false), error = ref(''), pending = ref<WorkflowUploadRequest | null>(null), incoming = ref<File[]>([])
const paths = ref<WorkflowFile[]>([]), pathCursor = ref<string | null>(null), preview = ref(''), previewPath = ref(''), nextOffset = ref<number | null>(null)
const selectedId = computed(() => { try { return String(JSON.parse(props.value || '{}').uploadId || '') } catch { return '' } })
watch(() => !!pending.value, value => emit('pending', value), { immediate: true, flush: 'sync' })
let alive = true, generation = 0
async function run(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; emit('busy', true); error.value = ''
  try { await action() } catch (failure) { if (alive) error.value = userFacingError(failure, '文档操作未完成，请重试或查看已上传资料。') }
  finally { if (alive) { busy.value = false; emit('busy', false) } }
}
function select(value: WorkflowUpload) {
  if (props.disabled || busy.value || !value.ready) return
  chosen.value = value; paths.value = []; preview.value = ''; pending.value = null; incoming.value = []
  emit('change', JSON.stringify(value.reference))
}
function chooseFiles(event: Event) {
  if (props.disabled || busy.value) return
  const files = Array.from((event.target as HTMLInputElement).files || [])
  if (!files.length) return
  incoming.value = files
  ;(event.target as HTMLInputElement).value = ''
  if (!pending.value) pending.value = { requestKey: crypto.randomUUID(), expectedVersion: props.version, expectedRevision: props.revision }
}
async function upload() {
  if (props.disabled || !incoming.value.length || !pending.value) return
  const input = [...incoming.value], metadata = { ...pending.value }, owner = props.requirement
  if (input.length > 10 || input.some(file => !file.size || file.size > 20 * 1024 * 1024) || input.reduce((size, file) => size + file.size, 0) > 50 * 1024 * 1024) { error.value = '请选择 1–10 份文档，单份不超过 20 MiB，合计不超过 50 MiB。'; return }
  await run(async () => {
    const result = await workflowDocuments.upload(owner, metadata, input)
    if (!alive || owner !== props.requirement) return
    if (!result.ready) throw new Error('原文件尚未完整保存，请保留本次文件并重试上传。')
    chosen.value = result; pending.value = null; incoming.value = []; paths.value = []; preview.value = ''
    emit('change', JSON.stringify(result.reference))
  })
}
async function load(more = false) {
  const owner = props.requirement
  await run(async () => { const result = await workflowDocuments.list(owner, more ? cursor.value || '' : ''); if (alive && owner === props.requirement) { history.value = more ? [...history.value, ...result.items] : result.items; cursor.value = result.nextCursor || null } })
}
function resume(row: WorkflowUpload) { if (props.disabled || busy.value || !row.resume) return; pending.value = { ...row.resume }; incoming.value = []; error.value = '请按原顺序重新选择这次上传的全部原文件，然后点击上传。' }
function newUpload() { if (props.disabled || busy.value) return; pending.value = null; incoming.value = []; error.value = '' }
async function loadFiles(more = false) {
  const selected = chosen.value
  if (!selected) return
  await run(async () => { const result = await workflowDocuments.files(props.requirement, selected.id, more ? pathCursor.value || '' : ''); if (alive && chosen.value?.id === selected.id) { paths.value = more ? [...paths.value, ...result.items] : result.items; pathCursor.value = result.nextCursor || null } })
}
async function read(path: string, more = false) {
  const selected = chosen.value
  if (!selected) return
  await run(async () => { const result = await workflowDocuments.text(props.requirement, selected.id, path, more ? nextOffset.value || 0 : 0); if (alive && chosen.value?.id === selected.id) { preview.value = more ? preview.value + result.text : result.text; previewPath.value = path; nextOffset.value = result.nextOffset } })
}
watch(() => [props.requirement, selectedId.value], async () => {
  const ticket = ++generation, owner = props.requirement, id = selectedId.value
  paths.value = []; preview.value = ''; chosen.value = null
  if (!id) return
  try { const result = await workflowDocuments.get(owner, id); if (alive && ticket === generation) chosen.value = result }
  catch (failure) { if (alive && ticket === generation) error.value = userFacingError(failure, '已选文档无法读取，请重新选择。') }
}, { immediate: true })
onBeforeUnmount(() => { alive = false; generation++; emit('busy', false); emit('pending', false) })
</script>
<template>
  <div class="workflow-document-input">
    <p v-if="chosen?.ready" role="status">已选 {{ chosen.originals.length }} 份文档</p>
    <button v-if="chosen" type="button" :disabled="disabled || busy" @click="emit('change', '')">取消选用</button>
    <ul v-if="chosen"><li v-for="file in chosen.originals" :key="file.path"><a :href="workflowDocuments.fileUrl(requirement, chosen.id, file.path)" download>{{ file.filename }}</a> · {{ file.sections }} 个章节<small v-for="limitation in file.limitations" :key="limitation">{{ limitation }}</small></li></ul>
    <label>选择{{ title }}<input type="file" accept=".docx,.md,.markdown,.pdf" multiple :disabled="disabled || busy" @change="chooseFiles" /></label>
    <p v-if="incoming.length">待上传：{{ incoming.map(file => file.name).join('、') }}</p>
    <p v-if="pending && !busy" role="status">本次上传尚未完成，文件与请求已保留。请上传并选用，或明确改为新上传后再关闭面板、离开或执行。</p>
    <small>DOCX、Markdown、文本 PDF；单份 20 MiB，合计 50 MiB。</small>
    <div class="workflow-document-actions"><button type="button" :disabled="disabled || busy || !incoming.length" @click="upload">{{ busy ? '处理中…' : '上传并选用' }}</button><button v-if="pending" type="button" :disabled="disabled || busy" @click="newUpload">改为新上传</button><button type="button" :disabled="busy" @click="load()">选择已上传资料</button></div>
    <p v-if="error" role="alert">{{ error }}</p>
    <ul v-if="history.length"><li v-for="row in history" :key="row.id"><span>{{ row.originals.map(file => file.filename).join('、') }}</span><small>{{ new Date(row.createdAt).toLocaleString() }} · {{ row.ready ? '已保存' : '等待补传' }}</small><button type="button" :disabled="disabled || busy" @click="row.ready ? select(row) : resume(row)">{{ row.ready ? '选用这份资料' : '补传原文件' }}</button></li></ul>
    <button v-if="cursor" type="button" :disabled="busy" @click="load(true)">更多上传记录</button>
    <button v-if="chosen?.ready" type="button" :disabled="busy" @click="loadFiles()">查看解析内容</button>
    <ul v-if="chosen && paths.length"><li v-for="file in paths.filter(file => file.path.startsWith('parsed/'))" :key="file.path"><button type="button" :disabled="busy" @click="read(file.path)">文档 {{ Number(file.path.split('/')[1]) }} · 第 {{ Number(file.path.split('/')[2]?.replace('.md', '')) }} 节</button></li></ul>
    <button v-if="pathCursor" type="button" :disabled="busy" @click="loadFiles(true)">更多章节</button>
    <div v-if="preview" class="workflow-document-preview"><MarkdownDocument :content="preview" :allow-images="false" /><button v-if="nextOffset !== null" type="button" :disabled="busy" @click="read(previewPath, true)">继续读取</button></div>
  </div>
</template>
<style scoped>
.workflow-document-input { display: grid; gap: .65rem; min-width: 0; }
.workflow-document-input ul { display: grid; gap: .65rem; padding-left: 1.1rem; margin: 0; overflow-wrap: anywhere; }
.workflow-document-input small { display: block; color: var(--color-text-muted); }
.workflow-document-actions { display: flex; flex-wrap: wrap; gap: .5rem; }
.workflow-document-preview { max-height: 24rem; overflow: auto; }
.workflow-document-input input { max-width: 100%; }
</style>
