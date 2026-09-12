<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api/client'
import type { Project } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'

const props = defineProps<{ project?: Project; demo?: boolean }>()
const emit = defineEmits<{ close: []; saved: [project: Project] }>()
const path = ref('')
const saving = ref(false)
const error = ref('')
watch(() => props.project, project => { path.value = project?.documentPath ?? ''; error.value = '' }, { immediate: true })
async function save() {
  if (!props.project || saving.value) return
  saving.value = true
  error.value = ''
  try {
    if (!props.demo && props.project.version === undefined) throw new Error('项目设置已更新，请刷新项目列表后重试')
    const result = props.demo ? { ...props.project, documentPath: path.value.trim() || undefined }
      : await api.updateProjectDocumentPath(props.project.id, path.value, props.project.version!)
    emit('saved', result)
    emit('close')
    ElMessage.success('文档路径已保存')
  } catch (failure) { error.value = userFacingError(failure, '文档路径保存失败，请刷新后重试') }
  finally { saving.value = false }
}
</script>

<template>
  <el-dialog :model-value="!!project" title="项目文档路径" width="min(640px, calc(100vw - 32px))" :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving" @close="emit('close')">
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item :label="project?.name"><el-input v-model="path" aria-label="项目文档路径" placeholder="项目相对路径或绝对路径" maxlength="2048" :disabled="saving" /></el-form-item>
      <p class="muted tiny">新建模板任务默认使用此路径，留空使用任务目录。</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
    </el-form>
    <template #footer><el-button :disabled="saving" @click="emit('close')">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
  </el-dialog>
</template>
