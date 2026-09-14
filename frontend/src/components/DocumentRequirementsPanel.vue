<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { DocumentClarification, DocumentTemplateOverview, DocumentRequirementDetail, DocumentRequirementSummary, DocumentSection, DocumentRequirementSource } from '@/types/domain'
import { requirementConclusionLabel, requirementKindLabel, userFacingError } from '@/utils/displayLabels'
import MarkdownDocument from '@/components/MarkdownDocument.vue'
import DocumentClarificationForm from '@/components/DocumentClarificationForm.vue'
const props = defineProps<{ run: DocumentTemplateOverview }>()
const emit = defineEmits<{ updated: [run: DocumentTemplateOverview] }>()
const clarifications = ref<DocumentClarification[]>()
async function history() {
  const current = generation
  try { const answers = await api.documentClarifications(props.run.id, props.run.requirementRevision); if (current === generation) clarifications.value = answers }
  catch (failure) { if (current === generation) error.value = userFacingError(failure, '澄清记录读取失败，请重试') }
}
const items = ref<DocumentRequirementSummary[]>([])
const next = ref<number | null>(null)
const issuesOnly = ref(false)
const loading = ref(false)
const error = ref('')
const selected = ref('')
const details = ref<Record<string, DocumentRequirementDetail>>({})
const detailLoading = ref(false)
const source = ref<DocumentSection>()
let generation = 0
let detailGeneration = 0
async function load(append = false) {
  const current = generation
  loading.value = true; error.value = ''
  try {
    const page = await api.documentRequirements(props.run.id, props.run.requirementRevision, append ? next.value ?? -1 : -1, issuesOnly.value)
    if (current !== generation) return
    items.value = append ? [...items.value, ...page.items] : page.items; next.value = page.nextOffset
  } catch (failure) { if (current === generation) error.value = userFacingError(failure, '需求清单读取失败，请重试') }
  finally { if (current === generation) loading.value = false }
}
watch(() => [props.run.id, props.run.requirementRevision, issuesOnly.value], () => {
  ++generation; ++detailGeneration; clarifications.value = undefined; items.value = []; next.value = null; details.value = {}; selected.value = ''; source.value = undefined
  void load()
}, { immediate: true })
async function expand(key: string) {
  selected.value = selected.value === key ? '' : key; source.value = undefined
  const current = ++detailGeneration
  if (!selected.value || details.value[key]) { detailLoading.value = false; return }
  detailLoading.value = true; error.value = ''
  try {
    const value = await api.documentRequirement(props.run.id, props.run.requirementRevision, key)
    if (current === detailGeneration) details.value[key] = value
  } catch (failure) { if (current === detailGeneration) error.value = userFacingError(failure, '需求正文读取失败，请重试') }
  finally { if (current === detailGeneration) detailLoading.value = false }
}
function filename(id: string) { return props.run.files.find(file => file.id === id)?.filename ?? '来源文档' }
async function openSource(ref: DocumentRequirementSource) {
  const file = props.run.files.find(file => file.id === ref.fileId)
  if (!file) return
  const current = detailGeneration
  try {
    const result = await api.documentSection(props.run.id, file.id, ref.section, file.sha256)
    if (current === detailGeneration) source.value = result
  } catch (failure) { if (current === detailGeneration) error.value = userFacingError(failure, '原文读取失败，请刷新来源清单') }
}
onBeforeUnmount(() => { ++generation; ++detailGeneration })
</script>
<template>
  <section class="card card-pad requirements-panel" aria-label="需求清单">
    <header><h2>需求清单 · {{ run.progress.requirements }}</h2><el-checkbox v-model="issuesOnly">仅看待澄清事项</el-checkbox></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button text @click="load()">重新读取</el-button></el-alert>
    <p v-if="!items.length" class="muted">{{ loading ? '正在读取需求…' : issuesOnly ? '当前版本没有待澄清事项' : '尚未形成经原文复核的需求清单' }}</p>
    <el-button v-if="run.requirementRevision > 1 && !clarifications" text @click="history">查看本版采用的业务回答</el-button>
    <details v-if="clarifications?.length"><summary>业务澄清记录 · {{ clarifications.length }} 项</summary>
      <article v-for="(entry, index) in clarifications" :key="index"><p>需求版本 {{ entry.sourceRevision }}：{{ entry.statement }}</p><blockquote>{{ entry.answer }}</blockquote></article>
    </details>
    <article v-for="item in items" :key="item.requirementKey" class="requirement-item">
      <button type="button" class="requirement-heading" :aria-expanded="selected === item.requirementKey" @click="expand(item.requirementKey)">
        <span><strong>{{ item.title }}</strong><small class="muted">{{ item.groupName }} · {{ requirementKindLabel(item.kind) }}</small></span>
        <span>{{ requirementConclusionLabel(item.conclusion) }}<small v-if="item.issueCount"> · {{ item.issueCount }} 项待澄清</small></span>
      </button>
      <div v-if="selected === item.requirementKey" class="requirement-body">
        <p v-if="detailLoading" class="muted">正在读取需求与证据…</p>
        <template v-if="details[item.requirementKey]">
          <p>{{ details[item.requirementKey]!.requirement.statement }}</p>
          <ul><li v-for="(acceptance, index) in details[item.requirementKey]!.requirement.acceptance" :key="index">{{ acceptance }}</li></ul>
          <el-alert v-for="(issue, index) in details[item.requirementKey]!.requirement.issues" :key="index" :title="issue" type="warning" :closable="false" />
          <DocumentClarificationForm v-if="run.templateId === 'REQUIREMENT_DEVELOPMENT' && run.state === 'WAITING_INPUT' && (!run.designerId || run.waitingReasonCode === 'DOCUMENT_SUPPLEMENT_BUSINESS_INPUT') && item.issueCount > 0"
            :run="run" :requirement-key="item.requirementKey" @updated="emit('updated', $event)" />
          <details open><summary>原文依据</summary>
            <div v-for="(ref, index) in details[item.requirementKey]!.requirement.sources" :key="index">
              <el-button text @click="openSource(ref)">{{ filename(ref.fileId) }} · 分段 {{ ref.section + 1 }}</el-button><blockquote>{{ ref.quote }}</blockquote>
            </div>
          </details>
          <div v-if="details[item.requirementKey]!.assessment">
            <p>{{ details[item.requirementKey]!.assessment!.rationale }}</p>
            <p>测试源码：{{ details[item.requirementKey]!.assessment!.testSourceCoverage }}；本次未执行测试。</p>
            <details><summary>代码证据与已检查范围</summary>
              <div v-for="(ref, index) in details[item.requirementKey]!.assessment!.evidence" :key="index"><code>{{ ref.path }}:{{ ref.startLine }}–{{ ref.endLine }}</code><pre>{{ ref.quote }}</pre></div>
              <ul><li v-for="path in details[item.requirementKey]!.assessment!.checkedPaths" :key="path">{{ path }}</li></ul>
              <p v-if="details[item.requirementKey]!.assessment!.missingEntryEvidence">{{ details[item.requirementKey]!.assessment!.missingEntryEvidence }}</p>
            </details>
            <ul><li v-for="(limit, index) in details[item.requirementKey]!.assessment!.limitations" :key="index">{{ limit }}</li></ul>
          </div>
          <details v-if="source" open><summary>原文分段 · {{ source.title }}</summary><MarkdownDocument :content="source.content" /></details>
        </template>
      </div>
    </article>
    <el-button v-if="next !== null" :loading="loading" @click="load(true)">加载更多需求</el-button>
  </section>
</template>
<style scoped>
.requirements-panel { display: grid; gap: 16px; min-width: 0; }
header, .requirement-heading { display: flex; justify-content: space-between; gap: 16px; align-items: start; flex-wrap: wrap; }
h2 { margin: 0; font-size: 18px; }
.requirement-item { border-top: 1px solid var(--color-border-default); padding-top: 12px; }
.requirement-heading { border: 0; background: transparent; color: var(--color-text-primary); font: inherit; width: 100%; text-align: left; padding: 8px 0; cursor: pointer; }
.requirement-heading > span:first-child { display: grid; gap: 6px; }.requirement-body { line-height: 1.8; overflow-wrap: anywhere; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; }summary { cursor: pointer; }.requirement-heading:focus-visible { outline: 2px solid var(--color-action-primary); }
</style>
