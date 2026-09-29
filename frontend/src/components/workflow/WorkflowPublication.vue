<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { workflowPublication } from '@/api/workflowPublication'
import { userFacingError, workflowStateLabel } from '@/utils/displayLabels'
import type { WorkflowPublicationSource, WorkflowPublicationPreview, WorkflowWritebackPreview as WritebackPreview } from '@/types/domain'
import WorkflowCodeChanges from './WorkflowCodeChanges.vue'
import WorkflowPublicationCommit from './WorkflowPublicationCommit.vue'
import WorkflowWritebackPreview from './WorkflowWritebackPreview.vue'
import WorkflowWriteback from './WorkflowWriteback.vue'
import StatusBadge from '@/components/StatusBadge.vue'
const props = defineProps<{ requirement: string; revision: number; disabled?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean] }>()
const commitBusy = ref(false), commitPanel = ref<InstanceType<typeof WorkflowPublicationCommit>>()
const writebackBusy = ref(false), writebackRecorded = ref(false), writebackPanel = ref<InstanceType<typeof WorkflowWriteback>>(), checked = ref<WritebackPreview | null>(null)
const publicationBusy = computed(() => commitBusy.value || writebackBusy.value)
watch(publicationBusy, value => emit('busy', value))
const opened = ref(false), rows = ref<WorkflowPublicationSource[]>([]), cursor = ref<string | null>(null), loaded = ref(false)
const listing = ref(false), reading = ref(false), listError = ref(''), readError = ref(''), selected = ref<WorkflowPublicationSource | null>(null), preview = ref<WorkflowPublicationPreview | null>(null)
let generation = 0, selection = 0, listAbort: AbortController | undefined, readAbort: AbortController | undefined, failedMore = false
const key = (source: WorkflowPublicationSource) => `${source.attemptId}:${source.outputName}`
function reset() { checked.value = null; generation++; selection++; listAbort?.abort(); readAbort?.abort(); rows.value = []; cursor.value = null; loaded.value = false; listing.value = false; reading.value = false; listError.value = ''; readError.value = ''; selected.value = null; preview.value = null }
async function load(more = false) {
  if (listing.value || props.disabled || publicationBusy.value) return
  if (!more) reset()
  const current = generation; listAbort = new AbortController(); listing.value = true; listError.value = ''; failedMore = more
  try {
    const page = await workflowPublication.sources(props.requirement, props.revision, more ? cursor.value || '' : '', listAbort.signal)
    if (current === generation) { rows.value = more ? [...rows.value, ...page.items] : page.items; cursor.value = page.nextCursor || null; loaded.value = true }
  } catch (failure) { if (current === generation) listError.value = userFacingError(failure, '代码成果暂时无法读取，请刷新后重试。') }
  finally { if (current === generation) listing.value = false }
}
async function choose(source: WorkflowPublicationSource) {
  if (props.disabled || publicationBusy.value) return
  const current = generation, chosen = ++selection; readAbort?.abort(); readAbort = new AbortController(); selected.value = source; preview.value = null; checked.value = null; readError.value = ''; reading.value = true
  try {
    const result = await workflowPublication.preview(props.requirement, props.revision, source, readAbort.signal)
    if (current === generation && chosen === selection) {
      if (result.requirementId !== props.requirement || result.planRevision !== props.revision || key(result.source) !== key(source) || result.source.nodeKey !== source.nodeKey) throw new Error('成果版本已变化，请刷新后重新选择。')
      preview.value = result
    }
  } catch (failure) { if (current === generation && chosen === selection) readError.value = userFacingError(failure, '所选成果暂时无法预览，请重试。') }
  finally { if (current === generation && chosen === selection) reading.value = false }
}
function toggle() { if (props.disabled || publicationBusy.value) return; opened.value = !opened.value; if (opened.value) void load(); else reset() }
defineExpose({ canLeave: () => (!commitPanel.value || commitPanel.value.canLeave()) && (!writebackPanel.value || writebackPanel.value.canLeave()) })
watch(() => [props.requirement, props.revision], () => { reset(); if (opened.value) void load() })
onBeforeUnmount(reset)
</script>
<template>
  <section class="workflow-publication" aria-label="需求代码成果">
    <button :disabled="disabled || publicationBusy" :aria-expanded="opened" @click="toggle">{{ opened ? '收起代码成果' : '查看代码成果' }}</button>
    <div v-if="opened" class="publication-panel">
      <header><div><h2>选择代码成果</h2><p>查看当前计划中已停止节点保存的代码。每份成果包含继承的上游改动，不自动合并多个版本。</p></div><button :disabled="disabled || listing || publicationBusy" @click="load()">刷新成果</button></header>
      <p v-if="listing && !loaded" role="status">正在读取代码成果…</p>
      <p v-if="loaded && !rows.length">当前计划还没有已保存且停止执行的代码成果。</p>
      <div class="publication-layout">
        <div class="publication-sources"><button v-for="source in rows" :key="key(source)" :disabled="disabled || publicationBusy" :aria-pressed="!!selected && key(source) === key(selected)" @click="choose(source)"><strong>{{ source.nodeTitle }} · {{ source.outputTitle }}</strong><span>第 {{ source.ordinal }} 次执行 · {{ source.changedFiles }} 个改动文件</span><StatusBadge :status="source.attemptState" :label="workflowStateLabel(source.attemptState)" /></button></div>
        <section v-if="selected" class="publication-preview" aria-label="所选代码成果">
          <p v-if="reading" role="status">正在核对所选成果…</p>
          <p v-if="readError" role="alert">{{ readError }} <button :disabled="disabled || reading" @click="choose(selected)">重试预览</button></p>
          <template v-if="preview"><h3>{{ preview.source.nodeTitle }} · {{ preview.source.outputTitle }}</h3><p>{{ preview.workspaceKind === 'GIT' ? 'Git 项目成果' : '普通目录成果' }}<span v-if="preview.workspaceKind === 'GIT' && preview.sourceBranch"> · 来源分支 {{ preview.sourceBranch }}</span></p>
            <p v-if="preview.source.attemptState === 'FAILED'" class="publication-failure">该节点执行失败；这里展示已经保存的代码及实际执行结果。</p>
            <p>新增 {{ preview.added }} · 修改 {{ preview.modified }} · 删除 {{ preview.deleted }}</p><p>固定版本共 {{ preview.source.totalFiles }} 个文件 · {{ (preview.totalBytes / 1024).toFixed(1) }} KiB</p>
            <WorkflowCodeChanges :key="`${preview.sha256}:${key(preview.source)}`" :requirement="requirement" :node="preview.source.nodeKey" :attempt="preview.source.attemptId" direction="outputs" :name="preview.source.outputName" />
            <WorkflowWritebackPreview v-if="!writebackRecorded" :source="preview" :disabled="disabled || publicationBusy" @checked="checked = $event" />
          </template>
        </section>
      </div>
      <p v-if="listError" role="alert">{{ listError }} <button :disabled="disabled || listing || publicationBusy" @click="load(failedMore)">重试读取成果</button></p>
      <button v-else-if="cursor" :disabled="disabled || listing || publicationBusy" @click="load(true)">{{ listing ? '读取成果…' : '更多代码成果' }}</button>
      <WorkflowPublicationCommit ref="commitPanel" :requirement="requirement" :preview="preview" :disabled="disabled || reading || listing || writebackBusy" @busy="value => commitBusy = value" />
      <WorkflowWriteback ref="writebackPanel" :requirement="requirement" :source="preview" :checked="checked" :disabled="disabled || reading || listing || commitBusy" @busy="writebackBusy = $event" @recorded="writebackRecorded = $event" />
    </div>
  </section>
</template>
<style scoped>
.workflow-publication { margin-block: .75rem; }
.publication-panel { margin-top: .75rem; padding: 1rem; border: 1px solid var(--color-border-default); border-radius: var(--radius-control); background: var(--color-bg-surface); overflow-wrap: anywhere; }
header { display: flex; align-items: flex-start; gap: 1rem; justify-content: space-between; } header h2 { margin: 0; font-size: 1.05rem; } header p { color: var(--color-text-secondary); }
.publication-layout { display: grid; grid-template-columns: minmax(12rem, 1fr) minmax(0, 2fr); gap: 1rem; }
.publication-sources { display: grid; align-content: start; gap: .5rem; }
.publication-sources button { display: flex; align-items: flex-start; flex-direction: column; white-space: normal; text-align: left; gap: .35rem; }
.publication-sources button[aria-pressed='true'] { outline: 2px solid var(--color-action-primary); }
.publication-preview { min-width: 0; } .publication-preview :deep(a) { text-decoration: underline; text-underline-offset: .15em; } .publication-failure { color: var(--color-text-secondary); }
@media(max-width: 720px) { header { flex-direction: column; } .publication-layout { grid-template-columns: 1fr; } }
</style>
