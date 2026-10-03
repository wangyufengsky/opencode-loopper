import { api } from '@/api/client'
import { createSnapshotController } from '@/foundation/contracts/controller'
import { OwnedResourceCleanupError } from '@/foundation/contracts/resource'
import type { NavigationRequest } from '@/foundation/contracts/navigation'
import { userFacingError } from '@/utils/displayLabels'
import type { SourceTemplatePreview, TemplateBranchChoice, TemplateProjectChoice, TemplateTaskCatalog, TemplateTaskDefinition, SourceTemplateRequest, TemplateTaskRequest, DocumentTemplateRequest } from '@/types/domain'
import { createTemplateCreationController, documentUploadError, type CreationKind } from './creation'
import { createTemplateHistoryArchiveController } from './history'

export interface CatalogState {
  catalog?: TemplateTaskCatalog; loading: boolean; error: string; dirty: boolean; draftRevision: number
  selected: string; templateQuery: string; category: string; projectId: string; branchId: string; projects: TemplateProjectChoice[]; branches: TemplateBranchChoice[]
  projectQuery: string; branchQuery: string; projectCursor?: string | null; branchCursor?: string | null; loadingProjects: boolean; loadingBranches: boolean
  remoteAvailable: boolean; remoteProblems: string[]; branchError: string; startDate: string; endDate: string; reviewMode: 'DATE_INCREMENTAL' | 'FULL'
  documentPath: string; sourcePath: string; testPath: string; requirements: string; checking: boolean; preview?: SourceTemplatePreview; picking: string; files: { name: string; size: number }[]
}
let epoch = 0
export function createTemplateCatalogController(inheritedProject = '') {
  const creations = { report: createTemplateCreationController('report'), source: createTemplateCreationController('source'), document: createTemplateCreationController('document') }
  const history = createTemplateHistoryArchiveController()
  let files: readonly File[] = [], projectGeneration = 0, branchGeneration = 0, previewGeneration = 0, pickerGeneration = 0
  let initialization: Promise<void> | undefined
  const initial: CatalogState = { loading: true, error: '', dirty: false, draftRevision: 0, selected: '', templateQuery: '', category: '全部', projectId: '', branchId: '', projects: [], branches: [], projectQuery: '', branchQuery: '', loadingProjects: false, loadingBranches: false,
    remoteAvailable: true, remoteProblems: [], branchError: '', startDate: '', endDate: '', reviewMode: 'DATE_INCREMENTAL', documentPath: '', sourcePath: '', testPath: '', requirements: '', checking: false, picking: '', files: [] }
  const owner = createSnapshotController({ identity: { domain: 'template-catalog', id: 'catalog', epoch: ++epoch }, initial,
    canLeave: s => s.dirty || files.length ? { kind: 'CONFIRM_DISCARD', description: '仍有未提交模板参数或文档，是否放弃后离开？', draftRevision: s.draftRevision } : { kind: 'ALLOW' },
  })
  const state = owner.getSnapshot, set = (patch: Partial<CatalogState>) => owner.project({ ...state(), ...patch })
  const templates = () => (state().catalog?.templates ?? []).filter(t => t.id !== 'REQUIREMENT_DEVELOPMENT')
  const definition = (): TemplateTaskDefinition | undefined => templates().find(t => t.id === state().selected)
  const kind = (): CreationKind => definition()?.inputs?.sourcePath ? 'source' : definition()?.inputs?.documents ? 'document' : 'report'
  const needsBranch = () => definition()?.inputs?.branch ?? true
  const needsDates = () => definition()?.workflow === 'SNAPSHOT_CODE_REVIEW' ? state().reviewMode === 'DATE_INCREMENTAL' : definition()?.inputs?.dates ?? true
  const currentCreation = () => creations[kind()]
  const blocked = () => Object.values(creations).some(c => !['IDLE', 'SETTLED'].includes(c.getSnapshot().phase))
  for (const child of Object.values(creations)) child.subscribe(() => { set({}) })
  const dateError = () => state().startDate && state().endDate && state().endDate < state().startDate ? '结束日期不能早于开始日期' : ''
  const fileError = () => files.length ? documentUploadError(files) : ''
  function valid() { const s = state(); return !!definition() && !!s.projectId && !blocked() && !s.picking && (!needsBranch() || !!s.branchId && !s.loadingBranches)
    && (!needsDates() || !!s.startDate && !!s.endDate && !dateError()) && (kind() === 'source' ? !!s.sourcePath.trim() && !!s.preview && s.preview.targetCount > 0 && !s.preview.configurationProblem && !s.checking : kind() === 'document' ? files.length > 0 && !fileError() : true) }
  async function projects(query = '', append = false) {
    const ticket = ++projectGeneration, token = owner.capture(); set({ projectQuery: query, loadingProjects: true })
    try { const page = await api.templateProjects(query, append ? state().projectCursor ?? undefined : undefined); if (token.isCurrent() && ticket === projectGeneration) set({ projects: append ? [...state().projects, ...page.items] : page.items, projectCursor: page.nextCursor, error: '' }) }
    catch (failure) { if (token.isCurrent() && ticket === projectGeneration) set({ error: userFacingError(failure, '项目列表加载失败，请重试') }) }
    finally { if (token.isCurrent() && ticket === projectGeneration) set({ loadingProjects: false }) }
  }
  async function branches(query = '', append = false, chooseDefault = false) {
    const ticket = ++branchGeneration, token = owner.capture(), projectId = state().projectId; if (!projectId || !needsBranch()) return
    set({ branchQuery: query, loadingBranches: true, branchError: '' })
    try {
      const result = await api.templateBranches(projectId, query, append ? state().branchCursor ?? undefined : undefined)
      if (!token.isCurrent() || ticket !== branchGeneration || projectId !== state().projectId) return
      const branches = append ? [...state().branches, ...result.page.items] : [...result.page.items]
      if (chooseDefault && result.defaultBranch && !branches.some(b => b.id === result.defaultBranch!.id)) branches.unshift(result.defaultBranch)
      set({ branches, branchCursor: result.page.nextCursor, remoteAvailable: result.remoteAvailable, remoteProblems: result.remoteProblems ?? [], ...(chooseDefault && result.defaultBranch ? { branchId: result.defaultBranch.id } : {}) })
    } catch (failure) { if (token.isCurrent() && ticket === branchGeneration) set({ branchError: userFacingError(failure, '分支读取失败，请检查仓库连接后重试') }) }
    finally { if (token.isCurrent() && ticket === branchGeneration) set({ loadingBranches: false }) }
  }
  function invalidateInputs() { ++previewGeneration; ++pickerGeneration; set({ preview: undefined, checking: false, picking: '', error: '', dirty: true, draftRevision: state().draftRevision + 1 }) }
  async function project(projectId: string, markDirty = true) {
    if (blocked()) return
    ++branchGeneration; invalidateInputs()
    set({ projectId, branches: [], branchId: '', branchCursor: null, branchQuery: '', sourcePath: '', testPath: '', requirements: '', documentPath: state().projects.find(p => p.id === projectId)?.documentPath ?? '', dirty: markDirty })
    if (needsBranch()) await branches('', false, true)
  }
  async function select(id: string, discard = false) {
    if (blocked() || state().dirty && !discard || !templates().some(t => t.id === id)) return false
    files = []; ++branchGeneration; invalidateInputs(); set({ selected: id, sourcePath: '', testPath: '', requirements: '', files: [], branchId: '', branches: [], loadingBranches: false, branchError: '', dirty: false })
    if (needsBranch() && state().projectId) await branches('', false, true)
    return true
  }
  function change(field: 'branchId' | 'startDate' | 'endDate' | 'reviewMode' | 'documentPath' | 'sourcePath' | 'testPath' | 'requirements', value: string) {
    if (blocked() || !owner.capture().isCurrent()) return false
    if (['sourcePath', 'testPath', 'documentPath', 'requirements'].includes(field)) invalidateInputs()
    return set({ [field]: value, dirty: true, draftRevision: state().draftRevision + 1 })
  }
  function chooseFiles(selected: readonly File[]) { if (blocked() || !owner.capture().isCurrent()) return false; if (!selected.length) return false; files = Object.freeze([...selected]); return set({ files: selected.map(f => ({ name: f.name, size: f.size })), dirty: true, draftRevision: state().draftRevision + 1 }) }
  function removeFile(index: number) { if (blocked()) return; files = Object.freeze(files.filter((_, i) => i !== index)); set({ files: files.map(f => ({ name: f.name, size: f.size })), dirty: true, draftRevision: state().draftRevision + 1 }) }
  async function pick(field: 'sourcePath' | 'testPath' | 'documentPath') {
    if (blocked() || state().picking || !owner.capture().isCurrent()) return
    const ticket = ++pickerGeneration, token = owner.capture(), before = state()[field]; set({ picking: field })
    try { const result = await api.pickProjectDirectory(); if (token.isCurrent() && ticket === pickerGeneration && !blocked() && state()[field] === before && result.selected && result.path) change(field, result.path) }
    catch (failure) { if (token.isCurrent() && ticket === pickerGeneration) set({ error: userFacingError(failure, '无法打开文件夹选择器，请手动填写路径') }) }
    finally { if (token.isCurrent() && ticket === pickerGeneration) set({ picking: '' }) }
  }
  function sourceInput(): Omit<SourceTemplateRequest, 'requestKey'> {
    const s = state(), d = definition()!
    return { templateId: d.id, templateVersion: d.version, projectId: s.projectId, sourcePath: s.sourcePath.trim(), requirements: s.requirements.trim(),
      ...(d.inputs?.testOutputPath ? { testOutputPath: s.testPath.trim() || undefined } : {}), ...(d.inputs?.documentOutputPath ? { documentPath: s.documentPath.trim() || undefined } : {}) }
  }
  async function preview() {
    if (blocked() || !state().projectId || !state().sourcePath.trim() || state().checking || state().picking) return
    const ticket = ++previewGeneration, token = owner.capture(), input = sourceInput(); set({ checking: true, error: '' })
    try { const result = await api.sourcePreview({ ...input, requestKey: crypto.randomUUID() }); if (token.isCurrent() && ticket === previewGeneration) set({ preview: result }) }
    catch (failure) { if (token.isCurrent() && ticket === previewGeneration) set({ error: userFacingError(failure, '范围检查未完成，请核对路径后重试') }) }
    finally { if (token.isCurrent() && ticket === previewGeneration) set({ checking: false }) }
  }
  async function submit() {
    if (!valid()) return undefined
    const s = state(), d = definition()!, creation = currentCreation(), token = owner.capture(); set({ error: '' })
    const input = kind() === 'source' ? sourceInput() : kind() === 'document'
      ? { templateId: d.id, templateVersion: d.version, projectId: s.projectId, ...(needsBranch() ? { branchId: s.branchId } : {}) } satisfies Omit<DocumentTemplateRequest, 'requestKey'>
      : { templateId: d.id, templateVersion: d.version, projectId: s.projectId, branchId: s.branchId, ...(needsDates() ? { startDate: s.startDate, endDate: s.endDate } : {}),
        ...(d.workflow === 'SNAPSHOT_CODE_REVIEW' ? { reviewMode: s.reviewMode } : {}), documentPath: s.documentPath.trim() || undefined } satisfies Omit<TemplateTaskRequest, 'requestKey'>
    const id = await creation.start(input, files)
    if (!token.isCurrent()) return undefined
    if (id) set({ dirty: false })
    return id
  }
  function initialize() {
    if (initialization) return initialization
    const token = owner.capture()
    initialization = (async () => {
      try {
        await Promise.all([projects(), api.templateCatalog().then(catalog => { if (token.isCurrent()) {
          const pending = Object.values(creations).find(child => !['IDLE','SETTLED'].includes(child.getSnapshot().phase))
          const identity = pending?.originalIdentity()
          const original = identity && 'body' in identity ? identity.body : identity
          const selected = original && 'templateId' in original && catalog.templates.some(t => t.id === original.templateId) ? original.templateId : catalog.templates.find(t => t.id !== 'REQUIREMENT_DEVELOPMENT')?.id ?? ''
          set({ catalog, selected, startDate: catalog.defaultStartDate ?? '', endDate: catalog.defaultEndDate ?? '', ...(original ? {
            projectId: original.projectId, ...('branchId' in original ? {branchId: original.branchId} : {}), ...('sourcePath' in original ? {sourcePath: original.sourcePath,requirements: original.requirements ?? '',testPath: original.testOutputPath ?? '',documentPath: original.documentPath ?? ''} : {})
          } : {}) })
        } })])
        if (!token.isCurrent()) return
        if (inheritedProject && !blocked()) { const inherited = await api.templateProject(inheritedProject); if (!token.isCurrent()) return; if (!state().projects.some(p => p.id === inherited.id)) set({ projects: [inherited, ...state().projects] }); await project(inherited.id, false) }
        await creations.document.restore()
      } catch (failure) { if (token.isCurrent()) set({ error: userFacingError(failure, '模板目录加载失败，请重新读取') }) }
      finally { if (token.isCurrent()) set({ loading: false }) }
    })()
    return initialization
  }
  return { ...owner, creations, history, initialize, projects, branches, project, select, change, chooseFiles, removeFile, pick, preview, submit, sourceInput,
    definition, templates, kind, needsBranch, needsDates, currentCreation, blocked, valid, dateError, fileError, originalFiles: () => files,
    filter(field: 'templateQuery' | 'category', value: string) { set({ [field]: value }) },
    canLeave(request?: NavigationRequest) { for (const child of Object.values(creations)) { const policy = child.canLeave(request); if (policy.kind === 'BLOCK') return policy } return request && Object.values(creations).some(child => child.getSnapshot().destination === request.destination) ? { kind: 'ALLOW' as const } : owner.canLeave(request) },
    retire(forced = false) {
      const decision = this.canLeave(); if (!forced && decision.kind !== 'ALLOW') return decision
      ++projectGeneration; ++branchGeneration; ++previewGeneration; ++pickerGeneration
      const failures: unknown[] = []
      for (const child of [owner, ...Object.values(creations), history]) try { child.retire(forced) } catch (failure) { failures.push(failure) }
      if (failures.length) throw new OwnedResourceCleanupError(failures)
      return { kind: 'ALLOW' as const }
    },
  }
}
export type TemplateCatalogController = ReturnType<typeof createTemplateCatalogController>
