<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import WorkflowChoice from '@/components/workflow/WorkflowChoice.vue'
import { useWorkflowCommand } from '@/components/workflow/command'
import { workflowApi } from '@/api/workflow'
import { workflowRuns } from '@/api/workflowRuns'
import { userFacingError } from '@/utils/displayLabels'
import '@/components/workflow/workflow.css'
const route = useRoute(), router = useRouter(), command = useWorkflowCommand()
const project = ref<{ id: string; title: string } | null>(null), flow = ref<{ id: string; title: string; revision?: number } | null>(null), title = ref(''), objective = ref(''), error = ref(''), loading = ref(false)
let alive = true, leaving = false
const dirty = computed(() => !!title.value || !!objective.value || !!project.value || !!command.pending.value)
const valid = computed(() => !!project.value && !!flow.value?.revision && !!title.value.trim() && !!objective.value.trim())
async function create() {
  if (!valid.value || command.locked.value) return
  const body = { requestKey: crypto.randomUUID(), projectId: project.value!.id, templateId: flow.value!.id, templateRevision: flow.value!.revision!, title: title.value.trim(), objective: objective.value.trim() }
  await command.submit('创建需求', () => workflowRuns.create(body), async receipt => { leaving = true; try { await router.push(`/requirements/${receipt.id}`) } finally { leaving = false } })
}
onBeforeRouteLeave(() => leaving || !dirty.value || window.confirm(command.pending.value ? '创建结果尚未确认，仍要离开？可先重试原操作以找回已创建的需求。' : '需求内容尚未保存，仍要离开？'))
const unload = (event: BeforeUnloadEvent) => { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', unload)
onBeforeUnmount(() => { alive = false; window.removeEventListener('beforeunload', unload) })
onMounted(async () => {
  if (route.query.legacyDraft === '1') {
    try { objective.value = sessionStorage.getItem('opencode-loopper.designer-draft-prompt') || '' } catch { /* Keep the form usable when storage is unavailable. */ }
  }
  const templateId = typeof route.query.template === 'string' ? route.query.template : 'builtin.workflow.development'
  const projectId = typeof route.query.projectId === 'string' ? route.query.projectId : ''
  loading.value = true
  const [selectedFlow, selectedProject] = await Promise.allSettled([
    workflowApi.get(templateId), projectId ? workflowRuns.project(projectId) : Promise.resolve(null),
  ])
  if (!alive) return
  const failures: string[] = []
  if (selectedFlow.status === 'fulfilled') {
    const value = selectedFlow.value; flow.value = { id: value.id, title: value.title, revision: value.revision }
  } else failures.push(userFacingError(selectedFlow.reason, '指定流程无法读取，请重新选择。'))
  if (selectedProject.status === 'fulfilled') {
    const value = selectedProject.value; if (value) project.value = { id: value.id, title: value.name }
  } else failures.push(userFacingError(selectedProject.reason, '指定项目无法读取，请重新选择。'))
  error.value = failures.join(' '); loading.value = false
})
</script>
<template><main id="main-content" class="workflow-page workflow-new"><PageHeader eyebrow="需求任务" title="安排一次新的工作"><template #actions><RouterLink to="/requirements">返回需求列表</RouterLink></template></PageHeader><form class="workflow-create-card" @submit.prevent="create"><h2>目标与工作方式</h2><p>先进入规划画布，调整本次节点和任务，再开始执行。</p><fieldset class="workflow-fields" :disabled="command.locked.value || loading"><label>需求名称<input v-model="title" maxlength="120" required placeholder="例如：为订单服务补充退款能力" /></label><label>需求说明<textarea v-model="objective" rows="7" required placeholder="描述目标、约束和希望得到的交付物" /></label><div><span class="workflow-field-label">工作项目</span><WorkflowChoice kind="project" :value="project" :disabled="command.locked.value" @select="value => project = value" /></div><div><span class="workflow-field-label">使用流程</span><WorkflowChoice kind="template" :value="flow" :disabled="command.locked.value" @select="value => flow = value" /></div></fieldset><p v-if="error || command.error.value" role="alert" class="workflow-error">{{ error || command.error.value }}</p><button v-if="command.pending.value" type="button" :disabled="command.busy.value" @click="command.retry">{{ command.pending.value.accepted ? '打开已创建的需求' : '重试创建' }}</button><button v-else class="primary-button" type="submit" :disabled="!valid || loading">进入规划画布</button></form></main></template>
