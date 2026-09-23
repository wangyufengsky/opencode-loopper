<script setup lang="ts">
import { ElAlert } from 'element-plus'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { SourceTemplatePreview, TemplateTaskDefinition } from '@/types/domain'
import DirectoryPathInput from '@/components/DirectoryPathInput.vue'
import { useSourceTemplateStore } from '@/stores/sourceTemplateStore'
import { userFacingError } from '@/utils/displayLabels'
const props = defineProps<{ definition: TemplateTaskDefinition; projectId: string; documentPath: string }>()
const store = useSourceTemplateStore()
const sourcePath = ref('')
const testPath = ref('')
const outputPath = ref(props.documentPath)
const requirements = ref('')
const pickingSource = ref(false), pickingTest = ref(false), pickingOutput = ref(false)
const preview = ref<SourceTemplatePreview>()
const checking = ref(false), error = ref('')
let generation = 0
const input = computed(() => ({
  templateId: props.definition.id, templateVersion: props.definition.version, projectId: props.projectId,
  sourcePath: sourcePath.value.trim(), requirements: requirements.value.trim(),
  ...(props.definition.inputs?.testOutputPath ? { testOutputPath: testPath.value.trim() || undefined } : {}),
  ...(props.definition.inputs?.documentOutputPath ? { documentPath: outputPath.value.trim() || undefined } : {}),
}))
const picking = computed(() => pickingSource.value || pickingTest.value || pickingOutput.value)
const valid = computed(() => !!props.projectId && !!sourcePath.value.trim() && !!preview.value
  && preview.value.targetCount > 0 && !preview.value.configurationProblem && !picking.value && !checking.value)
watch(() => [props.projectId, props.definition.id], () => { sourcePath.value = ''; testPath.value = ''; requirements.value = ''; outputPath.value = props.documentPath })
watch(() => props.documentPath, value => { outputPath.value = value })
watch(input, () => { ++generation; preview.value = undefined; error.value = ''; checking.value = false }, { deep: true })
async function check() {
  if (!props.projectId || !sourcePath.value.trim() || picking.value || checking.value) return
  const token = ++generation; checking.value = true; error.value = ''
  try {
    const result = await api.sourcePreview({ ...input.value, requestKey: crypto.randomUUID() })
    if (token === generation) preview.value = result
  } catch (failure) { if (token === generation) error.value = userFacingError(failure, '范围检查未完成，请核对路径后重试') }
  finally { if (token === generation) checking.value = false }
}
async function submit() { if (valid.value) return store.create(input.value) }
onBeforeUnmount(() => { ++generation })
defineExpose({ valid, submitting: computed(() => store.submitting), submit })
</script>
<template>
  <section class="source-fields" aria-label="源码模板参数">
    <label>源码路径<DirectoryPathInput v-model="sourcePath" v-model:picking="pickingSource" label="源码路径" :scope-key="`${projectId}:${definition.id}`" :disabled="store.submitting" placeholder="项目内目录或单个源码文件；目录递归处理" /></label>
    <label v-if="definition.inputs?.testOutputPath">测试目录<DirectoryPathInput v-model="testPath" v-model:picking="pickingTest" label="测试目录" :scope-key="`${projectId}:${definition.id}`" :disabled="store.submitting" placeholder="留空按构建配置与已有测试自动识别" /></label>
    <label v-if="definition.inputs?.documentOutputPath">文档目录<DirectoryPathInput v-model="outputPath" v-model:picking="pickingOutput" label="文档目录" :scope-key="`${projectId}:${definition.id}`" :disabled="store.submitting" placeholder="留空使用项目默认目录或任务目录" /></label>
    <label>补充要求<el-input v-model="requirements" type="textarea" aria-label="补充要求" :rows="3" maxlength="8000" :disabled="store.submitting" placeholder="可填写重点场景、关注模块或文档要求" /></label>
    <div><el-button :loading="checking" :disabled="!projectId || !sourcePath.trim() || picking || store.submitting" @click="check">检查处理范围</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-if="preview" class="scope-preview" aria-label="处理范围预览">
      <p><strong>{{ preview.targetCount }} 个处理文件</strong> · {{ preview.moduleCount }} 个{{ preview.testProfile ? '构建模块' : '源码目录' }} · {{ preview.excludedCount }} 项排除</p>
      <p class="muted">开始执行时将重新核对并冻结实际源码。</p>
      <el-alert v-if="preview.configurationProblem" :title="preview.configurationProblem" type="warning" :closable="false" />
      <el-alert v-if="preview.targetCount === 0" title="没有适用的处理对象，请调整源码路径。" type="warning" :closable="false" />
      <ul v-if="preview.testProfile"><li v-for="module in preview.testProfile.modules" :key="module.root"><strong>{{ module.root === '.' ? '项目根模块' : module.root }}</strong> · {{ module.framework }}<p>测试：{{ module.testRoots.join('、') }}</p><p v-if="module.fixtureRoots.length">夹具：{{ module.fixtureRoots.join('、') }}</p></li></ul>
      <p v-else-if="definition.inputs?.documentOutputPath">输出位置：{{ preview.documentPath || '任务目录' }} 下的本次独立子目录</p>
      <details><summary>查看文件与排除原因</summary><ul class="scope-files"><li v-for="file in preview.files" :key="file.path">{{ file.path }}<span v-if="file.exclusion"> · {{ file.exclusion }}</span></li></ul><p v-if="preview.truncated" class="muted">这里只展示前 100 项，完整清单在开始后分页查看。</p></details>
    </div>
  </section>
</template>
<style scoped>
.source-fields, .source-fields label { display: grid; gap: 10px; min-width: 0; }.source-fields { gap: 18px; }.scope-preview { border: 1px solid var(--color-border-default); border-radius: var(--radius-control); padding: 16px; overflow-wrap: anywhere; }.scope-files { max-height: 280px; overflow: auto; }p { line-height: 1.6; }summary { cursor: pointer; }
</style>
