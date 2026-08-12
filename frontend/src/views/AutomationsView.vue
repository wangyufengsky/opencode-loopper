<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Icon } from '@iconify/vue'
import PageHeader from '@/components/PageHeader.vue'
import { api } from '@/api/client'
import type { AutomationImportResult, AutomationRule, AutomationRuleMutation, AutomationRun, AutomationRunFeed, CreateAutomationRuleInput, LoopSpecTemplate, LoopSpecTemplateVersion, Project } from '@/types/domain'

type Trigger = AutomationRule['triggerType']
type TemplateVersion = LoopSpecTemplateVersion
type Template = LoopSpecTemplate
type Rule = AutomationRule
type Run = AutomationRun
type RuleMutation = AutomationRuleMutation
type Workspace = { templates: Template[]; rules: Rule[]; runs: Run[]; serverTime: string }
/** Expected public client signatures for the coordinator:
 * getProjects(): Promise<Project[]>
 * getLoopSpecTemplates(): Promise<Template[]>
 * createLoopSpecTemplate({name,description}): Promise<Template>
 * createLoopSpecTemplateVersion(templateId,{specJson,autoStartApproved}): Promise<TemplateVersion>
 * previewLoopSpecTemplateImport(json): Promise<{previewId,templates,rules}>
 * confirmLoopSpecTemplateImport(previewId): Promise<Template[]> (required extra signature: preview alone must not write)
 * exportLoopSpecTemplate(): Promise<string>
 * getAutomationRules(): Promise<Rule[]> (webhookToken is never included here)
 * createAutomationRule({name,projectId,templateVersionId,triggerType,triggerConfig}): Promise<{rule:Rule;webhookToken?:string;webhookPath?:string}>
 * updateAutomationRule(rule: Rule): Promise<Rule>
 * triggerAutomationRule(ruleId): Promise<Run>
 * confirmAutomationRun(runId,title?): Promise<Run>
 * getAutomationRuns(): Promise<{runs:Run[],serverTime:string}>
 */
