<script setup lang="ts">
import { computed } from 'vue'
import { Icon } from '@iconify/vue'
import { usePptStore } from '@/stores/pptStore'
import { pptGenerationLabel, pptGenerationStepLabel, pptRunLabel } from '@/utils/displayLabels'
import type { PptGenerationStep } from '@/types/domain'

const store = usePptStore()
const steps: PptGenerationStep[] = ['PLANNING', 'PRODUCING', 'PREVIEW', 'EXPORT']
const current = computed(() => steps.indexOf(store.generation?.step || 'PLANNING'))
const job = computed(() => store.jobs.find((item) => item.id === store.generation?.jobId))
const waiting = computed(
  () => store.agent?.state === 'WAITING_INPUT' || store.generation?.state === 'WAITING_INPUT',
)
const interrupted = computed(() => ['STOPPED', 'FAILED'].includes(store.generation?.state || ''))
const clarifying = computed(() => ['CLARIFYING', 'AWAITING_CONFIRMATION'].includes(store.agent?.requirementsState || ''))
const confirming = computed(() => store.agent?.requirementsState === 'AWAITING_CONFIRMATION')
const heading = computed(() =>
  store.generation?.recovery?.retryAt
    ? '等待自动恢复'
    : clarifying.value && !interrupted.value && store.generation?.state !== 'STOPPING'
    ? confirming.value ? '请确认制作需求' : '先聊清你的想法'
    : store.generation
    ? pptGenerationLabel(store.generation.state)
    : store.active
      ? pptRunLabel(store.agent?.state || 'IDLE')
      : store.document?.phase === 'BRIEFING'
        ? '需求讨论中'
      : '让我们继续完成这份演示',
)
</script>

<template>
  <section class="ppt-generation-status" aria-label="制作进度" aria-live="polite">
    <div class="ppt-generation-emblem" :class="{ waiting, interrupted }" aria-hidden="true">
      <Icon
        :icon="
          waiting
            ? 'lucide:message-circle-question'
            : interrupted
              ? 'lucide:pause'
              : 'lucide:sparkles'
        "
      />
    </div>
    <h2>{{ heading }}</h2>
    <p v-if="store.generation?.detail" class="ppt-generation-detail">
      {{ store.generation.detail }}
    </p>
    <p v-else-if="confirming">确认下面的需求后，助手才会开始第一轮设计。</p>
    <p v-else-if="clarifying">助手会和你逐轮确认内容与风格，你也可以随时补充想法。</p>
    <p v-else-if="waiting">补充下面的信息后，助手会继续。</p>
    <p v-else-if="store.generationActive">你可以离开这个页面，制作会继续。</p>
    <p v-else-if="!store.generation && store.document?.phase === 'BRIEFING'">
      可以继续补充或修改要求；准备好后点击“确认需求并执行”。
    </p>
    <p v-else-if="!store.generation">告诉助手你希望这份 PPT 讲什么。</p>
    <ol v-if="store.generation && !clarifying" class="ppt-generation-steps">
      <li
        v-for="(step, index) in steps"
        :key="step"
        :class="{ done: index < current, current: index === current }"
        :aria-current="index === current ? 'step' : undefined"
      >
        <span>
          <Icon v-if="index < current" icon="lucide:check" />
          <span v-else>{{ index + 1 }}</span>
        </span>
        {{ pptGenerationStepLabel(step) }}
      </li>
    </ol>
    <p v-if="store.generation?.recovery && store.generation.recovery.revision === store.document?.revision" class="ppt-generation-count">
      已通过 {{ store.generation.recovery.completedPages }} 页检查，待补充 {{ store.generation.recovery.missingPages }} 页，待检查问题 {{ store.generation.recovery.issues }} 项
    </p>
    <p v-if="job?.kind === 'PREVIEW' && job.total" class="ppt-generation-count">
      {{ job.completed }} / {{ job.total }} 页已处理
    </p>
    <button
      v-if="store.generation?.canResume"
      class="ppt-primary"
      :disabled="store.busy || !!store.pending"
      @click="store.resume"
    >
      <Icon icon="lucide:play" />
      按当前要求继续
    </button>
  </section>
</template>
