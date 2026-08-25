<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import AiActivityPanel from '@/components/AiActivityPanel.vue'
import { api } from '@/api/client'
import type { ArtifactKind, DesignerActivity, DesignerSession, TaskIntent } from '@/types/domain'
import {
  artifactKindLabel,
  executionStrategyLabel,
  rolePackLabel,
  statusLabel,
  taskIntentLabel,
  testPolicyLabel,
  userFacingError,
  workflowTemplateLabel,
} from '@/utils/displayLabels'

const props = defineProps<{ modelValue: boolean; session: DesignerSession; busy?: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: []
  reroute: []
  save: [value: { intent: TaskIntent; artifact: ArtifactKind; largeTaskMode: boolean; componentKeys: string[] }]
}>()

const activity = ref<DesignerActivity>()
const activityLoading = ref(false)
const activityError = ref('')
const now = ref(Date.now())
const editing = ref(false)
const intent = ref<TaskIntent>('SOFTWARE_CHANGE')
const artifact = ref<ArtifactKind>('SOURCE_CODE')
const largeTaskMode = ref(false)
const componentKeys = ref<string[]>([])
let pollTimer: ReturnType<typeof setTimeout> | undefined
let clockTimer: ReturnType<typeof setInterval> | undefined
let generation = 0

const run = computed(() => props.session.routerRun)
const profile = computed(() => props.session.taskProfile)
const running = computed(() => run.value?.state === 'PENDING' || run.value?.state === 'RUNNING')
const failed = computed(() => run.value?.state === 'FAILED'
  || profile.value.evidence.some(item => item.startsWith('router-error=')))
const elapsedSeconds = computed(() => Math.max(0, Math.floor((now.value - Date.parse(run.value?.createdAt ?? '')) / 1_000) || 0))
const limitSeconds = computed(() => {
  const start = Date.parse(run.value?.createdAt ?? '')
  const end = Date.parse(run.value?.deadlineAt ?? '')
  return Number.isFinite(start) && Number.isFinite(end) ? Math.max(1, Math.round((end - start) / 1_000)) : 240
})
const stateText = computed(() => `${statusLabel(run.value?.externalState || run.value?.state || 'PENDING')} · 已用 ${elapsedSeconds.value} 秒 / 上限 ${limitSeconds.value} 秒`)
const affectedComponents = computed(() => {
  const candidates = profile.value.candidateComponents ?? []
  const selected = new Set(profile.value.componentKeys ?? [])
  const labels = candidates.filter(item => selected.has(item.key))
    .map(item => item.relativeRoot === '.' ? '项目根目录' : item.relativeRoot)
  return labels.length ? labels.join('、') : profile.value.componentKeys?.join('、') || '待确认'
})

function resetForm() {
  intent.value = profile.value.intent
  artifact.value = profile.value.artifactKinds[0] ?? 'OTHER'
  largeTaskMode.value = profile.value.largeTaskMode
  componentKeys.value = [...(profile.value.componentKeys ?? [])]
  editing.value = false
}

function stopPolling() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = undefined
}

async function refreshActivity(current = generation) {
  if (!props.modelValue || !running.value) return
  activityLoading.value = true
  try {
    const next = await api.getDesignerActivity(props.session.id)
    if (current !== generation) return
    const previous = activity.value?.parts.at(-1)
    activity.value = !next.connected && !next.parts.length && previous
      ? { ...next, parts: [previous] }
      : { ...next, parts: next.parts.length ? [next.parts.at(-1)!] : [] }
    activityError.value = ''
  } catch (error) {
    if (current !== generation) return
    activityError.value = userFacingError(error, '识别活动暂时无法刷新')
  } finally {
    if (current === generation) {
      activityLoading.value = false
      if (props.modelValue && running.value) pollTimer = setTimeout(() => void refreshActivity(current), 1_200)
    }
  }
}

function restartActivity() {
  generation += 1
  stopPolling()
  activity.value = undefined
  activityError.value = ''
  now.value = Date.now()
  if (clockTimer) clearInterval(clockTimer)
  clockTimer = setInterval(() => { now.value = Date.now() }, 1_000)
  if (props.modelValue && running.value) void refreshActivity(generation)
}

function beforeClose(done: () => void) {
  if (!running.value) done()
}

function componentLabel(component: NonNullable<DesignerSession['taskProfile']['candidateComponents']>[number]) {
  const stack = component.technologies.length ? component.technologies.join(' / ') : '通用'
  return `${component.relativeRoot === '.' ? '项目根目录' : component.relativeRoot} · ${stack}`
}

watch(() => `${props.modelValue}:${run.value?.id ?? ''}:${run.value?.state ?? ''}`, restartActivity, { immediate: true })
watch(() => `${profile.value.id ?? ''}:${profile.value.version}`, resetForm, { immediate: true })
watch(intent, value => { if (value !== 'SOFTWARE_CHANGE') largeTaskMode.value = false })
onBeforeUnmount(() => { generation += 1; stopPolling(); if (clockTimer) clearInterval(clockTimer) })
</script>

