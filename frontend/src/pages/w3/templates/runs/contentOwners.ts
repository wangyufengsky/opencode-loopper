import { api } from '@/api/client'
import type { DocumentTemplateOverview, DocumentRequirementSummary, DocumentRequirementDetail, DocumentClarification, DocumentSection, DocumentRequirementSource, SourceTemplateCoverage, DocumentReportSummary, SourceArtifact } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import { runOwner } from './core'

export function createRequirementsOwner(run: DocumentTemplateOverview) {
  const core = runOwner('document-requirements', `${run.id}:${run.requirementRevision}`, { items: [] as DocumentRequirementSummary[], next: null as number | null, issuesOnly: false, loading: false, error: '', selected: '', details: {} as Record<string, DocumentRequirementDetail>, detailLoading: false, source: undefined as DocumentSection | undefined, clarifications: undefined as DocumentClarification[] | undefined })
  const { base, patch, ticket } = core
  async function load(append = false) {
    const current = ticket('list'), state = base.getSnapshot(); patch({ loading: true, error: '' })
    try { const page = await api.documentRequirements(run.id, run.requirementRevision, append ? state.next ?? -1 : -1, state.issuesOnly)
      if (current.current()) patch({ items: append ? [...state.items, ...page.items] : page.items, next: page.nextOffset })
    } catch (failure) { if (current.current()) patch({ error: userFacingError(failure, '需求清单读取失败，请重试') }) }
    finally { if (current.current()) patch({ loading: false }) }
  }
  async function select(key: string) {
    const current = ticket('detail'); patch({ selected: key, source: undefined })
    if (!key || base.getSnapshot().details[key]) { patch({ detailLoading: false }); return }
    patch({ detailLoading: true })
    try { const detail = await api.documentRequirement(run.id, run.requirementRevision, key); if (current.current()) patch({ details: { ...base.getSnapshot().details, [key]: detail } }) }
    catch (failure) { if (current.current()) patch({ error: userFacingError(failure, '需求正文读取失败，请重试') }) }
    finally { if (current.current()) patch({ detailLoading: false }) }
  }
  async function history() {
    const current = ticket('history')
    try { const clarifications = await api.documentClarifications(run.id, run.requirementRevision); if (current.current()) patch({ clarifications }) }
    catch (failure) { if (current.current()) patch({ error: userFacingError(failure, '澄清记录读取失败，请重试') }) }
  }
  async function source(reference: DocumentRequirementSource) {
    const file = run.files.find(file => file.id === reference.fileId), key = base.getSnapshot().selected
    if (!file) return
    const current = ticket('source')
    try { const source = await api.documentSection(run.id, file.id, reference.section, file.sha256); if (current.current() && base.getSnapshot().selected === key) patch({ source }) }
    catch (failure) { if (current.current()) patch({ error: userFacingError(failure, '原文读取失败，请刷新来源清单') }) }
  }
  core.setStart(() => { void load() })
  return { ...base, load, select, history, source, filter(issuesOnly: boolean) { ticket('detail'); patch({ issuesOnly, items: [], next: null, selected: '', details: {}, source: undefined }); void load() } }
}

