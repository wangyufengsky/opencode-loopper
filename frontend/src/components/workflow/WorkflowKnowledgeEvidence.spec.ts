import { flushPromises, mount } from '@/pages/w6-tests/workflow/react-test-root'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { WorkflowKnowledgeEvidence } from '@/pages/w6-tests/workflow/read-panels'
import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowKnowledgeBody, WorkflowKnowledgeEntry } from '@/types/domain'
vi.mock('@/api/workflowRuns', () => ({ workflowRuns: { knowledgeEvidence: vi.fn(), knowledgeEvidenceBody: vi.fn() } }))
afterEach(() => vi.resetAllMocks())
const props = { requirement: 'req', node: 'node', attempt: 'attempt' }
const entry = (id: string): WorkflowKnowledgeEntry => ({ id, toolName: 'read_knowledge_source', createdAt: '2026-09-29T02:00:00Z' })
const body = (id: string): WorkflowKnowledgeBody => ({ ...entry(id), content: { kind: 'DOCUMENT', text: `固定原文 ${id}` } })
describe('节点知识证据', () => {
  it('先读元数据，点击才读正文；分页失败保留内容并重试原游标', async () => {
    vi.mocked(workflowRuns.knowledgeEvidence).mockResolvedValueOnce({ items: [entry('one')], nextCursor: 'next' })
      .mockRejectedValueOnce(new Error('failed')).mockResolvedValueOnce({ items: [entry('two')], nextCursor: null })
    vi.mocked(workflowRuns.knowledgeEvidenceBody).mockResolvedValue(body('one'))
    const view = mount(WorkflowKnowledgeEvidence, { props }); await flushPromises()
    expect(workflowRuns.knowledgeEvidenceBody).not.toHaveBeenCalled(); expect(view.text()).not.toContain('one')
    await view.get('li button').trigger('click'); await flushPromises()
    expect(workflowRuns.knowledgeEvidenceBody).toHaveBeenCalledWith('req', 'node', 'attempt', 'one')
    expect(view.get('.knowledge-evidence .w3-code-text').text()).toContain('固定原文 one')
    await view.get('li button').trigger('click'); await flushPromises(); expect(workflowRuns.knowledgeEvidenceBody).toHaveBeenCalledTimes(1)
    await view.findAll('button').find(button => button.attributes('data-semantic') === 'ui.loadMore')!.trigger('click'); await flushPromises()
    expect(view.findAll('li')).toHaveLength(1); expect(view.get('.knowledge-evidence .w3-code-text').text()).toContain('固定原文 one')
    await view.get('[role="alert"] button').trigger('click'); await flushPromises()
    expect(workflowRuns.knowledgeEvidence).toHaveBeenLastCalledWith('req', 'node', 'attempt', 'next')
    expect(view.findAll('li')).toHaveLength(2); view.unmount()
  })
  it('正文失败可重试，快速切换选择时旧正文不能覆盖新选择', async () => {
    vi.mocked(workflowRuns.knowledgeEvidence).mockResolvedValue({ items: [entry('one'), entry('two')], nextCursor: null })
    let resolve!: (result: WorkflowKnowledgeBody) => void
    vi.mocked(workflowRuns.knowledgeEvidenceBody).mockReturnValueOnce(new Promise(done => { resolve = done }))
      .mockRejectedValueOnce(new Error('failed')).mockResolvedValueOnce(body('two'))
    const view = mount(WorkflowKnowledgeEvidence, { props }); await flushPromises()
    await view.findAll('li button')[0]!.trigger('click'); await view.findAll('li button')[1]!.trigger('click'); await flushPromises()
    expect(view.get('[role="alert"]').text()).toContain('重试')
    await view.get('[role="alert"] button').trigger('click'); await flushPromises()
    resolve(body('one')); await flushPromises(); expect(view.get('.knowledge-evidence .w3-code-text').text()).toContain('固定原文 two')
    expect(view.get('.knowledge-evidence .w3-code-text').text()).not.toContain('one'); view.unmount()
  })
  it('切换尝试卸载后旧请求不能污染新尝试，空列表可刷新', async () => {
    vi.mocked(workflowRuns.knowledgeEvidence).mockResolvedValueOnce({ items: [entry('old')], nextCursor: null })
      .mockResolvedValueOnce({ items: [], nextCursor: null }).mockResolvedValueOnce({ items: [entry('new')], nextCursor: null })
    let resolve!: (result: WorkflowKnowledgeBody) => void
    vi.mocked(workflowRuns.knowledgeEvidenceBody).mockReturnValueOnce(new Promise(done => { resolve = done }))
    const old = mount(WorkflowKnowledgeEvidence, { props }); await flushPromises()
    await old.get('li button').trigger('click'); old.unmount()
    const current = mount(WorkflowKnowledgeEvidence, { props: { ...props, attempt: 'next' } }); await flushPromises()
    expect(current.text()).toContain('尚无保存的检索证据'); resolve(body('old')); await flushPromises()
    expect(current.find('.knowledge-evidence').exists()).toBe(false)
    await current.get('button').trigger('click'); await flushPromises()
    expect(workflowRuns.knowledgeEvidence).toHaveBeenLastCalledWith('req', 'node', 'next', ''); expect(current.findAll('li')).toHaveLength(1)
    current.unmount()
  })
  it('正文缓存只保留最近三份，长列表不会累积全部大正文', async () => {
    vi.mocked(workflowRuns.knowledgeEvidence).mockResolvedValue({ items: ['a', 'b', 'c', 'd'].map(entry), nextCursor: null })
    vi.mocked(workflowRuns.knowledgeEvidenceBody).mockImplementation(async (_r, _n, _a, id) => body(id))
    const view = mount(WorkflowKnowledgeEvidence, { props }); await flushPromises()
    for (const button of view.findAll('li button')) { await button.trigger('click'); await flushPromises() }
    await view.findAll('li button')[3]!.trigger('click'); await flushPromises(); expect(workflowRuns.knowledgeEvidenceBody).toHaveBeenCalledTimes(4)
    await view.findAll('li button')[0]!.trigger('click'); await flushPromises(); expect(workflowRuns.knowledgeEvidenceBody).toHaveBeenCalledTimes(5)
    view.unmount()
  })
})
