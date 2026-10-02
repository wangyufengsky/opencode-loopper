import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowRequirementSummary } from '@/types/domain'
import WorkflowRequirementListView from './WorkflowRequirementListView.vue'

vi.mock('@/api/workflowRuns', () => ({ workflowRuns: { list: vi.fn() } }))
const api = vi.mocked(workflowRuns)
let wrapper: VueWrapper | undefined
const summary = (overrides: Partial<WorkflowRequirementSummary> = {}): WorkflowRequirementSummary => ({ id: 'req', projectId: 'project', title: '退款能力', state: 'PLANNING', headRevision: 2, version: 1, createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-02T10:00:00Z', ...overrides })
beforeEach(() => { vi.resetAllMocks(); api.list.mockResolvedValue({ items: [summary()], nextCursor: 'next-page' }) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined })
async function render() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/requirements', component: WorkflowRequirementListView }, { path: '/requirements/:id', component: { template: '<div />' } }, { path: '/workflows', component: { template: '<div />' } }] })
  await router.push('/requirements'); await router.isReady()
  wrapper = mount(WorkflowRequirementListView, { global: { plugins: [router], stubs: { Icon: true, WorkflowChoice: { template: '<button @click="$emit(\'select\', { id: \'selected-project\', title: \'订单服务\' })">选择项目</button>' } } } })
  await flushPromises(); return router
}
const button = (label: string) => wrapper!.findAll('button').find(node => node.text() === label)!
describe('requirement list', () => {
  it('browses paginated requirements, resets the cursor for a project, and preserves it when creating', async () => {
    await render()
    api.list.mockResolvedValueOnce({ items: [summary({ id: 'second', title: '新接口', state: 'COMPLETED' })] })
    await button('加载更多需求').trigger('click'); await flushPromises()
    expect(api.list).toHaveBeenLastCalledWith(undefined, 'next-page')
    expect(wrapper!.findAll('.workflow-entry-requirement')).toHaveLength(2)
    expect(wrapper!.findAll('.status-badge')[1]!.text()).toBe('已完成')
    api.list.mockResolvedValueOnce({ items: [summary({ projectId: 'selected-project' })] })
    await button('选择项目').trigger('click'); await flushPromises()
    expect(api.list).toHaveBeenLastCalledWith('selected-project', '')
    expect(wrapper!.findAll('.workflow-entry-requirement')).toHaveLength(1)
    expect(wrapper!.findAll('a').find(link => link.text() === '新增需求')!.attributes('href')).toBe('/requirements/new?projectId=selected-project')
    expect(wrapper!.findAll('a').find(link => link.text() === '打开画布')!.attributes('href')).toBe('/requirements/req')
  })
  it('shows a recoverable loading error separately from the empty state', async () => {
    api.list.mockRejectedValueOnce(new Error('network')); await render()
    expect(wrapper!.get('[role="alert"]').text()).toContain('需求任务暂时无法读取')
    expect(wrapper!.find('.workflow-entry-empty').exists()).toBe(false)
    api.list.mockResolvedValueOnce({ items: [] })
    await button('重新加载').trigger('click'); await flushPromises()
    expect(wrapper!.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper!.get('.workflow-entry-empty').text()).toContain('从一个需求开始')
  })
})
