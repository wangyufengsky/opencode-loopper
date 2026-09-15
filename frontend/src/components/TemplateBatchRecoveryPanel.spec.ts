import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import type { Task, TemplateFailedBatch } from '@/types/domain'
import TemplateBatchRecoveryPanel from './TemplateBatchRecoveryPanel.vue'
vi.mock('@/api/client', () => ({ api: { templateFailedBatches: vi.fn(), retryTemplateBatch: vi.fn(), documentFailedBatches: vi.fn(), retryDocumentBatch: vi.fn() } }))
const batch: TemplateFailedBatch = { id: 'batch-35', ordinal: 34, purpose: 'REVIEW', generation: 2, state: 'FAILED', errorMessage: '未提交有效结果', version: 7, createdAt: 'now' }
const task: Task = { id: 'task', projectId: 'project', projectName: '项目', title: '报告', goal: '', branch: 'main', worktreePath: '', status: 'RUNNING', attemptCount: 2, maxAttempts: 12, createdAt: '', updatedAt: '', executionMode: 'TEMPLATE_REPORT' }
const button = { props: ['loading', 'disabled'], template: '<button :disabled="disabled"><slot /></button>' }
describe('batch recovery', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.mocked(api.templateFailedBatches).mockResolvedValue({ items: [batch], nextCursor: undefined, facets: {} }) })
  it('retains manual retry after automatic budget exhaustion and suppresses double click', async () => {
    let resolve!: (value: { id: string; state: string }) => void
    vi.mocked(api.retryTemplateBatch).mockReturnValue(new Promise(r => { resolve = r }))
    const view = mount(TemplateBatchRecoveryPanel, { props: { task }, global: { stubs: { ElButton: button } } })
    await flushPromises()
    expect(view.text()).toContain('第 35 批')
    expect(view.text()).toContain('自动重试额度已用完')
    await view.find('button').trigger('click'); await view.find('button').trigger('click')
    expect(api.retryTemplateBatch).toHaveBeenCalledTimes(1)
    expect(api.retryTemplateBatch).toHaveBeenCalledWith('task', batch)
    vi.mocked(api.templateFailedBatches).mockResolvedValue({ items: [], nextCursor: undefined, facets: {} })
    resolve({ id: 'next', state: 'PREPARED' }); await flushPromises()
    expect(view.find('[aria-label="批次恢复"]').exists()).toBe(false)
  })
  it('keeps retry available with a Chinese error after an unconfirmed response', async () => {
    vi.mocked(api.retryTemplateBatch).mockRejectedValue(new Error('HTTP 409'))
    const view = mount(TemplateBatchRecoveryPanel, { props: { task }, global: { stubs: { ElButton: button } } })
    await flushPromises(); await view.find('button').trigger('click'); await flushPromises()
    expect(view.find('[role="alert"]').text()).toContain('暂不能重试')
    expect(view.text()).toContain('重试该批次')
    expect(view.text()).not.toContain('HTTP 409')
  })
})
