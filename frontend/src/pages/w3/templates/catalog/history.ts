import { api, ApiError, request } from '@/api/client'
import { createSnapshotController } from '@/foundation/contracts/controller'
import { captureDto } from '@/foundation/contracts/immutable'
import type { AutomationRule, AutomationRun } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'

export interface HistoricalVersion { id: string; templateId: string; versionNumber: number; spec: unknown; specSha256: string; immutable: boolean; autoStartApproved: boolean; createdAt: string }
export interface HistoricalTemplate { id: string; name: string; description: string; state: 'ACTIVE' | 'ARCHIVED'; createdAt?: string; updatedAt: string; version: number; versions: HistoricalVersion[] }
export interface HistoricalWorkspace { templates: HistoricalTemplate[]; rules: AutomationRule[]; runs: AutomationRun[]; serverTime: string }
export type HistoryTab = 'overview' | 'templates' | 'rules' | 'runs' | 'export'
const object = (value: unknown): Record<string, unknown> => { if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('历史记录格式不完整，请重新读取'); return value as Record<string, unknown> }
const text = (value: unknown, required = false) => { if (typeof value === 'string' && (!required || value)) return value; if (!required && value == null) return ''; throw new Error('历史记录字段不完整，请重新读取') }
const number = (value: unknown) => { if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error('历史版本或计数无效，请重新读取'); return value }
const list = (value: unknown): unknown[] => { if (!Array.isArray(value)) throw new Error('历史记录列表格式无效，请重新读取'); return value }
const bool = (value: unknown) => { if (typeof value !== 'boolean') throw new Error('冻结版本标志无效，请重新读取'); return value }
function enumText<T extends string>(value: unknown, choices: readonly T[]): T { if (!choices.includes(value as T)) throw new Error('历史状态尚无法识别，请保留原记录并重新读取'); return value as T }
export function decodeHistoricalVersion(value: unknown, expectedTemplate?: string): HistoricalVersion {
  const raw = object(value), templateId = text(raw.templateId, true)
  if (expectedTemplate && templateId !== expectedTemplate) throw new Error('历史版本归属不匹配，请重新读取原模板')
  const spec = raw.spec ?? (typeof raw.specJson === 'string' ? JSON.parse(raw.specJson) as unknown : undefined)
  if (!spec || typeof spec !== 'object') throw new Error('冻结合同缺失，请重新读取原版本')
  return captureDto({ id: text(raw.id, true), templateId, versionNumber: number(raw.versionNumber), spec, specSha256: text(raw.specSha256, true), immutable: bool(raw.immutable), autoStartApproved: bool(raw.autoStartApproved), createdAt: text(raw.createdAt) })
}
export function decodeHistoricalTemplate(value: unknown, expectedId?: string): HistoricalTemplate {
  const raw = object(value), id = text(raw.id, true)
  if (expectedId && id !== expectedId) throw new Error('历史模板身份不匹配，请重新选择')
  return captureDto({ id, name: text(raw.name, true), description: text(raw.description), state: enumText(raw.state, ['ACTIVE', 'ARCHIVED']),
    ...(typeof raw.createdAt === 'string' ? { createdAt: raw.createdAt } : {}), updatedAt: text(raw.updatedAt), version: number(raw.version), versions: list(raw.versions).map(item => decodeHistoricalVersion(item, id)) })
}
export function decodeHistoricalRule(value: unknown): AutomationRule {
  const raw = object(value), triggerType = enumText(raw.triggerType, ['MANUAL', 'CRON', 'GIT_HEAD_CHANGED', 'WEBHOOK']), config = object(raw.triggerConfig)
  const base = { id: text(raw.id, true), name: text(raw.name, true), projectId: text(raw.projectId, true), templateVersionId: text(raw.templateVersionId, true), state: enumText(raw.state, ['DISABLED', 'ENABLED']),
    approvalMode: enumText(raw.approvalMode, ['REVIEW_REQUIRED', 'AUTO_START']), updatedAt: text(raw.updatedAt), version: number(raw.version) }
  const healthRaw = raw.health == null ? undefined : object(raw.health)
  const health = healthRaw ? { status: enumText(healthRaw.status, ['CHECKED', 'FAILED']), lastCheckedAt: text(healthRaw.lastCheckedAt, true), lastSuccessAt: text(healthRaw.lastSuccessAt) || undefined,
    consecutiveFailures: number(healthRaw.consecutiveFailures), errorCode: text(healthRaw.errorCode) || undefined, errorMessage: text(healthRaw.errorMessage) || undefined } : undefined
  // Explicit public fields only: no token, hash, lastObservedHead or fabricated mutation identity.
  if (triggerType === 'CRON') return captureDto({ ...base, health, triggerType, triggerConfig: { expression: text(config.expression, true), timezone: text(config.timezone, true) } })
  if (triggerType === 'GIT_HEAD_CHANGED') return captureDto({ ...base, health, triggerType, triggerConfig: typeof config.branch === 'string' ? { branch: config.branch } : {} })
  return captureDto({ ...base, health, triggerType, triggerConfig: {} })
}
export function decodeHistoricalRun(value: unknown, expectedRule?: string): AutomationRun {
  const raw = object(value), ruleId = text(raw.ruleId, true)
  if (expectedRule && ruleId !== expectedRule) throw new Error('历史运行归属不匹配，请读取原规则')
  return captureDto({ id: text(raw.id, true), ruleId, triggerType: enumText(raw.triggerType, ['MANUAL', 'CRON', 'GIT_HEAD_CHANGED', 'WEBHOOK']), state: enumText(raw.state, ['DETECTED', 'REVIEW_REQUIRED', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED']),
    draftId: text(raw.draftId) || undefined, taskId: text(raw.taskId) || undefined, evidence: object(raw.evidence), error: text(raw.error) || undefined,
    detectedAt: text(raw.detectedAt), startedAt: text(raw.startedAt) || undefined, endedAt: text(raw.endedAt) || undefined })
}
export function decodeHistoricalWorkspace(value: unknown): HistoricalWorkspace {
  const raw = object(value)
  return captureDto({ templates: list(raw.templates).map(item => decodeHistoricalTemplate(item)), rules: list(raw.rules).map(decodeHistoricalRule), runs: list(raw.runs).map(item => decodeHistoricalRun(item)), serverTime: text(raw.serverTime, true) })
}
export function validateHistoricalExport(content: string, kind: 'latest' | 'workspace'): string {
  const raw = object(JSON.parse(content) as unknown)
  function check(value: unknown) {
    if (!value || typeof value !== 'object') return
    for (const [key, child] of Object.entries(value)) {
      if (/^(webhookToken|webhookPath|tokenHash|tokenSha256|webhookTokenHash|secret)$/i.test(key)) throw new Error('导出包含不应公开的密钥字段，已阻止下载')
      check(child)
    }
  }
  check(raw)
  if (kind === 'workspace') {
    if (raw.formatVersion !== 1) throw new Error('历史导出格式不匹配，请重新读取')
    list(raw.templates); list(raw.rules)
    if ('runs' in raw || 'health' in raw) throw new Error('历史格式不包含运行记录或检测信息，已阻止异常下载')
    for (const rule of list(raw.rules)) { const row = object(rule); if (['state', 'approvalMode', 'health', 'version', 'lastObservedHead'].some(key => key in row)) throw new Error('历史规则导出字段超出原格式，已阻止异常下载') }
  }
  return content // Preserve exact server JSON bytes; never normalize/recompile frozen spec.
}
const enc = encodeURIComponent
async function exportGet(path: string, kind: 'latest' | 'workspace') {
  const response = await fetch(`${import.meta.env.VITE_API_BASE ?? '/api'}${path}`, { headers: { Accept: 'application/json' } })
  if (!response.ok) { const problem = await response.json().catch(() => ({})) as { detail?: string }; throw new ApiError(problem.detail || '历史导出读取失败，请重新读取', response.status) }
  return validateHistoricalExport(await response.text(), kind)
}
/** Nine existing public GETs, with no legacy mutation or subscription port. */
export const archiveReads = {
  workspace: async () => decodeHistoricalWorkspace(await request<unknown>('/automations/workspace')),
  templates: async () => list(await request<unknown>('/automations/templates')).map(item => decodeHistoricalTemplate(item)),
  template: async (id: string) => decodeHistoricalTemplate(await request<unknown>(`/automations/templates/${enc(id)}`), id),
  versions: async (id: string) => list(await request<unknown>(`/automations/templates/${enc(id)}/versions`)).map(item => decodeHistoricalVersion(item, id)),
  latestExport: (id: string) => exportGet(`/automations/templates/${enc(id)}/export`, 'latest'),
  workspaceExport: () => exportGet('/automations/templates/export', 'workspace'),
  rules: async () => list(await request<unknown>('/automations/rules')).map(decodeHistoricalRule),
  ruleRuns: async (id: string) => list(await request<unknown>(`/automations/rules/${enc(id)}/runs`)).map(item => decodeHistoricalRun(item, id)),
  runs: async () => { const raw = object(await request<unknown>('/automations/runs')); return { runs: list(raw.runs).map(item => decodeHistoricalRun(item)), serverTime: text(raw.serverTime, true) } },
}
export interface HistoryState { open: boolean; tab: HistoryTab; loading: boolean; error: string; workspace?: HistoricalWorkspace; templates: HistoricalTemplate[]; rules: AutomationRule[]; runs: AutomationRun[]; serverTime: string;
  selectedTemplate?: HistoricalTemplate; versions: HistoricalVersion[]; selectedRule?: AutomationRule; selectedRun?: AutomationRun; taskVerified?: string; taskError: string }
let epoch = 0
export function createTemplateHistoryArchiveController(reads = archiveReads) {
  const owner = createSnapshotController<HistoryState>({ identity: { domain: 'template-history', id: 'archive', epoch: ++epoch }, initial: { open: false, tab: 'overview', loading: false, error: '', templates: [], rules: [], runs: [], serverTime: '', versions: [], taskError: '' }, canLeave: () => ({ kind: 'ALLOW' }) })
  const state = owner.getSnapshot, set = (patch: Partial<HistoryState>) => owner.project({ ...state(), ...patch })
  let generation = 0
  const urls = new Set<string>()
  function releaseUrls() { for (const url of urls) { URL.revokeObjectURL(url) } urls.clear() }
  async function run(action: (apply: (patch: Partial<HistoryState>) => void) => Promise<void>) {
    const ticket = ++generation, token = owner.capture(); set({ loading: true, error: '' })
    const apply = (patch: Partial<HistoryState>) => { if (token.isCurrent() && ticket === generation && state().open) set(patch) }
    try { await action(apply) } catch (failure) { apply({ error: userFacingError(failure, '历史记录读取失败，请重新读取') }) }
    finally { apply({ loading: false }) }
  }
  async function refresh() {
    if (!state().open) return
    const tab = state().tab
    await run(async apply => {
      if (tab === 'overview') { const workspace = await reads.workspace(); apply({ workspace, serverTime: workspace.serverTime }) }
      else if (tab === 'templates') apply({ templates: await reads.templates() })
      else if (tab === 'rules') { const rules = await reads.rules(); apply({ rules, selectedRule: rules.find(rule => rule.id === state().selectedRule?.id) }) }
      else if (tab === 'runs') { const feed = await reads.runs(); apply({ runs: feed.runs, serverTime: feed.serverTime, selectedRun: feed.runs.find(run => run.id === state().selectedRun?.id) }) }
    })
  }
  async function open() { if (state().open) return; set({ open: true, tab: 'overview' }); await refresh() }
  function close() { ++generation; releaseUrls(); set({ open: false, loading: false, error: '' }) }
  async function tab(tab: HistoryTab) { generation++; set({ tab, selectedTemplate: undefined, selectedRule: undefined, selectedRun: undefined, versions: [], taskVerified: undefined, taskError: '', loading: false }); if (tab !== 'export') await refresh() }
  async function template(id: string) {
    set({ selectedTemplate: undefined, versions: [] })
    await run(async apply => { const [selectedTemplate, versions] = await Promise.all([reads.template(id), reads.versions(id)]); apply({ selectedTemplate, versions }) })
  }
  async function rule(rule: AutomationRule) {
    set({ selectedRule: rule, selectedRun: undefined, runs: [], taskVerified: undefined, taskError: '' })
    await run(async apply => { apply({ runs: await reads.ruleRuns(rule.id) }) })
  }
  async function verifyTask() {
    const id = state().selectedRun?.taskId; if (!id) return
    set({ taskVerified: undefined, taskError: '' })
    await run(async apply => { try { const task = await api.getTask(id); if (!task || task.id !== id) throw new Error('关联任务读取结果不匹配'); apply({ taskVerified: id }) } catch (failure) { apply({ taskError: userFacingError(failure, '关联任务已不可读取，原运行记录仍保留') }) } })
  }
  async function download(kind: 'latest' | 'workspace') {
    const templateId = state().selectedTemplate?.id
    if (kind === 'latest' && !templateId) return
    await run(async apply => {
      const ticket = generation, content = kind === 'latest' ? await reads.latestExport(templateId!) : await reads.workspaceExport()
      if (!owner.capture().isCurrent() || ticket !== generation || !state().open) return
      validateHistoricalExport(content, kind)
      const url = URL.createObjectURL(new Blob([content], { type: 'application/json' })); urls.add(url)
      const anchor = document.createElement('a'); anchor.href = url; anchor.download = kind === 'latest' ? 'historical-template-latest.json' : 'historical-templates-and-rules.json'; anchor.click()
      // This explicit download owns its URL; no timeout/global cleanup is needed.
      URL.revokeObjectURL(url); urls.delete(url); apply({ error: '' })
    })
  }
  return { ...owner, open, close, tab, refresh, template, rule, verifyTask, download,
    selectRun(run: AutomationRun) { generation++; set({ selectedRun: run, taskVerified: undefined, taskError: '', loading: false }) },
    retire(forced = false) { ++generation; releaseUrls(); return owner.retire(forced) },
  }
}
