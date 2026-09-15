<script setup lang="ts">
import { ref, useId } from 'vue'
import { Icon } from '@iconify/vue'

const props = defineProps<{ modelValue: File[]; disabled?: boolean; inputId?: string }>()
const emit = defineEmits<{ 'update:modelValue': [File[]] }>()
const input = ref<HTMLInputElement>()
const generatedId = useId()
function choose(event: Event) {
  const selected = Array.from((event.target as HTMLInputElement).files ?? [])
  if (selected.length) emit('update:modelValue', selected)
  // Allow selecting the same file again after removing it; cancellation keeps the current list.
  if (input.value) input.value.value = ''
}
function remove(index: number) {
  if (!props.disabled) emit('update:modelValue', props.modelValue.filter((_, position) => position !== index))
}
function fileType(name: string) { return name.split('.').pop()?.toUpperCase() || '文件' }
function size(bytes: number) { return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MiB` : `${(bytes / 1024).toFixed(1)} KiB` }
</script>

<template>
  <div class="document-picker" :class="{ disabled }">
    <input :id="inputId || generatedId" ref="input" hidden type="file" multiple
      accept=".docx,.md,.markdown,.pdf" :disabled="disabled" aria-label="选择需求文档" tabindex="-1" @change="choose" />
    <div class="picker-entry">
      <span class="upload-icon"><Icon icon="lucide:files" width="24" /></span>
      <div class="picker-copy"><strong>添加需求文档</strong><p>DOCX、Markdown 或文本 PDF</p></div>
      <el-button class="choose-files" :disabled="disabled" @click="input?.click()"><Icon icon="lucide:folder-open" width="16" />{{ modelValue.length ? '重新选择' : '选择文件' }}</el-button>
    </div>
    <p class="picker-hint">最多 10 份 · 每份 20 MiB · 总计 50 MiB</p>
    <div v-if="modelValue.length" class="selected-files">
      <div class="files-summary" role="status"><strong>已选 {{ modelValue.length }} 份文档</strong><span>共 {{ size(modelValue.reduce((total, file) => total + file.size, 0)) }}</span></div>
      <ul class="file-list" aria-label="已选需求文档">
        <li v-for="(file, index) in modelValue" :key="`${index}-${file.name}`" class="file-row">
          <span class="file-icon"><Icon :icon="/\.pdf$/i.test(file.name) ? 'lucide:file' : 'lucide:file-text'" width="20" /></span>
          <div class="file-copy"><span class="file-name">{{ file.name }}</span><span class="file-meta">{{ fileType(file.name) }} · {{ size(file.size) }}</span></div>
          <el-button class="remove-file" text :disabled="disabled" :aria-label="`移除 ${file.name}`" @click="remove(index)"><Icon icon="lucide:x" width="16" /></el-button>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.document-picker { width: 100%; min-width: 0; }
.picker-entry { display: flex; align-items: center; gap: 16px; padding: 22px 20px; border: 1px dashed var(--color-border-default); border-radius: var(--radius-card); background: var(--color-bg-canvas); }
.upload-icon, .file-icon { display: grid; place-items: center; flex-shrink: 0; width: 44px; height: 44px; border-radius: 10px; background: var(--color-bg-elevated); color: var(--color-accent-cyan); }
.picker-copy { flex: 1; min-width: 0; }.picker-copy strong { font-size: 14px; font-weight: 500; }.picker-copy p { margin: 7px 0 0; font-size: 12px; color: var(--color-text-secondary); line-height: 1.6; }
.choose-files { flex-shrink: 0; min-height: 36px; }.choose-files :deep(svg) { margin-right: 8px; }
.picker-hint { margin: 10px 0 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.7; }
.selected-files { margin-top: 20px; }.files-summary { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; font-size: 12px; }.files-summary strong { font-weight: 500; }.files-summary > span { color: var(--color-text-secondary); }
.file-list { display: grid; gap: 8px; list-style: none; margin: 0; padding: 0; }
.file-row { display: flex; align-items: center; gap: 12px; min-width: 0; padding: 12px; border: 1px solid var(--color-border-default); border-radius: var(--radius-control); background: var(--color-bg-surface); }
.file-icon { width: 36px; height: 40px; border-radius: 6px; color: var(--color-text-secondary); }
.file-copy { display: grid; gap: 5px; flex: 1; min-width: 0; }.file-name { font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }.file-meta { font-size: 11px; color: var(--color-text-secondary); }.remove-file { flex-shrink: 0; width: 32px; height: 32px; padding: 0; }
.disabled { opacity: .65; }
@media (max-width: 600px) { .picker-entry { flex-wrap: wrap; padding: 16px; gap: 12px; }.picker-copy { min-width: 140px; }.choose-files { width: 100%; } }
</style>
