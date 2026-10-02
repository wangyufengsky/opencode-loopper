import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { workflowApi } from '@/api/workflow'
import WorkflowLibraryView from './WorkflowLibraryView.vue'
import { summary } from '@/components/workflow/workflowTestFixtures'
vi.mock('@/api/workflow', () => ({ workflowApi: { list: vi.fn(), copy: vi.fn(), archive: vi.fn() } }))
const api = vi.mocked(workflowApi)
let wrapper: VueWrapper | undefined
beforeEach(() => { vi.resetAllMocks(); api.list.mockResolvedValue({ items: [summary()], nextCursor: 'page-2' }); vi.spyOn(window, 'confirm').mockReturnValue(true) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })
async function render() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/requirements/new', component: { template: '<div />' } }, { path: '/workflows', component: WorkflowLibraryView }, { path: '/workflows/:id', component: { template: '<div />' } }] })
  await router.push('/workflows'); await router.isReady(); wrapper = mount(WorkflowLibraryView, { attachTo: document.body, global: { plugins: [router], stubs: { Icon: true } } }); await flushPromises()
}
const button = (label: string) => wrapper!.findAll('button').find(node => node.text() === label)!
const openActions = () => wrapper!.get('[data-action-trigger]').trigger('click')
describe('workflow library', () => {
  it('reveals secondary actions on demand and returns keyboard focus on Escape', async () => {
    await render()
    expect(wrapper!.find('.workflow-entry-action-panel').exists()).toBe(false)
    await openActions()
    expect(wrapper!.get('[data-action-trigger]').attributes('aria-expanded')).toBe('true')
    expect(button('复制').exists()).toBe(true)
    ;(button('复制').element as HTMLButtonElement).focus()
    await button('复制').trigger('keydown', { key: 'Escape' })
    expect(wrapper!.find('.workflow-entry-action-panel').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper!.get('[data-action-trigger]').element)
    await openActions(); document.body.dispatchEvent(new Event('pointerdown', { bubbles: true })); await flushPromises()
    expect(wrapper!.find('.workflow-entry-action-panel').exists()).toBe(false)
  })
  it('passes cursor and server filters, resetting pagination for a new search', async () => {
    await render(); api.list.mockResolvedValueOnce({ items: [summary({ id: 'next', title: '第二页流程' })] }); await button('加载更多流程').trigger('click'); await flushPromises()
    expect(api.list).toHaveBeenLastCalledWith('', 'ALL', 'page-2'); expect(wrapper!.findAll('.workflow-template-card')).toHaveLength(2)
    await wrapper!.get('input').setValue('第二'); await wrapper!.get('form').trigger('submit'); await flushPromises(); expect(api.list).toHaveBeenLastCalledWith('第二', 'ALL', '')
    await button('程序内置').trigger('click'); await flushPromises(); expect(api.list).toHaveBeenLastCalledWith('第二', 'BUILTIN', '')
  })
  it('reuses the copy key after an uncertain response, including after changing filters', async () => {
    await render(); api.copy.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({ id: 'copy', revision: 1, version: 0, layoutVersion: 0, state: 'ACTIVE' })
    await openActions(); await button('复制').trigger('click'); await flushPromises()
    await wrapper!.get('[data-action-trigger]').trigger('keydown', { key: 'Escape' })
    expect(wrapper!.get('[role="alert"]').text()).toContain('操作未完成')
    await button('我的流程').trigger('click'); await flushPromises(); expect(button('重试原操作').exists()).toBe(true)
    await button('重试原操作').trigger('click'); await flushPromises(); expect(api.copy.mock.calls[0]).toEqual(api.copy.mock.calls[1])
  })
  it('requires confirmation and sends the displayed version for deletion', async () => {
    await render(); await openActions(); vi.mocked(window.confirm).mockReturnValueOnce(false); await button('删除').trigger('click'); expect(api.archive).not.toHaveBeenCalled()
    api.archive.mockResolvedValue({ id: 'example', revision: 2, version: 4, layoutVersion: 4, state: 'ARCHIVED' }); await button('删除').trigger('click'); await flushPromises()
    expect(api.archive).toHaveBeenCalledWith('example', { expectedVersion: 3, requestKey: expect.any(String) })
  })
  it('keeps built-in flows read-only while allowing copies', async () => {
    api.list.mockResolvedValue({ items: [summary({ builtin: true })] }); await render(); await openActions()
    expect(wrapper!.get('.workflow-entry-action-panel').text()).toContain('查看流程')
    expect(wrapper!.get('.workflow-entry-action-panel').text()).not.toContain('删除')
    expect(button('复制').attributes('disabled')).toBeUndefined()
  })
})
