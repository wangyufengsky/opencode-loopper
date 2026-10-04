import { mount, flushPromises } from '@/pages/w6-tests/workflow/react-test-root'
import { describe, expect, it, vi } from 'vitest'
import { WorkflowKnowledgeReport as WorkflowKnowledgeReport } from '@/pages/w5/workflow/reports'
import { WorkflowInputContent } from '@/pages/w6-tests/workflow/read-panels'
import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowInputs } from '@/types/domain'
import { knowledgeBody } from './knowledgeBundle'
vi.mock('@/api/workflowRuns', () => ({ workflowRuns: { inputContent: vi.fn() } }))
const content = () => ({ version: 1, type: 'KNOWLEDGE_EVIDENCE', entries: [{ reference: 'call:private-receipt', toolName: 'read_knowledge_source', createdAt: '2026-09-29T02:00:00Z', sha256: 'private-content-hash', content: { kind: 'DOCUMENT', name: '业务规则.md', text: '采集时的原始规则' } }], limitations: ['尚未核对历史版本'] })
describe('正式知识证据交付', () => {
  it('保留局限，点击资料再展开原文，不展示私有调用身份', async () => {
    const view = mount(WorkflowKnowledgeReport, { props: { content: content() } })
    expect(view.text()).toContain('尚未核对历史版本'); expect(view.text()).toContain('不能代替完整原文')
    expect(view.text()).not.toContain('private-'); expect(view.find('.knowledge-evidence').exists()).toBe(false)
    await view.get('button').trigger('click'); expect(view.get('.knowledge-evidence .w3-code-text').text()).toBe('采集时的原始规则')
    await view.setProps({ content: { ...content(), entries: [] } }); expect(view.find('.knowledge-evidence').exists()).toBe(false)
    expect(view.text()).toContain('没有交付来源证据'); expect(view.text()).toContain('尚未核对历史版本'); view.unmount()
  })
  it('拒绝损坏结构并把不可信名称和局限作为文本', () => {
    const report = content(); report.entries[0]!.content.name = '<img src=x onerror=alert(1)>'
    report.limitations = ['<script>alert(1)</script>']
    const view = mount(WorkflowKnowledgeReport, { props: { content: report } })
    expect(view.find('img').exists()).toBe(false); expect(view.find('script').exists()).toBe(false); view.unmount()
    const invalid = mount(WorkflowKnowledgeReport, { props: { content: { ...content(), entries: [null] } } })
    expect(invalid.text()).toContain('格式不完整'); invalid.unmount()
    const malformed = knowledgeBody({ kind: 'DOCUMENT', text: { bad: 'value' }, limitations: 'invalid' }, '保存结果')
    expect(malformed.kind).toBe('METADATA'); expect(malformed.text).toBeUndefined(); expect(malformed.limitations).toBeUndefined()
  })
  it('后继按固定输入分页读完整证据后再展示，保持输入身份和懒加载', async () => {
    const text = JSON.stringify(content()), split = 45
    const input: WorkflowInputs['values'][number] = { name: 'evidence', kind: 'JSON', source: 'NODE', sourceId: 'research', outputName: 'evidence', attemptId: 'producer', sha256: 'fixed', content: null, reference: { version: 1, contentSha256: 'hash', sizeBytes: text.length } }
    vi.mocked(workflowRuns.inputContent).mockResolvedValueOnce({ name: 'evidence', kind: 'JSON', sha256: 'fixed', text: text.slice(0, split), offset: 0, nextOffset: split, totalLength: text.length })
      .mockResolvedValueOnce({ name: 'evidence', kind: 'JSON', sha256: 'fixed', text: text.slice(split), offset: split, nextOffset: null, totalLength: text.length })
    const view = mount(WorkflowInputContent, { props: { requirement: 'req', node: 'summary', attempt: 'next', input } })
    expect(workflowRuns.inputContent).not.toHaveBeenCalled(); await view.get('button').trigger('click'); await flushPromises()
    expect(view.find('[aria-label="交付的来源证据"]').exists()).toBe(false)
    await view.get('button').trigger('click'); await flushPromises()
    expect(view.text()).toContain('业务规则.md'); expect(view.text()).not.toContain('private-')
    await view.get('button').trigger('click'); expect(view.get('.knowledge-evidence .w3-code-text').text()).toContain('采集时的原始规则'); view.unmount()
  })
})
