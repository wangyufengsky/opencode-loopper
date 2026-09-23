<script setup lang="ts">
import { ElAlert } from 'element-plus'
import { onBeforeUnmount, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { api } from '@/api/client'
import { userFacingError } from '@/utils/displayLabels'

const props = defineProps<{ label: string; placeholder?: string; disabled?: boolean; scopeKey?: string; demo?: boolean }>()
const path = defineModel<string>({ required: true })
const picking = defineModel<boolean>('picking', { default: false })
const error = ref('')
let generation = 0
watch(() => props.scopeKey, () => { generation++; picking.value = false; error.value = '' })
onBeforeUnmount(() => { generation++; picking.value = false })

async function choose() {
  if (props.disabled || props.demo || picking.value) return
  const request = ++generation
  const original = path.value
  picking.value = true
  error.value = ''
  try {
    const selected = await api.pickProjectDirectory()
    if (request !== generation || props.disabled || path.value !== original) return
    if (selected.selected && selected.path) path.value = selected.path
  } catch (failure) {
    if (request === generation) error.value = userFacingError(failure, '无法打开文件夹选择器，请手动填写路径')
  } finally {
    if (request === generation) picking.value = false
  }
}
</script>

<template>
  <div class="directory-path-input">
    <div class="directory-path-row">
      <el-input v-model="path" :aria-label="label" :placeholder="placeholder" maxlength="2048" :disabled="disabled || picking" />
      <el-button native-type="button" :aria-label="`选择${label}文件夹`" :loading="picking" :disabled="disabled || demo" @click="choose">
        <Icon icon="lucide:folder-open" />选择文件夹
      </el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
  </div>
</template>

<style scoped>
.directory-path-input { display: grid; gap: 8px; width: 100%; min-width: 0; }
.directory-path-row { display: flex; align-items: center; gap: 8px; min-width: 0; }
.directory-path-row :deep(.el-input) { flex: 1; min-width: 0; }
@media (max-width: 600px) { .directory-path-row { flex-wrap: wrap; } .directory-path-row :deep(.el-input) { flex-basis: 100%; } }
</style>
