<script setup lang="ts">
import { Icon } from '@iconify/vue'
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
    <section class="parameter-section" aria-label="源码与输出">
      <div class="section-heading"><span class="section-number">02</span><h3>源码与输出</h3></div>
      <div class="parameter-field">
        <label class="field-title" for="template-source-path">源码路径 <small>必填</small></label>
        <DirectoryPathInput v-model="sourcePath" v-model:picking="pickingSource" input-id="template-source-path" label="源码路径" :scope-key="`${projectId}:${definition.id}`" :disabled="store.submitting" placeholder="例如 src/main/java，也可填写单个源码文件" />
        <p class="field-help">选择项目内的目录或文件，目录下的源码将递归处理。</p>
      </div>
      <div v-if="definition.inputs?.testOutputPath" class="parameter-field">
        <label class="field-title" for="template-test-path">测试目录 <small>可选 · 自动识别</small></label>
        <DirectoryPathInput v-model="testPath" v-model:picking="pickingTest" input-id="template-test-path" label="测试目录" :scope-key="`${projectId}:${definition.id}`" :disabled="store.submitting" placeholder="留空按构建配置与已有测试自动识别" />
        <p class="field-help">在测试与夹具目录内补齐测试，保留业务源码和已有有效测试。</p>
      </div>
      <div v-if="definition.inputs?.documentOutputPath" class="parameter-field">
        <label class="field-title" for="template-document-path">文档目录 <small>可选</small></label>
        <DirectoryPathInput v-model="outputPath" v-model:picking="pickingOutput" input-id="template-document-path" label="文档目录" :scope-key="`${projectId}:${definition.id}`" :disabled="store.submitting" placeholder="留空使用项目默认目录或任务目录" />
        <p class="field-help">文档保存到本次任务的独立子目录，包含模块详情、流程图与源码覆盖清单。</p>
      </div>
    </section>
    <section class="parameter-section" aria-label="补充要求">
      <div class="section-heading"><span class="section-number">03</span><h3>补充要求</h3><span class="section-note">可选</span></div>
      <el-input v-model="requirements" type="textarea" aria-label="补充要求" :rows="2" maxlength="8000" :disabled="store.submitting"
        :placeholder="definition.inputs?.testOutputPath ? '例如：优先覆盖异常分支、边界条件和权限校验' : '例如：重点说明模块职责、接口调用与异常处理流程'" />
    </section>
    <section class="scope-check" aria-label="范围检查">
      <div class="scope-check-heading"><div><strong>处理范围</strong><p class="field-help">检查文件与输出位置后，即可创建任务。</p></div>
        <el-button :loading="checking" :disabled="!projectId || !sourcePath.trim() || picking || store.submitting" @click="check"><Icon icon="lucide:scan-search" />检查处理范围</el-button>
      </div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <div v-if="preview" class="scope-preview" aria-label="处理范围预览" aria-live="polite">
        <div class="scope-stats"><strong>{{ preview.targetCount }} 个处理文件</strong><span>{{ preview.moduleCount }} 个{{ preview.testProfile ? '构建模块' : '源码目录' }}</span><span>{{ preview.excludedCount }} 项排除</span></div>
        <el-alert v-if="preview.configurationProblem" :title="preview.configurationProblem" type="warning" :closable="false" />
        <el-alert v-if="preview.targetCount === 0" title="没有适用的处理对象，请调整源码路径。" type="warning" :closable="false" />
        <ul v-if="preview.testProfile" class="module-list"><li v-for="module in preview.testProfile.modules" :key="module.root"><strong>{{ module.root === '.' ? '项目根模块' : module.root }}</strong><span class="module-framework">{{ module.framework }}</span><p>测试：{{ module.testRoots.join('、') }}</p><p v-if="module.fixtureRoots.length">夹具：{{ module.fixtureRoots.join('、') }}</p></li></ul>
        <p v-else-if="definition.inputs?.documentOutputPath" class="output-location">输出位置：{{ preview.documentPath || '任务目录' }} 下的本次独立子目录</p>
        <details><summary>查看文件与排除原因</summary><ul class="scope-files"><li v-for="file in preview.files" :key="file.path">{{ file.path }}<span v-if="file.exclusion"> · {{ file.exclusion }}</span></li></ul><p v-if="preview.truncated" class="field-help">这里只展示前 100 项，完整清单在开始后分页查看。</p></details>
        <p class="field-help">开始执行时将重新核对并冻结实际源码。</p>
      </div>
    </section>
  </section>
</template>
<style scoped>
.source-fields { display: grid; align-content: start; gap: 24px; min-width: 0; }
.scope-check { display: grid; gap: 14px; padding: 16px; border: 1px solid var(--color-border-default); border-radius: var(--radius-control); background: var(--shade-fill-lighter); }
.scope-check-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }
.scope-check-heading strong { display: block; margin-bottom: 4px; font-size: 13px; }
.scope-check-heading > .el-button { min-height: 36px; margin: 0; }
.scope-check-heading :deep(.el-button > span) { gap: 6px; }
.scope-preview { display: grid; gap: 12px; min-width: 0; padding-top: 14px; border-top: 1px solid var(--color-border-default); overflow-wrap: anywhere; font-size: 12px; line-height: 1.6; }
.scope-stats { display: flex; flex-wrap: wrap; gap: 8px 16px; color: var(--color-text-secondary); }
.scope-stats strong { color: var(--color-text-primary); }
.module-list { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.module-list li { padding: 10px 12px; background: var(--color-bg-surface); border-radius: var(--radius-control); }
.module-list p { margin: 4px 0 0; color: var(--color-text-secondary); }
.module-framework { margin-left: 10px; color: var(--color-text-secondary); }
.output-location { margin: 0; }
.scope-files { max-height: 240px; overflow: auto; padding-left: 20px; }
.scope-files li + li { margin-top: 6px; }
summary { cursor: pointer; color: var(--color-action-primary); }
</style>
