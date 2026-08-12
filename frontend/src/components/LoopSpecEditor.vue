<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import type { LoopSpec, LoopVerifierSpec } from '@/types/domain'

const props = withDefaults(defineProps<{
  modelValue: string
  ariaLabel?: string
}>(), { ariaLabel: 'LoopSpec 结构化编辑器' })

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const spec = ref<LoopSpec>()
const parseError = ref('')

const textAutosize = { minRows: 2, maxRows: 10 }
const compactAutosize = { minRows: 1, maxRows: 6 }
const createFreshOnVerifierFailure = computed({
  get: () => spec.value?.sessionPolicy?.createFreshOnVerifierFailure ?? true,
  set: (value: boolean) => {
    if (!spec.value) return
    spec.value.sessionPolicy = {
      reuseHealthySession: spec.value.sessionPolicy?.reuseHealthySession ?? true,
      createFreshOnVerifierFailure: value,
    }
  },
})

function defaultVerifier(): LoopVerifierSpec {
  return { type: 'PROCESS', command: [], processPurpose: 'TEST', criterionIds: [], testTargets: [] }
}

function normalizeVerifier(value: LoopVerifierSpec): LoopVerifierSpec {
  return structuredClone(value)
}

function normalizeSpec(value: LoopSpec): LoopSpec {
  return {
    ...value,
    schemaVersion: value.schemaVersion || 'v2',
    context: value.context ?? '',
    stages: (value.stages ?? []).map((stage) => ({
      ...stage,
      allowedPaths: [...(stage.allowedPaths ?? [])],
      forbiddenPaths: [...(stage.forbiddenPaths ?? [])],
      deliverables: [...(stage.deliverables ?? [])],
      acceptanceCriteria: [...(stage.acceptanceCriteria ?? [])],
      verifiers: (stage.verifiers ?? []).map(normalizeVerifier),
    })),
    limits: {
      maxStageAttempts: value.limits?.maxStageAttempts ?? 3,
      maxTaskAttempts: value.limits?.maxTaskAttempts ?? 12,
      sessionErrorLimit: value.limits?.sessionErrorLimit ?? 3,
      stagnationLimit: value.limits?.stagnationLimit ?? 2,
      maxDuration: value.limits?.maxDuration ?? 'PT2H',
      attemptTimeout: value.limits?.attemptTimeout ?? 'PT30M',
      verifierTimeout: value.limits?.verifierTimeout ?? 'PT10M',
    },
    model: { ...(value.model ?? {}) },
    sessionPolicy: {
      reuseHealthySession: value.sessionPolicy?.reuseHealthySession ?? true,
      createFreshOnVerifierFailure: value.sessionPolicy?.createFreshOnVerifierFailure ?? true,
    },
  }
}

watch(() => props.modelValue, (value) => {
  try {
    const parsed = normalizeSpec(JSON.parse(value) as LoopSpec)
    if (JSON.stringify(parsed, null, 2) !== JSON.stringify(spec.value, null, 2)) spec.value = parsed
    parseError.value = ''
  } catch (error) {
    parseError.value = error instanceof Error ? error.message : 'LoopSpec JSON 无法解析'
  }
}, { immediate: true })

const serialized = computed(() => spec.value ? JSON.stringify(spec.value, null, 2) : '')
watch(serialized, (value) => {
  if (value && value !== props.modelValue) emit('update:modelValue', value)
})

function addStage() {
  spec.value?.stages.push({
    objective: '描述这个阶段要完成的结果',
    allowedPaths: [],
    forbiddenPaths: [],
    deliverables: ['可验证实现'],
    acceptanceCriteria: [],
    verifiers: [],
  })
}

function removeStage(index: number) {
  if (!spec.value || spec.value.stages.length <= 1) return
  spec.value.stages.splice(index, 1)
}

function addVerifier(stageIndex: number) {
  spec.value?.stages[stageIndex]?.verifiers.push(defaultVerifier())
}

function removeVerifier(stageIndex: number, verifierIndex: number) {
  spec.value?.stages[stageIndex]?.verifiers.splice(verifierIndex, 1)
}

type StageListKey = 'allowedPaths' | 'forbiddenPaths' | 'deliverables'
function addStageListItem(stageIndex: number, key: StageListKey) {
  spec.value?.stages[stageIndex]?.[key].push('')
}

function addCriterion(stageIndex: number) {
  const criteria = spec.value?.stages[stageIndex]?.acceptanceCriteria
  if (!criteria) return
  const used = new Set(criteria.map((criterion) => criterion.id))
  let ordinal = criteria.length + 1
  while (used.has(`AC-${ordinal}`)) ordinal++
  criteria.push({ id: `AC-${ordinal}`, description: '' })
}

function removeCriterion(stageIndex: number, criterionIndex: number) {
  spec.value?.stages[stageIndex]?.acceptanceCriteria?.splice(criterionIndex, 1)
}

function enableRuntime(stageIndex: number, enabled: boolean) {
  const stage = spec.value?.stages[stageIndex]
  if (!stage) return
  stage.verificationRuntime = enabled ? { startCommand: [], readiness: { path: '/actuator/health', expectedStatus: 200 }, startupTimeoutSeconds: 60, shutdownTimeoutSeconds: 10 } : undefined
}