<template>
  <el-dialog :model-value="modelValue" width="760px" append-to-body destroy-on-close
    :title="running ? '任务设置识别中' : '任务设置识别结果'"
    :close-on-click-modal="!running" :close-on-press-escape="!running" :show-close="!running"
    :before-close="beforeClose" class="task-profile-router-dialog"
    @update:model-value="emit('update:modelValue', $event)">
    <template v-if="running">
      <p class="router-intro">正在结合需求快照与项目技术栈识别任务设置。这里显示真实活动和用时，不使用估算百分比。</p>
      <AiActivityPanel title="需求分析师正在识别" panel-label="任务设置识别活动"
        :state-text="stateText" :activity="activity" :loading="activityLoading" :error="activityError" />
      <dl class="router-runtime">
        <div><dt>远端状态</dt><dd>{{ statusLabel(run?.externalState || run?.state || 'PENDING') }}</dd></div>
        <div><dt>已用时间</dt><dd>{{ elapsedSeconds }} 秒</dd></div>
        <div><dt>超时上限</dt><dd>{{ limitSeconds }} 秒</dd></div>
      </dl>
    </template>
    <template v-else>
      <el-alert v-if="failed" type="warning" :closable="false" show-icon
        title="本次识别未能可靠完成，当前显示的是服务端降级设置"
        :description="run?.errorDetail || '可以重新识别、手动修改，或在理解降级范围后显式采用。'" />
      <div class="profile-result-grid" aria-label="任务设置识别字段">
        <article><span>识别置信度</span><strong>{{ profile.confidence }}%</strong></article>
        <article><span>技术栈</span><strong>{{ profile.technologies.length ? profile.technologies.join(' / ') : rolePackLabel(profile.rolePackId) }}</strong></article>
        <article><span>任务类型</span><strong>{{ taskIntentLabel(profile.intent) }}</strong></article>
        <article><span>影响组件</span><strong>{{ affectedComponents }}</strong></article>
        <article><span>流程</span><strong>{{ workflowTemplateLabel(profile.workflowTemplate) }}</strong></article>
        <article><span>主要制品</span><strong>{{ artifactKindLabel(profile.artifactKinds[0] ?? 'OTHER') }}</strong></article>
        <article><span>执行策略</span><strong>{{ executionStrategyLabel(profile.executionStrategy) }}</strong></article>
        <article><span>测试策略</span><strong>{{ testPolicyLabel(profile.testPolicy) }}</strong></article>
      </div>
      <div v-if="editing" class="router-profile-edit" aria-label="手动修改任务设置">
        <el-select v-model="intent" aria-label="弹窗任务类型"><el-option v-for="item in session.availableProfileOverrides" :key="item" :label="taskIntentLabel(item)" :value="item" /></el-select>
        <el-select v-model="artifact" aria-label="弹窗主要制品"><el-option v-for="item in session.availableArtifactOverrides" :key="item" :label="artifactKindLabel(item)" :value="item" /></el-select>
        <el-select v-if="profile.componentSelectionRequired" v-model="componentKeys" multiple collapse-tags aria-label="弹窗影响组件" placeholder="选择本任务影响的组件">
          <el-option v-for="component in profile.candidateComponents ?? []" :key="component.key" :label="componentLabel(component)" :value="component.key" />
        </el-select>
        <label v-if="intent === 'SOFTWARE_CHANGE'" class="router-large-task"><span><strong>大型任务</strong><small>开启多工作包拆解；默认关闭</small></span><el-switch v-model="largeTaskMode" aria-label="弹窗大型任务模式" /></label>
      </div>
    </template>
    <template v-if="!running" #footer>
      <div class="router-actions">
        <el-button v-if="editing" plain :disabled="busy" @click="editing = false">取消修改</el-button>
        <el-button v-if="editing" type="primary" :loading="busy"
          :disabled="profile.componentSelectionRequired && componentKeys.length === 0"
          @click="emit('save', { intent, artifact, largeTaskMode, componentKeys })">保存并进入设计</el-button>
        <template v-else>
          <el-button v-if="run?.retryAvailable" plain :loading="busy" @click="emit('reroute')">重新识别</el-button>
          <el-button plain :disabled="busy" @click="editing = true">手动修改</el-button>
          <el-button type="primary" :loading="busy" :disabled="profile.componentSelectionRequired" @click="emit('confirm')">确认并进入设计</el-button>
        </template>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.router-intro { margin: 0 0 14px; color: var(--color-text-secondary); font-size: 12px; line-height: 1.65; }
.router-runtime { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin: 14px 0 0; }.router-runtime div, .profile-result-grid article { padding: 12px; border: 1px solid var(--color-border-soft); border-radius: 9px; background: rgb(15 23 42 / 42%); }.router-runtime dt, .profile-result-grid span { color: var(--color-text-secondary); font-size: 10px; }.router-runtime dd { margin: 5px 0 0; color: var(--color-text-primary); font: 11px var(--font-code); }
.profile-result-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin-top: 14px; }.profile-result-grid article { display: grid; gap: 6px; }.profile-result-grid strong { color: var(--color-text-primary); font-size: 13px; }
.router-profile-edit { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--color-border-soft); }.router-large-task { display: flex; align-items: center; justify-content: space-between; gap: 12px; grid-column: 1 / -1; }.router-large-task span { display: grid; gap: 3px; }.router-large-task small { color: var(--color-text-secondary); }
.router-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
@media (max-width: 720px) { .router-runtime, .profile-result-grid, .router-profile-edit { grid-template-columns: 1fr; } }
</style>