type AutomationApi = {
  getProjects: () => Promise<Project[]>
  getLoopSpecTemplates: () => Promise<Template[]>
  createLoopSpecTemplate: (body: { name: string; description: string }) => Promise<Template>
  createLoopSpecTemplateVersion: (id: string, body: { specJson: string; autoStartApproved: boolean }) => Promise<TemplateVersion>
  previewLoopSpecTemplateImport: (json: string) => Promise<{ previewId: string; templates: Template[]; rules: Rule[] }>
  confirmLoopSpecTemplateImport: (previewId: string) => Promise<AutomationImportResult>
  exportLoopSpecTemplate: () => Promise<string>
  getAutomationRules: () => Promise<Rule[]>
  createAutomationRule: (body: CreateAutomationRuleInput) => Promise<RuleMutation>
  updateAutomationRule: (rule: Rule) => Promise<Rule>
  triggerAutomationRule: (ruleId: string) => Promise<Run>
  getAutomationRuns: () => Promise<AutomationRunFeed>
  confirmAutomationRun: (runId: string, title?: string) => Promise<Run>
}
const automation = api as unknown as AutomationApi
const workspace = ref<Workspace>(); const projects = ref<Project[]>([]); const loading = ref(true); const error = ref(''); const message = ref('')
const templateName = ref(''); const templateDescription = ref(''); const versionTemplateId = ref(''); const specJson = ref('{\n  "schemaVersion": "v2"\n}'); const autoStartApproved = ref(false)
const ruleName = ref(''); const ruleProjectId = ref(''); const ruleVersion = ref(''); const triggerType = ref<Trigger>('MANUAL'); const triggerConfig = ref('{}')
const importJson = ref(''); const preview = ref<{ previewId: string; templates: Template[]; rules: Rule[] }>(); const exported = ref(''); const revealedTokens = ref<Record<string, string>>({})
const versions = computed(() => workspace.value?.templates.flatMap(template => template.versions.map(version => ({ ...version, label: `${template.name} · v${version.versionNumber}` }))) ?? [])
function versionForRule(rule: Rule) { return versions.value.find(version => version.id === rule.templateVersionId) }
function canAutoStart(rule: Rule) { return versionForRule(rule)?.autoStartApproved === true }
function createRuleInput(config: Record<string, unknown>): CreateAutomationRuleInput {
  const base = { name: ruleName.value.trim(), projectId: ruleProjectId.value, templateVersionId: ruleVersion.value }
  if (triggerType.value === 'MANUAL' || triggerType.value === 'WEBHOOK') return { ...base, triggerType: triggerType.value, triggerConfig: {} }
  if (triggerType.value === 'CRON') {
    if (typeof config.expression !== 'string' || typeof config.timezone !== 'string') throw new Error('CRON 需要 expression 和 timezone。')
    return { ...base, triggerType: 'CRON', triggerConfig: { expression: config.expression, timezone: config.timezone } }
  }
  return { ...base, triggerType: 'GIT_HEAD_CHANGED', triggerConfig: typeof config.branch === 'string' ? { branch: config.branch } : {} }
}
async function load() { loading.value = true; error.value = ''; try { const [projectRows, templates, rules, runFeed] = await Promise.all([automation.getProjects(), automation.getLoopSpecTemplates(), automation.getAutomationRules(), automation.getAutomationRuns()]); projects.value = projectRows; workspace.value = { templates, rules, runs: runFeed.runs, serverTime: runFeed.serverTime } } catch (cause) { error.value = cause instanceof Error ? cause.message : '无法读取自动化工作台' } finally { loading.value = false } }
async function createTemplate() { if (!templateName.value.trim()) return; try { await automation.createLoopSpecTemplate({ name: templateName.value.trim(), description: templateDescription.value.trim() }); templateName.value = ''; templateDescription.value = ''; message.value = '模板已创建；版本发布后不可变。'; await load() } catch (cause) { error.value = cause instanceof Error ? cause.message : '模板创建失败' } }
async function createVersion() { if (!versionTemplateId.value) return; try { JSON.parse(specJson.value); await automation.createLoopSpecTemplateVersion(versionTemplateId.value, { specJson: specJson.value, autoStartApproved: autoStartApproved.value }); message.value = autoStartApproved.value ? '已发布批准的不可变版本，可用于 AUTO_START。' : '已发布不可变版本，默认仍需人工审核。'; await load() } catch (cause) { error.value = cause instanceof Error ? cause.message : '版本 JSON 无法创建' } }
async function createRule() { if (!ruleName.value.trim() || !ruleProjectId.value || !ruleVersion.value) return; try { const config = JSON.parse(triggerConfig.value) as Record<string, unknown>; const mutation = await automation.createAutomationRule(createRuleInput(config)); if (mutation.webhookToken) revealedTokens.value = { ...revealedTokens.value, [mutation.rule.id]: mutation.webhookToken }; workspace.value = workspace.value ? { ...workspace.value, rules: [mutation.rule, ...workspace.value.rules] } : workspace.value; message.value = mutation.webhookToken ? 'Webhook token 仅此一次显示，请立即保存。' : '规则已创建：服务端已强制默认停用和人工审核。' } catch (cause) { error.value = cause instanceof Error ? cause.message : '规则 JSON 无法创建' } }
async function previewImport() { try { preview.value = await automation.previewLoopSpecTemplateImport(importJson.value); message.value = '导入已预览；确认前不会创建模板、版本或规则。' } catch (cause) { error.value = cause instanceof Error ? cause.message : '导入 JSON 无法预览' } }
async function confirmImport() { if (!preview.value) return; try { const imported = await automation.confirmLoopSpecTemplateImport(preview.value.previewId); const tokens = Object.fromEntries(imported.rules.filter(rule => rule.webhookToken).map(rule => [rule.rule.id, rule.webhookToken as string])); revealedTokens.value = { ...revealedTokens.value, ...tokens }; workspace.value = workspace.value ? { ...workspace.value, templates: imported.templates, rules: [...imported.rules.map(rule => rule.rule), ...workspace.value.rules] } : workspace.value; preview.value = undefined; message.value = Object.keys(tokens).length ? '预览已确认；导入的 Webhook token 仅此一次显示，请立即保存。' : '预览已确认并创建。' } catch (cause) { error.value = cause instanceof Error ? cause.message : '导入创建失败' } }
async function exportWorkspace() { try { exported.value = await automation.exportLoopSpecTemplate(); message.value = '已导出当前持久化配置。' } catch (cause) { error.value = cause instanceof Error ? cause.message : '导出失败' } }
async function updateRule(rule: Rule, state: Rule['state'], approvalMode: Rule['approvalMode']) { try { const updated = await automation.updateAutomationRule({ ...rule, state, approvalMode, version: rule.version }); workspace.value = workspace.value ? { ...workspace.value, rules: workspace.value.rules.map(current => current.id === updated.id ? updated : current) } : workspace.value; message.value = state === 'ENABLED' ? `规则已显式启用：${approvalMode}。` : '规则已显式停用。' } catch (cause) { error.value = cause instanceof Error ? cause.message : '规则更新失败' } }
async function triggerRule(rule: Rule) { if (rule.triggerType !== 'MANUAL') return; try { const run = await automation.triggerAutomationRule(rule.id); workspace.value = workspace.value ? { ...workspace.value, runs: [run, ...workspace.value.runs] } : workspace.value; message.value = '手工触发请求已提交；状态以服务端运行记录为准。' } catch (cause) { error.value = cause instanceof Error ? cause.message : '触发失败' } }
async function confirmRun(run: Run) { try { const updated = await automation.confirmAutomationRun(run.id); workspace.value = workspace.value ? { ...workspace.value, runs: workspace.value.runs.map(current => current.id === updated.id ? updated : current) } : workspace.value; message.value = '已确认生成任务，运行状态以服务端返回为准。' } catch (cause) { error.value = cause instanceof Error ? cause.message : '确认运行失败' } }
onMounted(load)
</script>

