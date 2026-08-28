<script setup lang="ts">
import { computed } from 'vue'
import { Icon } from '@iconify/vue'

const props = withDefaults(defineProps<{
  files: File[]
  scopeLabel: string
  cardTestId: string
  fileTestId: string
  disabled?: boolean
  retentionNote?: string
}>(), {
  disabled: false,
  retentionNote: '必须和文字一起发送；发送失败会保留当前文字和文件',
})

const emit = defineEmits<{
  add: []
  remove: [index: number]
}>()

const fileCountLabel = computed(() => `${props.scopeLabel} · ${props.files.length} 个文件`)

function fileExtension(file: File) {
  const match = file.name.match(/\.([^.]+)$/)
  return match?.[1]?.slice(0, 8).toUpperCase() ?? 'FILE'
}

function fileTypeLabel(file: File) {
  const extension = fileExtension(file)
  return extension === 'FILE' ? '本地文件' : `${extension} 文件`
}

function formatFileSize(bytes: number) {
  return bytes < 1024 * 1024
    ? `${Math.max(1, Math.ceil(bytes / 1024))} KiB`
    : `${(bytes / 1024 / 1024).toFixed(1)} MiB`
}
</script>

<template>
  <div class="file-context-entry">
    <div class="file-context-trigger">
      <el-button plain size="small" :disabled="disabled" @click="emit('add')">
        <Icon icon="lucide:paperclip" />{{ files.length ? '继续添加上下文文件' : '添加上下文文件' }}
      </el-button>
    </div>

    <section
      v-if="files.length"
      class="file-context-card"
      :data-testid="cardTestId"
      aria-label="待发送文件上下文"
    >
      <header>
        <span class="file-context-mark"><Icon icon="lucide:files" /></span>
        <div>
          <strong>文件上下文</strong>
          <small>{{ fileCountLabel }}</small>
        </div>
      </header>

      <div class="file-context-list">
        <article
          v-for="(file, index) in files"
          :key="`${file.name}:${file.size}:${file.lastModified}`"
          class="file-context-row"
          :data-testid="fileTestId"
        >
          <span class="file-type-badge" aria-hidden="true">{{ fileExtension(file) }}</span>
          <span class="file-context-details">
            <b :title="file.name">{{ file.name }}</b>
            <small>{{ fileTypeLabel(file) }} · {{ formatFileSize(file.size) }}</small>
          </span>
          <button
            type="button"
            class="remove-file-button"
            :disabled="disabled"
            :aria-label="`移除 ${file.name}`"
            :title="`移除 ${file.name}`"
            @click="emit('remove', index)"
          >
            <Icon icon="lucide:trash-2" />
          </button>
        </article>
      </div>

      <footer>
        <Icon icon="lucide:shield-check" />
        <span>最多 10 个，单个 20 MiB；{{ retentionNote }}</span>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.file-context-entry { display: grid; gap: 10px; }
.file-context-trigger { display: flex; align-items: center; min-height: 30px; }
.file-context-trigger :deep(.el-button) { color: var(--color-text-secondary); border-color: rgb(57 78 113 / 82%); background: rgb(7 12 23 / 44%); }
.file-context-trigger :deep(.el-button:hover), .file-context-trigger :deep(.el-button:focus-visible) { color: var(--color-text-primary); border-color: rgb(34 211 238 / 42%); background: rgb(34 211 238 / 7%); }
.file-context-card { overflow: hidden; border: 1px solid rgb(57 78 113 / 88%); border-radius: var(--radius-card); background: linear-gradient(155deg, rgb(15 24 42 / 94%), rgb(9 15 28 / 96%)); box-shadow: 0 14px 34px rgb(0 0 0 / 18%); }
.file-context-card > header { display: flex; align-items: center; gap: 11px; padding: 12px 14px; border-bottom: 1px solid rgb(57 78 113 / 64%); background: rgb(7 11 20 / 30%); }
.file-context-mark { display: grid; flex: 0 0 auto; width: 34px; height: 34px; place-items: center; border: 1px solid rgb(34 211 238 / 24%); border-radius: 9px; color: var(--color-accent-cyan); background: rgb(34 211 238 / 7%); }
.file-context-card > header > div { display: grid; min-width: 0; gap: 3px; }
.file-context-card > header strong { color: var(--color-text-primary); font-size: 12px; font-weight: 700; }
.file-context-card > header small { color: var(--color-text-muted); font: 9px/1.35 var(--font-code); }
.file-context-list { display: grid; max-height: 260px; overflow-y: auto; }
.file-context-row { display: grid; grid-template-columns: 38px minmax(0, 1fr) 32px; align-items: center; gap: 11px; min-height: 58px; padding: 9px 12px 9px 14px; border-bottom: 1px solid rgb(57 78 113 / 48%); }
.file-context-row:last-child { border-bottom: 0; }
.file-type-badge { display: grid; width: 38px; height: 38px; place-items: center; overflow: hidden; border: 1px solid rgb(99 102 241 / 30%); border-radius: 9px; color: #c4b5fd; background: rgb(99 102 241 / 10%); font: 700 8px/1 var(--font-code); letter-spacing: .03em; }
.file-context-details { display: grid; min-width: 0; gap: 4px; }
.file-context-details b { overflow: hidden; color: var(--color-text-primary); font-size: 11px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.file-context-details small { color: var(--color-text-muted); font: 9px/1.35 var(--font-code); }
.remove-file-button { display: grid; width: 30px; height: 30px; place-items: center; border: 1px solid transparent; border-radius: 8px; color: var(--color-text-muted); background: transparent; cursor: pointer; }
.remove-file-button:hover, .remove-file-button:focus-visible { border-color: rgb(239 68 68 / 32%); color: #fca5a5; background: rgb(239 68 68 / 8%); outline: none; }
.remove-file-button:disabled { opacity: .45; cursor: not-allowed; }
.file-context-card > footer { display: flex; align-items: flex-start; gap: 7px; padding: 9px 14px; border-top: 1px solid rgb(57 78 113 / 54%); color: var(--color-text-muted); background: rgb(7 11 20 / 30%); font: 9px/1.5 var(--font-code); }
.file-context-card > footer svg { flex: 0 0 auto; margin-top: 2px; color: var(--color-success); }

@media (max-width: 720px) {
  .file-context-trigger :deep(.el-button) { width: 100%; }
  .file-context-row { grid-template-columns: 34px minmax(0, 1fr) 32px; gap: 9px; padding-inline: 10px; }
  .file-type-badge { width: 34px; height: 34px; }
}
</style>
