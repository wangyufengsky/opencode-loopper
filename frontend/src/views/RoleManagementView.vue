<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import PageHeader from '@/components/PageHeader.vue'
import { api, ApiError } from '@/api/client'
import type {
  Project, RoleCatalogItem, RoleComparison, RoleDetail,
  RoleImportValidation, RolePermissionPreview, RoleRevision, RoleRevisionSummary, RoleSlotBinding,
} from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import { roleToolDescription, stableToolName, workflowForSlot } from '@/utils/rolePresentation'

const PAGE_SIZE = 12
const roles = ref<RoleCatalogItem[]>([])
const listCursor = ref('')
const nextCursor = ref<string | null>(null)
const previousCursors = ref<string[]>([])
const search = ref('')
const appliedSearch = ref('')
const listLoading = ref(false)
const listError = ref('')
const listRetryCursor = ref('')
const listRetryAction = ref<'load' | 'next' | 'previous'>('load')
let listGeneration = 0

const selected = ref<RoleDetail | null>(null)
const requestedRoleId = ref('')
const detailLoading = ref(false)
const detailError = ref('')
const activeTab = ref<'overview' | 'permissions' | 'prompt' | 'history'>('overview')
const tabs = [
  { key: 'overview', label: '描述' },
  { key: 'permissions', label: '权限与 MCP' },
  { key: 'prompt', label: 'Prompt 模板' },
  { key: 'history', label: '版本历史' },
] as const
let detailGeneration = 0

const bindings = ref<RoleSlotBinding[]>([])
const bindingsError = ref('')
const projects = ref<Project[]>([])
const projectsError = ref('')
const projectId = ref('')
const selectedSlot = ref('')
const preview = ref<RolePermissionPreview | null>(null)
const previewLoading = ref(false)
const previewError = ref('')
let previewGeneration = 0

const revision = ref<RoleRevision | null>(null)
const revisionLoading = ref(false)
const revisionError = ref('')
let revisionGeneration = 0
const revisions = ref<RoleRevisionSummary[]>([])
const historyCursor = ref('')
const historyNextCursor = ref<string | null>(null)
const historyRetryCursor = ref('')
const historyRetryAppend = ref(false)
const historyLoading = ref(false)
const historyError = ref('')
let historyGeneration = 0
const historyRevision = ref<RoleRevision | null>(null)
const historyRevisionId = ref('')
const historyRevisionLoading = ref(false)
const historyRevisionError = ref('')
let historyRevisionGeneration = 0
const comparison = ref<RoleComparison | null>(null)
const comparisonLoading = ref(false)
const comparisonError = ref('')
let comparisonGeneration = 0
const exportingRevisionId = ref('')
const exportError = ref('')

const importFile = ref<File | null>(null)
const importPreview = ref<RoleImportValidation | null>(null)
const importError = ref('')
const importSuccess = ref('')
const validating = ref(false)
const publishing = ref(false)
const confirmed = ref(false)
const importIdempotencyKey = ref('')
const importOpen = ref(false)

const activeBindings = computed(() => bindings.value.filter(binding => binding.activeRoleId === selected.value?.roleId))
const groupedRoles = computed(() => {
  const groups = new Map<string, RoleCatalogItem[]>()
  for (const role of roles.value) {
    const label = role.activeSlots[0] ? workflowForSlot(role.activeSlots[0]).name : role.groupLabel || '专项角色'
    const items = groups.get(label) ?? []
    items.push(role)
    groups.set(label, items)
  }
  return [...groups].map(([label, items]) => ({ label, items }))
})
const availableSlots = computed(() => {
  const declared = revision.value?.manifest.allowedSlots
  const ids = Array.isArray(declared) ? declared.filter((value): value is string => typeof value === 'string') : selected.value?.activeSlots ?? []
  return bindings.value.filter(binding => ids.includes(binding.slot) || binding.activeRoleId === selected.value?.roleId)
})
const promptFragments = computed(() => Object.entries(revision.value?.promptFragments ?? {}))
const importChanges = computed(() => {
  const result = [...(importPreview.value?.changes ?? [])]
  for (const role of importPreview.value?.roles ?? []) result.push(...(role.changes ?? []))
  return result
})
const visibleTools = computed(() => {
  const tools = new Map<string, { name: string; description: string; source: string; required?: boolean }>()
  for (const tool of preview.value?.mcpTools ?? []) {
    tools.set(tool.name, { ...tool, description: roleToolDescription(tool.name), source: tool.source === 'NATIVE_POLICY' ? '原生工具' : tool.source === 'SYSTEM_REQUIRED' ? '服务端必需' : tool.source === 'BUNDLED_POLICY' ? '程序内置' : '配置声明' })
  }
  for (const name of [...(revision.value?.nativeTools ?? []), ...(revision.value?.mcpTools ?? [])]) {
    if (!tools.has(name)) tools.set(name, { name, description: roleToolDescription(name), source: '配置声明', required: revision.value?.requiredMcpTools?.includes(name) })
  }
  return [...tools.values()]
})

const canPublish = computed(() => !!importFile.value && !!importPreview.value?.valid && confirmed.value
  && !validating.value && !publishing.value && !!importPreview.value.sourceSha256 && !!importIdempotencyKey.value
  && importChanges.value.length > 0 && !importPreview.value.diagnostics.length)

function safeError(error: unknown, fallback: string): string {
  if (error instanceof ApiError && /^请求失败 \(\d+\)$/.test(error.message)) return fallback
  return userFacingError(error, fallback)
}

