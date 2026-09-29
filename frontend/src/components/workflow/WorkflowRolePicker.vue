<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { api } from '@/api/client'
import { userFacingError } from '@/utils/displayLabels'
import type { RoleCatalogItem, RoleRevisionSummary, WorkflowNode } from '@/types/domain'
const props = defineProps<{ node: WorkflowNode; disabled?: boolean }>()
const emit = defineEmits<{ change: [roleId: string, revisionId: string]; label: [roleId: string, label: string] }>()
const query = ref(''), roles = ref<RoleCatalogItem[]>([]), cursor = ref<string | null>(null), loading = ref(false), error = ref(''), current = ref(''), revisions = ref<RoleRevisionSummary[]>([]), revisionCursor = ref<string | null>(null)
let generation = 0
async function load(more = false) {
  const ticket = ++generation; loading.value = true; error.value = ''
  try { const page = await api.getRoles(query.value, more ? cursor.value || '' : '', 20); if (ticket !== generation) return; roles.value = more ? [...roles.value, ...page.items] : page.items; cursor.value = page.nextCursor ?? null }
  catch (failure) { if (ticket === generation) error.value = userFacingError(failure, '角色读取失败，请重试。') }
  finally { if (ticket === generation) loading.value = false }
}
async function choose(roleId: string, revisionId?: string) {
  if (!roleId) return
  const nodeId = props.node.id; loading.value = true; error.value = ''
  try {
    const role = await api.getRole(roleId), id = revisionId ?? role.latestRevisionId, revision = await api.getRoleRevision(roleId, id)
    if (props.node.id !== nodeId) return
    const slot = ['free.write', 'source.test-write'].includes(props.node.moduleId || '') ? 'WORKFLOW_WRITE' : 'WORKFLOW_READ_ONLY'
    const allowed = revision.manifest.allowedSlots
    if (!Array.isArray(allowed) || !allowed.includes(slot) || typeof revision.manifest.workInstructions !== 'string' || !revision.manifest.workInstructions.trim()) throw new Error('这个角色版本不支持当前工作类型，请选择含工作说明的兼容版本。')
    current.value = `${role.displayName} · v${revision.revisionNumber}`
    emit('label', roleId, role.displayName); emit('change', roleId, id)
  } catch (failure) { error.value = userFacingError(failure, '角色版本读取失败，请重试。') }
  finally { loading.value = false }
}
async function history(more = false) {
  if (!props.node.roleId) return
  const nodeId = props.node.id, roleId = props.node.roleId
  loading.value = true
  try { const page = await api.getRoleRevisions(roleId, more ? revisionCursor.value || '' : '', 20); if (nodeId !== props.node.id || roleId !== props.node.roleId) return; revisions.value = more ? [...revisions.value, ...page.items] : page.items; revisionCursor.value = page.nextCursor ?? null }
  catch (failure) { error.value = userFacingError(failure, '角色版本读取失败，请重试。') }
  finally { loading.value = false }
}
watch(() => [props.node.id, props.node.roleId, props.node.roleRevisionId], async () => {
  revisions.value = []; revisionCursor.value = null; current.value = props.node.roleId ? '已固定角色版本' : '尚未选择角色'
  const nodeId = props.node.id, roleId = props.node.roleId, revisionId = props.node.roleRevisionId
  if (!roleId || !revisionId) return
  try { const [role, version] = await Promise.all([api.getRole(roleId), api.getRoleRevision(roleId, revisionId)]); if (nodeId !== props.node.id || roleId !== props.node.roleId || revisionId !== props.node.roleRevisionId) return; current.value = `${role.displayName} · v${version.revisionNumber}`; emit('label', roleId, role.displayName) } catch { /* Retain the frozen choice when catalog reads are unavailable. */ }
}, { immediate: true })
onMounted(() => { void load() })
</script>
<template>
  <fieldset class="workflow-role-picker" :disabled="disabled || loading"><legend>执行角色</legend><p>{{ current }}</p>
    <div class="workflow-inline"><input v-model="query" aria-label="搜索执行角色" placeholder="搜索角色" @keydown.enter.prevent="load()" /><button type="button" @click="load()">搜索</button></div>
    <select aria-label="选择执行角色" :value="''" @change="choose(($event.target as HTMLSelectElement).value)"><option value="">选择角色并固定版本</option><option v-for="role in roles" :key="role.roleId" :value="role.roleId">{{ role.displayName }}</option></select>
    <button v-if="cursor" type="button" @click="load(true)">更多角色</button><button v-if="node.roleId" type="button" @click="history()">选择其他版本</button>
    <select v-if="revisions.length" aria-label="角色版本" :value="node.roleRevisionId || ''" @change="choose(node.roleId!, ($event.target as HTMLSelectElement).value)"><option value="" disabled>选择已发布版本</option><option v-for="version in revisions" :key="version.revisionId" :value="version.revisionId">版本 {{ version.revisionNumber }}</option></select><button v-if="revisionCursor" type="button" @click="history(true)">更多版本</button>
  </fieldset><p v-if="error" role="alert" class="workflow-error">{{ error }}</p>
</template>
