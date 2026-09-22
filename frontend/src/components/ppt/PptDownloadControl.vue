<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { usePptStore } from '@/stores/pptStore'
import PptArtifactDownload from './PptArtifactDownload.vue'

const store = usePptStore()
const downloader = ref<InstanceType<typeof PptArtifactDownload>>()
const requestedRevision = ref<number | null>(null)
const artifact = computed(() =>
  store.jobs
    .find(
      (job) =>
        job.kind === 'EXPORT' &&
        job.state === 'COMPLETED' &&
        job.revision === store.document?.revision,
    )
    ?.artifacts.find((value) => value.mediaType !== 'image/png'),
)
const preparing = computed(
  () =>
    requestedRevision.value !== null ||
    store.jobs.some(
      (job) =>
        job.kind === 'EXPORT' &&
        ['PREPARED', 'PENDING', 'RUNNING'].includes(job.state) &&
        job.revision === store.document?.revision,
    ),
)
const allowed = computed(
  () =>
    !store.active &&
    !store.busy &&
    !store.document?.archived &&
    !!store.capabilities?.renderingAvailable &&
    ['REVIEW', 'EXPORTED'].includes(store.document?.phase || ''),
)

async function prepare() {
  if (!store.document || !allowed.value) return
  requestedRevision.value = store.document.revision
  if (!(await store.createJob('EXPORT'))) requestedRevision.value = null
}
watch([artifact, downloader], () => {
  if (artifact.value && downloader.value && requestedRevision.value === store.document?.revision) {
    requestedRevision.value = null
    void downloader.value.download()
  }
})
watch(
  () => store.jobs,
  () => {
    if (
      store.jobs.some(
        (job) =>
          job.kind === 'EXPORT' &&
          job.revision === requestedRevision.value &&
          job.state === 'FAILED',
      )
    )
      requestedRevision.value = null
  },
  {
    deep: true,
  },
)
watch(
  () => store.document?.revision,
  () => {
    requestedRevision.value = null
  },
)
</script>

<template>
  <div class="ppt-download-control">
    <PptArtifactDownload
      v-if="artifact && store.document"
      ref="downloader"
      :document-id="store.document.id"
      :artifact="artifact"
      label="下载 PPT"
      :preserve-name="true"
      primary
    />
    <button v-else class="ppt-primary" :disabled="!allowed || preparing" @click="prepare">
      <Icon
        :icon="preparing ? 'lucide:loader-circle' : 'lucide:download'"
        :class="{ 'ppt-spin': preparing }"
      />
      {{ preparing ? '正在准备' : '下载 PPT' }}
    </button>
  </div>
</template>