function diagnosticMessage(code: string, message: string): string {
  if (/[\u3400-\u9fff]/.test(message)) return message
  return ({
    ROLE_SLOTS_REQUIRED: '角色必须声明支持的流程阶段。',
    ROLE_PERMISSION_MODE_CONFLICT: '权限模式与工具清单冲突，请改为收窄模式或移除工具覆盖。',
    ROLE_SLOT_UNKNOWN: '配置包引用了当前版本不支持的流程阶段。',
    ROLE_SLOT_DUPLICATE: '同一流程阶段在配置包中被重复激活。',
    ROLE_NATIVE_TOOL_UNSAFE: '该流程阶段不允许配置所选原生工具。',
    ROLE_MCP_TOOL_UNAVAILABLE: '所选 MCP 工具不属于此阶段的授权范围。',
    ROLE_PROMPT_SLOT_UNKNOWN: '提示模板片段不属于当前版本支持的插槽。',
    ROLE_PROMPT_VARIABLE_UNSUPPORTED: '当前版本不支持导入模板中的变量。',
  } as Record<string, string>)[code] ?? '该配置项不符合当前角色合同，请检查配置包后重新校验。'
}

function importChangeLabel(value?: string): string {
  return ({ NEW: '新角色', UPDATED: '更新版本', UNCHANGED: '内容未变' } as Record<string, string>)[value ?? ''] ?? '待核对'
}

function originLabel(value: string): string {
  return ({ BUILTIN: '内置', IMPORTED: '导入' } as Record<string, string>)[value] ?? '来源待核对'
}

function bindingPurposeLabel(binding: RoleSlotBinding): string {
  const rawPurpose = binding.purpose ?? ''
  const purpose = ({
    DEFAULT: binding.label || '用于此流程阶段',
    REQUIREMENT: '处理需求相关工作', RISK: '处理风险相关工作',
    COMMIT_MESSAGE: '生成提交说明', MERGE_ADVISOR: '提供合并建议',
    DESIGNER: '参与设计讨论', ACCOUNTING: '提供统计辅助',
  } as Record<string, string>)[rawPurpose]
  return purpose ?? (/[^\x00-\x7f]/.test(rawPurpose) ? rawPurpose : binding.label || '用于此流程阶段')
}

function modelPolicyLabel(value: string): string {
  return value === 'INHERIT_WORKFLOW' ? '按当前流程继承' : '未知模型策略，请检查配置清单'
}

function permissionModeLabel(value: RoleRevision): string {
  if (value.manifest.runtimePolicy === 'ACCOUNTING_COMMAND') return '固定统计命令权限（角色配置不可扩权）'
  return value.permissionMode === 'INTERSECT' ? '在既有授权内收窄'
    : value.permissionMode === 'BASELINE' ? '沿用既有授权' : '配置模式待核实'
}

function formatValue(value: unknown): string {
  if (value === undefined) return '无'
  if (typeof value === 'string') return value || '空'
  return JSON.stringify(value, null, 2)
}

function dateLabel(value?: string): string {
  if (!value) return '时间未知'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '时间未知' : date.toLocaleString('zh-CN')
}

async function loadRoles(cursor = '', reset = false) {
  const generation = ++listGeneration
  listLoading.value = true
  listError.value = ''
  listRetryCursor.value = cursor
  try {
    const page = await api.getRoles(appliedSearch.value, cursor, PAGE_SIZE)
    if (generation !== listGeneration) return false
    roles.value = page.items
    listCursor.value = cursor
    nextCursor.value = page.nextCursor ?? null
    if (reset) { ++detailGeneration; ++previewGeneration; selected.value = null }
    listRetryAction.value = 'load'
    return true
  } catch (error) {
    if (generation === listGeneration) listError.value = safeError(error, '角色列表读取失败，请重试。')
    return false
  } finally {
    if (generation === listGeneration) listLoading.value = false
  }
}

function submitSearch() {
  appliedSearch.value = search.value.trim()
  previousCursors.value = []
  listRetryAction.value = 'load'
  void loadRoles('', true)
}

async function nextPage() {
  if (!nextCursor.value || listLoading.value) return
  const previous = listCursor.value
  listRetryAction.value = 'next'
  if (await loadRoles(nextCursor.value, true)) previousCursors.value.push(previous)
}

async function previousPage() {
  if (!previousCursors.value.length || listLoading.value) return
  const cursor = previousCursors.value.at(-1) ?? ''
  listRetryAction.value = 'previous'
  if (await loadRoles(cursor, true)) previousCursors.value.pop()
}

function retryList() {
  if (listRetryAction.value === 'next') void nextPage()
  else if (listRetryAction.value === 'previous') void previousPage()
  else void loadRoles(listRetryCursor.value, true)
}

async function loadBindings() {
  bindingsError.value = ''
  try {
    bindings.value = await api.getRoleSlots()
    if (selected.value && !selectedSlot.value) selectedSlot.value = availableSlots.value[0]?.slot ?? ''
  }
  catch (error) { bindingsError.value = safeError(error, '角色绑定读取失败，请刷新。') }
}

async function loadProjects() {
  projectsError.value = ''
  try { projects.value = await api.getProjects() }
  catch (error) { projects.value = []; projectsError.value = safeError(error, '项目列表读取失败，请重试。') }
}

async function selectRole(roleId: string) {
  const generation = ++detailGeneration
  ++previewGeneration
  ++revisionGeneration
  ++historyGeneration
  ++historyRevisionGeneration
  ++comparisonGeneration
  requestedRoleId.value = roleId
  selected.value = null
  detailLoading.value = true
  detailError.value = ''
  revision.value = null
  revisionLoading.value = false
  revisionError.value = ''
  revisions.value = []
  historyCursor.value = ''
  historyNextCursor.value = null
  historyRetryCursor.value = ''
  historyRetryAppend.value = false
  historyLoading.value = false
  historyError.value = ''
  historyRevision.value = null
  historyRevisionId.value = ''
  historyRevisionLoading.value = false
  historyRevisionError.value = ''
  comparison.value = null
  comparisonLoading.value = false
  comparisonError.value = ''
  preview.value = null
  previewLoading.value = false
  previewError.value = ''
  selectedSlot.value = ''
  activeTab.value = 'overview'
  try {
    const value = await api.getRole(roleId)
    if (generation !== detailGeneration) return
    selected.value = value
    selectedSlot.value = value.activeSlots?.[0] ?? bindings.value.find(item => item.activeRoleId === roleId)?.slot ?? ''
  } catch (error) {
    if (generation === detailGeneration) detailError.value = safeError(error, '角色详情读取失败，请重试。')
  } finally {
    if (generation === detailGeneration) detailLoading.value = false
  }
}

