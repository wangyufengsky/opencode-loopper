<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { Icon } from '@iconify/vue'
import WorkflowChoice from '@/components/workflow/WorkflowChoice.vue'
import { useWorkflowCommand } from '@/components/workflow/command'
import { workflowApi } from '@/api/workflow'
import { workflowRuns } from '@/api/workflowRuns'
import { userFacingError } from '@/utils/displayLabels'
import '@/components/workflow/workflow.css'
import '@/components/workflow/workflow-entry.css'
const route = useRoute(), router = useRouter(), command = useWorkflowCommand()
const project = ref<{ id: string; title: string } | null>(null), flow = ref<{ id: string; title: string; revision?: number } | null>(null), title = ref(''), objective = ref(''), projectError = ref(''), flowError = ref(''), loading = ref(false)
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
  if (selectedFlow.status === 'fulfilled') {
    const value = selectedFlow.value; flow.value = { id: value.id, title: value.title, revision: value.revision }
  } else flowError.value = userFacingError(selectedFlow.reason, '指定流程无法读取，请重新选择。')
  if (selectedProject.status === 'fulfilled') {
    const value = selectedProject.value; if (value) project.value = { id: value.id, title: value.name }
  } else projectError.value = userFacingError(selectedProject.reason, '指定项目无法读取，请重新选择。')
  loading.value = false
})
</script>
<template>
  <main id="main-content" class="workflow-page workflow-entry workflow-entry-new">
    <header class="workflow-entry-header">
      <div><h1>新建需求</h1><p>写下目标，再到画布中安排工作。</p></div>
      <RouterLink class="workflow-entry-link" to="/requirements"><Icon icon="lucide:arrow-left" aria-hidden="true" />返回需求列表</RouterLink>
    </header>
    <form class="workflow-entry-create" @submit.prevent="create">
      <div class="workflow-entry-create-body">
        <fieldset class="workflow-entry-goal" :disabled="command.locked.value || loading">
          <legend>需求内容</legend>
          <label for="workflow-requirement-title">需求名称</label><input id="workflow-requirement-title" v-model="title" maxlength="120" required placeholder="例如：为订单服务补充退款能力" />
          <label for="workflow-requirement-objective">需求说明</label><textarea id="workflow-requirement-objective" v-model="objective" rows="10" required placeholder="描述目标、约束和希望得到的交付物" />
        </fieldset>
        <fieldset class="workflow-entry-settings" :disabled="command.locked.value || loading">
          <legend>工作方式</legend>
          <div class="workflow-entry-field"><span class="workflow-field-label">工作项目</span><WorkflowChoice kind="project" :value="project" :disabled="command.locked.value || loading" @select="value => { project = value; projectError = '' }" /><p v-if="projectError" role="alert" class="workflow-entry-field-error">{{ projectError }}</p></div>
          <div class="workflow-entry-field"><span class="workflow-field-label">使用流程</span><WorkflowChoice kind="template" :value="flow" :disabled="command.locked.value || loading" @select="value => { flow = value; flowError = '' }" /><p v-if="flowError" role="alert" class="workflow-entry-field-error">{{ flowError }}</p><p v-else-if="flow?.revision" class="workflow-entry-field-hint">版本 {{ flow.revision }} · 本次需求独立保存</p></div>
          <p class="workflow-entry-planning-hint"><Icon icon="lucide:mouse-pointer-2" aria-hidden="true" /><span>进入画布后，可继续调整节点与任务。</span></p>
        </fieldset>
      </div>
      <div v-if="command.error.value" role="alert" class="workflow-error"><Icon icon="lucide:circle-alert" aria-hidden="true" /><span>{{ command.error.value }}</span></div>
      <footer class="workflow-entry-create-footer">
        <p v-if="loading" role="status">正在读取项目与流程…</p><p v-else-if="command.busy.value" role="status">正在创建需求…</p><p v-else>确认计划后，再开始执行。</p>
        <button v-if="command.pending.value" class="primary-button" type="button" :disabled="command.busy.value" @click="command.retry">{{ command.pending.value.accepted ? '打开已创建的需求' : '重试创建' }}<Icon icon="lucide:arrow-right" aria-hidden="true" /></button>
        <button v-else class="primary-button" type="submit" :disabled="!valid || loading">进入规划画布<Icon icon="lucide:arrow-right" aria-hidden="true" /></button>
      </footer>
    </form>
  </main>
</template>