function removeStageListItem(stageIndex: number, key: StageListKey, itemIndex: number) {
  spec.value?.stages[stageIndex]?.[key].splice(itemIndex, 1)
}

type VerifierListKey = 'command' | 'allowedPaths' | 'forbiddenPaths' | 'criterionIds' | 'testTargets'
function verifierList(verifier: LoopVerifierSpec, key: VerifierListKey): string[] {
  if (!verifier[key]) verifier[key] = []
  return verifier[key] as string[]
}

function addVerifierListItem(verifier: LoopVerifierSpec, key: VerifierListKey) {
  verifierList(verifier, key).push('')
}

function removeVerifierListItem(verifier: LoopVerifierSpec, key: VerifierListKey, itemIndex: number) {
  verifierList(verifier, key).splice(itemIndex, 1)
}

function configureVerifier(verifier: LoopVerifierSpec) {
  verifier.criterionIds ??= []
  if (verifier.type === 'GIT_DIFF') {
    verifier.requireChanges ??= true
    verifier.forbidDeletes ??= true
  }
  if (verifier.type === 'PROCESS') { verifier.command ??= []; verifier.processPurpose ??= 'TEST'; verifier.testTargets ??= [] }
  if (verifier.type === 'HTTP_STATUS') { verifier.url ??= ''; verifier.httpMethod ??= 'GET'; verifier.expectedStatus ??= 200 }
  if (verifier.type === 'JSON_PATH') { verifier.url ??= ''; verifier.httpMethod ??= 'GET'; verifier.jsonPath ??= '$'; verifier.matchMode ??= 'EXACT' }
  if (verifier.type === 'FILE_CONTENT') { verifier.path ??= ''; verifier.expectedContent ??= '' }
  if (verifier.type === 'FILE_HASH') { verifier.path ??= ''; verifier.expectedSha256 ??= '' }
  if (verifier.type === 'JUNIT_XML' || verifier.type === 'FILE_EXISTS' || verifier.type === 'FILE_NOT_EXISTS') verifier.path ??= ''
  if (verifier.type === 'DATABASE_QUERY') { verifier.path ??= ''; verifier.sql ??= ''; verifier.expectedRowCount ??= 1 }
  if (verifier.type === 'BROWSER') { verifier.url ??= ''; verifier.assertions ??= [{ type: 'VISIBLE', selector: 'body' }] }
}
</script>