async function loadPreview() {
  const role = selected.value
  const slot = selectedSlot.value
  if (!role || !slot) { preview.value = null; return }
  const generation = ++previewGeneration
  previewLoading.value = true
  previewError.value = ''
  preview.value = null
  try {
    const value = await api.previewRoleSlot(role.roleId, slot, projectId.value)
    if (generation === previewGeneration && selected.value?.roleId === role.roleId) preview.value = value
  } catch (error) {
    if (generation === previewGeneration) previewError.value = safeError(error, '权限预览失败，请重试。')
  } finally {
    if (generation === previewGeneration) previewLoading.value = false
  }
}

async function loadRevision() {
  const role = selected.value
  if (!role?.latestRevisionId || revision.value?.revisionId === role.latestRevisionId) return
  const generation = ++revisionGeneration
  revisionLoading.value = true
  revisionError.value = ''
  try {
    const value = await api.getRoleRevision(role.roleId, role.latestRevisionId)
    if (generation === revisionGeneration && selected.value?.roleId === role.roleId) {
      revision.value = value
      if (!selectedSlot.value) selectedSlot.value = availableSlots.value[0]?.slot ?? ''
    }
  }
  catch (error) { if (generation === revisionGeneration) revisionError.value = safeError(error, '提示模板读取失败，请重试。') }
  finally { if (generation === revisionGeneration) revisionLoading.value = false }
}

async function loadHistory(cursor = '', append = false) {
  const role = selected.value
  if (!role) return
  const generation = ++historyGeneration
  historyLoading.value = true
  historyError.value = ''
  historyRetryCursor.value = cursor
  historyRetryAppend.value = append
  try {
    const page = await api.getRoleRevisions(role.roleId, cursor, PAGE_SIZE)
    if (generation !== historyGeneration || selected.value?.roleId !== role.roleId) return
    revisions.value = append ? [...revisions.value, ...page.items] : page.items
    historyCursor.value = cursor
    historyNextCursor.value = page.nextCursor ?? null
  } catch (error) { if (generation === historyGeneration) historyError.value = safeError(error, '版本历史读取失败，请重试。') }
  finally { if (generation === historyGeneration) historyLoading.value = false }
}

async function openHistoryRevision(revisionId: string) {
  const role = selected.value
  if (!role) return
  const generation = ++historyRevisionGeneration
  historyRevisionId.value = revisionId
  historyRevision.value = null
  historyRevisionLoading.value = true
  historyRevisionError.value = ''
  try {
    const value = await api.getRoleRevision(role.roleId, revisionId)
    if (generation === historyRevisionGeneration && selected.value?.roleId === role.roleId) historyRevision.value = value
  } catch (error) {
    if (generation === historyRevisionGeneration) historyRevisionError.value = safeError(error, '历史配置读取失败，请重试。')
  } finally {
    if (generation === historyRevisionGeneration) historyRevisionLoading.value = false
  }
}

function showBindingRevision(revisionId: string) {
  if (!revisionId) return
  activeTab.value = 'history'
  if (!revisions.value.length && !historyLoading.value) void loadHistory()
  void openHistoryRevision(revisionId)
}

async function compare(revisionId: string) {
  const role = selected.value
  if (!role?.latestRevisionId || role.latestRevisionId === revisionId) return
  const generation = ++comparisonGeneration
  comparison.value = null
  comparisonError.value = ''
  comparisonLoading.value = true
  try {
    const value = await api.compareRoleRevisions(role.roleId, revisionId, role.latestRevisionId)
    if (generation === comparisonGeneration && selected.value?.roleId === role.roleId) comparison.value = value
  }
  catch (error) { if (generation === comparisonGeneration) comparisonError.value = safeError(error, '版本差异读取失败，请重试。') }
  finally { if (generation === comparisonGeneration) comparisonLoading.value = false }
}

