<script setup lang="ts">
import { computed } from 'vue'
import type { PptJob } from '@/types/domain'
import { pptJobLabel } from '@/utils/displayLabels'
const props = defineProps<{
  jobs: PptJob[]
  revision: number
  busy?: boolean
}>()
const emit = defineEmits<{
  history: []
  retry: [id: string]
}>()
const visible = computed(() =>
  props.jobs
    .filter(
      (job) =>
        ['PREPARED', 'PENDING', 'QUEUED', 'RUNNING'].includes(job.state) ||
        (job.state === 'FAILED' && job.revision === props.revision),
    )
    .slice(0, 3),
)
</script>
<template>
  <section v-if="visible.length" class="ppt-progress" aria-label="制作进度">
    <article v-for="job in visible" :key="job.id">
      <div>
        <strong>{{ job.kind === 'EXPORT' ? '导出 PPTX' : '生成页面预览' }}</strong>
        <span>
          {{ pptJobLabel(job.state) }} · {{ job.completed }} / {{ job.total }}
          {{ job.kind === 'EXPORT' ? '个文件' : '页' }} · 版本 {{ job.revision
          }}{{ job.revision !== revision ? '（草稿已有更新）' : '' }}
        </span>
      </div>
      <progress
        v-if="job.total && job.state !== 'FAILED'"
        :max="job.total"
        :value="job.completed"
      />
      <p v-if="job.detail">{{ job.detail }}</p>
      <button v-if="job.state === 'FAILED'" :disabled="busy" @click="emit('retry', job.id)">
        {{ job.kind === 'EXPORT' ? '重试导出' : '继续未完成页面' }}
      </button>
      <button @click="emit('history')">查看记录</button>
    </article>
  </section>
</template>