<template>
  <PageHeader eyebrow="Automation / Immutable Evidence" title="自动化工作台"><template #actions><el-button :loading="loading" @click="load"><Icon icon="lucide:refresh-cw" />刷新</el-button></template></PageHeader>
  <main id="main-content" class="content automation" tabindex="-1">
    <section v-if="error" class="error-panel error-panel-task" role="status"><Icon class="error-panel-icon" icon="lucide:triangle-alert" /><div><h3>自动化状态未同步</h3><p>{{ error }}</p></div></section><p v-if="message" class="notice"><Icon icon="lucide:info" />{{ message }}</p>
    <section v-if="loading" class="card empty-state"><div><Icon icon="lucide:loader-circle" class="spin" /><strong>正在读取服务端自动化记录</strong></div></section>
    <template v-else-if="workspace"><p class="server-time">服务器时间：<time>{{ workspace.serverTime }}</time> · 所有运行状态均来自持久化记录。</p><section class="grid"><article class="card pad"><h2>模板与不可变版本</h2><div class="form"><input v-model="templateName" placeholder="模板名称" /><input v-model="templateDescription" placeholder="用途说明" /><el-button type="primary" @click="createTemplate">创建模板</el-button></div><div v-if="!workspace.templates.length" class="empty">尚无模板</div><article v-for="template in workspace.templates" :key="template.id" class="item"><b>{{ template.name }}</b><span>{{ template.description }}</span><small v-for="version in template.versions" :key="version.id">v{{ version.versionNumber }} · 不可变 · {{ version.autoStartApproved ? '已批准 AUTO_START' : '需 REVIEW_REQUIRED' }}</small></article><div class="form version"><select v-model="versionTemplateId"><option value="">选择模板</option><option v-for="template in workspace.templates" :key="template.id" :value="template.id">{{ template.name }}</option></select><textarea v-model="specJson" aria-label="版本 JSON" /><label><input v-model="autoStartApproved" type="checkbox" />批准为 AUTO_START 版本</label><el-button @click="createVersion">发布不可变版本</el-button></div></article>
      <article class="card pad"><h2>新自动化规则</h2><p class="guard">创建请求不含状态或审批字段；服务端强制 <code>DISABLED</code> + <code>REVIEW_REQUIRED</code>。AUTO_START 仅能在后续显式更新中授予已批准版本；WEBHOOK 只接受 loopback 回调。</p><div class="form"><input v-model="ruleName" placeholder="规则名称" /><select v-model="ruleProjectId" aria-label="项目"><option value="">选择项目</option><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option></select><select v-model="ruleVersion"><option value="">选择不可变版本</option><option v-for="version in versions" :key="version.id" :value="version.id">{{ version.label }}</option></select><select v-model="triggerType"><option>MANUAL</option><option>CRON</option><option>GIT_HEAD_CHANGED</option><option>WEBHOOK</option></select><textarea v-model="triggerConfig" aria-label="触发配置 JSON" /><el-button type="primary" @click="createRule">创建默认停用规则</el-button></div></article></section>
    <section class="grid lower"><article class="card pad"><h2>JSON 导入与导出</h2><textarea v-model="importJson" aria-label="导入 JSON" placeholder="粘贴导出的 JSON 后先预览" /><div class="actions"><el-button @click="previewImport">预览导入</el-button><el-button @click="exportWorkspace">导出</el-button></div><div v-if="preview" class="preview"><b>预览：{{ preview.templates.length }} 个模板 · {{ preview.rules.length }} 条规则</b><p>未创建任何记录。</p><el-button type="primary" @click="confirmImport">确认创建</el-button></div><textarea v-if="exported" :value="exported" readonly aria-label="导出 JSON" /></article><article class="card pad"><h2>规则与 webhook</h2><div v-if="!workspace.rules.length" class="empty">尚无自动化规则</div><article v-for="rule in workspace.rules" :key="rule.id" class="item"><b>{{ rule.name }}</b><span><code>{{ rule.triggerType }}</code> · {{ rule.state }} · {{ rule.approvalMode }}</span><p v-if="revealedTokens[rule.id]" class="token">Token（仅显示一次）：<code>{{ revealedTokens[rule.id] }}</code></p><div class="actions"><el-button v-if="rule.state === 'DISABLED'" size="small" @click="updateRule(rule, 'ENABLED', 'REVIEW_REQUIRED')">启用 REVIEW_REQUIRED</el-button><el-button v-else size="small" @click="updateRule(rule, 'DISABLED', rule.approvalMode)">停用</el-button><el-button v-if="canAutoStart(rule)" size="small" type="warning" @click="updateRule(rule, 'ENABLED', 'AUTO_START')">启用 AUTO_START</el-button><el-button v-if="rule.triggerType === 'MANUAL'" size="small" @click="triggerRule(rule)">立即触发</el-button></div></article></article></section>
    <section class="card pad history"><h2>运行历史</h2><div v-if="!workspace.runs.length" class="empty">尚无运行历史；不会显示模拟进度。</div><table v-else><thead><tr><th>触发</th><th>状态 / 队列</th><th>Draft / Task</th><th>错误</th><th>服务器时间</th><th>操作</th></tr></thead><tbody><tr v-for="run in workspace.runs" :key="run.id"><td><code>{{ run.triggerType }}</code></td><td>{{ run.state }}<small v-if="run.queueState"> · {{ run.queueState }}</small></td><td><code>{{ run.draftId || '—' }}</code><br /><code>{{ run.taskId || '—' }}</code></td><td>{{ run.error || '—' }}</td><td><time>{{ run.detectedAt }}</time></td><td><el-button v-if="run.state === 'REVIEW_REQUIRED'" size="small" type="primary" @click="confirmRun(run)">确认生成任务</el-button><span v-else>—</span></td></tr></tbody></table></section></template>
  </main>
