<script setup lang="ts">
import { Icon } from '@iconify/vue'
import type { PptInputFile } from './usePptCreation'

const prompt = defineModel<string>({
  required: true,
})
defineProps<{
  files: PptInputFile[]
  busy?: boolean
  locked?: boolean
  detail?: string
  error?: string
}>()
const emit = defineEmits<{
  submit: []
  files: [files: File[]]
  remove: [id: string]
}>()

function choose(event: Event) {
  const input = event.target as HTMLInputElement
  emit('files', [...(input.files || [])])
  input.value = ''
}

function drop(event: DragEvent) {
  emit('files', [...(event.dataTransfer?.files || [])])
}
</script>

<template>
  <form
    class="ppt-prompt-card"
    @submit.prevent="emit('submit')"
    @dragover.prevent
    @drop.prevent="!busy && drop($event)"
  >
    <label class="ppt-sr-only" for="ppt-first-prompt">你想制作什么 PPT</label>
    <textarea
      id="ppt-first-prompt"
      v-model="prompt"
      rows="4"
      maxlength="24000"
      :readonly="locked"
      :disabled="busy"
      :placeholder="'告诉我你要讲什么，也可以附上资料。\n例如：做一份季度经营汇报，给管理层看，重点突出成果和下一步计划。'"
      @keydown.meta.enter.prevent="!busy && prompt.trim() && emit('submit')"
      @keydown.ctrl.enter.prevent="!busy && prompt.trim() && emit('submit')"
    />
    <ul v-if="files.length" class="ppt-input-files" aria-label="已选择附件">
      <li v-for="file in files" :key="file.id" :class="{ missing: !file.file && !file.uploaded }">
        <Icon :icon="file.kind === 'assets' ? 'lucide:image' : 'lucide:file-text'" />
        <span>
          {{ file.name }}
          <small v-if="!file.file && !file.uploaded">请重新选择</small>
        </span>
        <Icon v-if="file.uploaded" icon="lucide:check" aria-label="已读取" />
        <button
          v-else-if="!locked"
          type="button"
          :aria-label="`移除附件 ${file.name}`"
          :disabled="busy"
          @click="emit('remove', file.id)"
        >
          <Icon icon="lucide:x" />
        </button>
      </li>
    </ul>
    <footer>
      <label class="ppt-attach-button">
        <Icon icon="lucide:paperclip" />
        添加资料
        <input
          type="file"
          accept=".md,.docx,.xlsx,.pptx,.pdf,.png,.jpg,.jpeg"
          multiple
          :disabled="busy"
          aria-label="添加制作资料"
          @change="choose"
        />
      </label>
      <span v-if="busy" role="status" class="ppt-prompt-status">
        {{ detail || '正在开始制作' }}
      </span>
      <button class="ppt-primary ppt-generate-button" :disabled="busy || !prompt.trim()">
        <Icon
          :icon="busy ? 'lucide:loader-circle' : 'lucide:sparkles'"
          :class="{ 'ppt-spin': busy }"
        />
        {{ busy ? '正在开始' : locked ? '继续生成' : '生成 PPT' }}
        <Icon v-if="!busy" icon="lucide:arrow-up-right" />
      </button>
    </footer>
    <p v-if="error" role="alert" class="ppt-inline-error">{{ error }}</p>
  </form>
</template>
