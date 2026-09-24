import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SessionRoleSummary from '@/components/SessionRoleSummary.vue'
import { api, ApiError } from '@/api/client'
import type { TaskSessionRoleSummary as Summary } from '@/types/domain'

const configured: Summary = {
  configured: true, roleId: 'builtin.package-designer', revisionId: 'revision-2',
  revisionSha256: 'a'.repeat(64), slot: 'PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY',
  adapterProfile: 'PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY', adapterVersion: 'v1',
  permissions: [{ permission: 'read', pattern: '*', action: 'allow' }, { permission: 'bash', pattern: '*git*push*', action: 'deny' }],
  permissionSha256: 'b'.repeat(64),
}

afterEach(() => vi.restoreAllMocks())

describe('SessionRoleSummary', () => {
  it('queries only after expansion and shows frozen rule summaries with technical IDs collapsed', async () => {
    const get = vi.spyOn(api, 'getTaskSessionRole').mockResolvedValue(configured)
    const wrapper = mount(SessionRoleSummary, { props: { taskId: 'task-1', sessionKey: 'execution:local-1' } })
    expect(get).not.toHaveBeenCalled()
    expect(wrapper.get('.session-role-toggle').attributes('aria-expanded')).toBe('false')
    await wrapper.get('.session-role-toggle').trigger('click')
    await flushPromises()
    expect(get).toHaveBeenCalledOnce()
    expect(get).toHaveBeenCalledWith('task-1', 'execution:local-1')
    expect(wrapper.text()).toContain('角色配置摘要')
    expect(wrapper.text()).toContain('读取文件')
    expect(wrapper.text()).toContain('拒绝')
    expect(wrapper.get('.technical-details').attributes('open')).toBeUndefined()
    await wrapper.get('.session-role-toggle').trigger('click')
    await wrapper.get('.session-role-toggle').trigger('click')
    expect(get).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('explains a legacy Session without inventing a configured role', async () => {
    vi.spyOn(api, 'getTaskSessionRole').mockResolvedValue({ ...configured, configured: false, permissions: [] })
    const wrapper = mount(SessionRoleSummary, { props: { taskId: 'task-1', sessionKey: 'execution:legacy' } })
    await wrapper.get('.session-role-toggle').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('未记录角色配置快照')
    expect(wrapper.find('.permission-rules').exists()).toBe(false)
    wrapper.unmount()
  })

  it('retains the selected Session and offers an explicit retry after a read failure', async () => {
    const get = vi.spyOn(api, 'getTaskSessionRole').mockRejectedValueOnce(new ApiError('请求失败 (503)', 503)).mockResolvedValueOnce(configured)
    const wrapper = mount(SessionRoleSummary, { props: { taskId: 'task-1', sessionKey: 'execution:local-1' } })
    await wrapper.get('.session-role-toggle').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('会话角色摘要读取失败，请重试')
    await wrapper.get('.retry-button').trigger('click')
    await flushPromises()
    expect(get).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('创建时的权限规则')
    wrapper.unmount()
  })
})
