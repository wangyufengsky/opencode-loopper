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
const heading = computed(() =>
  store.generation
    ? pptGenerationLabel(store.generation.state)
    : store.active
      ? pptRunLabel(store.agent?.state || 'IDLE')
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
    <p v-else-if="waiting">补充下面的信息后，助手会接着完成制作。</p>
    <p v-else-if="store.generationActive">你可以离开这个页面，制作会继续。</p>
    <p v-else-if="!store.generation">告诉助手你希望这份 PPT 讲什么。</p>
    <ol v-if="store.generation" class="ppt-generation-steps">
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
      继续制作
    </button>
  </section>
</template>
