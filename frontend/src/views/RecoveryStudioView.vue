<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import { api, ApiError } from '@/api/client'
import type { RecoveryDraft, RecoveryMode, Task } from '@/types/domain'
import { displayLabel, errorEventMessage, statusLabel, userFacingError } from '@/utils/displayLabels'

type FailureContext = { code: string; message: string; stageId?: string; at?: string }

const route = useRoute()
const router = useRouter()
const parentId = computed(() => String(route.params.id ?? ''))
const parent = ref<Task>()
const loading = ref(false)
const creating = ref(false)
const error = ref('')
const result = ref<RecoveryDraft>()
const recoveries = ref<RecoveryDraft[]>([])
const mode = ref<RecoveryMode>('FROM_FAILED_STAGE')

const eligible = computed(() => parent.value?.status === 'FAILED' || parent.value?.status === 'CANCELLED')
const failureContexts = computed<FailureContext[]>(() => parent.value?.errors ?? [])
const selectedStage = computed(() => {
  const stages = parent.value?.stages ?? []
  return stages.find((stage) => stage.status === 'BLOCKED')
    ?? stages.find((stage) => stage.status === 'RUNNING' || stage.status === 'PAUSED')
    ?? stages.find((stage) => stage.status !== 'SUCCEEDED')
    ?? stages.at(-1)
})
async function load() {
  if (!parentId.value) return
  loading.value = true
  error.value = ''
  result.value = undefined
  try {
    const [task, lineage] = await Promise.all([api.getTask(parentId.value), api.getTaskRecoveries(parentId.value)])
    parent.value = task
    recoveries.value = lineage
  } catch (cause) {
    parent.value = undefined
    error.value = userFacingError(cause, '无法读取失败上下文')
  } finally {
    loading.value = false
  }
}

async function createRecovery() {
  if (!eligible.value || !parent.value) return
  creating.value = true
  error.value = ''
  try {
    result.value = await api.createTaskRecovery(parent.value.id, mode.value)
    recoveries.value = [result.value, ...recoveries.value.filter((item) => item.taskId !== result.value?.taskId)]
  } catch (cause) {
    const prefix = cause instanceof ApiError && cause.status === 409 ? '恢复被安全阻止（409）' : '恢复草稿创建失败'
    const code = cause instanceof ApiError && cause.code ? ` · ${cause.code}` : ''
    error.value = `${prefix}${displayLabel(code)}：${userFacingError(cause, '恢复创建失败')}`
  } finally {
    creating.value = false
  }
}

watch(parentId, load, { immediate: true })
</script>

