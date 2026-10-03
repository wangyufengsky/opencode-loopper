import { api, ApiError } from '@/api/client'
import type { Project, RoleCatalogItem, RoleComparison, RoleDetail, RoleImportPublication, RoleImportValidation, RolePermissionPreview, RoleRevision, RoleRevisionSummary, RoleSlotBinding } from '@/types/domain'
import type { OperationOwner } from '@/foundation/contracts/receipt'
import { userFacingError } from '@/utils/displayLabels'
import { noCommand, pageController, unresolved, type CommandStatus } from '../workflow/controllerCore'
import { availableRoleSlots, importChanges } from './rolePresentation'

export type RoleTab = 'overview' | 'permissions' | 'prompt' | 'history'
export interface RoleState {
  rows: RoleCatalogItem[]; search: string; appliedSearch: string; cursor: string; nextCursor: string; previous: string[]; listLoading: boolean; listError: string
  selectedId: string; selected: RoleDetail | null; detailLoading: boolean; detailError: string; tab: RoleTab
  bindings: RoleSlotBinding[]; bindingsError: string; projects: Project[]; projectsError: string; slot: string; projectId: string
  preview: RolePermissionPreview | null; previewLoading: boolean; previewError: string
  revision: RoleRevision | null; revisionLoading: boolean; revisionError: string
  history: RoleRevisionSummary[]; historyCursor: string; historyNext: string; historyLoading: boolean; historyError: string
  historical: RoleRevision | null; historicalId: string; historicalLoading: boolean; historicalError: string
  comparison: RoleComparison | null; comparisonId: string; comparisonLoading: boolean; comparisonError: string; exportId: string; exportRetryId: string; exportError: string
  importOpen: boolean; fileName: string; importPreview: RoleImportValidation | null; validating: boolean; confirmed: boolean; importError: string; importSuccess: string
  draftRevision: number; command: CommandStatus
}
type RolePort = Pick<typeof api, 'getRoles' | 'getRole' | 'getRoleSlots' | 'getProjects' | 'previewRoleSlot' | 'getRoleRevision' | 'getRoleRevisions' | 'compareRoleRevisions' | 'exportRoleRevision' | 'validateRoleImport' | 'publishRoleImport'>
type PublishBody = { sourceSha256: string; idempotencyKey: string; activations: { slot: string; roleId: string; expectedVersion: number }[] }
export function createRoleManagementController(options: { api?: RolePort; key?: () => string; download?: (blob: Blob, filename: string, own: (dispose: () => void) => () => void) => void } = {}) {
  const port = options.api ?? api
  const core = pageController<RoleState>('role-management', { rows: [], search: '', appliedSearch: '', cursor: '', nextCursor: '', previous: [], listLoading: false, listError: '',
    selectedId: '', selected: null, detailLoading: false, detailError: '', tab: 'overview', bindings: [], bindingsError: '', projects: [], projectsError: '', slot: '', projectId: '',
    preview: null, previewLoading: false, previewError: '', revision: null, revisionLoading: false, revisionError: '', history: [], historyCursor: '', historyNext: '', historyLoading: false, historyError: '',
    historical: null, historicalId: '', historicalLoading: false, historicalError: '', comparison: null, comparisonId: '', comparisonLoading: false, comparisonError: '', exportId: '', exportRetryId: '', exportError: '',
    importOpen: false, fileName: '', importPreview: null, validating: false, confirmed: false, importError: '', importSuccess: '', draftRevision: 0, command: noCommand },
  snapshot => snapshot.fileName ? { kind: 'CONFIRM_DISCARD', description: '当前配置包尚未发布，是否放弃配置包后离开？', draftRevision: snapshot.draftRevision } : { kind: 'ALLOW' })
  const state = core.owner.getSnapshot
  let file: File | null = null, key = ''
  let operation: OperationOwner<PublishBody, RoleImportPublication> | null = null
  let listRetry: { cursor: string; action: 'load' | 'next' | 'previous' } = { cursor: '', action: 'load' }
  let historyRetry = { cursor: '', append: false }
  function clearSelection() {
    core.invalidate('detail', 'preview', 'revision', 'history', 'historical', 'comparison', 'export')
    core.patch({ selectedId: '', selected: null, tab: 'overview', slot: '', preview: null, revision: null, history: [], historical: null, comparison: null,
      detailError: '', detailLoading: false, previewLoading: false, previewError: '', revisionLoading: false, revisionError: '', historyCursor: '', historyNext: '', historyLoading: false, historyError: '',
      historicalId: '', historicalError: '', historicalLoading: false, comparisonId: '', comparisonError: '', comparisonLoading: false, exportId: '', exportRetryId: '', exportError: '' })
  }
  async function loadRoles(cursor = '', action: 'load' | 'next' | 'previous' = 'load', reset = true) {
    if (!core.active()) return false
    const current = core.ticket('list'), s = state(); listRetry = { cursor, action }
    core.patch({ listLoading: true, listError: '' })
    if (reset) clearSelection()
    try {
      const page = await port.getRoles(s.appliedSearch, cursor, 12)
      if (!current.current()) return false
      core.patch({ rows: page.items, cursor, nextCursor: page.nextCursor ?? '',
        previous: action === 'next' ? [...s.previous, s.cursor] : action === 'previous' ? s.previous.slice(0, -1) : s.previous })
      return true
    } catch (failure) { if (current.current()) core.patch({ listError: userFacingError(failure, '角色列表读取失败，请重试。') }); return false }
    finally { if (current.current()) core.patch({ listLoading: false }) }
  }
  async function bindings() {
    if (!core.active()) return false
    const current = core.ticket('bindings'); core.patch({ bindingsError: '' })
    try {
      const bindings = await port.getRoleSlots()
      if (!current.current()) return false
      core.patch({ bindings })
      if (state().selected && !state().slot) core.patch({ slot: availableRoleSlots(state().selected, state().revision, bindings)[0]?.slot ?? '' })
      return true
    } catch (failure) { if (current.current()) core.patch({ bindingsError: userFacingError(failure, '角色绑定读取失败，请刷新。') }); return false }
  }
  async function projects() {
    if (!core.active()) return
    const current = core.ticket('projects'); core.patch({ projectsError: '' })
    try { const projects = await port.getProjects(); if (current.current()) core.patch({ projects }) }
    catch (failure) { if (current.current()) core.patch({ projectsError: userFacingError(failure, '项目列表读取失败，请重试。') }) }
  }
  async function select(roleId: string) {
    clearSelection()
    if (!roleId || !core.active()) return
    const current = core.ticket('detail'); core.patch({ selectedId: roleId, detailLoading: true })
    try { const selected = await port.getRole(roleId); if (current.current()) core.patch({ selected, slot: selected.activeSlots?.[0] ?? state().bindings.find(binding => binding.activeRoleId === roleId)?.slot ?? '' }) }
    catch (failure) { if (current.current()) core.patch({ detailError: userFacingError(failure, '角色详情读取失败，请重试。') }) }
    finally { if (current.current()) core.patch({ detailLoading: false }) }
  }
  async function preview() {
    if (!core.active()) return
    const { selected, slot, projectId } = state(), current = core.ticket('preview')
    if (!selected || !slot) { core.patch({ preview: null }); return }
    core.patch({ preview: null, previewLoading: true, previewError: '' })
    try { const preview = await port.previewRoleSlot(selected.roleId, slot, projectId); if (current.current()) core.patch({ preview }) }
    catch (failure) { if (current.current()) core.patch({ previewError: userFacingError(failure, '权限预览失败，请重试。') }) }
    finally { if (current.current()) core.patch({ previewLoading: false }) }
  }
  async function revision() {
    if (!core.active()) return
    const { selected } = state(); if (!selected?.latestRevisionId) return
    const current = core.ticket('revision'); core.patch({ revisionLoading: true, revisionError: '' })
    try {
      const revision = await port.getRoleRevision(selected.roleId, selected.latestRevisionId)
      if (current.current()) {
        core.patch({ revision })
        if (!state().slot) {
          core.patch({ slot: availableRoleSlots(selected, revision, state().bindings)[0]?.slot ?? '' })
          if (state().tab === 'permissions') void preview()
        }
      }
    } catch (failure) { if (current.current()) core.patch({ revisionError: userFacingError(failure, '提示模板读取失败，请重试。') }) }
    finally { if (current.current()) core.patch({ revisionLoading: false }) }
  }
  async function history(cursor = '', append = false) {
    if (!core.active() || !state().selected) return
    const role = state().selected!, old = state().history, current = core.ticket('history'); historyRetry = { cursor, append }
    core.patch({ historyLoading: true, historyError: '' })
    try { const page = await port.getRoleRevisions(role.roleId, cursor, 12); if (current.current()) core.patch({ history: append ? [...old, ...page.items] : page.items, historyCursor: cursor, historyNext: page.nextCursor ?? '' }) }
    catch (failure) { if (current.current()) core.patch({ historyError: userFacingError(failure, '版本历史读取失败，请重试。') }) }
    finally { if (current.current()) core.patch({ historyLoading: false }) }
  }
  async function historical(id: string) {
    if (!core.active() || !state().selected) return
    const role = state().selected!, current = core.ticket('historical')
    core.patch({ historicalId: id, historical: null, historicalLoading: true, historicalError: '' })
    try { const historical = await port.getRoleRevision(role.roleId, id); if (current.current()) core.patch({ historical }) }
    catch (failure) { if (current.current()) core.patch({ historicalError: userFacingError(failure, '历史配置读取失败，请重试。') }) }
    finally { if (current.current()) core.patch({ historicalLoading: false }) }
  }
  async function compare(id: string) {
    if (!core.active()) return
    const role = state().selected; if (!role || id === role.latestRevisionId) return
    const current = core.ticket('comparison'); core.patch({ comparison: null, comparisonId: id, comparisonLoading: true, comparisonError: '' })
    try { const comparison = await port.compareRoleRevisions(role.roleId, id, role.latestRevisionId); if (current.current()) core.patch({ comparison }) }
    catch (failure) { if (current.current()) core.patch({ comparisonError: userFacingError(failure, '版本差异读取失败，请重试。') }) }
    finally { if (current.current()) core.patch({ comparisonLoading: false }) }
  }
  async function exportRevision(id: string) {
    const role = state().selected; if (!core.active() || !role || state().exportId) return
    const current = core.ticket('export'); core.patch({ exportId: id, exportRetryId: id, exportError: '' })
    try {
      const blob = await port.exportRoleRevision(role.roleId, id)
      if (current.current()) {
        const name = `${role.roleId.replace(/[^a-zA-Z0-9._-]/g, '_')}-v${id.replace(/[^a-zA-Z0-9._-]/g, '_')}.zip`
        if (options.download) options.download(blob, name, core.own)
        else {
          const url = URL.createObjectURL(blob)
          let timer: ReturnType<typeof setTimeout>
          const release = core.own(() => { clearTimeout(timer); URL.revokeObjectURL(url) })
          const link = document.createElement('a'); link.href = url; link.download = name
          document.body.appendChild(link)
          try { link.click() } finally { link.remove(); timer = setTimeout(release, 60_000) }
        }
      }
    } catch (failure) { if (current.current()) core.patch({ exportError: userFacingError(failure, '配置包下载失败，请重试。') }) }
    finally { if (current.current()) core.patch({ exportId: '' }) }
  }
  async function validate() {
    if (!file || !core.active() || state().validating || unresolved(state().command)) return
    const selectedFile = file, current = core.ticket('validation'); key = ''
    core.patch({ validating: true, importPreview: null, confirmed: false, importError: '', importSuccess: '' })
    if (!file.name.toLowerCase().endsWith('.zip')) { core.patch({ validating: false, importError: '请选择 ZIP 配置包，然后重新校验。' }); return }
    try {
      const importPreview = await port.validateRoleImport(selectedFile)
      if (current.current() && file === selectedFile) { key = (options.key ?? (() => crypto.randomUUID()))(); core.patch({ importPreview }) }
    } catch (failure) { if (current.current()) core.patch({ importError: userFacingError(failure, '配置包校验失败，请检查文件后重试。') }) }
    finally { if (current.current()) core.patch({ validating: false }) }
  }
  function canPublish() {
    const s = state()
    return !!file && !!s.importPreview?.valid && !!s.importPreview.sourceSha256 && !!key && s.confirmed && !s.validating && !unresolved(s.command)
      && importChanges(s.importPreview).length > 0 && !s.importPreview.diagnostics.length
  }
  async function publish() {
    if (!canPublish() || !core.active()) return
    const originalFile = file!, value = state().importPreview!
    const body: PublishBody = { sourceSha256: value.sourceSha256, idempotencyKey: key,
      activations: value.activations.map(({ slot, roleId, expectedVersion }) => ({ slot, roleId, expectedVersion })) }
    const current = core.command<PublishBody, RoleImportPublication>({ label: '发布角色配置', input: { endpoint: '/role-imports/publish', method: 'POST', body,
      requestKey: key, files: [originalFile], versions: Object.fromEntries(body.activations.map(binding => [binding.slot, binding.expectedVersion])) },
      capability: { kind: 'IDEMPOTENT_KEY' }, write: identity => port.publishRoleImport(identity.files[0]!, identity.body), changed: command => core.patch({ command }),
      rejected: failure => {
        if (failure instanceof ApiError && failure.status === 409) {
          key = ''; core.patch({ importPreview: null, confirmed: false, importError: '角色配置或绑定版本已变化。请重新校验同一配置包，核对新差异后再发布。' }); void bindings()
        }
      },
      read: async (receipt, context) => {
        if (!core.active()) throw new Error('页面读取已暂停，请显式恢复已接受的发布结果。')
        const [listed, bound] = await Promise.all([loadRoles('', 'load', true), bindings()])
        if (!listed || !bound) throw new Error('配置已发布，目录或绑定尚未读取完成。请恢复原结果，不能再次发布。')
        context.apply(() => { file = null; key = ''; core.patch({ fileName: '', importPreview: null, confirmed: false, previous: [], importError: '',
          importSuccess: receipt.replayed ? '配置已发布过，当前结果已核对。' : '配置包已发布并激活；新建会话使用新配置。' }) })
      },
    })
    operation = current
    try { await current.execute() } catch { /* Exact File/body/key retained by operation owner. */ }
  }
  async function recover() {
    if (!operation || operation.getSnapshot().busy) return
    try { if (operation.getSnapshot().accepted) await operation.retryReadback(); else await operation.recoverWrite() } catch { /* Status remains visible. */ }
  }
  function discardImport() {
    if (state().validating || unresolved(state().command)) return false
    core.invalidate('validation'); file = null; key = ''
    core.patch({ fileName: '', importPreview: null, confirmed: false, importError: '', importSuccess: '', draftRevision: state().draftRevision + 1 }); return true
  }
  core.setStart(() => {
    const previous = state()
    // A read lease may have been detached during a request. Its late result is inert; a new
    // view must not inherit an eternal spinner or automatically resend a publication.
    core.patch({ detailLoading: false, previewLoading: false, revisionLoading: false, historyLoading: false, historicalLoading: false, comparisonLoading: false, exportId: '', validating: false,
      ...(previous.validating ? { importError: '配置包校验读取已暂停，请重新校验同一文件。' } : {}) })
    void loadRoles(state().cursor, 'load', false); void bindings(); void projects()
    if (previous.selectedId && !previous.selected) void select(previous.selectedId)
    else if (previous.selected) {
      if (previous.tab === 'permissions') { void preview(); if (!previous.revision) void revision() }
      else if (previous.tab === 'prompt' && !previous.revision) void revision()
      else if (previous.tab === 'history') { if (previous.historyLoading) void history(historyRetry.cursor, historyRetry.append); if (previous.historicalLoading) void historical(previous.historicalId) }
    }
  })
  return { ...core.owner, loadRoles, bindings, projects, select, clearSelection, preview, revision, history, historical, compare, exportRevision, validate, publish, canPublish, recover, discardImport,
    getOperation: () => operation, getFile: () => file,
    chooseImport(value: File) { if (file || unresolved(state().command) || state().validating) return false; file = value; core.patch({ importOpen: true, fileName: value.name, draftRevision: state().draftRevision + 1 }); void validate(); return true },
    openImport(importOpen: boolean) { core.patch({ importOpen }) },
    confirm(confirmed: boolean) { if (!unresolved(state().command) && !state().validating) core.patch({ confirmed }) },
    search(search: string) { core.patch({ search }) },
    submitSearch() { core.patch({ appliedSearch: state().search.trim(), previous: [] }); void loadRoles() },
    next() { if (state().nextCursor && !state().listLoading && !state().listError) void loadRoles(state().nextCursor, 'next') },
    previous() { if (state().previous.length && !state().listLoading && !state().listError) void loadRoles(state().previous[state().previous.length - 1] ?? '', 'previous') },
    retryList() { void loadRoles(listRetry.cursor, listRetry.action) }, retryHistory() { void history(historyRetry.cursor, historyRetry.append) },
    tab(tab: RoleTab) { core.patch({ tab }); if (tab === 'permissions') { if (!state().preview && !state().previewLoading) void preview(); if (!state().revision && !state().revisionLoading) void revision() }
      else if (tab === 'prompt' && !state().revision && !state().revisionLoading) void revision(); else if (tab === 'history' && !state().history.length && !state().historyLoading) void history() },
    showBindingRevision(id: string) { core.patch({ tab: 'history' }); if (!state().history.length && !state().historyLoading) void history(); void historical(id) },
    scope(slot: string, projectId: string) { core.invalidate('preview'); core.patch({ slot, projectId }); if (state().tab === 'permissions') void preview() },
  }
}
