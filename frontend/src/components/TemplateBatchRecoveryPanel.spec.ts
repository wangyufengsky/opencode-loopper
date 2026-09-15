import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { Task, TemplateFailedBatch } from '@/types/domain'
import TemplateBatchRecoveryPanel from './TemplateBatchRecoveryPanel.vue'
vi.mock('@/api/client', () => ({ api: { templateFailedBatches: vi.fn(), retrySelectedTemplateBatches: vi.fn(), documentFailedBatches: vi.fn(), retrySelectedDocumentBatches: vi.fn() } }))
const batch: TemplateFailedBatch = { id: 'batch-39', ordinal: 38, purpose: 'REVIEW', generation: 0, state: 'FAILED', errorMessage: '未提交有效结果', version: 7, createdAt: 'now' }
const task: Task = { id: 'task', projectId: 'project', projectName: '项目', title: '报告', goal: '', branch: 'main', worktreePath: '', status: 'RUNNING', attemptCount: 2, maxAttempts: 12, createdAt: '', updatedAt: '', executionMode: 'TEMPLATE_REPORT' }
const button = { props: ['loading', 'disabled'], template: '<button :disabled="disabled"><slot /></button>' }
const mountPanel = () => mount(TemplateBatchRecoveryPanel, { props: { task }, global: { stubs: { ElButton: button } } })
describe('batch recovery', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.templateFailedBatches).mockResolvedValue({ items: [batch], facets: { retrySelectionReady: 1 } })
  })
  it('records failure while independent work continues and reveals selection only on server readiness', async () => {
    vi.mocked(api.templateFailedBatches).mockResolvedValue({ items: [batch], facets: { retrySelectionReady: 0 } })
    const view = mountPanel(); await flushPromises()
    expect(view.text()).toContain('后续批次继续执行')
    expect(view.find('input').exists()).toBe(false)
    expect(view.text()).not.toContain('第 39 批')
    vi.mocked(api.templateFailedBatches).mockResolvedValue({ items: [batch], facets: { retrySelectionReady: 1 } })
    await view.setProps({ task: { ...task, status: 'WAITING_INPUT' } }); await flushPromises()
    expect(view.text()).toContain('第 39 批')
    expect(view.text()).not.toContain('第 40 批')
    expect(view.find('button').attributes('disabled')).toBeDefined()
  })
  it('submits the selected failures together once and clears them after acceptance', async () => {
    const second = { ...batch, id: 'batch-42', ordinal: 41 }
    vi.mocked(api.templateFailedBatches).mockResolvedValue({ items: [batch, second], facets: { retrySelectionReady: 1 } })
    let resolve!: (value: { id: string; state: string }[]) => void
    vi.mocked(api.retrySelectedTemplateBatches).mockReturnValue(new Promise(r => { resolve = r }))
    const view = mountPanel(); await flushPromises()
    await view.find('input').setValue(true)
    await view.find('button').trigger('click'); await view.find('button').trigger('click')
    expect(api.retrySelectedTemplateBatches).toHaveBeenCalledTimes(1)
    expect(api.retrySelectedTemplateBatches).toHaveBeenCalledWith('task', [batch, second])
    vi.mocked(api.templateFailedBatches).mockResolvedValue({ items: [], facets: { retrySelectionReady: 0 } })
    resolve([{ id: 'next', state: 'PREPARED' }]); await flushPromises()
    expect(view.find('[aria-label="批次恢复"]').exists()).toBe(false)
  })
  it('retains the choice after a conflict and never automatically resends an unknown request', async () => {
    vi.mocked(api.retrySelectedTemplateBatches).mockRejectedValue(new Error('HTTP 409'))
    const view = mountPanel(); await flushPromises()
    await view.find('input').setValue(true); await view.find('button').trigger('click'); await flushPromises()
    expect(view.find('[role="alert"]').text()).toContain('暂不能重新触发')
    expect(view.text()).toContain('重新触发所选批次（1）')
    expect(view.text()).not.toContain('HTTP 409')
    expect(api.retrySelectedTemplateBatches).toHaveBeenCalledTimes(1)
  })
  it('ignores a late page from the previous task and resets selection when switching task', async () => {
    let resolve!: (value: Awaited<ReturnType<typeof api.templateFailedBatches>>) => void
    vi.mocked(api.templateFailedBatches).mockReturnValueOnce(new Promise(r => { resolve = r }))
    const view = mountPanel()
    vi.mocked(api.templateFailedBatches).mockResolvedValue({ items: [], facets: { retrySelectionReady: 0 } })
    await view.setProps({ task: { ...task, id: 'other' } }); await flushPromises()
    resolve({ items: [batch], facets: { retrySelectionReady: 1 } }); await flushPromises()
    expect(view.find('[aria-label="批次恢复"]').exists()).toBe(false)
  })
})