<template>
  <section class="loop-spec-form" :aria-label="ariaLabel">
    <div v-if="parseError" class="parse-alert" role="alert"><Icon icon="lucide:triangle-alert" />LoopSpec 数据无法展示：{{ parseError }}</div>
    <template v-else-if="spec">
      <section class="form-section overview-section">
        <header class="section-heading">
          <span class="section-icon cyan"><Icon icon="lucide:file-text" /></span>
          <div><p class="section-kicker">基本信息</p><h3>任务目标与执行上下文</h3><p>这些内容会原样交给执行 Agent。</p></div>
        </header>
        <div class="readonly-grid">
          <div><span>规范版本</span><strong>{{ spec.schemaVersion }}</strong></div>
          <div><span>项目 ID</span><strong class="mono">{{ spec.projectId }}</strong></div>
        </div>
        <label class="field-block">
          <span class="field-title">任务目标 <em>必填</em></span>
          <span class="field-help">清楚描述最终需要交付的结果。</span>
          <el-input v-model="spec.goal" type="textarea" :autosize="textAutosize" resize="none" aria-label="任务目标" />
        </label>
        <label class="field-block">
          <span class="field-title">执行上下文</span>
          <span class="field-help">补充仓库环境、技术约束、禁止事项与运行方式。</span>
          <el-input v-model="spec.context" type="textarea" :autosize="textAutosize" resize="none" aria-label="执行上下文" />
        </label>
      </section>

      <section class="stages-section">
        <div class="collection-heading">
          <div><p class="section-kicker">执行阶段</p><h3>分阶段交付与验收</h3><p>修改路径是给 Agent 的可选建议，只有明确列出的验收器会决定阶段是否通过。</p></div>
          <el-button plain size="small" @click="addStage"><Icon icon="lucide:plus" />添加阶段</el-button>
        </div>

        <article v-for="(stage, stageIndex) in spec.stages" :key="stageIndex" class="stage-card">
          <header class="stage-header">
            <div class="stage-number">{{ stageIndex + 1 }}</div>
            <div><span>阶段 {{ stageIndex + 1 }}</span><strong>{{ stage.objective || '尚未填写阶段目标' }}</strong></div>
            <el-button text type="danger" :disabled="spec.stages.length <= 1" aria-label="删除阶段" @click="removeStage(stageIndex)"><Icon icon="lucide:trash-2" /></el-button>
          </header>

          <div class="stage-body">
            <label class="field-block">
              <span class="field-title">阶段目标 <em>必填</em></span>
              <span class="field-help">描述本阶段结束时可观察、可验证的结果。</span>
              <el-input v-model="stage.objective" type="textarea" :autosize="textAutosize" resize="none" :aria-label="`阶段 ${stageIndex + 1} 目标`" />
            </label>

            <div class="boundary-grid">
              <section class="list-field allowed-list">
                <header><div><span>建议修改路径</span><small>仅作为 Agent 提示，不会自动加入验收</small></div><button type="button" aria-label="添加允许路径" @click="addStageListItem(stageIndex, 'allowedPaths')"><Icon icon="lucide:plus" /></button></header>
                <div v-for="(_, itemIndex) in stage.allowedPaths" :key="itemIndex" class="list-row">
                  <el-input v-model="stage.allowedPaths[itemIndex]" type="textarea" :autosize="compactAutosize" resize="none" class="mono" :aria-label="`阶段 ${stageIndex + 1} 允许路径 ${itemIndex + 1}`" placeholder="src/**" />
                  <button type="button" aria-label="删除允许路径" @click="removeStageListItem(stageIndex, 'allowedPaths', itemIndex)"><Icon icon="lucide:x" /></button>
                </div>
                <button v-if="stage.allowedPaths.length === 0" type="button" class="empty-add" @click="addStageListItem(stageIndex, 'allowedPaths')">+ 添加路径</button>
              </section>

              <section class="list-field forbidden-list">
                <header><div><span>建议避让路径</span><small>仅作为 Agent 提示，不会自动加入验收</small></div><button type="button" aria-label="添加禁止路径" @click="addStageListItem(stageIndex, 'forbiddenPaths')"><Icon icon="lucide:plus" /></button></header>
                <div v-for="(_, itemIndex) in stage.forbiddenPaths" :key="itemIndex" class="list-row">
                  <el-input v-model="stage.forbiddenPaths[itemIndex]" type="textarea" :autosize="compactAutosize" resize="none" class="mono" :aria-label="`阶段 ${stageIndex + 1} 禁止路径 ${itemIndex + 1}`" placeholder="data/**" />
                  <button type="button" aria-label="删除禁止路径" @click="removeStageListItem(stageIndex, 'forbiddenPaths', itemIndex)"><Icon icon="lucide:x" /></button>
                </div>
                <button v-if="stage.forbiddenPaths.length === 0" type="button" class="empty-add" @click="addStageListItem(stageIndex, 'forbiddenPaths')">+ 添加路径</button>
              </section>
            </div>

            <section class="list-field deliverables-list">
              <header><div><span>交付物</span><small>逐项写明本阶段需要产出的文件或结果</small></div><button type="button" aria-label="添加交付物" @click="addStageListItem(stageIndex, 'deliverables')"><Icon icon="lucide:plus" /></button></header>
              <div v-for="(_, itemIndex) in stage.deliverables" :key="itemIndex" class="list-row">
                <el-input v-model="stage.deliverables[itemIndex]" type="textarea" :autosize="compactAutosize" resize="none" :aria-label="`阶段 ${stageIndex + 1} 交付物 ${itemIndex + 1}`" placeholder="例如：可编译运行的实现与验证结果" />
                <button type="button" aria-label="删除交付物" @click="removeStageListItem(stageIndex, 'deliverables', itemIndex)"><Icon icon="lucide:x" /></button>
              </div>
              <button v-if="stage.deliverables.length === 0" type="button" class="empty-add" @click="addStageListItem(stageIndex, 'deliverables')">+ 添加交付物</button>
            </section>

            <section v-if="spec.schemaVersion === 'v2'" class="list-field criteria-list">
              <header><div><span>行为验收条件</span><small>每项都必须由至少一个行为验收器覆盖</small></div><button type="button" aria-label="添加验收条件" @click="addCriterion(stageIndex)"><Icon icon="lucide:plus" /></button></header>
              <div v-for="(criterion, criterionIndex) in stage.acceptanceCriteria" :key="criterionIndex" class="criterion-row">
                <el-input v-model="criterion.id" class="mono" placeholder="AC-1" :aria-label="`阶段 ${stageIndex + 1} 验收条件 ID ${criterionIndex + 1}`" />
                <el-input v-model="criterion.description" type="textarea" :autosize="compactAutosize" resize="none" placeholder="描述用户可观察、可判定的行为结果" :aria-label="`阶段 ${stageIndex + 1} 验收条件 ${criterionIndex + 1}`" />
                <button type="button" aria-label="删除验收条件" @click="removeCriterion(stageIndex, criterionIndex)"><Icon icon="lucide:x" /></button>
              </div>
              <button v-if="!stage.acceptanceCriteria?.length" type="button" class="empty-add" @click="addCriterion(stageIndex)">+ 添加行为验收条件</button>
            </section>

            <section v-if="spec.schemaVersion === 'v2'" class="runtime-block">
              <header class="runtime-heading"><div><span>托管临时运行时</span><small>HTTP、JSON、浏览器验收必须验证本阶段启动的实例</small></div><el-switch :model-value="Boolean(stage.verificationRuntime)" aria-label="启用托管临时运行时" @change="enableRuntime(stageIndex, Boolean($event))" /></header>
              <template v-if="stage.verificationRuntime">
                <section class="list-field nested-list"><header><div><span>启动命令</span><small>直接 argv；必须使用 LOOPPER_PORT 动态端口占位符</small></div><button type="button" aria-label="添加运行时命令参数" @click="stage.verificationRuntime.startCommand.push('')"><Icon icon="lucide:plus" /></button></header><div v-for="(_, itemIndex) in stage.verificationRuntime.startCommand" :key="itemIndex" class="list-row"><el-input v-model="stage.verificationRuntime.startCommand[itemIndex]" class="mono" :placeholder="itemIndex === 0 ? 'java' : '--server.port={{LOOPPER_PORT}}'" /><button type="button" aria-label="删除运行时命令参数" @click="stage.verificationRuntime.startCommand.splice(itemIndex, 1)"><Icon icon="lucide:x" /></button></div></section>
                <div class="runtime-grid"><label class="field-block compact-field"><span class="field-title">就绪路径</span><el-input v-model="stage.verificationRuntime.readiness.path" class="mono" placeholder="/actuator/health" /></label><label class="field-block compact-field"><span class="field-title">期望状态码</span><el-input-number v-model="stage.verificationRuntime.readiness.expectedStatus" :min="100" :max="599" /></label><label class="field-block compact-field"><span class="field-title">JSON Path（可选）</span><el-input v-model="stage.verificationRuntime.readiness.jsonPath" class="mono" placeholder="$.status" /></label><label class="field-block compact-field"><span class="field-title">JSON 匹配方式</span><el-select v-model="stage.verificationRuntime.readiness.matchMode"><el-option label="精确" value="EXACT" /><el-option label="包含" value="CONTAINS" /><el-option label="存在" value="EXISTS" /></el-select></label><label class="field-block compact-field"><span class="field-title">期望值（可选）</span><el-input v-model="stage.verificationRuntime.readiness.expectedValue" /></label><label class="field-block compact-field"><span class="field-title">启动超时（秒）</span><el-input-number v-model="stage.verificationRuntime.startupTimeoutSeconds" :min="1" :max="600" /></label><label class="field-block compact-field"><span class="field-title">停止超时（秒）</span><el-input-number v-model="stage.verificationRuntime.shutdownTimeoutSeconds" :min="1" :max="120" /></label></div>
              </template>
            </section>

            <section class="verifiers-block">
              <header class="verifiers-heading"><div><span>验收器</span><small>后台会按照这些规则判定本阶段是否通过</small></div><el-button plain size="small" @click="addVerifier(stageIndex)"><Icon icon="lucide:plus" />添加验收器</el-button></header>
              <article v-for="(verifier, verifierIndex) in stage.verifiers" :key="verifierIndex" class="verifier-card">
                <header><span class="verifier-index">验收 {{ verifierIndex + 1 }}</span><el-button text type="danger" aria-label="删除验收器" @click="removeVerifier(stageIndex, verifierIndex)"><Icon icon="lucide:trash-2" /></el-button></header>
                <div class="verifier-grid">
                  <label class="field-block compact-field"><span class="field-title">验收类型</span><el-select v-model="verifier.type" filterable style="width:100%" :aria-label="`阶段 ${stageIndex + 1} 验收器 ${verifierIndex + 1} 类型`" @change="configureVerifier(verifier)"><el-option label="运行命令" value="PROCESS" /><el-option label="HTTP 状态" value="HTTP_STATUS" /><el-option label="JSON 断言" value="JSON_PATH" /><el-option label="浏览器验收" value="BROWSER" /><el-option label="数据库查询" value="DATABASE_QUERY" /><el-option label="文件内容" value="FILE_CONTENT" /><el-option label="文件哈希" value="FILE_HASH" /><el-option label="JUnit XML 报告" value="JUNIT_XML" /><el-option label="Git 差异检查" value="GIT_DIFF" /><el-option label="文件必须不存在" value="FILE_NOT_EXISTS" /><el-option label="旧版文件存在提示" value="FILE_EXISTS" /></el-select></label>
                  <label v-if="verifier.type === 'PROCESS' && spec.schemaVersion === 'v2'" class="field-block compact-field"><span class="field-title">命令用途</span><el-select v-model="verifier.processPurpose" style="width:100%"><el-option label="聚焦测试" value="TEST" /><el-option label="自检" value="SELF_CHECK" /><el-option label="构建/静态检查" value="BUILD" /></el-select></label>
                  <label v-if="verifier.type === 'FILE_EXISTS' || verifier.type === 'FILE_NOT_EXISTS'" class="field-block compact-field"><span class="field-title">目标路径</span><el-input v-model="verifier.path" type="textarea" :autosize="compactAutosize" resize="none" class="mono" placeholder="src/main/java/App.java" /></label>
                  <label v-if="['FILE_CONTENT','FILE_HASH','JUNIT_XML','DATABASE_QUERY'].includes(verifier.type)" class="field-block compact-field"><span class="field-title">目标路径</span><el-input v-model="verifier.path" class="mono" placeholder="target/results.db" /></label>
                  <label v-if="['HTTP_STATUS','JSON_PATH','BROWSER'].includes(verifier.type)" class="field-block compact-field full-width"><span class="field-title">URL</span><el-input v-model="verifier.url" class="mono" placeholder="http://127.0.0.1:{{LOOPPER_PORT}}/api/health" /></label>
                  <label v-if="['HTTP_STATUS','JSON_PATH'].includes(verifier.type)" class="field-block compact-field"><span class="field-title">HTTP 方法</span><el-select v-model="verifier.httpMethod"><el-option label="GET" value="GET" /><el-option label="HEAD" value="HEAD" /></el-select></label>
                  <label v-if="verifier.type === 'HTTP_STATUS'" class="field-block compact-field"><span class="field-title">期望状态码</span><el-input-number v-model="verifier.expectedStatus" :min="100" :max="599" /></label>
                  <label v-if="verifier.type === 'JSON_PATH'" class="field-block compact-field"><span class="field-title">JSON Path</span><el-input v-model="verifier.jsonPath" class="mono" placeholder="$.status" /></label>
                  <label v-if="verifier.type === 'JSON_PATH'" class="field-block compact-field"><span class="field-title">匹配方式</span><el-select v-model="verifier.matchMode"><el-option label="精确" value="EXACT" /><el-option label="包含" value="CONTAINS" /><el-option label="存在" value="EXISTS" /></el-select></label>
                  <label v-if="verifier.type === 'JSON_PATH'" class="field-block compact-field"><span class="field-title">期望值</span><el-input v-model="verifier.expectedValue" /></label>
                  <label v-if="verifier.type === 'FILE_CONTENT'" class="field-block compact-field full-width"><span class="field-title">期望内容</span><el-input v-model="verifier.expectedContent" type="textarea" :autosize="compactAutosize" /></label>
                  <label v-if="verifier.type === 'FILE_HASH'" class="field-block compact-field full-width"><span class="field-title">SHA-256</span><el-input v-model="verifier.expectedSha256" class="mono" /></label>
                  <label v-if="verifier.type === 'DATABASE_QUERY'" class="field-block compact-field full-width"><span class="field-title">只读 SQL</span><el-input v-model="verifier.sql" type="textarea" :autosize="compactAutosize" class="mono" /></label>
                  <label v-if="verifier.type === 'DATABASE_QUERY'" class="field-block compact-field"><span class="field-title">期望行数</span><el-input-number v-model="verifier.expectedRowCount" :min="0" /></label>
                  <label v-if="verifier.type === 'PROCESS'" class="field-block compact-field full-width"><span class="field-title">输出必须包含（可选）</span><el-input v-model="verifier.outputContains" type="textarea" :autosize="compactAutosize" resize="none" placeholder="例如：BUILD SUCCESS" /></label>
                </div>

                <section v-if="spec.schemaVersion === 'v2'" class="list-field nested-list"><header><div><span>覆盖的验收条件</span><small>仅 BEHAVIOR 分类会形成有效覆盖</small></div><button type="button" aria-label="添加验收条件映射" @click="addVerifierListItem(verifier, 'criterionIds')"><Icon icon="lucide:plus" /></button></header><div v-for="(_, itemIndex) in verifierList(verifier, 'criterionIds')" :key="itemIndex" class="list-row"><el-select v-model="verifier.criterionIds![itemIndex]" style="width:100%"><el-option v-for="criterion in stage.acceptanceCriteria" :key="criterion.id" :label="`${criterion.id} · ${criterion.description}`" :value="criterion.id" /></el-select><button type="button" aria-label="删除验收条件映射" @click="removeVerifierListItem(verifier, 'criterionIds', itemIndex)"><Icon icon="lucide:x" /></button></div></section>

                <section v-if="verifier.type === 'PROCESS'" class="list-field nested-list">
                  <header><div><span>命令参数</span><small>每个参数独立一项，不经过 Shell 拼接</small></div><button type="button" aria-label="添加命令参数" @click="addVerifierListItem(verifier, 'command')"><Icon icon="lucide:plus" /></button></header>
                  <div v-for="(_, itemIndex) in verifierList(verifier, 'command')" :key="itemIndex" class="list-row"><el-input v-model="verifier.command![itemIndex]" type="textarea" :autosize="compactAutosize" resize="none" class="mono" :placeholder="itemIndex === 0 ? 'mvn' : 'test'" /><button type="button" aria-label="删除命令参数" @click="removeVerifierListItem(verifier, 'command', itemIndex)"><Icon icon="lucide:x" /></button></div>
                </section>
                <section v-if="verifier.type === 'PROCESS' && verifier.processPurpose === 'TEST' && spec.schemaVersion === 'v2'" class="list-field nested-list"><header><div><span>测试目标</span><small>明确类、文件或测试场景</small></div><button type="button" aria-label="添加测试目标" @click="addVerifierListItem(verifier, 'testTargets')"><Icon icon="lucide:plus" /></button></header><div v-for="(_, itemIndex) in verifierList(verifier, 'testTargets')" :key="itemIndex" class="list-row"><el-input v-model="verifier.testTargets![itemIndex]" class="mono" placeholder="UserServiceTest" /><button type="button" aria-label="删除测试目标" @click="removeVerifierListItem(verifier, 'testTargets', itemIndex)"><Icon icon="lucide:x" /></button></div></section>
                <section v-if="verifier.type === 'BROWSER'" class="list-field nested-list"><header><div><span>浏览器断言</span></div><button type="button" aria-label="添加浏览器断言" @click="verifier.assertions!.push({ type: 'VISIBLE', selector: 'body' })"><Icon icon="lucide:plus" /></button></header><div v-for="(assertion, assertionIndex) in verifier.assertions" :key="assertionIndex" class="browser-assertion"><el-select v-model="assertion.type"><el-option label="存在" value="EXISTS" /><el-option label="可见" value="VISIBLE" /><el-option label="文本包含" value="TEXT_CONTAINS" /><el-option label="数量" value="COUNT" /><el-option label="属性相等" value="ATTRIBUTE_EQUALS" /></el-select><el-input v-model="assertion.selector" class="mono" placeholder="[data-testid=save]" /><el-input v-if="assertion.type === 'ATTRIBUTE_EQUALS'" v-model="assertion.attribute" placeholder="属性名" /><el-input-number v-if="assertion.type === 'COUNT'" v-model="assertion.expectedCount" :min="0" /><el-input v-else-if="['TEXT_CONTAINS','ATTRIBUTE_EQUALS'].includes(assertion.type)" v-model="assertion.value" placeholder="期望值" /><span v-else></span><button type="button" aria-label="删除浏览器断言" @click="verifier.assertions!.splice(assertionIndex,1)"><Icon icon="lucide:x" /></button></div></section>

                <template v-if="verifier.type === 'GIT_DIFF'">
                  <div class="switch-grid"><el-switch v-model="verifier.requireChanges" active-text="必须产生改动" /><el-switch v-model="verifier.forbidDeletes" active-text="禁止删除文件" /></div>
                  <div class="boundary-grid verifier-boundaries">
                    <section class="list-field nested-list allowed-list"><header><div><span>差异允许路径</span></div><button type="button" aria-label="添加差异允许路径" @click="addVerifierListItem(verifier, 'allowedPaths')"><Icon icon="lucide:plus" /></button></header><div v-for="(_, itemIndex) in verifierList(verifier, 'allowedPaths')" :key="itemIndex" class="list-row"><el-input v-model="verifier.allowedPaths![itemIndex]" type="textarea" :autosize="compactAutosize" resize="none" class="mono" /><button type="button" aria-label="删除差异允许路径" @click="removeVerifierListItem(verifier, 'allowedPaths', itemIndex)"><Icon icon="lucide:x" /></button></div></section>
                    <section class="list-field nested-list forbidden-list"><header><div><span>差异禁止路径</span></div><button type="button" aria-label="添加差异禁止路径" @click="addVerifierListItem(verifier, 'forbiddenPaths')"><Icon icon="lucide:plus" /></button></header><div v-for="(_, itemIndex) in verifierList(verifier, 'forbiddenPaths')" :key="itemIndex" class="list-row"><el-input v-model="verifier.forbiddenPaths![itemIndex]" type="textarea" :autosize="compactAutosize" resize="none" class="mono" /><button type="button" aria-label="删除差异禁止路径" @click="removeVerifierListItem(verifier, 'forbiddenPaths', itemIndex)"><Icon icon="lucide:x" /></button></div></section>
                  </div>
                </template>
              </article>
              <button v-if="stage.verifiers.length === 0" type="button" class="empty-verifier" @click="addVerifier(stageIndex)"><Icon icon="lucide:badge-check" />添加本阶段的第一个验收器</button>
            </section>
          </div>
        </article>
      </section>

      <slot name="after-stages" />

      <section class="form-section limits-section">
        <header class="section-heading"><span class="section-icon violet"><Icon icon="lucide:gauge" /></span><div><p class="section-kicker">调度限制</p><h3>重试次数与超时时间</h3><p>限制 Agent 循环的最大成本，避免任务无限运行。</p></div></header>
        <div class="limits-grid">
          <label><span>每阶段最大尝试次数</span><el-input-number v-model="spec.limits.maxStageAttempts" :min="1" :max="50" /></label>
          <label><span>整个任务最大尝试次数</span><el-input-number v-model="spec.limits.maxTaskAttempts" :min="1" :max="100" /></label>
          <label><span>连续停滞阈值</span><el-input-number v-model="spec.limits.stagnationLimit" :min="1" :max="20" aria-label="连续停滞阈值" /></label>
          <label><span>任务最长运行时间</span><el-input v-model="spec.limits.maxDuration" class="mono" placeholder="PT2H" /><small>支持 ISO-8601（PT2H）或秒数（7200）</small></label>
          <label><span>单次尝试超时</span><el-input v-model="spec.limits.attemptTimeout" class="mono" placeholder="PT30M" /><small>支持 ISO-8601（PT30M）或秒数（1800）</small></label>
        </div>
        <div class="retry-policy-grid">
          <label class="switch-field"><span>验证失败后自动新建 Session</span><el-switch v-model="createFreshOnVerifierFailure" aria-label="验证失败后自动新建 Session" /></label>
          <label class="field-block">
            <span class="field-title">下一轮提示模板</span>
            <span class="field-help">支持服务端限定的 Attempt、失败摘要、验证结果、变更路径和工作区指纹占位符。</span>
            <el-input v-model="spec.nextAttemptPromptTemplate" type="textarea" :autosize="textAutosize" resize="none" aria-label="下一轮提示模板" />
          </label>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.loop-spec-form { display: grid; gap: 16px; padding: 0 20px 20px; color: var(--color-text-primary); }
