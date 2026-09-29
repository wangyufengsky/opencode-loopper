<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { workflowWriteback } from '@/api/workflowWriteback'
import type { WorkflowPublicationPreview, WorkflowWritebackPreview, WorkflowWritebackView } from '@/types/domain'
import { userFacingError, workflowWritebackStateLabel, workflowWritebackReason } from '@/utils/displayLabels'
import { useWorkflowCommand } from './command'
const props = defineProps<{ requirement: string; source: WorkflowPublicationPreview | null; checked: WorkflowWritebackPreview | null; disabled?: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; recorded: [value: boolean] }>()
const command = useWorkflowCommand(), status = ref<WorkflowWritebackView | null>(null), reading = ref(false), loaded = ref(false), error = ref(''), open = ref(false)
let alive = true, generation = 0, abort: AbortController | undefined, timer: ReturnType<typeof setTimeout> | undefined
const available = computed(() => loaded.value && !error.value && !status.value && props.source?.workspaceKind === 'DIRECT' && props.source.requirementState === 'COMPLETED'
  && props.checked?.requirementId === props.requirement && props.checked.sourceSha256 === props.source.sha256 && props.checked.requirementVersion === props.source.requirementVersion
  && props.checked.conflictCount === 0 && !!props.checked.targetSha256)
const editable = computed(() => available.value && !props.disabled && !command.locked.value && !reading.value)
watch(() => !!status.value, value => emit('recorded', value), { immediate: true })
watch(() => command.locked.value || open.value, value => emit('busy', value), { immediate: true })
async function refresh(strict = false) {
  const id = props.requirement, current = ++generation; abort?.abort(); abort = new AbortController(); clearTimeout(timer); reading.value = true
  try {
    const next = await workflowWriteback.status(id, abort.signal)
    if (!alive || current !== generation) return
    if (next && (next.requirementId !== id || next.preview.requirementId !== id)) throw new Error('回填记录与当前需求不一致，请重新读取。')
    status.value = next || null; loaded.value = true; error.value = ''; if (next) open.value = false
  } catch (failure) { if (alive && current === generation) { error.value = userFacingError(failure, '回填记录暂时无法读取，请重试。'); if (strict) throw failure } }
  finally { if (alive && current === generation) { reading.value = false; if (status.value && ['CONFIRMED', 'APPLYING'].includes(status.value.state)) timer = setTimeout(() => { void refresh() }, 2500) } }
}
async function submit() {
  const source = props.source, checked = props.checked; if (!source || !checked || !editable.value) return
  const id = props.requirement, body = { requestKey: crypto.randomUUID(), expectedVersion: checked.requirementVersion, selection: { revision: source.planRevision, node: source.source.nodeKey, attempt: source.source.attemptId, output: source.source.outputName, sourceSha256: source.sha256 }, previewSha256: checked.sha256 }
  await command.submit('回填所选成果', () => workflowWriteback.confirm(id, body), async () => { if (props.requirement === id) { await refresh(true); open.value = false } })
}
async function retry() { const value = status.value; if (!value || props.disabled || command.locked.value) return; const id = props.requirement; await command.submit('恢复原回填', () => workflowWriteback.retry(id, value.version), async () => { if (props.requirement === id) await refresh(true) }) }
function canLeave() { return !(command.locked.value || open.value) || window.confirm('回填确认或操作结果尚未确认，仍要离开？') }
watch(() => props.requirement, () => { status.value = null; loaded.value = false; open.value = false; void refresh() }, { immediate: true })
watch(() => [props.source?.sha256, props.checked?.sha256], () => { if (!command.locked.value) open.value = false })
onBeforeUnmount(() => { alive = false; generation++; abort?.abort(); clearTimeout(timer); emit('busy', false) })
defineExpose({ canLeave })
</script>
<template>
  <section class="workflow-writeback" aria-label="普通目录成果回填">
    <template v-if="status"><strong>{{ workflowWritebackStateLabel(status.state) }}</strong><p>{{ status.nodeTitle }} · {{ status.outputTitle }}</p><p>原目录：{{ status.preview.directory }}</p>
      <p v-if="status.attemptState === 'FAILED'">所选节点原执行结果为失败，人工完成需求后明确选择了这份成果。</p>
      <p v-if="status.queueState === 'QUEUED'">等待工作区写入权，当前排在第 {{ status.queuePosition }} 位。</p>
      <p v-else-if="status.state === 'CONFIRMED'">正在保存回填前的文件备份。</p>
      <p v-else-if="status.state === 'APPLYING'">正在应用已确认的改动，完成核对后会交出工作区写入权。</p>
      <p v-else-if="status.state === 'APPLIED'">已核对全部目标文件，回填前的备份已保存。</p>
      <p v-if="status.state === 'BLOCKED'" role="alert">{{ workflowWritebackReason(status.blocker) }}</p>
      <button v-if="status.state === 'BLOCKED'" :disabled="disabled || reading || command.locked.value" @click="retry">恢复原回填</button>
      <button :disabled="reading || command.busy.value" @click="refresh()">刷新回填状态</button>
    </template>
    <form v-else-if="open" @submit.prevent="submit"><h3>确认回填原目录</h3><p>{{ source?.source.nodeTitle }} · {{ source?.source.outputTitle }}</p><p>目录：{{ checked?.directory }}</p>
      <p>将新增 {{ checked?.added }} · 修改 {{ checked?.modified }} · 删除 {{ checked?.deleted }} 个文件，保留 {{ checked?.preservedChanges }} 处已有修改。回填前会保存本次目录备份。</p>
      <p>如果等待期间目录发生变化，回填会停止并保留现场。</p>
      <p v-if="source?.source.attemptState === 'FAILED'">该节点原执行结果为失败。确认后按已人工完成的需求回填这份成果，保留原失败记录。</p>
      <div class="writeback-actions"><button type="submit" :disabled="!editable">确认回填这些改动</button><button type="button" :disabled="command.locked.value" @click="open = false">取消</button></div>
    </form>
    <button v-else-if="available" :disabled="!editable" @click="open = true">回填所选成果</button>
    <p v-if="error || command.error.value" role="alert">{{ command.error.value || error }} <button v-if="command.pending.value" :disabled="command.busy.value" @click="command.retry">{{ command.pending.value.accepted ? '刷新操作结果' : '重试原回填操作' }}</button><button v-else :disabled="reading" @click="refresh()">重新读取回填记录</button></p>
  </section>
</template>
<style scoped>
.workflow-writeback { margin-top: 1rem; overflow-wrap: anywhere; } .workflow-writeback form { padding: 1rem; background: var(--color-bg-surface); border: 1px solid var(--color-border-default); border-radius: var(--radius-control); }
.workflow-writeback p { color: var(--color-text-secondary); } .writeback-actions { display: flex; flex-wrap: wrap; gap: .5rem; margin-top: 1rem; }
</style>
