<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { workflowRuns } from '@/api/workflowRuns'
import { useWorkflowCommand } from './command'
import { userFacingError, workflowFinishLabel } from '@/utils/displayLabels'
import type { WorkflowFinish, WorkflowFinishTarget, WorkflowRequirementState } from '@/types/domain'

const props = defineProps<{ requirement: string; version: number; state: WorkflowRequirementState; disabled: boolean; beforeOpen?: () => boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; changed: [] }>()
const command = useWorkflowCommand(), value = ref<WorkflowFinish | null>(null), open = ref(false), target = ref<WorkflowFinishTarget>('CANCELLED'), reason = ref(''), error = ref(''), reading = ref(false)
let alive = true, ticket = 0, timer: ReturnType<typeof setTimeout> | undefined
const intent = computed(() => value.value?.intent), ending = computed(() => !!intent.value && !intent.value.finalizedAt)
const available = computed(() => !!value.value && !intent.value && !['STOPPING', 'COMPLETED', 'FAILED', 'CANCELLED'].includes(props.state) && !['STOPPING', 'COMPLETED', 'FAILED', 'CANCELLED'].includes(value.value.state))
const editable = computed(() => available.value && !props.disabled && !command.locked.value && !reading.value && !error.value)
watch(() => open.value || command.locked.value, busy => emit('busy', busy), { immediate: true })
async function refresh(strict = false) {
  const current = ++ticket, id = props.requirement; reading.value = true; clearTimeout(timer)
  try {
    const next = await workflowRuns.finishStatus(id)
    if (!alive || current !== ticket || id !== props.requirement) return
    if (next.requirementId !== id) throw new Error('结束记录与当前需求不一致，请重新读取。')
    value.value = next; error.value = ''
    if (next.intent) open.value = false
  } catch (failure) { if (alive && current === ticket) { error.value = userFacingError(failure, '结束记录暂时无法读取，请重试。'); if (strict) throw failure } }
  finally {
    if (alive && current === ticket) {
      reading.value = false
      if (ending.value || props.state === 'STOPPING') timer = setTimeout(() => { if (!command.locked.value) void refresh(); else schedule() }, 2500)
    }
  }
}
function schedule() { clearTimeout(timer); if (alive) timer = setTimeout(() => { void refresh() }, 2500) }
function show() { if (editable.value && (!props.beforeOpen || props.beforeOpen())) open.value = true }
function close() { if (!command.locked.value) { open.value = false; reason.value = ''; command.error.value = ''; void refresh() } }
async function submit() {
  if (!editable.value || !reason.value.trim() || reason.value.length > 4000) return
  const id = props.requirement, body = { requestKey: crypto.randomUUID(), expectedVersion: props.version, target: target.value, reason: reason.value.trim() }
  await command.submit('结束需求', () => workflowRuns.finish(id, body), async () => { await refresh(true); reason.value = ''; open.value = false; emit('changed') })
}
function canLeave() { return !(command.pending.value || open.value && reason.value.trim()) || window.confirm('结束需求的原因或操作结果尚未确认，仍要离开？') }
watch(() => [props.requirement, props.version, props.state], () => { if (!command.locked.value) void refresh() }, { immediate: true })
onBeforeUnmount(() => { alive = false; ticket++; clearTimeout(timer); emit('busy', false) })
defineExpose({ canLeave })
</script>
<template>
  <section v-if="intent || open || available || error || command.error.value" class="workflow-finish" aria-label="结束需求">
    <div v-if="intent" role="status" class="workflow-finish-summary">
      <strong>{{ ending ? '正在结束需求' : workflowFinishLabel(intent.targetState) }}</strong>
      <p v-if="ending">已选择{{ workflowFinishLabel(intent.targetState) }}，正在确认活动节点停止并归还工作目录。停止状态未确认前会保留在此状态。</p>
      <p v-else>此结果来自用户决定，节点原有交付物、检查与审查结论均保留。</p>
      <p class="workflow-finish-reason">结束原因：{{ intent.reason }}</p>
      <p v-if="ending && value">等待收束：{{ value.pending.attempts }} 次节点执行，{{ value.pending.resources }} 项运行或目录记录。可点击节点查看恢复操作。</p>
      <button :disabled="reading || command.busy.value" @click="refresh()">刷新结束状态</button>
    </div>
    <template v-else-if="!open"><button v-if="available" class="danger" :disabled="!editable" @click="show">提前结束需求</button></template>
    <form v-else @submit.prevent="submit">
      <strong>提前结束需求</strong><p>停止后续执行，并收束活动节点。结束结果与成果提交分别处理。</p>
      <fieldset :disabled="!editable" class="workflow-fields">
        <label>结束结果<select v-model="target" aria-label="结束结果"><option value="CANCELLED">取消需求</option><option value="FAILED">人工认定失败</option><option value="COMPLETED">人工认定成功</option></select></label>
        <label>结束原因<textarea v-model="reason" maxlength="4000" rows="3" required placeholder="说明为什么提前结束，以及如何处理现有成果" /></label>
      </fieldset>
      <p v-if="target === 'COMPLETED'">人工认定成功保留现有失败与审查意见，不表示未执行的检查已经通过。</p>
      <div class="workflow-inline"><button type="submit" class="danger" :disabled="!editable || !reason.trim()">确认结束需求</button><button type="button" :disabled="command.locked.value" @click="close">返回画布</button></div>
    </form>
    <div v-if="error || command.error.value" role="alert" class="workflow-error">{{ command.error.value || error }}
      <button v-if="command.pending.value" :disabled="command.busy.value" @click="command.retry">{{ command.pending.value.accepted ? '刷新操作结果' : '重试原结束操作' }}</button>
      <button v-else :disabled="reading" @click="refresh()">重新读取结束记录</button>
    </div>
  </section>
</template>
<style scoped>
.workflow-finish { border: 1px solid var(--color-border-default); border-radius: var(--radius-control); padding: 14px 18px; margin: 0 0 14px; background: var(--color-bg-surface); }
.workflow-finish p { margin: 8px 0; color: var(--color-text-secondary); line-height: 1.6; }
.workflow-finish form { max-width: 720px; }
.workflow-finish-reason { white-space: pre-wrap; overflow-wrap: anywhere; }
.workflow-finish .workflow-fields { padding: 0; border: 0; }
</style>