.form-section, .stage-card { overflow: hidden; border: 1px solid rgb(71 85 105 / 52%); border-radius: 14px; background: linear-gradient(145deg, rgb(15 23 42 / 82%), rgb(7 12 23 / 72%)); box-shadow: 0 12px 34px rgb(0 0 0 / 14%); }
.form-section { padding: 18px; }
.section-heading { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 18px; padding-bottom: 14px; border-bottom: 1px solid rgb(71 85 105 / 42%); }
.section-icon { display: grid; flex: 0 0 auto; place-items: center; width: 36px; height: 36px; border: 1px solid; border-radius: 10px; }
.section-icon.cyan { border-color: rgb(34 211 238 / 28%); color: var(--color-accent-cyan); background: rgb(34 211 238 / 8%); }
.section-icon.violet { border-color: rgb(139 92 246 / 30%); color: #c4b5fd; background: rgb(139 92 246 / 9%); }
.section-heading h3, .collection-heading h3 { margin: 2px 0 4px; font-size: 14px; letter-spacing: -.01em; }
.section-heading p:last-child, .collection-heading p:last-child { margin: 0; color: var(--color-text-muted); font-size: 10px; line-height: 1.5; }
.section-kicker { margin: 0; color: var(--color-accent-cyan); font-family: var(--font-code); font-size: 9px; font-weight: 750; letter-spacing: .12em; text-transform: uppercase; }
.readonly-grid { display: grid; grid-template-columns: 110px minmax(0, 1fr); gap: 8px; margin-bottom: 16px; }
.readonly-grid div { min-width: 0; padding: 10px 11px; border: 1px solid rgb(71 85 105 / 35%); border-radius: 9px; background: rgb(2 6 23 / 28%); }
.readonly-grid span { display: block; margin-bottom: 4px; color: var(--color-text-muted); font-size: 9px; }
.readonly-grid strong { display: block; overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.field-block { display: block; margin-top: 14px; }
.field-title { display: block; color: var(--color-text-primary); font-size: 11px; font-weight: 700; }
.field-title em { margin-left: 4px; color: var(--color-danger); font-size: 9px; font-style: normal; }
.field-help { display: block; margin: 4px 0 8px; color: var(--color-text-muted); font-size: 9px; line-height: 1.45; }
.loop-spec-form :deep(.el-input__inner), .loop-spec-form :deep(.el-textarea__inner), .loop-spec-form :deep(.el-select__selected-item) { font-family: var(--font-ui); font-size: 12px; font-weight: 450; letter-spacing: 0; }
.loop-spec-form :deep(.el-input__inner), .loop-spec-form :deep(.el-textarea__inner) { color: var(--color-text-secondary); }
.loop-spec-form :deep(.el-input__inner::placeholder), .loop-spec-form :deep(.el-textarea__inner::placeholder) { color: var(--color-text-muted); font-weight: 400; opacity: .8; }
.field-block :deep(.el-textarea__inner) { padding: 10px 11px; line-height: 1.62; }
.collection-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 14px; padding: 2px 2px 0; }
.stages-section { display: grid; gap: 12px; }
.stage-card { border-color: rgb(59 130 246 / 28%); }
.stage-header { display: grid; grid-template-columns: 36px minmax(0, 1fr) auto; align-items: center; gap: 11px; padding: 13px 15px; border-bottom: 1px solid rgb(59 130 246 / 18%); background: linear-gradient(90deg, rgb(59 130 246 / 10%), transparent); }
.stage-number { display: grid; place-items: center; width: 32px; height: 32px; border: 1px solid rgb(34 211 238 / 26%); border-radius: 9px; color: var(--color-accent-cyan); background: rgb(34 211 238 / 7%); font-family: var(--font-code); font-size: 12px; font-weight: 800; }
.stage-header div:nth-child(2) { min-width: 0; }
.stage-header span { display: block; color: var(--color-accent-cyan); font-family: var(--font-code); font-size: 8px; font-weight: 700; letter-spacing: .1em; text-transform: uppercase; }
.stage-header strong { display: block; margin-top: 3px; overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.stage-body { padding: 4px 15px 16px; }
.boundary-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin-top: 14px; }
.list-field { padding: 11px; border: 1px solid rgb(71 85 105 / 42%); border-radius: 10px; background: rgb(2 6 23 / 26%); }
.list-field > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; margin-bottom: 8px; }
.list-field > header span { display: block; font-size: 10px; font-weight: 700; }
.list-field > header small { display: block; margin-top: 3px; color: var(--color-text-muted); font-size: 8px; line-height: 1.35; }
.list-field button { display: grid; flex: 0 0 auto; place-items: center; width: 25px; height: 25px; padding: 0; border: 1px solid rgb(71 85 105 / 45%); border-radius: 7px; color: var(--color-text-muted); background: rgb(15 23 42 / 65%); cursor: pointer; }
.list-field button:hover { border-color: var(--color-accent-cyan); color: var(--color-accent-cyan); }
.allowed-list { border-color: rgb(34 197 94 / 20%); }
.allowed-list > header span { color: #86efac; }
.forbidden-list { border-color: rgb(248 113 113 / 20%); }
.forbidden-list > header span { color: #fca5a5; }
.list-row { display: grid; grid-template-columns: minmax(0, 1fr) 25px; align-items: start; gap: 6px; margin-top: 6px; }
.list-row :deep(.el-textarea__inner) { min-height: 32px !important; padding: 7px 8px; font-size: 10px; line-height: 1.45; }
.deliverables-list { margin-top: 10px; }
.criteria-list, .runtime-block { margin-top: 10px; }
.criterion-row { display: grid; grid-template-columns: 100px minmax(0, 1fr) 25px; align-items: start; gap: 6px; margin-top: 6px; }
.runtime-block { padding: 11px; border: 1px solid rgb(34 211 238 / 22%); border-radius: 10px; background: rgb(8 47 73 / 14%); }
.runtime-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.runtime-heading span { display: block; font-size: 10px; font-weight: 700; }
.runtime-heading small { display: block; margin-top: 3px; color: var(--color-text-muted); font-size: 8px; }
.runtime-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin-top: 9px; }
.browser-assertion { display: grid; grid-template-columns: 120px minmax(0, 1fr) minmax(0, 1fr) minmax(0, 1fr) 25px; gap: 6px; margin-top: 6px; }
.empty-add { width: 100% !important; color: var(--color-text-muted) !important; font-size: 9px; }
.verifiers-block { margin-top: 14px; padding-top: 14px; border-top: 1px solid rgb(71 85 105 / 42%); }
.verifiers-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 9px; }
.verifiers-heading span { display: block; font-size: 11px; font-weight: 750; }
.verifiers-heading small { display: block; margin-top: 3px; color: var(--color-text-muted); font-size: 8px; }
.verifier-card { margin-top: 8px; padding: 11px; border: 1px solid rgb(139 92 246 / 22%); border-radius: 11px; background: linear-gradient(135deg, rgb(139 92 246 / 7%), rgb(2 6 23 / 22%)); }
.verifier-card > header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.verifier-index { color: #c4b5fd; font-family: var(--font-code); font-size: 9px; font-weight: 750; letter-spacing: .08em; text-transform: uppercase; }
.verifier-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.compact-field { margin-top: 0; }
.compact-field .field-title { margin-bottom: 6px; font-size: 9px; }
.full-width { grid-column: 1 / -1; }
.nested-list { margin-top: 9px; padding: 9px; }
.nested-list > header { margin-bottom: 5px; }
.switch-grid { display: flex; flex-wrap: wrap; gap: 18px; margin-top: 10px; }
.verifier-boundaries { margin-top: 9px; }
.empty-verifier { display: flex; align-items: center; justify-content: center; gap: 7px; width: 100%; padding: 13px; border: 1px dashed rgb(139 92 246 / 28%); border-radius: 10px; color: #c4b5fd; background: rgb(139 92 246 / 5%); cursor: pointer; }
.limits-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.limits-grid label { display: grid; gap: 7px; min-width: 0; }
.limits-grid label > span { color: var(--color-text-secondary); font-size: 9px; font-weight: 650; }
.limits-grid label > small { color: var(--color-text-muted); font-size: 8px; }
.limits-grid :deep(.el-input-number) { width: 100%; }
.retry-policy-grid { display: grid; gap: 12px; margin-top: 16px; padding-top: 14px; border-top: 1px solid rgb(71 85 105 / 42%); }
.switch-field { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: var(--color-text-secondary); font-size: 10px; font-weight: 650; }
.parse-alert { display: flex; align-items: center; gap: 8px; padding: 14px; border: 1px solid rgb(248 113 113 / 32%); border-radius: 10px; color: #fecaca; background: rgb(239 68 68 / 8%); font-size: 11px; }
.loop-spec-form .mono :deep(.el-textarea__inner), .loop-spec-form .mono :deep(.el-input__inner) { font-family: var(--font-code); font-size: 10px; font-weight: 450; }
@media (max-width: 720px) {
  .readonly-grid, .boundary-grid, .verifier-grid, .limits-grid, .runtime-grid, .criterion-row, .browser-assertion { grid-template-columns: 1fr; }
  .collection-heading, .verifiers-heading { align-items: stretch; flex-direction: column; }
}
</style>