</template>

<style scoped>
.automation{display:grid;gap:16px}.grid{display:grid;grid-template-columns:1.15fr .85fr;gap:16px}.lower{grid-template-columns:1fr 1fr}.pad{padding:17px}.pad h2{margin:0 0 13px;font-size:15px}.form{display:grid;gap:8px}.form input,.form select,.form textarea,.pad>textarea{width:100%;padding:9px;border:1px solid var(--color-border-default);border-radius:6px;color:var(--color-text-primary);background:#0b1221;font:11px var(--font-code)}textarea{min-height:74px;resize:vertical}.guard,.notice,.server-time{margin:0;color:var(--color-text-secondary);font-size:11px;line-height:1.55}.guard{padding:10px;border-left:2px solid var(--color-accent-ai);background:rgb(139 92 246 / 7%)}.notice{padding:10px;border:1px solid rgb(34 211 238 / 25%);border-radius:8px;color:#bae6fd;background:rgb(34 211 238 / 6%)}.server-time{font-family:var(--font-code)}.item{display:grid;gap:5px;margin-top:10px;padding:10px;border:1px solid var(--color-border-default);border-radius:8px;background:rgb(2 6 23 / 35%)}.item b{font-size:12px}.item span,.item small,.item p{margin:0;color:var(--color-text-secondary);font-size:10px}.token{color:#fcd34d!important}.actions{display:flex;gap:8px;margin:9px 0}.preview{margin-top:10px;padding:10px;border:1px solid rgb(34 211 238 / 28%);border-radius:8px;background:rgb(34 211 238 / 5%);font-size:11px}.empty{padding:22px 4px;color:var(--color-text-muted);font-size:11px}.history{overflow:auto}table{width:100%;border-collapse:collapse;font-size:11px}th,td{padding:10px;border-bottom:1px solid var(--color-border-default);text-align:left;vertical-align:top}th{color:var(--color-text-muted);font:700 9px var(--font-code)}td{color:var(--color-text-secondary)}@media(max-width:900px){.grid,.lower{grid-template-columns:1fr}}
</style>
