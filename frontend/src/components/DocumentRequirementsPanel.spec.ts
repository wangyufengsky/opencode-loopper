import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { DocumentTemplateOverview, DocumentRequirementDetail } from '@/types/domain'
import DocumentRequirementsPanel from './DocumentRequirementsPanel.vue'
vi.mock('@/api/client', () => ({ api: { documentRequirements: vi.fn(), documentRequirement: vi.fn(), documentSection: vi.fn(), documentClarifications: vi.fn() } }))
const run = { id: 'run', templateId: 'REQUIREMENT_DEVELOPMENT', state: 'WAITING_INPUT', requirementRevision: 1, version: 3, designerId: null,
  progress: { requirements: 1 }, files: [{ id: 'file', filename: '付款需求.md', sha256: 'original-sha' }] } as DocumentTemplateOverview
const item = { requirementKey: 'RQ-1', ordinal: 0, title: '付款权限', groupName: '付款', kind: 'PERMISSION', issueCount: 1, conclusion: null }
const detail: DocumentRequirementDetail = { requirement: { key: 'RQ-1', title: '付款权限', group: '付款', kind: 'PERMISSION', statement: '付款前必须鉴权',
  sources: [{ fileId: 'file', section: 0, quote: '付款前必须鉴权' }], acceptance: ['未授权不能付款'], issues: ['审批阈值待决'] }, assessment: null }
function render(value = run) { return mount(DocumentRequirementsPanel, { props: { run: value }, global: { plugins: [ElementPlus], stubs: { MarkdownDocument: { props: ['content'], template: '<div>{{ content }}</div>' } } } }) }
beforeEach(() => { vi.clearAllMocks(); vi.mocked(api.documentRequirements).mockResolvedValue({ items: [item], nextOffset: null, revision: 1 }); vi.mocked(api.documentRequirement).mockResolvedValue(detail) })
it('reads only summaries until expanded, then verifies original file identity and exposes an actionable business question', async () => {
  const wrapper = render(); await flushPromises()
  expect(api.documentRequirement).not.toHaveBeenCalled(); expect(api.documentSection).not.toHaveBeenCalled()
  await wrapper.get('.requirement-heading').trigger('click'); await flushPromises()
  expect(api.documentRequirement).toHaveBeenCalledWith('run', 1, 'RQ-1'); expect(wrapper.find('form').exists()).toBe(true)
  vi.mocked(api.documentSection).mockResolvedValue({ fileId: 'file', ordinal: 0, title: '付款', content: '完整原文', sha256: 'section-sha' })
  const source = wrapper.findAll('button').find(button => button.text().includes('付款需求.md'))!
  await source.trigger('click'); await flushPromises()
  expect(api.documentSection).toHaveBeenCalledWith('run', 'file', 0, 'original-sha'); expect(wrapper.text()).toContain('完整原文')
  await wrapper.setProps({ run: { ...run, designerId: 'designer' } }); expect(wrapper.find('form').exists()).toBe(false)
  wrapper.unmount()
})
it('does not show old requirement text after the frozen revision changes', async () => {
  let done!: (value: DocumentRequirementDetail) => void
  vi.mocked(api.documentRequirement).mockReturnValue(new Promise(resolve => { done = resolve }))
  const wrapper = render(); await flushPromises(); await wrapper.get('.requirement-heading').trigger('click')
  await wrapper.setProps({ run: { ...run, requirementRevision: 2 } }); done(detail); await flushPromises()
  expect(wrapper.text()).not.toContain('付款前必须鉴权'); expect(api.documentRequirements).toHaveBeenLastCalledWith('run', 2, -1, false)
  expect(api.documentClarifications).not.toHaveBeenCalled(); wrapper.unmount()
})
