import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { DocumentTemplateOverview } from '@/types/domain'
import DocumentSourcesPanel from './DocumentSourcesPanel.vue'
vi.mock('@/api/client', () => ({ api: { documentSections: vi.fn(), documentSection: vi.fn() } }))
it('loads chapter metadata and frozen body only when requested and discards a stale body after source revision changes', async () => {
  const run = { id: 'run', sourceRevision: 1, files: [{ id: 'file', filename: '需规.md', sha256: 'file-sha', sectionCount: 2 }] } as DocumentTemplateOverview
  vi.mocked(api.documentSections).mockResolvedValue({ items: [{ fileId: 'file', ordinal: 0, title: '付款', characters: 10, sha256: 'section-sha' }], nextOffset: 1 })
  let done!: (value: { fileId: string; ordinal: number; title: string; content: string; sha256: string }) => void
  vi.mocked(api.documentSection).mockReturnValue(new Promise(resolve => { done = resolve }))
  const wrapper = mount(DocumentSourcesPanel, { props: { run }, global: { plugins: [ElementPlus], stubs: { MarkdownDocument: { props: ['content'], template: '<div>{{ content }}</div>' } } } })
  expect(api.documentSections).not.toHaveBeenCalled()
  await wrapper.get('button').trigger('click'); await flushPromises()
  expect(api.documentSections).toHaveBeenCalledWith('run', 'file', 0)
  expect(api.documentSection).not.toHaveBeenCalled()
  await wrapper.findAll('button').find(button => button.text() === '读取原文')!.trigger('click')
  expect(api.documentSection).toHaveBeenCalledWith('run', 'file', 0, 'file-sha')
  await wrapper.setProps({ run: { ...run, sourceRevision: 2 } })
  done({ fileId: 'file', ordinal: 0, title: '付款', content: '不应显示的旧响应', sha256: 'section-sha' }); await flushPromises()
  expect(wrapper.text()).not.toContain('不应显示的旧响应')
  wrapper.unmount()
})
