<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import type { JudgeApproval } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'

const props = defineProps<{ taskId: string; taskVersion?: number }>()
const emit = defineEmits<{ reload: [] }>()
const approval = ref<JudgeApproval>()
const busy = ref(false)
const error = ref('')
let generation = 0
async function load() {
  const request = ++generation
  try {
    const result = await api.getJudgeApproval(props.taskId)
    if (request === generation) { approval.value = result; error.value = '' }
  } catch (cause) { if (request === generation) error.value = userFacingError(cause, '无法读取人工认定状态') }
}
watch(() => [props.taskId, props.taskVersion], load, { immediate: true })
async function approve() {
  const current = approval.value
  if (!current?.available || busy.value) return
  try {
    await ElMessageBox.confirm('AI 双评审结果将保留为参考。确认人工认定本轮通过，并继续提交、推送或合并？', '人工认定通过', {
      confirmButtonText: '认定通过', cancelButtonText: '返回检查', type: 'warning',
    })
  } catch { return }
  busy.value = true
  try {
    await api.approveJudges(props.taskId, {
      expectedTaskVersion: current.taskVersion, cycleId: current.cycleId,
      expectedCycleVersion: current.cycleVersion, reviewBatchId: current.reviewBatchId,
    })
    await load(); emit('reload')
  } catch (cause) { error.value = userFacingError(cause, '人工认定未完成，请刷新当前结果') }
  finally { busy.value = false }
}
</script>

<template>
  <div class="human-review">
    <p>AI 双评审仅供参考。确定性验收通过后，可以人工认定本轮通过。</p>
    <p v-if="approval?.approved" class="approved" role="status">已由人工认定通过 · AI 原始结论保留如下</p>
    <el-button v-if="approval?.available" type="primary" :loading="busy" @click="approve">人工认定通过</el-button>
    <p v-if="error" role="alert">{{ error }} <el-button link @click="load">重试</el-button></p>
  </div>
</template>

<style scoped>
.human-review{margin:0 0 16px;padding:12px 16px;border:1px solid var(--color-border-default);border-radius:8px;color:var(--color-text-secondary);font-size:12px;line-height:1.7}.human-review p{margin:0 0 8px}.human-review .approved{color:var(--color-success)}
</style>
