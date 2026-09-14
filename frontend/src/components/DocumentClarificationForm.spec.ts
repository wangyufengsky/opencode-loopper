import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { DocumentTemplateOverview } from '@/types/domain'
import DocumentClarificationForm from './DocumentClarificationForm.vue'
vi.mock('@/api/client', () => ({ api: { answerDocumentRequirements: vi.fn() } }))
const run = { id: 'run', requirementRevision: 1, version: 3 } as DocumentTemplateOverview
beforeEach(() => vi.clearAllMocks())
it('keeps the same identity for an unknown reply and prevents duplicate submission', async () => {
  vi.mocked(api.answerDocumentRequirements).mockRejectedValueOnce(new Error('响应丢失'))
  const wrapper = mount(DocumentClarificationForm, { props: { run, requirementKey: 'RQ-1' }, global: { plugins: [ElementPlus] } })
  await wrapper.get('textarea').setValue('超过 1000 元审批')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  const first = vi.mocked(api.answerDocumentRequirements).mock.calls[0]![1]
  expect(first).toMatchObject({ expectedVersion: 3, requirementRevision: 1, answers: [{ requirementKey: 'RQ-1', answer: '超过 1000 元审批' }] })
  let done!: (value: DocumentTemplateOverview) => void
  vi.mocked(api.answerDocumentRequirements).mockReturnValueOnce(new Promise(resolve => { done = resolve }))
  await wrapper.get('form').trigger('submit'); await wrapper.get('form').trigger('submit')
  expect(api.answerDocumentRequirements).toHaveBeenCalledTimes(2)
  expect(vi.mocked(api.answerDocumentRequirements).mock.calls[1]![1].requestKey).toBe(first.requestKey)
  const updated = { ...run, state: 'ANALYZING' } as DocumentTemplateOverview
  done(updated); await flushPromises(); expect(wrapper.emitted('updated')).toEqual([[updated]])
  wrapper.unmount()
})
it('does not apply a late response to a different requirement', async () => {
  let done!: (value: DocumentTemplateOverview) => void
  vi.mocked(api.answerDocumentRequirements).mockReturnValue(new Promise(resolve => { done = resolve }))
  const wrapper = mount(DocumentClarificationForm, { props: { run, requirementKey: 'RQ-1' }, global: { plugins: [ElementPlus] } })
  await wrapper.get('textarea').setValue('金额采用人民币')
  await wrapper.get('form').trigger('submit'); await wrapper.setProps({ requirementKey: 'RQ-2' })
  done({ ...run, version: 4 }); await flushPromises()
  expect(wrapper.emitted('updated')).toBeUndefined(); expect(wrapper.get('textarea').element.value).toBe('')
  wrapper.unmount()
})
