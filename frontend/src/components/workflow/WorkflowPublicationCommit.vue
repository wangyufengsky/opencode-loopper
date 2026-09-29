<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { workflowPublication } from '@/api/workflowPublication'
import type { WorkflowPublicationCommit, WorkflowPublicationPreview } from '@/types/domain'
import { userFacingError, workflowPublicationStateLabel, workflowPublicationReason } from '@/utils/displayLabels'
import { useWorkflowCommand } from './command'
import WorkflowPush from './WorkflowPush.vue'
const props = defineProps<{ requirement: string; preview: WorkflowPublicationPreview | null; disabled?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean] }>()
const command = useWorkflowCommand(), status = ref<WorkflowPublicationCommit | null>(null), reading = ref(false), loaded = ref(false), error = ref(''), open = ref(false), message = ref('')
const pushPanel = ref<{ canLeave: () => boolean } | null>(null), pushBusy = ref(false)
let alive = true, generation = 0, abort: AbortController | undefined, timer: ReturnType<typeof setTimeout> | undefined
const available = computed(() => loaded.value && !error.value && !status.value && props.preview?.requirementState === 'COMPLETED' && props.preview.workspaceKind === 'GIT')
const editable = computed(() => available.value && !props.disabled && !command.locked.value && !reading.value)
watch(() => command.locked.value || open.value || pushBusy.value, value => emit('busy', value), { immediate: true })
async function refresh(strict = false) {
  const id = props.requirement, current = ++generation; abort?.abort(); abort = new AbortController(); clearTimeout(timer); reading.value = true
  try {
    const next = await workflowPublication.status(id, abort.signal)
    if (!alive || current !== generation) return
    if (next && next.requirementId !== id) throw new Error('提交记录与当前需求不一致，请重新读取。')
    status.value = next || null; loaded.value = true; error.value = ''; if (next) open.value = false
  } catch (failure) { if (alive && current === generation) { error.value = userFacingError(failure, '提交记录暂时无法读取，请重试。'); if (strict) throw failure } }
  finally { if (alive && current === generation) { reading.value = false; if (status.value?.state === 'CONFIRMED') timer = setTimeout(() => { void refresh() }, 2500) } }
}
async function submit() {
  const p = props.preview; if (!p || !editable.value || !message.value.trim() || message.value.length > 200) return
  const id = props.requirement, body = { requestKey: crypto.randomUUID(), expectedVersion: p.requirementVersion, revision: p.planRevision, node: p.source.nodeKey, attempt: p.source.attemptId, output: p.source.outputName, previewSha256: p.sha256, message: message.value.trim() }
  await command.submit('保存本地提交', () => workflowPublication.confirm(id, body), async () => { await refresh(true); open.value = false; message.value = '' })
}
async function retry() { const value = status.value; if (!value || props.disabled || command.locked.value) return; const id = props.requirement; await command.submit('恢复原提交', () => workflowPublication.retry(id, value.version), async () => { await refresh(true) }) }
function canLeave() { if (pushPanel.value && !pushPanel.value.canLeave()) return false; return !(command.locked.value || open.value && message.value.trim()) || window.confirm('提交说明或操作结果尚未确认，仍要离开？') }
watch(() => props.requirement, () => { status.value = null; loaded.value = false; open.value = false; message.value = ''; void refresh() }, { immediate: true })
watch(() => props.preview?.sha256, () => { if (!command.locked.value) { open.value = false; message.value = '' } })
onBeforeUnmount(() => { alive = false; generation++; abort?.abort(); clearTimeout(timer); emit('busy', false) })
defineExpose({ canLeave })
</script>
<template>
  <section class="publication-commit" aria-label="保存代码成果">
    <template v-if="status"><strong>{{ workflowPublicationStateLabel(status.state) }}</strong><p>{{ status.nodeTitle }} · {{ status.outputTitle }}：{{ status.message }}</p>
      <p v-if="status.attemptState === 'FAILED'">所选节点原执行结果为失败，人工完成需求后明确提交了这份成果。</p>
      <p>成果分支：<code>{{ status.branch }}</code></p><p v-if="status.commit">提交：<code>{{ status.commit }}</code></p>
      <p v-if="status.state === 'COMMITTED'">固定代码已保存到本地成果分支。</p>
      <p v-if="status.state === 'BLOCKED'">{{ workflowPublicationReason(status.reasonCode) }}</p>
      <button v-if="status.state === 'BLOCKED'" :disabled="disabled || command.locked.value || reading" @click="retry">恢复原提交</button>
      <button :disabled="reading || command.busy.value" @click="refresh()">刷新提交状态</button>
      <WorkflowPush v-if="status.state === 'COMMITTED'" ref="pushPanel" :requirement="requirement" :disabled="disabled || reading || command.locked.value" @busy="pushBusy = $event" />
    </template>
    <form v-else-if="open" @submit.prevent="submit"><h3>保存所选成果为本地提交</h3><p>将 {{ preview?.source.nodeTitle }} · {{ preview?.source.outputTitle }} 保存到新建成果分支，作者记为 Loopper。当前文件、暂存区和来源分支保持原样。</p>
      <p v-if="preview?.source.attemptState === 'FAILED'">该节点原执行结果为失败。此操作按已经人工完成的需求保存你选定的成果，保留原失败记录。</p>
      <label>提交说明<input v-model="message" maxlength="200" required :disabled="!editable" placeholder="说明本次成果包含的改动" /></label>
      <div class="commit-actions"><button type="submit" :disabled="!editable || !message.trim()">确认保存本地提交</button><button type="button" :disabled="command.locked.value" @click="open = false">取消</button></div>
    </form>
    <button v-else-if="available" :disabled="!editable" @click="open = true">保存为本地提交</button>
    <p v-if="error || command.error.value" role="alert">{{ command.error.value || error }} <button v-if="command.pending.value" :disabled="command.busy.value" @click="command.retry">{{ command.pending.value.accepted ? '刷新操作结果' : '重试原提交操作' }}</button><button v-else :disabled="reading" @click="refresh()">重新读取提交记录</button></p>
  </section>
</template>
<style scoped>
.publication-commit { margin-top: 1rem; overflow-wrap: anywhere; } .publication-commit form { padding: 1rem; background: var(--color-bg-surface); border: 1px solid var(--color-border-default); border-radius: var(--radius-control); }
.publication-commit p { color: var(--color-text-secondary); } .publication-commit label { display: grid; gap: .5rem; } .publication-commit input { min-width: 0; width: 100%; box-sizing: border-box; } .commit-actions { display: flex; flex-wrap: wrap; gap: .5rem; margin-top: 1rem; }
</style>