<template>
  <PageHeader eyebrow="任务恢复" :title="parent ? `恢复工作台 · ${parent.title}` : '恢复工作台'" :title-tooltip="parent?.goal">
    <template #actions>
      <el-button plain :loading="loading" @click="load"><Icon icon="lucide:refresh-cw" />刷新上下文</el-button>
      <el-button plain @click="router.push(`/tasks/${parentId}`)"><Icon icon="lucide:arrow-left" />返回父任务</el-button>
    </template>
  </PageHeader>

  <main id="main-content" class="content recovery-studio" tabindex="-1">
    <section v-if="loading" class="card card-pad" aria-live="polite"><div class="skeleton-block" style="height: 230px" /></section>
    <section v-else-if="error && !parent" class="card empty-state recovery-empty" role="status"><div><Icon icon="lucide:triangle-alert" width="30" /><strong>无法读取恢复上下文</strong><p>{{ userFacingError(error, '恢复上下文读取失败') }}</p></div></section>
    <section v-else-if="parent" class="recovery-layout">
      <article class="card context-card">
        <header class="section-header"><div><p class="eyebrow">失败上下文</p><h2>父任务</h2></div><span :class="['terminal-chip', `is-${parent.status.toLowerCase()}`]">{{ statusLabel(parent.status) }}</span></header>
        <div class="context-body">
          <p class="goal">{{ parent.goal || '该任务未保留额外目标说明。' }}</p>
          <dl class="context-facts"><div><dt>项目</dt><dd>{{ parent.projectName || '未命名项目' }}</dd></div><div><dt>恢复起点</dt><dd>{{ selectedStage ? `阶段 ${selectedStage.ordinal} · ${selectedStage.objective}` : '没有可复制阶段' }}</dd></div></dl>
          <div v-if="failureContexts.length" class="failure-list"><article v-for="(failure, index) in failureContexts" :key="`${failure.code}-${index}`"><Icon icon="lucide:circle-x" /><div><strong>{{ displayLabel(failure.code) }}</strong><p>{{ errorEventMessage(failure.code, failure.message) }}</p></div></article></div>
        </div>
      </article>

      <article class="card mode-card">
        <div v-if="!eligible" class="ineligible"><Icon icon="lucide:lock-keyhole" /><strong>当前任务不可恢复</strong></div>
        <template v-else>
          <div class="mode-options" role="radiogroup" aria-label="恢复模式">
            <label v-for="item in (['FROM_FAILED_STAGE', 'ALL_STAGES', 'VERIFY_ONLY'] as RecoveryMode[])" :key="item" :class="['mode-option', { selected: mode === item }]">
              <input v-model="mode" type="radio" :value="item" />
              <span><b>{{ item === 'FROM_FAILED_STAGE' ? '从失败阶段恢复' : item === 'ALL_STAGES' ? '复制全部阶段' : '只读验证' }}</b></span>
            </label>
          </div>
          <el-button type="primary" :loading="creating" @click="createRecovery"><Icon icon="lucide:git-branch-plus" />创建恢复草稿</el-button>
        </template>
      </article>

      <article v-if="result || recoveries.length" class="card lineage-card" aria-live="polite">
        <header class="section-header"><div><p class="eyebrow">恢复记录</p><h2>{{ result ? '恢复草稿已创建' : '已创建的恢复草稿' }}</h2></div><Icon icon="lucide:badge-check" width="22" /></header>
        <section v-for="item in recoveries" :key="item.taskId" class="lineage-entry">
          <dl><div><dt>恢复方式</dt><dd>{{ displayLabel(item.mode) }}</dd></div><div><dt>执行权限</dt><dd>{{ item.writableSession ? '可执行修改' : '只读验证' }}</dd></div></dl>
          <el-button plain @click="router.push(`/tasks/${item.taskId}`)"><Icon icon="lucide:external-link" />打开派生任务</el-button>
        </section>
      </article>
      <section v-if="error" class="error-panel recovery-error" role="status"><Icon icon="lucide:shield-alert" /><div><h3>恢复未创建</h3><p>{{ userFacingError(error, '恢复草稿创建失败') }}</p></div></section>
    </section>
  </main>
</template>

