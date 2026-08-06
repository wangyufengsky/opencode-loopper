import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import InboxView from '@/views/InboxView.vue'
import type { Interaction } from '@/types/domain'

const mocks = vi.hoisted(() => ({ getInteractions: vi.fn(), resolveInteraction: vi.fn() }))
vi.mock('@/api/client', () => ({ api: mocks }))

const pendingPermission: Interaction = {
  id: 'permission-local', kind: 'PERMISSION', state: 'PENDING', taskId: 'task-12345678', sessionId: 'session-1',
  externalRequestId: 'permission-1', version: 4, createdAt: 'now', updatedAt: 'now',
  payload: { permission: 'bash', patterns: ['git status'], metadata: {}, title: '检查仓库', hardDenied: false },
}

const hardDenied: Interaction = {
  id: 'permission-danger', kind: 'PERMISSION', state: 'HARD_DENIED', taskId: 'task-12345678', sessionId: 'session-1',
  externalRequestId: 'permission-2', version: 0, createdAt: 'now', updatedAt: 'now',
  payload: { permission: 'bash', patterns: ['git push origin main'], metadata: {}, title: '发布', hardDenied: true, hardDenyReason: 'git push 不可由运行 Session 授权' },
}

beforeEach(() => {
  mocks.getInteractions.mockReset().mockResolvedValue([pendingPermission, hardDenied])
  mocks.resolveInteraction.mockReset().mockResolvedValue({ ...pendingPermission, state: 'RESOLVED', version: 6 })
})
afterEach(() => vi.useRealTimers())

describe('统一待处理中心', () => {
  it('renders server-authoritative permissions and submits the current optimistic version', async () => {
    const wrapper = mount(InboxView, {
      global: { plugins: [ElementPlus], stubs: { Icon: true, PageHeader: { template: '<header><slot name="actions" /></header>' } } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('1 项等待处理')
    expect(wrapper.text()).toContain('git push 不可由运行 Session 授权')
    expect(wrapper.findAll('button').filter((button) => button.text().includes('本 Session 允许'))).toHaveLength(1)

    await wrapper.findAll('button').find((button) => button.text().includes('仅本次允许'))!.trigger('click')
    await flushPromises()
    expect(mocks.resolveInteraction).toHaveBeenCalledWith('permission-local', { action: 'ONCE', version: 4 })
    wrapper.unmount()
  })

  it('keeps the persisted snapshot visible when a refresh fails', async () => {
    mocks.getInteractions.mockResolvedValueOnce([pendingPermission]).mockRejectedValueOnce(new Error('OpenCode 暂时不可达'))
    const wrapper = mount(InboxView, {
      global: { plugins: [ElementPlus], stubs: { Icon: true, PageHeader: { template: '<header><slot name="actions" /></header>' } } },
    })
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text().includes('刷新'))!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('OpenCode 暂时不可达')
    expect(wrapper.text()).toContain('检查仓库')
    wrapper.unmount()
  })
})
