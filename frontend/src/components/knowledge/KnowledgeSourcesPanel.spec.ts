import { flushPromises, mount, useTestPage } from '@/pages/w6-tests/knowledge-ppt-template/react-test-root'
import { createElement, useLayoutEffect } from 'react'
import { createKnowledgeController } from '@/pages/w3/knowledge/controller'
import { KnowledgeSourcesPanel as Sources } from '@/pages/w3/knowledge/SourcesPanel'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { knowledgeApi as api } from '@/api/knowledge'
import type { KnowledgeSearch, KnowledgeSource } from '@/types/domain'
let owner: ReturnType<typeof createKnowledgeController>
function KnowledgeSourcesPanel({project}:{project:string}) { const props=useTestPage(); useLayoutEffect(()=>{ if(owner.getSnapshot().project !== project) void owner.changeProject(project) },[project]); return createElement(Sources,{owner,props,open:true}) }

vi.mock('@/api/knowledge', () => ({ knowledgeApi: { sources: vi.fn(), searchProject: vi.fn(), read: vi.fn(), database: vi.fn() } }))
const sources: KnowledgeSource[] = [
  { id: 'code', kind: 'CODE', name: '项目代码', state: 'READY', detail: '', version: 0 },
  { id: 'database:db', kind: 'DATABASE', name: '业务库', state: 'READY', detail: '', version: 2 },
]
const page = (nextCursor: string | null = null): KnowledgeSearch => ({ matches: [{ sourceId: 'code', name: 'Customer.java', path: 'Customer.java', kind: 'CODE', startLine: 7, sha256: 'abc', snippet: 'customer_id', matchType: 'FIELD' }], nextCursor, incomplete: !!nextCursor, limitations: [], detail: '', coverage: [{ sourceId: 'code', name: '项目代码', kind: 'CODE', state: nextCursor ? 'PARTIAL' : 'COMPLETE', examined: 50, matched: 1, limited: false, limitations: [] }] })
let wrapper: ReturnType<typeof mount>
beforeEach(async () => {
  vi.clearAllMocks()
  vi.mocked(api.sources).mockResolvedValue({items:sources,facets:{}})
  owner=createKnowledgeController({storage:null}); await owner.changeProject('p')
  wrapper = mount(KnowledgeSourcesPanel, { props: { project: 'p' }, global: { stubs: { Icon: true, KnowledgeEvidence: true, KnowledgeGitBrowser: true } } })
})
afterEach(() => {wrapper.unmount();owner.retire(true)})
async function search() { await wrapper.get('[aria-label="来源搜索"]').setValue('customerId'); await wrapper.get('form.knowledge-search').trigger('submit'); await flushPromises() }

it('uses the unified endpoint for selected files and databases and preserves filters on continuation', async () => {
  vi.mocked(api.searchProject).mockResolvedValueOnce(page('next')).mockResolvedValueOnce(page())
  await wrapper.get('[aria-label="检索方式"]').setValue('FIELD'); await search()
  expect(api.searchProject).toHaveBeenLastCalledWith('p', expect.objectContaining({ sourceIds: 'code,database:db', mode: 'FIELD', query: 'customerId', cursor: '' }))
  expect(wrapper.text()).toContain('待继续检索')
  await wrapper.findAll('button').find(b => b.attributes('data-semantic') === 'ui.loadMore')!.trigger('click'); await flushPromises()
  expect(api.searchProject).toHaveBeenLastCalledWith('p', expect.objectContaining({ sourceIds: 'code,database:db', mode: 'FIELD', cursor: 'next' }))
  expect(wrapper.findAll('.knowledge-search-match')).toHaveLength(2)
  await wrapper.get('[aria-label="来源搜索"]').setValue('other'); expect(wrapper.find('[aria-label="检索结果"]').exists()).toBe(false)
})
it('reads a file hit using its returned version and original line', async () => {
  vi.mocked(api.searchProject).mockResolvedValue(page()); await search()
  await wrapper.get('.knowledge-search-match').trigger('click'); await flushPromises()
  expect(api.read).toHaveBeenCalledWith('p', 'code', expect.objectContaining({ path: 'Customer.java', startLine: 7, expectedSha: 'abc' }))
})
it('preserves the document text offset when opening a cross-section match', async () => {
  const result = page(); result.matches = [{ sourceId: 'code', kind: 'DOCUMENT', name: 'guide.md', path: 'guide.md', section: 0, sha256: 'original', snippet: 'customerId', read: { tool: 'read_knowledge_source', arguments: { section: 0, textOffset: 11930 } } }]
  vi.mocked(api.searchProject).mockResolvedValue(result)
  await search(); await wrapper.get('.knowledge-search-match').trigger('click'); await flushPromises()
  expect(api.read).toHaveBeenCalledWith('p', 'code', expect.objectContaining({ section: 0, textOffset: 11930, expectedSha: 'original' }))
})
it('opens database metadata hits without executing a business-data query', async () => {
  const result = page(); result.matches = [{ sourceId: 'database:db', kind: 'DATABASE', name: 'customer_id', path: 'app.customer.customer_id', schema: 'app', table: 'customer', column: 'customer_id', sha256: 'meta', snippet: '客户编号', read: { tool: 'inspect_database_schema', arguments: { offset: 100 } } }]
  vi.mocked(api.searchProject).mockResolvedValue(result)
  vi.mocked(api.database).mockResolvedValue({ kind: 'DATABASE', sourceId: 'database:db', path: '', name: '业务库', sha256: '', nextOffset: -1 })
  await search(); await wrapper.get('.knowledge-search-match').trigger('click'); await flushPromises()
  expect(api.database).toHaveBeenCalledWith('p', 'database:db', expect.objectContaining({ schema: 'app', table: 'customer', kind: 'columns', offset: 100 }))
  expect(api.read).not.toHaveBeenCalled(); expect(wrapper.text()).not.toContain('下一页结构')
})
it('discards late search pages after editing query or changing project', async () => {
  let resolve!: (value: KnowledgeSearch) => void
  vi.mocked(api.searchProject).mockReturnValue(new Promise(r => { resolve = r }))
  await search(); await wrapper.get('[aria-label="来源搜索"]').setValue('另一问题')
  resolve(page()); await flushPromises(); expect(wrapper.find('[aria-label="检索结果"]').exists()).toBe(false)
  vi.mocked(api.searchProject).mockReturnValue(new Promise(r => { resolve = r }))
  await search(); await wrapper.setProps({ project: 'other' }); resolve(page()); await flushPromises()
  expect(wrapper.find('[aria-label="检索结果"]').exists()).toBe(false)
})
