import { flushPromises, mount } from '@/pages/w6-tests/workflow/react-test-root'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { WorkflowDocumentInput } from '@/pages/w6-tests/workflow/upload-panel'
import { createRequirementController } from '@/pages/w5/requirements/controller'
import { createUploadController } from '@/pages/w5/requirements/uploadController'
import { WorkflowNodeRun } from '@/pages/w6-tests/workflow/command-panels'
import { requirement, execution, attempt } from './workflowRunTestFixtures'
import { workflowRuns } from '@/api/workflowRuns'
import { workflowDocuments } from '@/api/workflowDocuments'
import type { WorkflowUpload } from '@/types/domain'
vi.mock('@/api/workflowDocuments', () => ({ workflowDocuments: { upload: vi.fn(), list: vi.fn(), get: vi.fn(), files: vi.fn(), text: vi.fn(), fileUrl: vi.fn(() => '/download') } }))
const source: WorkflowUpload = { id: 'saved', createdAt: '2026-09-29T00:00:00Z', ready: true, reference: { version: 1, type: 'UPLOADED_DOCUMENTS', uploadId: 'saved', sha256: 'hash' }, parserVersion: 'ASSIST_DOCUMENT_V2', resume: null, originals: [{ filename: '需求.md', path: 'original/01/需求.md', sizeBytes: 15, sha256: '991c3650e1572fe40780f0701e981ea91ec99125f0780b0133d5fcd197dc8dd4', representationSha256: 'parsed', format: 'md', sections: 2, limitations: ['图片文字未识别'] }] }
const component = () => mount(WorkflowDocumentInput, { props: { requirement: 'owner', version: 3, revision: 2, value: '', title: '需求文档' } })
const button = (wrapper: ReturnType<typeof component>, text: string) => wrapper.findAll('button').find(value => value.attributes('data-semantic') === ({'上传并选用':'workflow.uploadAndSelect','选择已上传资料':'workflow.selectUploaded','补传原文件':'workflow.resumeUpload','查看解析内容':'ui.open','文档 1 · 第 1 节':'ui.open','继续读取':'ui.loadMore'} as Record<string,string>)[text])!
async function choose(wrapper: ReturnType<typeof component>, files = [new File(['# 需求\n鉴权'], '需求.md')]) { const input = wrapper.get('input[type=file]'); Object.defineProperty(input.element, 'files', { configurable: true, value: files }); await input.trigger('change'); await vi.waitFor(async () => { await flushPromises(); expect(wrapper.owners<ReturnType<typeof createUploadController>>()[0]!.getSnapshot().hashing).toBe(false) }) }
beforeEach(() => { vi.clearAllMocks(); vi.mocked(workflowDocuments.get).mockResolvedValue(source); vi.mocked(workflowDocuments.list).mockResolvedValue({ items: [source], nextCursor: null }); vi.mocked(workflowDocuments.upload).mockResolvedValue(source) })
describe('固定需求原文', () => {
  it('上传使用当前版本并只在完整保存后选用', async () => {
    const wrapper = component(); await choose(wrapper); await button(wrapper, '上传并选用').trigger('click'); await flushPromises()
    expect(workflowDocuments.upload).toHaveBeenCalledWith('owner', expect.objectContaining({ expectedVersion: 3, expectedRevision: 2 }), expect.any(Array))
    expect(wrapper.emitted('change')?.[0]).toEqual([JSON.stringify(source.reference)]); expect(wrapper.text()).toContain('图片文字未识别')
    expect(wrapper.owners<ReturnType<typeof createUploadController>>()[0]!.getSnapshot().command.busy).toBe(false); expect(wrapper.owners<ReturnType<typeof createUploadController>>()[0]!.getSnapshot().command.accepted).toBe(true); wrapper.unmount()
  })
  it('响应丢失后重试保持同一上传身份及原文件', async () => {
    vi.mocked(workflowDocuments.upload).mockRejectedValueOnce(new Error('连接中断'))
    const wrapper = component(); await choose(wrapper); await button(wrapper, '上传并选用').trigger('click'); await flushPromises()
    expect(wrapper.emitted('change')).toBeUndefined(); expect(wrapper.get('[role=alert]').text()).toContain('连接中断')
    expect(wrapper.owners<ReturnType<typeof createUploadController>>()[0]!.canLeave().kind).toBe('BLOCK'); expect(wrapper.get('button[data-semantic="receipt.retryOriginal"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('button[data-semantic="receipt.retryOriginal"]').trigger('click'); await flushPromises()
    const calls = vi.mocked(workflowDocuments.upload).mock.calls; expect(calls[1]).toEqual(calls[0]); expect(wrapper.owners<ReturnType<typeof createUploadController>>()[0]!.canLeave().kind).toBe('ALLOW'); wrapper.unmount()
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
    await button(wrapper, '查看解析内容').trigger('click'); await flushPromises(); await wrapper.get('button[aria-label="查看：文档章节"]').trigger('click'); await flushPromises()
    await button(wrapper, '继续读取').trigger('click'); await flushPromises(); expect(workflowDocuments.text).toHaveBeenLastCalledWith('owner', source.id, 'parsed/01/0001.md', 4)
    expect(wrapper.text()).toContain('第一部分第二部分'); wrapper.unmount()
  })
  it('禁止空文档并保留未完成上传为待补传', async () => {
    const wrapper = component(); await choose(wrapper, [new File([], '空.md')]); await button(wrapper, '上传并选用').trigger('click'); await flushPromises(); expect(workflowDocuments.upload).not.toHaveBeenCalled()
    vi.mocked(workflowDocuments.upload).mockResolvedValue({ ...source, ready: false }); vi.mocked(workflowDocuments.get).mockResolvedValue({ ...source, ready: false }); await choose(wrapper); await button(wrapper, '上传并选用').trigger('click'); await flushPromises()
    expect(wrapper.emitted('change')).toBeUndefined(); expect(wrapper.text()).toContain('尚未完整核对'); wrapper.unmount()
  })
  it('人工节点的文档输出保留原编辑器，公共资料才提供上传入口', async () => {
    const node={...requirement().graph.nodes[0]!, outputs:[{name:'document',title:'文档',kind:'DOCUMENT' as const,required:true}]}
    vi.spyOn(workflowRuns,'attempts').mockResolvedValue({items:[attempt()]});vi.spyOn(workflowRuns,'attempt').mockResolvedValue(attempt());vi.spyOn(workflowRuns,'definition').mockResolvedValue(node)
    const wrapper=mount(WorkflowNodeRun,{props:{requirement:'req',node,version:3,summary:{id:'node',nodeKey:node.id,state:'WAITING_INPUT',attemptCount:1,latestAttemptId:'run',version:1,outcome:null}}});await flushPromises()
    expect(wrapper.find('input[type=file]').exists()).toBe(false);expect(wrapper.findAll('textarea')).toHaveLength(2);expect(wrapper.get('form').text()).toContain('文档');wrapper.unmount()
  })
  it('聚合每份公共文档的未确认状态，单项完成不释放其他上传的保护', async () => {
    const parent=createRequirementController('owner'),base=requirement(),snapshot=execution();base.id='owner';snapshot.execution.id='owner';snapshot.control.id='owner';parent.patch({base,execution:snapshot,readable:true});parent.attachView();parent.setStart(()=>{})
    const first=createUploadController('owner',{version:3,revision:2,value:''},()=>{}),second=createUploadController('owner',{version:3,revision:2,value:''},()=>{});first.attachView();second.attachView();await first.chooseFiles([new File(['# 需求\n鉴权'],'需求.md')]);await second.chooseFiles([new File(['# 需求\n鉴权'],'需求.md')]);vi.mocked(workflowDocuments.upload).mockRejectedValue(new Error('连接中断'));await first.upload();await second.upload();parent.registerChild(first);parent.registerChild(second)
    expect(parent.canLeave().kind).toBe('BLOCK');expect(parent.canStartWrite()).toBe(false);vi.mocked(workflowDocuments.upload).mockResolvedValue(source);await first.recover();expect(first.canLeave().kind).toBe('ALLOW');expect(second.canLeave().kind).toBe('BLOCK');expect(parent.canLeave().kind).toBe('BLOCK');await second.recover();expect(second.canLeave().kind).toBe('ALLOW');expect(parent.canLeave().kind).toBe('ALLOW');first.retire(true);second.retire(true);parent.retire(true)
  })
})