async function exportRevision(revisionId: string) {
  const role = selected.value
  if (!role || exportingRevisionId.value) return
  exportingRevisionId.value = revisionId
  exportError.value = ''
  try {
    const blob = await api.exportRoleRevision(role.roleId, revisionId)
    if (selected.value?.roleId !== role.roleId) return
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${role.roleId.replace(/[^a-zA-Z0-9._-]/g, '_')}-v${revisionId.replace(/[^a-zA-Z0-9._-]/g, '_')}.zip`
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
  } catch (error) {
    if (selected.value?.roleId === role.roleId) exportError.value = safeError(error, '配置包下载失败，请重试。')
  } finally {
    exportingRevisionId.value = ''
  }
}

function switchTab(tab: typeof activeTab.value) {
  activeTab.value = tab
  if (tab === 'permissions') {
    if (!preview.value && !previewLoading.value) void loadPreview()
    if (!revision.value && !revisionLoading.value) void loadRevision()
  }
  if (tab === 'prompt' && !revision.value && !revisionLoading.value) void loadRevision()
  if (tab === 'history' && !revisions.value.length && !historyLoading.value) void loadHistory()
}

async function chooseImport(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  importOpen.value = true
  importFile.value = file
  importPreview.value = null
  importError.value = ''
  importSuccess.value = ''
  confirmed.value = false
  importIdempotencyKey.value = ''
  if (!file.name.toLowerCase().endsWith('.zip')) {
    importError.value = '请选择 ZIP 配置包，然后重新校验。'
    return
  }
  validating.value = true
  try {
    importPreview.value = await api.validateRoleImport(file)
    importIdempotencyKey.value = crypto.randomUUID()
  } catch (error) { importError.value = safeError(error, '配置包校验失败，请检查文件后重试。') }
  finally { validating.value = false }
}

async function revalidate() {
  if (!importFile.value) return
  validating.value = true
  importError.value = ''
  importPreview.value = null
  confirmed.value = false
  try {
    importPreview.value = await api.validateRoleImport(importFile.value)
    importIdempotencyKey.value = crypto.randomUUID()
  } catch (error) { importError.value = safeError(error, '重新校验失败，请检查配置包后重试。') }
  finally { validating.value = false }
}

async function publish() {
  const file = importFile.value
  const value = importPreview.value
  if (!file || !value || !canPublish.value) return
  publishing.value = true
  importError.value = ''
  try {
    const result = await api.publishRoleImport(file, {
      sourceSha256: value.sourceSha256,
      idempotencyKey: importIdempotencyKey.value,
      activations: value.activations.map(item => ({ slot: item.slot, roleId: item.roleId, expectedVersion: item.expectedVersion })),
    })
    importSuccess.value = result.replayed ? '配置已发布过，当前结果已核对。' : '配置包已发布并激活；新建会话使用新配置。'
    importFile.value = null
    importPreview.value = null
    confirmed.value = false
    previousCursors.value = []
    await Promise.all([loadRoles('', true), loadBindings()])
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      importPreview.value = null
      confirmed.value = false
      importError.value = '角色配置或绑定版本已变化。请重新校验同一配置包，核对新差异后再发布。'
      await loadBindings()
    } else importError.value = safeError(error, '发布结果未确认。请保留当前配置包并重试，系统会核对同一发布请求。')
  } finally { publishing.value = false }
}

watch([projectId, selectedSlot], () => { if (activeTab.value === 'permissions' && selected.value) void loadPreview() })
onMounted(() => { void loadRoles(); void loadBindings(); void loadProjects() })
</script>

<template>
  <PageHeader eyebrow="系统" title="角色管理">
    <template #actions><button v-if="!importOpen && (importFile || importSuccess)" type="button" class="plain-button" @click="importOpen = true">查看导入结果</button><label class="import-button"><Icon icon="lucide:upload" width="16" />导入配置包<input type="file" accept=".zip,application/zip" aria-label="选择角色配置 ZIP" :disabled="validating || publishing" @change="chooseImport" /></label></template>
  </PageHeader>
  <main id="main-content" class="content roles-content" tabindex="-1">
    <section class="role-intro"><div class="intro-mark"><Icon icon="lucide:users-round" width="28" /></div><div><p class="eyebrow">协作角色</p><h2>让每个角色，各司其职</h2><p>了解职责与工作流，查看工具、权限和提示模板。</p></div><span class="intro-caption">角色目录 / 工作流 / 能力配置</span></section>
    <section v-if="importOpen" class="card import-panel" aria-label="导入角色配置">
      <div class="section-heading"><div><p class="eyebrow">配置运维</p><h2>导入配置包</h2></div><button type="button" class="plain-button" :disabled="publishing" @click="importOpen = false">收起</button></div>
      <p v-if="importFile" class="file-name">已选文件：{{ importFile.name }}</p>
      <p v-if="validating" role="status">正在校验配置包与现有配置…</p>
      <p v-if="importError" class="error-text" role="alert">{{ importError }}</p>
      <p v-if="importSuccess" class="success-text" role="status">{{ importSuccess }}</p>
      <div v-if="importFile && !validating && !importPreview && !importSuccess" class="panel-actions"><el-button @click="revalidate">重新校验</el-button></div>
      <template v-if="importPreview">
        <div class="import-result"><strong>{{ importPreview.valid ? '校验通过，待核对差异' : '校验未通过' }}</strong><span>来源摘要 {{ importPreview.sourceSha256.slice(0, 12) }}…</span></div>
        <ul v-if="importPreview.diagnostics.length" class="diagnostics"><li v-for="(item, index) in importPreview.diagnostics" :key="index"><strong>{{ item.path || '配置包' }}</strong>：{{ diagnosticMessage(item.code, item.message) }}</li></ul>
        <div class="import-groups">
          <section><h3>角色版本</h3><ul><li v-for="item in importPreview.roles" :key="item.roleId">{{ item.displayName || '未命名角色' }}<span v-if="item.change"> · {{ importChangeLabel(item.change) }}</span></li></ul><p v-if="!importPreview.roles.length">无角色版本变更</p></section>
          <section><h3>激活绑定</h3><ul><li v-for="item in importPreview.activations" :key="item.slot">{{ bindings.find(binding => binding.slot === item.slot)?.label || '角色阶段' }} → {{ importPreview.roles.find(role => role.roleId === item.roleId)?.displayName || '导入角色' }}</li></ul><p v-if="!importPreview.activations.length">保留现有角色绑定</p></section>
        </div>
        <section class="diff-panel" aria-label="配置字段差异"><h3>字段差异</h3><p v-if="!importChanges.length" class="error-text">校验结果未提供可核对的字段差异，当前不能发布。</p><div v-for="(change, index) in importChanges" :key="`${change.path}-${index}`" class="diff-entry"><strong>{{ change.path }}</strong><div class="diff-values"><div><span>当前</span><pre>{{ formatValue(change.before) }}</pre></div><div><span>导入后</span><pre>{{ formatValue(change.after) }}</pre></div></div></div></section>
        <p class="page-note">发布只影响新建会话；当前会话和冻结任务不会因导入获得新增权限。</p>
        <label v-if="importPreview.valid && importChanges.length" class="confirm-line"><input v-model="confirmed" type="checkbox" />我已核对角色、权限、提示模板与激活差异</label>
        <div class="panel-actions"><el-button @click="revalidate" :disabled="publishing">重新校验</el-button><el-button type="primary" :loading="publishing" :disabled="!canPublish" @click="publish">发布并激活</el-button></div>
      </template>
    </section>

    <div class="roles-layout">
      <section class="card role-list" aria-label="角色列表">
        <div class="section-heading"><h2>角色</h2><span>本页 {{ roles.length }} 项</span></div>
        <form class="search-row" @submit.prevent="submitSearch"><el-input v-model="search" aria-label="搜索角色" placeholder="搜索角色名称或用途" clearable /><el-button native-type="submit" :loading="listLoading">搜索</el-button></form>
        <p v-if="listError" class="error-text" role="alert">{{ listError }} <button class="inline-button" @click="retryList">重试</button></p>
        <p v-if="listLoading" role="status">正在读取角色…</p>
        <p v-else-if="!roles.length && !listError" class="empty-note">没有匹配的角色。</p>
        <div v-else class="role-items"><section v-for="group in groupedRoles" :key="group.label" class="role-group"><h3>{{ group.label }}</h3><button v-for="role in group.items" :key="role.roleId" type="button" :class="['role-item', { selected: selected?.roleId === role.roleId }]" :aria-pressed="selected?.roleId === role.roleId" @click="selectRole(role.roleId)"><span class="role-heading"><Icon icon="lucide:bot" width="18" /><strong>{{ role.displayName }}</strong></span><span class="role-description">{{ role.description }}</span><span class="role-meta">{{ role.activeSlots.length ? `${role.activeSlots.length} 个阶段在使用` : '暂无激活阶段' }} · 最新发布版本 {{ role.latestRevisionNumber }}</span></button></section></div>
        <div class="pagination"><el-button size="small" :disabled="!previousCursors.length || listLoading || !!listError" @click="previousPage">上一页</el-button><el-button size="small" :disabled="!nextCursor || listLoading || !!listError" @click="nextPage">下一页</el-button></div>
      </section>

      <section class="card role-detail" aria-label="角色详情">
        <p v-if="detailLoading" role="status">正在读取角色详情…</p>
        <p v-else-if="detailError" class="error-text" role="alert">{{ detailError }} <button class="inline-button" @click="selectRole(requestedRoleId)">重试</button></p>
        <div v-else-if="!selected" class="empty-detail"><Icon icon="lucide:users-round" width="32" /><h2>选择一个角色查看配置</h2><p>从左侧目录选择角色，了解它在工作流中的职责与能力。</p></div>
        <template v-else>
          <header class="detail-heading"><div><p class="eyebrow">{{ selected.groupLabel || '角色配置' }}</p><h2>{{ selected.displayName }}</h2><p>{{ selected.description }}</p></div><span class="revision-chip">最新发布版本 {{ selected.latestRevisionNumber }}</span></header>
          <nav class="detail-tabs" aria-label="角色详情分区"><button v-for="tab in tabs" :key="tab.key" type="button" :aria-pressed="activeTab === tab.key" :class="{ active: activeTab === tab.key }" @click="switchTab(tab.key)">{{ tab.label }}</button></nav>
          <div v-if="activeTab === 'overview'" class="detail-section">
            <h3>角色职责</h3>
            <p>{{ selected.description }}</p>
            <p>来源：{{ originLabel(selected.origin) }}</p>
            <h3 class="workflow-title">所属工作流与位置</h3><ul class="slot-list"><li v-for="binding in activeBindings" :key="binding.slot">
              <strong>{{ binding.label || '角色阶段' }}</strong>
              <span class="workflow-name">{{ workflowForSlot(binding.slot).name }}</span><p class="workflow-position">{{ workflowForSlot(binding.slot).position }}</p><span>{{ bindingPurposeLabel(binding) }}</span>
              <span>{{ binding.activeRevisionId === selected.latestRevisionId ? '使用最新发布版本' : '使用其他已发布版本' }}</span>
              <button type="button" class="inline-button" @click="showBindingRevision(binding.activeRevisionId)">查看此阶段使用的版本</button>
            </li></ul>
            <p v-if="!activeBindings.length" class="empty-note">当前未绑定运行阶段。</p>
            <details class="technical-details"><summary>查看技术标识</summary><p>角色标识：{{ selected.roleId }}</p><p>来源码：{{ selected.origin }}</p><p>最新发布修订：{{ selected.latestRevisionId }}</p></details>
          </div>
          <div v-if="activeTab === 'permissions'" class="detail-section">
            <div class="section-heading"><h3>工具与访问权限</h3><span v-if="revision">{{ permissionModeLabel(revision) }}</span></div>
            <p v-if="revision?.manifest.runtimePolicy === 'ACCOUNTING_COMMAND'" class="page-note">统计辅助通过独立命令运行，工具范围由服务端固定。</p>
            <p v-if="revisionError" class="error-text" role="alert">{{ revisionError }} <button class="inline-button" @click="loadRevision">重试</button></p>
            <div class="preview-controls">
              <el-select v-model="selectedSlot" aria-label="选择角色阶段" placeholder="选择阶段"><el-option v-for="binding in availableSlots" :key="binding.slot" :label="binding.label || '角色阶段'" :value="binding.slot" /></el-select>
              <el-select v-model="projectId" aria-label="权限预览所属项目"><el-option label="全局配置" value="" /><el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" /></el-select>
              <el-button :loading="previewLoading" :disabled="!selectedSlot" @click="loadPreview">刷新预览</el-button>
            </div>
            <p v-if="projectsError" class="error-text" role="alert">{{ projectsError }} <button class="inline-button" @click="loadProjects">重试</button></p>
            <p v-if="bindingsError" class="error-text" role="alert">{{ bindingsError }} <button class="inline-button" @click="loadBindings">重试</button></p>
            <p v-if="previewError" class="error-text" role="alert">{{ previewError }} <button class="inline-button" @click="loadPreview">重试</button></p>
            <p v-if="previewLoading || revisionLoading" role="status">正在读取工具与权限…</p>
            <p v-if="preview" class="preview-status">配置预览 · {{ preview.complete ? '信息完整' : '仍需运行时核定' }}</p>
            <ul v-if="preview?.limitations.length" class="limitations"><li v-for="(item, index) in preview.limitations" :key="index">{{ item }}</li></ul>
            <section class="capability-panel" aria-label="MCP 工具清单">
              <div class="section-heading"><h3><Icon icon="lucide:plug" width="18" /> MCP</h3><span>{{ visibleTools.length }} 项工具</span></div>
              <ul class="tool-list"><li v-for="tool in visibleTools" :key="tool.name"><div class="tool-copy"><strong>{{ tool.name }}</strong><span>{{ tool.description }}</span></div><small>{{ tool.source }}{{ tool.required && tool.source !== '服务端必需' ? ' · 必需' : '' }}</small></li></ul>
              <p v-if="!visibleTools.length && !previewLoading && !revisionLoading" class="empty-note">{{ previewError ? '工具清单读取失败，请重试。' : '当前阶段没有工具清单。' }}</p>
            </section>
            <section v-if="preview" class="capability-panel" aria-label="权限规则">
              <div class="section-heading"><h3><Icon icon="lucide:shield-check" width="18" /> 权限</h3><span>{{ preview.rules.length }} 条规则</span></div>
              <ul class="permission-list"><li v-for="(rule, index) in preview.rules" :key="index"><span :class="rule.action === 'allow' ? 'allowed' : 'denied'">{{ rule.action === 'allow' ? '允许' : rule.action === 'ask' ? '询问' : '拒绝' }}</span><div class="tool-copy"><strong>{{ stableToolName(rule.permission) }}</strong><span>{{ roleToolDescription(rule.permission) }}</span><small>范围：{{ rule.pattern === '*' ? '全部' : rule.pattern }}</small></div></li></ul>
              <p v-if="!preview.rules.length" class="empty-note">当前阶段没有可展示的权限规则。</p>
            </section>
          </div>
          <div v-if="activeTab === 'prompt'" class="detail-section"><h3>提示模板与变量</h3><p class="page-note">此处展示修订中的静态模板，不是某次会话实际投递的完整提示。任务内容、冻结合同及临时工具凭证不会在此重建。</p><p v-if="revisionError" class="error-text" role="alert">{{ revisionError }} <button class="inline-button" @click="loadRevision">重试</button></p><p v-if="revisionLoading" role="status">正在读取模板…</p><template v-if="revision"><p class="revision-meta">配置摘要 {{ revision.contentSha256 }} · {{ dateLabel(revision.publishedAt) }}</p><button type="button" class="inline-button" :disabled="!!exportingRevisionId" @click="exportRevision(revision.revisionId)">下载最新发布版本配置包</button><p v-if="exportError" class="error-text" role="alert">{{ exportError }}</p><h4>变量</h4><p v-if="!revision.promptVariables?.length">无显式模板变量。</p><ul v-else class="variables"><li v-for="name in revision.promptVariables" :key="name">{{ name }}</li></ul><h4>模板片段</h4><p v-if="!promptFragments.length">此版本未提供静态模板片段。</p><details v-for="([name, content]) in promptFragments" :key="name" class="fragment"><summary>{{ name }}</summary><pre>{{ content }}</pre></details><details class="technical-details"><summary>查看配置清单</summary><pre>{{ formatValue(revision.manifest) }}</pre></details></template></div>
          <div v-if="activeTab === 'history'" class="detail-section"><h3>版本历史</h3><p v-if="exportError" class="error-text" role="alert">{{ exportError }}</p><p v-if="historyError" class="error-text" role="alert">{{ historyError }} <button class="inline-button" @click="loadHistory(historyRetryCursor, historyRetryAppend)">重试</button></p><p v-if="historyLoading" role="status">正在读取历史…</p><ol class="history-list"><li v-for="item in revisions" :key="item.revisionId"><div><strong>版本 {{ item.revisionNumber }}</strong><small>{{ dateLabel(item.publishedAt) }}</small></div><button type="button" class="inline-button" @click="openHistoryRevision(item.revisionId)">查看此版本</button><button type="button" class="inline-button" :disabled="!!exportingRevisionId" @click="exportRevision(item.revisionId)">下载配置包</button><button v-if="item.revisionId !== selected.latestRevisionId" type="button" class="inline-button" @click="compare(item.revisionId)">与最新发布版本比较</button><span v-else>最新发布版本</span></li></ol><p v-if="historyRevisionLoading" role="status">正在读取所选版本…</p><p v-if="historyRevisionError" class="error-text" role="alert">{{ historyRevisionError }} <button class="inline-button" @click="openHistoryRevision(historyRevisionId)">重试</button></p><section v-if="historyRevision" class="history-revision" aria-label="所选历史配置"><h4>版本 {{ historyRevision.revisionNumber }} 的静态配置</h4><p class="page-note">这是已发布的配置版本，不是历史会话的完整 Prompt 快照。</p><p class="revision-meta">摘要 {{ historyRevision.contentSha256 }} · {{ dateLabel(historyRevision.publishedAt) }}</p><p>权限模式：{{ permissionModeLabel(historyRevision) }}</p><p v-if="historyRevision.modelPolicy">模型策略：{{ modelPolicyLabel(historyRevision.modelPolicy) }}</p><p>原生工具：{{ historyRevision.nativeTools?.map(tool => `${tool}（${roleToolDescription(tool)}）`).join('、') || '无显式清单' }}</p><p>精确 MCP：{{ historyRevision.mcpTools?.map(tool => `${tool}（${roleToolDescription(tool)}）`).join('、') || '无显式清单' }}</p><p>模板变量：{{ historyRevision.promptVariables?.join('、') || '无显式变量' }}</p><details v-for="([name, content]) in Object.entries(historyRevision.promptFragments)" :key="name" class="fragment"><summary>{{ name }}</summary><pre>{{ content }}</pre></details><details class="technical-details"><summary>查看此版本配置清单</summary><pre>{{ formatValue(historyRevision.manifest) }}</pre></details></section><el-button v-if="historyNextCursor" :loading="historyLoading" @click="loadHistory(historyNextCursor || '', true)">加载更多</el-button><p v-if="comparisonError" class="error-text" role="alert">{{ comparisonError }}</p><p v-if="comparisonLoading" role="status">正在读取差异…</p><section v-if="comparison" class="diff-panel"><h4>与最新发布版本的差异</h4><p v-if="!comparison.changes.length">没有字段差异。</p><div v-for="(change, index) in comparison.changes" :key="`${change.path}-${index}`" class="diff-entry"><strong>{{ change.path }}</strong><div class="diff-values"><div><span>历史版本</span><pre>{{ formatValue(change.before) }}</pre></div><div><span>最新发布版本</span><pre>{{ formatValue(change.after) }}</pre></div></div></div></section></div>
        </template>
      </section>
    </div>
  </main>
</template>

<style scoped>
.roles-content{max-width:1500px}.page-note{color:var(--color-text-secondary);font-size:13px;line-height:1.7;margin:0 0 18px}.back-link,.plain-button,.inline-button{color:var(--color-accent-cyan);background:none;border:0;cursor:pointer;font:inherit}.back-link{text-decoration:none}.inline-button{text-decoration:underline}.import-button{display:inline-flex;align-items:center;gap:8px;cursor:pointer;padding:9px 14px;border-radius:var(--radius-control);background:var(--color-accent-cyan);color:var(--shade-onEmphasis-02);font-size:13px}.import-button input{position:absolute;width:1px;height:1px;opacity:0;overflow:hidden}.import-button:focus-within{outline:2px solid var(--color-accent-cyan);outline-offset:3px}.card{min-width:0}.import-panel{padding:24px;margin-bottom:20px}.section-heading{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:18px}.section-heading h2,.detail-heading h2{margin:0}.section-heading span,.role-meta,.revision-meta{color:var(--color-text-secondary);font-size:12px}.file-name{overflow-wrap:anywhere}.error-text{color:var(--color-task-danger);line-height:1.6}.success-text{color:var(--color-success)}.import-result{display:flex;gap:12px;align-items:center;flex-wrap:wrap;padding:12px 14px;background:var(--color-bg-elevated);border-radius:var(--radius-control)}.import-result span{color:var(--color-text-secondary);font-size:12px}.diagnostics,.limitations{padding-left:22px;line-height:1.7}.import-groups{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.import-groups section{padding:16px;background:var(--color-bg-elevated);border-radius:var(--radius-control)}.import-groups h3{margin:0 0 8px;font-size:14px}.import-groups ul{margin:0;padding-left:20px;line-height:1.7}.import-groups p{margin:0;color:var(--color-text-secondary)}.diff-panel{margin:18px 0}.diff-panel h3,.diff-panel h4{font-size:14px}.diff-entry{border-top:1px solid var(--color-border-default);padding:14px 0}.diff-entry>strong{font:600 13px var(--font-code);overflow-wrap:anywhere}.diff-values{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin-top:8px}.diff-values>div{min-width:0}.diff-values span{font-size:12px;color:var(--color-text-secondary)}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.6 var(--font-code);background:var(--color-bg-elevated);border-radius:var(--radius-control);padding:12px;max-height:480px;overflow:auto}.confirm-line{display:flex;gap:8px;align-items:flex-start;margin:18px 0;font-size:13px}.confirm-line input{margin-top:3px}.panel-actions{display:flex;justify-content:flex-end;gap:10px}.roles-layout{display:grid;grid-template-columns:minmax(300px,360px) minmax(0,1fr);gap:20px;align-items:start}.role-list,.role-detail{padding:22px;min-height:480px}.search-row{display:flex;gap:8px}.search-row .el-input{min-width:0}.role-items{display:grid;gap:12px;margin-top:14px}.role-group{display:grid;gap:8px}.role-group h3{margin:12px 0 2px;color:var(--color-text-secondary);font-size:12px;font-weight:600}.role-item{display:grid;gap:7px;text-align:left;width:100%;padding:14px;border:1px solid var(--color-border-default);border-radius:var(--radius-control);background:var(--color-bg-elevated);color:var(--color-text-primary);cursor:pointer}.role-item.selected{border-color:var(--color-accent-cyan)}.role-item:focus-visible,.detail-tabs button:focus-visible{outline:2px solid var(--color-accent-cyan);outline-offset:2px}.role-heading{display:flex;justify-content:space-between;gap:12px}.role-description{font-size:13px;line-height:1.6}.pagination{display:flex;justify-content:space-between;margin-top:16px}.empty-note,.empty-detail{color:var(--color-text-secondary)}.empty-detail{display:grid;place-content:center;text-align:center;min-height:400px}.empty-detail h2{color:var(--color-text-primary);font-size:18px}.detail-heading{display:flex;justify-content:space-between;gap:16px;border-bottom:1px solid var(--color-border-default);padding-bottom:20px}.detail-heading p{line-height:1.6}.revision-chip,.preview-status{white-space:nowrap;color:var(--color-text-secondary);font-size:12px}.detail-tabs{display:flex;flex-wrap:wrap;gap:6px;margin:20px 0;border-bottom:1px solid var(--color-border-default)}.detail-tabs button{border:0;border-bottom:2px solid transparent;background:none;padding:10px 12px;color:var(--color-text-secondary);cursor:pointer;font:inherit}.detail-tabs button.active{border-bottom-color:var(--color-accent-cyan);color:var(--color-text-primary)}.detail-section h3{font-size:16px}.detail-section h4{font-size:13px;margin-top:24px}.detail-section>p{line-height:1.7}.slot-list,.history-list,.tool-list,.variables{padding-left:20px;line-height:1.7}.slot-list li{display:grid;gap:3px;margin-bottom:9px}.slot-list span,.history-list small{color:var(--color-text-secondary)}.technical-details,.fragment{margin-top:16px;border-top:1px solid var(--color-border-default);padding-top:14px}.technical-details summary,.fragment summary{cursor:pointer;font-weight:500}.technical-details p{overflow-wrap:anywhere}.preview-controls{display:flex;flex-wrap:wrap;gap:10px;margin:14px 0}.preview-controls .el-select{width:min(220px,100%)}.permission-list{list-style:none;margin:0;padding:0}.permission-list li{display:flex;flex-wrap:wrap;gap:10px;align-items:center;border-top:1px solid var(--color-border-default);padding:8px 0;font:12px var(--font-code)}.permission-list small,.tool-list small{color:var(--color-text-secondary)}.allowed{color:var(--color-success)}.denied{color:var(--color-task-danger)}.tool-list li{margin-bottom:6px;overflow-wrap:anywhere}.tool-list small{margin-left:8px}.variables li{font-family:var(--font-code)}.history-list li{display:flex;align-items:center;justify-content:space-between;gap:12px;border-bottom:1px solid var(--color-border-default);padding:10px 0}.history-list li div{display:grid}.history-list li span{color:var(--color-text-secondary);font-size:12px}@media(max-width:900px){.roles-layout{grid-template-columns:1fr}.role-list,.role-detail{min-height:0}.import-groups{grid-template-columns:1fr}}@media(max-width:600px){.role-list,.role-detail,.import-panel{padding:16px}.diff-values{grid-template-columns:1fr}.preview-controls .el-select{width:100%}.detail-heading{flex-direction:column}.panel-actions{flex-wrap:wrap}}
.history-list li{flex-wrap:wrap}.history-list li>div{margin-right:auto}.history-revision{margin:18px 0;padding:18px;border:1px solid var(--color-border-default);border-radius:var(--radius-control)}.history-revision h4{margin-top:0}.history-revision p{overflow-wrap:anywhere;line-height:1.6}
.slot-list .inline-button{justify-self:start;text-align:left}

.role-intro{display:flex;align-items:center;gap:18px;padding:24px 28px;margin-bottom:24px;border:1px solid var(--color-border-default);border-radius:var(--radius-card);background:linear-gradient(120deg,var(--color-bg-elevated),var(--color-bg-surface));position:relative;overflow:hidden}
.intro-mark{display:grid;place-items:center;width:56px;height:56px;flex-shrink:0;border-radius:var(--radius-control);color:var(--color-accent-cyan);background:var(--color-bg-surface);border:1px solid var(--color-border-default)}
.role-intro h2{font-size:22px;margin:4px 0 8px;letter-spacing:.02em}.role-intro p{margin:0;color:var(--color-text-secondary);font-size:13px}.intro-caption{margin-left:auto;font-size:12px;color:var(--color-text-secondary)}
.role-list{background:var(--color-bg-surface)}.role-group h3{padding:8px 0;margin:8px 0;color:var(--color-text-secondary);font-size:12px}.role-item{border-left:3px solid transparent;padding:15px 16px;transition:background .15s}.role-item.selected{border-left-color:var(--color-accent-cyan);background:var(--color-bg-elevated)}.role-heading{display:flex;align-items:center;justify-content:flex-start;gap:9px}.role-heading svg{color:var(--color-accent-cyan)}.role-description{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;line-height:1.65}
.detail-heading{padding-bottom:12px}.detail-heading h2{font-size:26px;letter-spacing:.02em}.detail-heading p{max-width:65ch;line-height:1.8}.detail-tabs{gap:8px;padding-bottom:12px}.detail-tabs button{border-radius:var(--radius-control);padding:10px 16px}.detail-tabs button.active{background:var(--color-bg-elevated)}
.workflow-title{margin-top:28px}.slot-list{list-style:none;padding:0;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.slot-list li{padding:18px;background:var(--color-bg-elevated);border:1px solid var(--color-border-default);border-radius:var(--radius-control)}.workflow-name{color:var(--color-accent-cyan)!important;font-weight:600}.workflow-position{line-height:1.8;margin:4px 0;font-size:13px}
.capability-panel{margin-top:24px;border:1px solid var(--color-border-default);border-radius:var(--radius-control);padding:18px;min-width:0}.capability-panel h3{display:flex;align-items:center;gap:8px;margin:0}.capability-panel .section-heading{margin-bottom:14px}.tool-list{list-style:none;padding:0;display:grid;gap:0}.tool-list li{display:flex;align-items:flex-start;justify-content:space-between;gap:14px;padding:14px 0;border-bottom:1px solid var(--color-border-default);background:none;border-radius:0}.tool-list li:last-child{border:0}.tool-copy{display:flex;flex-wrap:wrap;align-items:baseline;gap:6px 12px;min-width:0;line-height:1.65}.tool-copy strong{font-family:var(--font-code);font-size:12px;overflow-wrap:anywhere}.tool-copy span{font-size:13px;color:var(--color-text-secondary)}.tool-copy small{flex-basis:100%;overflow-wrap:anywhere}.tool-list li>small{white-space:nowrap;color:var(--color-text-secondary);font-size:11px;padding:4px 8px;background:var(--color-bg-elevated);border-radius:var(--radius-control)}.permission-list li{align-items:start;padding:12px 0;gap:12px}.preview-status{font-size:12px;color:var(--color-text-secondary)}
button:focus-visible{outline:2px solid var(--color-accent-cyan);outline-offset:3px}
@media(max-width:1100px){.intro-caption{display:none}.slot-list{grid-template-columns:1fr}}
@media(max-width:600px){.role-intro{padding:18px;gap:12px}.role-intro h2{font-size:19px}.intro-mark{width:42px;height:42px}.capability-panel{padding:12px}.tool-list li{flex-direction:column;gap:6px}.detail-tabs{flex-wrap:wrap}.detail-tabs button{padding:8px 10px}.detail-heading h2{font-size:22px}}
@media(prefers-reduced-motion:reduce){.role-item{transition:none}}
</style>
