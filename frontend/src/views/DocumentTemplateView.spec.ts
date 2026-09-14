import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { DocumentTemplateOverview } from '@/types/domain'
import DocumentTemplateView from './DocumentTemplateView.vue'
vi.mock('@/api/client', () => ({ api: { documentTemplate: vi.fn(), documentEvents: vi.fn(), documentTemplateCommand: vi.fn() } }))
const base: DocumentTemplateOverview = { id: 'one', projectId: 'project', templateId: 'REQUIREMENT_CODE_REVIEW', templateVersion: '1', title: '需求评审',
  state: 'ASSESSING', waitingReasonCode: null, waitingMessage: null, designerId: null, taskId: null,
  requirementRevision: 1, version: 3, createdAt: 'created', updatedAt: 'updated', canCancel: true, canResume: false, archived: false,
  uploadReady: true, snapshotSha: 'frozen-sha', files: [], progress: { attempts: 2, validated: 1, active: 1, stopped: 0, requirements: 1, reports: 0, revision: 3 } }
const stream = { close: vi.fn(), addEventListener: vi.fn(), onopen: undefined as undefined | (() => void), onerror: undefined as undefined | (() => void) }
beforeEach(() => { vi.clearAllMocks(); vi.useFakeTimers(); vi.stubGlobal('EventSource', class {}); vi.mocked(api.documentTemplate).mockResolvedValue(base); vi.mocked(api.documentEvents).mockReturnValue(stream as unknown as EventSource) })
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })
async function render() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/template-tasks/document-runs/:id', component: DocumentTemplateView }, { path: '/tasks/:id', component: { template: '<div />' } }] })
  await router.push('/template-tasks/document-runs/one'); await router.isReady()
  const wrapper = mount(DocumentTemplateView, { global: { plugins: [ElementPlus, router], stubs: { PageHeader: true,
    DocumentRequirementsPanel: { props: ['run'], template: '<div class="requirements">{{ run.id }}</div>' },
    DocumentReportsPanel: { props: ['runId'], template: '<div class="reports">{{ runId }}</div>' } } } })
  await flushPromises(); return { wrapper, router }
}
it('refreshes the authoritative overview after SSE disconnect and never implies tests ran', async () => {
  const { wrapper } = await render()
  expect(wrapper.text()).toContain('本次未执行构建或测试')
  expect(api.documentEvents).toHaveBeenCalledWith('one')
  stream.onerror?.(); await vi.advanceTimersByTimeAsync(180); await flushPromises()
  expect(api.documentTemplate).toHaveBeenCalledTimes(2)
  expect(wrapper.text()).toContain('实时连接中断')
  await vi.advanceTimersByTimeAsync(10_000); expect(api.documentTemplate).toHaveBeenCalledTimes(3)
  wrapper.unmount(); expect(stream.close).toHaveBeenCalled()
})
it('discards a late old overview on navigation and shows independent development result disposition', async () => {
  let finish!: (value: DocumentTemplateOverview) => void
  const { wrapper, router } = await render()
  vi.mocked(api.documentTemplate).mockImplementation(id => id === 'one' ? new Promise(resolve => { finish = resolve })
    : Promise.resolve({ ...base, id: 'two', templateId: 'REQUIREMENT_DEVELOPMENT', state: 'COMPLETED', taskId: 'execution', taskState: 'AWAITING_DECISION', canCancel: false }))
  stream.onerror?.(); await vi.advanceTimersByTimeAsync(180)
  await router.push('/template-tasks/document-runs/two'); await flushPromises()
  finish({ ...base, title: '不应覆盖当前任务' }); await flushPromises()
  expect(wrapper.get('.requirements').text()).toBe('two'); expect(wrapper.get('.reports').text()).toBe('two')
  expect(wrapper.text()).toContain('结果仍待你处置'); expect(wrapper.text()).not.toContain('不应覆盖当前任务')
  expect(wrapper.get('a[href="/tasks/execution"]').text()).toContain('开发执行')
  wrapper.unmount()
})

it('shows original source identity and defers the result matrix for direct document runs', async () => {
  vi.mocked(api.documentTemplate).mockResolvedValue({ ...base, sourceKind: 'DOCUMENT_SOURCE', sourceRevision: 1,
    templateVersion: '2', requirementRevision: 0, analysisConcurrency: 4 })
  const { wrapper } = await render()
  expect(wrapper.text()).toContain('原文版本 1')
  expect(wrapper.text()).toContain('并发上限 4')
  expect(wrapper.find('.requirements').exists()).toBe(false)
  expect(wrapper.text()).not.toContain('已复核需求')
  wrapper.unmount()
})
