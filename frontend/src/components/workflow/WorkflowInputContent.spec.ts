import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowInputPage, WorkflowInputs } from '@/types/domain'
import WorkflowInputContent from './WorkflowInputContent.vue'
vi.mock('@/api/workflowRuns', () => ({ workflowRuns: { inputContent: vi.fn() } }))
const api = vi.mocked(workflowRuns), input = (): WorkflowInputs['values'][number] => ({ name: 'draft', kind: 'TEXT', source: 'NODE', sourceId: 'producer', outputName: 'result', attemptId: 'parent', sha256: 'fixed-hash', content: null, reference: { version: 1, contentSha256: 'body-hash', sizeBytes: 20 } })
const page = (changes: Partial<WorkflowInputPage> = {}): WorkflowInputPage => ({ name: 'draft', kind: 'TEXT', sha256: 'fixed-hash', text: '第一段', offset: 0, nextOffset: 3, totalLength: 6, ...changes })
let wrapper: VueWrapper | undefined
beforeEach(() => vi.resetAllMocks())
afterEach(() => { wrapper?.unmount(); wrapper = undefined })
function render(value = input()) { wrapper = mount(WorkflowInputContent, { props: { requirement: 'req', node: 'review', attempt: 'run', input: value }, global: { stubs: { CodeMergeEditor: true, MarkdownDocument: { props: ['content'], template: '<article>{{ content }}</article>' } } } }); return wrapper }
const button = (name: string) => wrapper!.findAll('button').find(value => value.text() === name)!
describe('fixed input body', () => {
  it('does not download until requested and follows the server cursor without skipping content', async () => {
    api.inputContent.mockResolvedValueOnce(page()).mockResolvedValueOnce(page({ text: '第二段', offset: 3, nextOffset: null }))
    render(); expect(api.inputContent).not.toHaveBeenCalled()
    await button('查看固定版本正文').trigger('click'); await flushPromises()
    expect(wrapper!.text()).toContain('正文尚未读完'); expect(wrapper!.text()).toContain('第一段'); expect(wrapper!.find('article').exists()).toBe(false)
    await button('继续读取正文').trigger('click'); await flushPromises()
    expect(api.inputContent.mock.calls.map(value => value.slice(0, 5))).toEqual([['req', 'review', 'run', 'draft', 0], ['req', 'review', 'run', 'draft', 3]])
    expect(wrapper!.get('article').text()).toBe('第一段第二段'); expect(button('继续读取正文')).toBeUndefined()
    expect(wrapper!.text()).not.toContain('fixed-hash'); expect(wrapper!.text()).not.toContain('producer')
  })
  it('retries a failed page at the same cursor and retains previously read text', async () => {
    api.inputContent.mockResolvedValueOnce(page()).mockRejectedValueOnce(new Error('暂时断线')).mockResolvedValueOnce(page({ text: '第二段', offset: 3, nextOffset: null }))
    render(); await button('查看固定版本正文').trigger('click'); await flushPromises(); await button('继续读取正文').trigger('click'); await flushPromises()
    expect(wrapper!.text()).toContain('第一段'); expect(wrapper!.get('[role="alert"]').text()).toContain('暂时断线')
    await button('重试读取').trigger('click'); await flushPromises(); expect(api.inputContent.mock.calls[2]![4]).toBe(3)
    expect(wrapper!.get('article').text()).toBe('第一段第二段')
  })
  it.each([{ sha256: 'other-version' }, { name: 'other' }, { offset: 1 }, { nextOffset: 0 }, { totalLength: 1 }])('rejects a mismatched or non-progressing page %j', async changes => {
    api.inputContent.mockResolvedValue(page(changes)); render(); await button('查看固定版本正文').trigger('click'); await flushPromises()
    expect(wrapper!.text()).toContain('与固定输入不一致'); expect(wrapper!.find('pre').exists()).toBe(false)
  })
  it('aborts a stale request and keeps late content out of a different attempt', async () => {
    let resolve!: (value: WorkflowInputPage) => void
    api.inputContent.mockImplementationOnce(() => new Promise(r => { resolve = r })); render()
    await button('查看固定版本正文').trigger('click'); const signal = api.inputContent.mock.calls[0]![5]!
    await wrapper!.setProps({ attempt: 'other-run' }); expect(signal.aborted).toBe(true)
    resolve(page()); await flushPromises(); expect(wrapper!.text()).not.toContain('第一段'); expect(button('查看固定版本正文')).toBeDefined()
    api.inputContent.mockResolvedValue(page({ text: '新尝试', totalLength: 3, nextOffset: null }))
    await button('查看固定版本正文').trigger('click'); await flushPromises(); expect(wrapper!.get('article').text()).toBe('新尝试')
    expect(api.inputContent.mock.calls[1]![2]).toBe('other-run')
  })
  it('shows structured content only after all pages arrive and never inserts raw HTML', async () => {
    const text = '{"note":"<img src=x onerror=alert(1)>"}'
    api.inputContent.mockResolvedValue(page({ kind: 'JSON', text, totalLength: text.length, nextOffset: null }))
    render({ ...input(), kind: 'JSON' }); await button('查看固定版本正文').trigger('click'); await flushPromises()
    expect(wrapper!.find('img').exists()).toBe(false); expect(wrapper!.getComponent({ name: 'CodeMergeEditor' }).props('modelValue')).toContain('<img')
  })
})