export function createCoverageOwner(id: string, ready: boolean) {
  const core = runOwner('source-coverage', id, { rows: [] as SourceTemplateCoverage[], next: null as string | null, loading: false, error: '', expanded: {} as Record<string, string[]> })
  const { base, patch, ticket } = core
  async function load(append = false) {
    if (!ready) return
    const current = ticket('list'), state = base.getSnapshot(); patch({ loading: true, error: '' })
    try { const page = await api.sourceCoverage(id, append ? state.next ?? '' : ''); if (current.current()) patch({ rows: append ? [...state.rows, ...page.items] : page.items, next: page.nextCursor ?? null }) }
    catch (failure) { if (current.current()) patch({ error: userFacingError(failure, '覆盖清单读取失败，请重试') }) }
    finally { if (current.current()) patch({ loading: false }) }
  }
  async function details(row: SourceTemplateCoverage) {
    const before = base.getSnapshot().expanded
    if (before[row.path]) { const next = { ...before }; delete next[row.path]; patch({ expanded: next }); ticket(`detail:${row.path}`); return }
    const current = ticket(`detail:${row.path}`)
    try {
      const result = await api.sourceCoverageItem(id, row.path), mapping: unknown = JSON.parse(result.resultJson || '{}')
      let text: string[] = []
      if (Array.isArray(mapping)) text = mapping.map(item => `${String(item.title || '已验证场景')} · ${Array.isArray(item.verifications) ? item.verifications.length : 0} 项正式验证`)
      else if (mapping && typeof mapping === 'object' && 'documents' in mapping && Array.isArray(mapping.documents)) text = mapping.documents.map(item => `文档章节：${String(item)}`)
      if (current.current()) patch({ expanded: { ...base.getSnapshot().expanded, [row.path]: text.length ? text : [row.exclusion || '尚未形成完成依据'] } })
    } catch (failure) { if (current.current()) patch({ error: userFacingError(failure, '处理依据读取失败，请重试') }) }
  }
  core.setStart(() => { void load() })
  return { ...base, load, details }
}
export type Artifact = DocumentReportSummary | SourceArtifact
export function resolveReportLink(name: string, href: string) {
  if (/^(?:[a-z][a-z0-9+.-]*:|\/\/|#)/i.test(href)) return undefined
  const parts = name.split('/').slice(0, -1)
  for (const segment of decodeURIComponent(href.split(/[?#]/)[0]!).split('/')) {
    if (segment === '..') { if (!parts.length) throw new Error('报告链接不能越出目录'); parts.pop() }
    else if (segment && segment !== '.') parts.push(segment)
  }
  return parts.join('/')
}
export function createArtifactsOwner(kind: 'document' | 'source', id: string) {
  const core = runOwner('template-artifacts', `${kind}:${id}`, { reports: [] as Artifact[], next: null as string | null, loading: false, downloading: false, error: '', current: undefined as (Artifact & { content: string }) | undefined })
  const { base, patch, ticket } = core
  async function load(append = false) {
    const current = ticket('list'), before = base.getSnapshot(); patch({ loading: true })
    try { const page = await (kind === 'source' ? api.sourceArtifacts : api.documentReports)(id, append ? before.next ?? '' : ''); if (current.current()) patch({ reports: append ? [...before.reports, ...page.items] : page.items, next: page.nextCursor ?? null, error: '' }) }
    catch (failure) { if (current.current()) patch({ error: userFacingError(failure, '报告目录读取失败，请重试') }) }
    finally { if (current.current()) patch({ loading: false }) }
  }
  async function preview(name: string) {
    const current = ticket('preview'); patch({ loading: true, error: '' })
    try { const value = await (kind === 'source' ? api.sourceArtifactByName : api.documentReportByName)(id, name); if (current.current()) patch({ current: value }) }
    catch (failure) { if (current.current()) patch({ error: userFacingError(failure, '报告正文读取失败，请重试') }) }
    finally { if (current.current()) patch({ loading: false }) }
  }
  function save(blob: Blob, filename: string) {
    if (!core.active()) return
    const url = URL.createObjectURL(blob), release = core.own(() => URL.revokeObjectURL(url)), link = document.createElement('a')
    link.href = url; link.download = filename; link.click(); core.delay(release, 0)
  }
  async function bundle() {
    if (base.getSnapshot().downloading) return
    const current = ticket('download'); patch({ downloading: true })
    try { const blob = await (kind === 'source' ? api.downloadSourceArtifact : api.downloadDocumentReport)(id); if (current.current()) save(blob, kind === 'source' ? '详细设计文档.zip' : '需求报告.zip') }
    catch (failure) { if (current.current()) patch({ error: userFacingError(failure, '报告下载失败，请重试') }) }
    finally { if (current.current()) patch({ downloading: false }) }
  }
  core.setStart(() => { void load() })
  return { ...base, load, preview, bundle, close() { ticket('preview'); patch({ current: undefined }) },
    downloadCurrent() { const current = base.getSnapshot().current; if (current) save(new Blob([current.content], { type: 'text/plain;charset=utf-8' }), current.name.split('/').pop()!) },
    linked(href: string) { const current = base.getSnapshot().current; if (!current) return; try { const path = resolveReportLink(current.name, href); if (path) void preview(path) } catch { patch({ error: '报告链接无效，请从目录选择报告' }) } },
  }
}
