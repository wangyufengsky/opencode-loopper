import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { DocumentTemplateOverview } from '@/types/domain'
import DocumentSupplementForm from './DocumentSupplementForm.vue'
vi.mock('@/api/client', () => ({ api: { documentSupplementOptions: vi.fn(), uploadDocumentSupplement: vi.fn() } }))
const run = { id: 'run', files: [], version: 3 } as unknown as DocumentTemplateOverview
const request = { requestKey: 'supplement-request-key', expectedVersion: 3, expectedTaskVersion: -1 }
beforeEach(() => { vi.clearAllMocks(); vi.mocked(api.documentSupplementOptions).mockResolvedValue({ available: true, message: '补充与原文一起复核', request }) })
it('retries a lost response using the same upload identity and suppresses duplicate clicks', async () => {
  const wrapper = mount(DocumentSupplementForm, { props: { run }, global: { plugins: [ElementPlus] } })
  await wrapper.get('button').trigger('click'); await flushPromises()
  const input = wrapper.get('input'); const file = new File(['审批规则'], '审批.md')
  Object.defineProperty(input.element, 'files', { value: [file] }); await input.trigger('change')
  vi.mocked(api.uploadDocumentSupplement).mockRejectedValueOnce(new Error('响应丢失'))
  await wrapper.findAll('button').find(button => button.text().includes('上传'))!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('响应丢失')
  let done!: (value: DocumentTemplateOverview) => void
  vi.mocked(api.uploadDocumentSupplement).mockReturnValueOnce(new Promise(resolve => { done = resolve }))
  const submit = wrapper.findAll('button').find(button => button.text().includes('上传'))!
  await submit.trigger('click'); await submit.trigger('click')
  expect(api.uploadDocumentSupplement).toHaveBeenCalledTimes(2)
  expect(vi.mocked(api.uploadDocumentSupplement).mock.calls[1]).toEqual(['run', request, [file]])
  done({ ...run, state: 'ANALYZING' }); await flushPromises()
  expect(wrapper.emitted('updated')).toHaveLength(1); wrapper.unmount()
})
it('shows an unavailable server action and rejects a late response after navigation', async () => {
  vi.mocked(api.documentSupplementOptions).mockResolvedValueOnce({ available: false, message: '需要先停止当前执行', request: null })
  const wrapper = mount(DocumentSupplementForm, { props: { run }, global: { plugins: [ElementPlus] } })
  await wrapper.get('button').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('需要先停止当前执行'); expect(wrapper.find('input').exists()).toBe(false)
  await wrapper.setProps({ run: { ...run, id: 'next' } })
  expect(wrapper.text()).not.toContain('需要先停止当前执行'); expect(api.uploadDocumentSupplement).not.toHaveBeenCalled(); wrapper.unmount()
})
