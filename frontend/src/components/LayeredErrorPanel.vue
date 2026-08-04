<script setup lang="ts">
import { Icon } from '@iconify/vue'
import type { ErrorEvent } from '@/types/domain'

defineProps<{ error: ErrorEvent }>()
</script>

<template>
  <section v-if="error.layer !== 'FIELD'" :class="['error-panel', `error-panel-${error.layer.toLowerCase()}`]" :role="error.layer === 'TASK' ? 'alert' : 'status'" aria-live="polite">
    <Icon class="error-panel-icon" :icon="error.layer === 'TASK' ? 'lucide:octagon-x' : error.layer === 'SESSION' ? 'lucide:refresh-cw' : 'lucide:shield-alert'" />
    <div>
      <h3 v-if="error.layer === 'SESSION'">当前 Session 已结束，新 Session 将继续 Loop</h3>
      <h3 v-else-if="error.layer === 'VERIFICATION' && error.code.startsWith('JUDGE_')">Judge 未批准，Task 正在等待人工输入</h3>
      <h3 v-else-if="error.layer === 'VERIFICATION'">验证未通过，平台将携带证据进入下一轮</h3>
      <h3 v-else>Task 已终止，后续 Session 不会再创建</h3>
      <p>{{ error.message }}</p>
      <p class="mono tiny">{{ error.code }} · {{ error.occurredAt }}</p>
    </div>
  </section>
  <p v-else class="inline-field-error" role="alert"><Icon icon="lucide:circle-alert" /> {{ error.message }}</p>
</template>
