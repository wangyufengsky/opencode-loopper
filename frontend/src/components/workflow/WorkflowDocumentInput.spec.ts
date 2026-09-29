import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WorkflowDocumentInput from './WorkflowDocumentInput.vue'
import WorkflowValueFields from './WorkflowValueFields.vue'
import { workflowDocuments } from '@/api/workflowDocuments'
import type { WorkflowUpload } from '@/types/domain'
vi.mock('@/api/workflowDocuments', () => ({ workflowDocuments: { upload: vi.fn(), list: vi.fn(), get: vi.fn(), files: vi.fn(), text: vi.fn(), fileUrl: vi.fn(() => '/download') } }))
const source: WorkflowUpload = { id: 'saved', createdAt: '2026-09-29T00:00:00Z', ready: true, reference: { version: 1, type: 'UPLOADED_DOCUMENTS', uploadId: 'saved', sha256: 'hash' }, parserVersion: 'ASSIST_DOCUMENT_V2', resume: null, originals: [{ filename: '需求.md', path: 'original/01/需求.md', sizeBytes: 30, sha256: 'raw', representationSha256: 'parsed', format: 'md', sections: 2, limitations: ['图片文字未识别'] }] }
const component = () => mount(WorkflowDocumentInput, { props: { requirement: 'owner', version: 3, revision: 2, value: '', title: '需求文档' }, global: { stubs: { MarkdownDocument: { props: ['content'], template: '<div>{{ content }}</div>' } } } })
const button = (wrapper: ReturnType<typeof component>, text: string) => wrapper.findAll('button').find(value => value.text() === text)!
async function choose(wrapper: ReturnType<typeof component>, files = [new File(['# 需求\n鉴权'], '需求.md')]) { const input = wrapper.get('input[type=file]'); Object.defineProperty(input.element, 'files', { configurable: true, value: files }); await input.trigger('change') }
beforeEach(() => { vi.clearAllMocks(); vi.mocked(workflowDocuments.get).mockResolvedValue(source); vi.mocked(workflowDocuments.list).mockResolvedValue({ items: [source], nextCursor: null }); vi.mocked(workflowDocuments.upload).mockResolvedValue(source) })
describe('固定需求原文', () => {
  it('上传使用当前版本并只在完整保存后选用', async () => {
    const wrapper = component(); await choose(wrapper); await button(wrapper, '上传并选用').trigger('click'); await flushPromises()
    expect(workflowDocuments.upload).toHaveBeenCalledWith('owner', expect.objectContaining({ expectedVersion: 3, expectedRevision: 2 }), expect.any(Array))
    expect(wrapper.emitted('change')?.[0]).toEqual([JSON.stringify(source.reference)]); expect(wrapper.text()).toContain('图片文字未识别')
    expect(wrapper.emitted('busy')).toEqual([[true], [false]]); wrapper.unmount()
  })
  it('响应丢失后重试保持同一上传身份及原文件', async () => {
    vi.mocked(workflowDocuments.upload).mockRejectedValueOnce(new Error('连接中断'))
    const wrapper = component(); await choose(wrapper); await button(wrapper, '上传并选用').trigger('click'); await flushPromises()
    expect(wrapper.emitted('change')).toBeUndefined(); expect(wrapper.get('[role=alert]').text()).toContain('连接中断')
    await button(wrapper, '上传并选用').trigger('click'); await flushPromises()
    const calls = vi.mocked(workflowDocuments.upload).mock.calls; expect(calls[1]).toEqual(calls[0]); wrapper.unmount()
  })
  it('历史资料由用户选择，未完成上传可用原身份补传', async () => {
    const pending = { ...source, id: 'pending', ready: false, resume: { requestKey: 'original-request', expectedVersion: 1, expectedRevision: 2 } }
    vi.mocked(workflowDocuments.list).mockResolvedValue({ items: [source, pending], nextCursor: null })
    const wrapper = component(); await button(wrapper, '选择已上传资料').trigger('click'); await flushPromises(); expect(wrapper.emitted('change')).toBeUndefined()
    await button(wrapper, '补传原文件').trigger('click'); await choose(wrapper); await button(wrapper, '上传并选用').trigger('click'); await flushPromises()
    expect(workflowDocuments.upload).toHaveBeenCalledWith('owner', pending.resume, expect.any(Array)); wrapper.unmount()
  })
  it('刷新后读取固定选择并按需预览章节', async () => {
    const wrapper = component(); await wrapper.setProps({ value: JSON.stringify(source.reference) }); await flushPromises()
    vi.mocked(workflowDocuments.files).mockResolvedValue({ items: [{ path: 'parsed/01/0001.md', sizeBytes: 24, sha256: 'sha', mode: null }], nextCursor: null })
    vi.mocked(workflowDocuments.text).mockResolvedValueOnce({ text: '第一部分', nextOffset: 4 }).mockResolvedValueOnce({ text: '第二部分', nextOffset: null })
    await button(wrapper, '查看解析内容').trigger('click'); await flushPromises(); await button(wrapper, '文档 1 · 第 1 节').trigger('click'); await flushPromises()
    await button(wrapper, '继续读取').trigger('click'); await flushPromises(); expect(workflowDocuments.text).toHaveBeenLastCalledWith('owner', source.id, 'parsed/01/0001.md', 4)
    expect(wrapper.text()).toContain('第一部分第二部分'); wrapper.unmount()
  })
  it('禁止空文档并保留未完成上传为待补传', async () => {
    const wrapper = component(); await choose(wrapper, [new File([], '空.md')]); await button(wrapper, '上传并选用').trigger('click'); await flushPromises(); expect(workflowDocuments.upload).not.toHaveBeenCalled()
    vi.mocked(workflowDocuments.upload).mockResolvedValue({ ...source, ready: false }); await choose(wrapper); await button(wrapper, '上传并选用').trigger('click'); await flushPromises()
    expect(wrapper.emitted('change')).toBeUndefined(); expect(wrapper.text()).toContain('尚未完整保存'); wrapper.unmount()
  })
  it('人工节点的文档输出保留原编辑器，公共资料才提供上传入口', () => {
    const fields = [{ name: 'document', title: '文档', kind: 'DOCUMENT' as const, required: true }]
    const wrapper = mount(WorkflowValueFields, { props: { fields, values: {} }, global: { stubs: { CodeMergeEditor: true } } })
    expect(wrapper.findComponent(WorkflowDocumentInput).exists()).toBe(false); expect(wrapper.find('code-merge-editor-stub').exists()).toBe(true); wrapper.unmount()
  })
})
