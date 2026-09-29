<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { workflowPush } from '@/api/workflowPush'
import type { WorkflowPushPreview, WorkflowPushView } from '@/types/domain'
import { userFacingError, workflowPushReason, workflowPushStateLabel } from '@/utils/displayLabels'
import { useWorkflowCommand } from './command'
const props = defineProps<{ requirement: string; disabled?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean] }>()
const command = useWorkflowCommand(), value = ref<WorkflowPushView | null>(null), preview = ref<WorkflowPushPreview | null>(null)
const loaded = ref(false), reading = ref(false), opening = ref(false), checking = ref(false), open = ref(false), remotes = ref<string[]>([]), remote = ref(''), error = ref(''), formError = ref('')
let alive = true, generation = 0, formGeneration = 0, readAbort: AbortController | undefined, formAbort: AbortController | undefined, timer: ReturnType<typeof setTimeout> | undefined
const editable = computed(() => !props.disabled && !command.locked.value && !reading.value && !opening.value && !checking.value)
watch(() => open.value || command.locked.value, busy => emit('busy', busy), { immediate: true })
async function refresh(strict = false) {
  const current = ++generation, id = props.requirement; readAbort?.abort(); readAbort = new AbortController(); clearTimeout(timer); reading.value = true
  try { const next = await workflowPush.status(id, readAbort.signal); if (!alive || current !== generation) return
    if (next && next.requirementId !== id) throw new Error('推送记录与当前需求不一致，请重新读取。')
    value.value = next || null; loaded.value = true; error.value = ''; if (next) closeForm()
  } catch (failure) { if (alive && current === generation) { error.value = userFacingError(failure, '推送记录暂时无法读取，请重试。'); if (strict) throw failure } }
  finally { if (alive && current === generation) { reading.value = false; if (value.value && ['PREPARING', 'RUNNING'].includes(value.value.state)) timer = setTimeout(() => { void refresh() }, 2500) } }
}
function closeForm() { formGeneration++; formAbort?.abort(); open.value = false; preview.value = null; remote.value = ''; formError.value = ''; opening.value = false; checking.value = false }
async function show() {
  if (!editable.value || value.value) return; open.value = true; opening.value = true; formError.value = ''; const current = ++formGeneration; formAbort = new AbortController()
  try { const result = await workflowPush.remotes(props.requirement, formAbort.signal); if (alive && current === formGeneration) remotes.value = result }
  catch (failure) { if (alive && current === formGeneration) formError.value = userFacingError(failure, '远端配置无法读取，请重试。') }
  finally { if (alive && current === formGeneration) opening.value = false }
}
async function check() {
  if (!editable.value || !remote.value) return; const selected = remote.value, current = ++formGeneration, id = props.requirement; formAbort?.abort(); formAbort = new AbortController(); checking.value = true; preview.value = null; formError.value = ''
  try { const next = await workflowPush.preview(id, selected, formAbort.signal); if (!alive || current !== formGeneration) return
    if (next.requirementId !== id || next.remote !== selected) throw new Error('目标与所选远端不一致，请重新检查。'); preview.value = next
  } catch (failure) { if (alive && current === formGeneration) formError.value = userFacingError(failure, '推送目标尚未确认，请检查连接与账号后重试。') }
  finally { if (alive && current === formGeneration) checking.value = false }
}
async function submit() {
  const selected = preview.value; if (!editable.value || !selected) return
  const id = props.requirement, body = { requestKey: crypto.randomUUID(), expectedVersion: selected.publicationVersion, remote: selected.remote, previewSha256: selected.sha256 }
  await command.submit('确认推送', () => workflowPush.confirm(id, body), async () => { await refresh(true) })
}
async function retry() { const selected = value.value; if (!editable.value || !selected) return; const id = props.requirement; await command.submit('核对原推送', () => workflowPush.retry(id, selected.version), async () => { await refresh(true) }) }
function canLeave() { return !(command.locked.value || open.value && remote.value) || window.confirm('推送目标或操作结果尚未确认，仍要离开？') }
watch(remote, () => { formGeneration++; formAbort?.abort(); checking.value = false; preview.value = null; formError.value = '' })
watch(() => props.requirement, () => { closeForm(); value.value = null; loaded.value = false; void refresh() }, { immediate: true })
onBeforeUnmount(() => { alive = false; generation++; formGeneration++; readAbort?.abort(); formAbort?.abort(); clearTimeout(timer); emit('busy', false) })
defineExpose({ canLeave })
</script>
<template>
  <section class="workflow-push" aria-label="推送代码成果">
    <template v-if="value"><h3>{{ workflowPushStateLabel(value.state) }}</h3><p>{{ value.remote }} · {{ value.url }}</p><p>成果分支：<code>{{ value.branch }}</code></p>
      <p v-if="value.state === 'PUSHED'">已核对远端成果分支指向提交 <code>{{ value.commit }}</code>。该记录保留已确认的推送事实。</p>
      <p v-if="value.state === 'BLOCKED'" role="alert">{{ workflowPushReason(value.reasonCode) }}</p><button v-if="value.state === 'BLOCKED'" :disabled="!editable" @click="retry">核对并恢复原推送</button><button :disabled="reading || command.busy.value" @click="refresh()">刷新推送状态</button>
    </template>
    <form v-else-if="open" @submit.prevent="submit"><h3>确认远端推送</h3><p>选择远端并检查准确地址，再确认推送已保存的成果提交。</p>
      <p v-if="opening" role="status">正在读取远端配置…</p><p v-else-if="!remotes.length && !formError">项目尚未配置 Git 远端，请配置后重新打开。</p>
      <label>推送远端<select v-model="remote" :disabled="!editable"><option value="">请选择远端</option><option v-for="name in remotes" :key="name" :value="name">{{ name }}</option></select></label>
      <button type="button" :disabled="!editable || !remote" @click="check">{{ checking ? '正在检查目标…' : '检查推送目标' }}</button>
      <div v-if="preview" class="push-preview"><p>目标地址：{{ preview.url }}</p><p>成果分支：<code>{{ preview.branch }}</code></p><p>固定提交：<code>{{ preview.commit }}</code></p><p>{{ preview.remoteCommit ? '远端已有相同提交，确认后登记该结果。' : '将在远端新建这条成果分支；已有不同内容时停止操作。' }}</p><button type="submit" :disabled="!editable">确认推送成果分支</button></div>
      <p v-if="formError" role="alert">{{ formError }}</p><button v-if="formError && !remote" type="button" :disabled="!editable" @click="show">重新读取远端</button><button type="button" :disabled="command.locked.value" @click="closeForm">取消推送选择</button>
    </form>
    <template v-else-if="loaded && !error"><p>尚未推送到远端。</p><button :disabled="!editable" @click="show">推送到远端</button></template>
    <p v-if="error || command.error.value" role="alert">{{ command.error.value || error }} <button v-if="command.pending.value" :disabled="command.busy.value" @click="command.retry">{{ command.pending.value.accepted ? '刷新推送结果' : '重试原推送确认' }}</button><button v-else :disabled="reading" @click="refresh()">重新读取推送记录</button></p>
  </section>
</template>
<style scoped>
.workflow-push { margin-top: 1rem; border-top: 1px solid var(--color-border-default); padding-top: .75rem; overflow-wrap: anywhere; } .workflow-push h3 { font-size: 1rem; }
.workflow-push label { display: grid; gap: .5rem; margin-bottom: .75rem; } .workflow-push select { min-width: 0; max-width: 100%; } .workflow-push button { margin: .35rem .5rem .35rem 0; }
.push-preview { border: 1px solid var(--color-border-default); border-radius: var(--radius-control); padding: .75rem; margin-top: .75rem; }
</style>