<style scoped>
.recovery-layout { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(330px, .9fr); gap: 16px; }.context-card, .mode-card, .lineage-card { overflow: hidden; background: linear-gradient(145deg, rgb(14 24 43 / 92%), rgb(5 10 22 / 86%)); box-shadow: inset 0 1px rgb(255 255 255 / 5%), 0 18px 44px rgb(0 0 0 / 18%); }.section-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; padding: 18px 19px 14px; border-bottom: 1px solid var(--color-border-default); }.section-header h2 { margin: 4px 0 0; font-size: 15px; }.section-header > svg { color: var(--color-accent-cyan); }.terminal-chip { padding: 5px 7px; border: 1px solid rgb(148 163 184 / 30%); border-radius: 6px; color: var(--color-text-secondary); background: rgb(148 163 184 / 8%); font: 700 8px/1 var(--font-code); }.terminal-chip.is-failed { border-color: rgb(248 113 113 / 42%); color: #fca5a5; background: rgb(239 68 68 / 10%); }.terminal-chip.is-cancelled { border-color: rgb(251 191 36 / 38%); color: #fde68a; background: rgb(245 158 11 / 10%); }.context-body { padding: 17px 19px 19px; }.goal { margin: 0; color: var(--color-text-primary); font-size: 12px; line-height: 1.65; white-space: pre-wrap; }.context-facts { display: grid; grid-template-columns: 1fr 1.4fr; gap: 1px; margin: 15px 0; background: var(--color-border-default); }.context-facts div { min-width: 0; padding: 10px; background: rgb(2 6 23 / 46%); }.context-facts dt, .lineage-card dt { color: var(--color-text-muted); font: 700 8px/1.3 var(--font-code); letter-spacing: .08em; text-transform: uppercase; }.context-facts dd, .lineage-card dd { margin: 5px 0 0; overflow-wrap: anywhere; color: var(--color-text-secondary); font-size: 10px; line-height: 1.45; }.failure-list { display: grid; gap: 7px; }.failure-list article { display: flex; gap: 8px; padding: 10px; border: 1px solid rgb(239 68 68 / 20%); border-radius: 8px; background: rgb(127 29 29 / 10%); }.failure-list > article > svg { flex: 0 0 auto; margin-top: 1px; color: #f87171; }.failure-list code { color: #fca5a5; font-size: 9px; }.failure-list p { margin: 4px 0 0; color: var(--color-text-secondary); font-size: 10px; line-height: 1.5; }.no-failures, .mode-description, .safety-note { display: flex; align-items: flex-start; gap: 7px; margin: 0; color: var(--color-text-secondary); font-size: 10px; line-height: 1.55; }.no-failures svg, .mode-description svg, .safety-note svg { flex: 0 0 auto; margin-top: 1px; color: var(--color-accent-cyan); }.mode-card { padding-bottom: 18px; }.mode-options { display: grid; gap: 8px; padding: 16px 18px 11px; }.mode-option { display: flex; align-items: center; gap: 10px; padding: 11px; border: 1px solid var(--color-border-default); border-radius: 9px; cursor: pointer; background: rgb(2 6 23 / 35%); }.mode-option.selected { border-color: rgb(34 211 238 / 52%); background: rgb(34 211 238 / 8%); box-shadow: 0 0 18px rgb(34 211 238 / 8%); }.mode-option input { accent-color: #22d3ee; }.mode-option b { display: block; color: var(--color-text-primary); font-size: 11px; }.mode-option small { display: block; margin-top: 4px; color: var(--color-text-muted); font: 8px/1 var(--font-code); }.mode-description, .safety-note { margin: 0 18px 10px; padding: 10px; border-radius: 8px; background: rgb(34 211 238 / 5%); }.safety-note { color: #c4b5fd; background: rgb(139 92 246 / 7%); }.safety-note svg { color: #c4b5fd; }.mode-card :deep(.el-button) { margin: 5px 18px 0; }.ineligible { display: grid; place-items: center; min-height: 240px; padding: 24px; color: var(--color-text-muted); text-align: center; }.ineligible svg { color: #fbbf24; }.ineligible strong { margin-top: 9px; color: var(--color-text-primary); font-size: 13px; }.ineligible p { max-width: 280px; margin: 7px 0 0; font-size: 10px; line-height: 1.55; }.lineage-card { grid-column: 1 / -1; }.lineage-entry + .lineage-entry { border-top: 1px solid var(--color-border-default); }.lineage-flow { display: flex; align-items: center; gap: 10px; padding: 18px 19px; color: var(--color-accent-cyan); }.lineage-flow code { max-width: 42%; overflow: hidden; color: var(--color-text-secondary); text-overflow: ellipsis; white-space: nowrap; font-size: 10px; }.lineage-card dl { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 1px; margin: 0; background: var(--color-border-default); }.lineage-card dl div { min-width: 0; padding: 11px; background: rgb(2 6 23 / 46%); }.fingerprint { font-family: var(--font-code); }.lineage-card :deep(.el-button) { margin: 15px 19px 19px; }.recovery-error { grid-column: 1 / -1; }.recovery-empty { min-height: 300px; }@media (max-width: 900px) { .recovery-layout { grid-template-columns: 1fr; }.lineage-card { grid-column: auto; }.lineage-card dl { grid-template-columns: repeat(2, minmax(0, 1fr)); } }@media (max-width: 560px) { .context-facts, .lineage-card dl { grid-template-columns: 1fr; } }
</style>
