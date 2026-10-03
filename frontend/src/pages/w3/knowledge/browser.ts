import { knowledgeApi } from '@/api/knowledge'
import { createSnapshotController } from '@/foundation/contracts/controller'
import { userFacingError } from '@/utils/displayLabels'
import type { KnowledgeContent, KnowledgeListing, KnowledgeSearch, KnowledgeSource } from '@/types/domain'

export interface BrowserState { project: string; conversationId?: string; sources: KnowledgeSource[]; selected: string[]; query: string; mode: string; searchSource: string;
  busy: boolean; error: string; active?: KnowledgeSource; path: string; listing?: KnowledgeListing; results?: KnowledgeSearch; content?: KnowledgeContent;
  schema: string; schemas: string[]; table: string; author: string; gitQuery: string; commits?: KnowledgeContent; commit?: KnowledgeContent }
let epoch = 0
export function createKnowledgeBrowser() {
  const owner = createSnapshotController<BrowserState>({ identity: { domain: 'knowledge-browser', id: 'sources', epoch: ++epoch }, initial: {
    project: '', sources: [], selected: [], query: '', mode: 'AUTO', searchSource: '', busy: false, error: '', path: '', schema: '', schemas: [], table: '', author: '', gitQuery: '' }, canLeave: () => ({ kind: 'ALLOW' }) })
  const state = owner.getSnapshot, set = (patch: Partial<BrowserState>) => owner.project({ ...state(), ...patch })
  let generation = 0, searchGeneration = 0
  const searchable = () => state().sources.filter(s => s.state === 'READY' && (state().conversationId || state().selected.includes(s.id)))
  function configure(project: string, conversationId: string | undefined, sources: KnowledgeSource[], selected: string[]) {
    if (project !== state().project || conversationId !== state().conversationId) { generation++; searchGeneration++; set({ active: undefined, listing: undefined, results: undefined, content: undefined, commits: undefined, commit: undefined, busy: false, error: '' }) }
    if (selected.join(',') !== state().selected.join(',')) { searchGeneration++; set({ results: undefined }) }
    set({ project, conversationId, sources, selected })
    if (state().searchSource && !searchable().some(s => s.id === state().searchSource)) set({ searchSource: '' })
  }
  async function read(action: (apply: (patch: Partial<BrowserState>) => void) => Promise<void>) {
    if (state().busy || !owner.capture().isCurrent()) return
    const token = owner.capture(), ticket = generation; set({ busy: true, error: '' })
    const apply = (patch: Partial<BrowserState>) => { if (token.isCurrent() && ticket === generation) set(patch) }
    try { await action(apply) } catch (failure) { apply({ error: userFacingError(failure, '资料读取失败，请刷新重试') }) }
    finally { apply({ busy: false }) }
  }
  function filter(field: 'query' | 'mode' | 'searchSource', value: string) { searchGeneration++; set({ [field]: value, results: undefined }) }
  async function search(cursor = '') {
    if (!state().query.trim()) return
    const ticket = searchGeneration, s = state(), ids = s.searchSource ? [s.searchSource] : searchable().map(source => source.id)
    await read(async apply => {
      if (!ids.length) throw new Error('请先勾选要检索的资料来源')
      const page = await knowledgeApi.searchProject(s.project, { conversationId: s.conversationId, sourceIds: ids.join(','), query: s.query.trim(), mode: s.mode, cursor, limit: 20 })
      if (ticket !== searchGeneration) return
      apply({ results: cursor && state().results ? { ...page, matches: [...state().results!.matches, ...page.matches] } : page, content: undefined, listing: undefined })
    })
  }
  async function database(source: KnowledgeSource, schema = state().schema, offset = 0, kind?: 'columns' | 'tables') {
    const s = state()
    await read(async apply => {
      const body = await knowledgeApi.database(s.project, source.id, { conversationId: s.conversationId, schema, table: s.table || undefined, kind: kind ?? (s.table ? 'columns' : 'tables'), offset })
      apply({ active: source, listing: undefined, results: undefined, content: body, schemas: Array.isArray(body.schemas) ? body.schemas.filter((value): value is string => typeof value === 'string') : s.schemas })
    })
  }
  async function browse(source: KnowledgeSource, path = '', cursor = '') {
    if (source.kind === 'DATABASE') { await database(source, ''); return }
    if (source.kind === 'GIT') { set({ active: source, listing: undefined, results: undefined, content: undefined }); await git(); return }
    const s = state()
    await read(async apply => { const page = await knowledgeApi.browse(s.project, source.id, { conversationId: s.conversationId, path, cursor }); apply({ active: source, path, listing: cursor && s.listing ? { ...page, items: [...s.listing.items, ...page.items] } : page, results: undefined, content: undefined }) })
  }
  async function file(path: string, section = -1, startLine = 1, expectedSha?: string, offset = 0, textOffset?: number) {
    const s = state(); if (!s.active) return
    await read(async apply => { const body = await knowledgeApi.read(s.project, s.active!.id, { conversationId: s.conversationId, path, section, startLine, expectedSha, offset, textOffset }); apply({ content: body }) })
  }
  async function match(value: KnowledgeSearch['matches'][number]) {
    const s = state(), source = s.sources.find(source => source.id === value.sourceId); if (source) set({ active: source })
    if (value.kind === 'DATABASE' && source && value.schema && value.table) {
      set({ schema: value.schema, table: value.table, schemas: [value.schema] })
      await database(source, value.schema, typeof value.read?.arguments.offset === 'number' ? value.read.arguments.offset : 0, value.column ? 'columns' : 'tables'); return
    }
    await file(value.path, value.section ?? -1, value.startLine ?? 1, value.sha256, 0, typeof value.read?.arguments.textOffset === 'number' ? value.read.arguments.textOffset : undefined)
  }
  async function git(tool = 'search_knowledge_git_commits', sha?: string, cursor?: string, line?: number) {
    const s = state(); if (!s.active) return
    await read(async apply => {
      const body = await knowledgeApi.git(s.project, s.active!.id, { conversationId: s.conversationId, tool, author: tool === 'search_knowledge_git_commits' ? s.author : undefined, query: tool === 'search_knowledge_git_commits' ? s.gitQuery : undefined, commit: sha, cursor, startLine: line })
      apply(sha ? { commit: body } : { commits: cursor && s.commits ? { ...body, items: [...(s.commits.items as unknown[] || []), ...(body.items as unknown[] || [])] } : body, commit: undefined })
    })
  }
  return { ...owner, configure, searchable, filter, search, database, browse, file, match, git,
    setField(field: 'schema' | 'table' | 'author' | 'gitQuery', value: string) { set({ [field]: value }) },
    back() { generation++; set({ active: undefined, results: undefined, content: undefined, listing: undefined, commits: undefined, commit: undefined, busy: false }) },
    backResults() { set({ results: undefined }) }, backCommits() { set({ commit: undefined }) },
    pauseReads() { generation++; searchGeneration++; set({ busy: false }) },
  }
}
