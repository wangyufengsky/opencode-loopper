import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { pptApi } from '@/api/ppt'
import PptProjectPicker from './PptProjectPicker.vue'
vi.mock('@/api/ppt', () => ({ pptApi: { projects: vi.fn() } }))
const api = vi.mocked(pptApi)
beforeEach(() => { vi.clearAllMocks() })
describe('PPT project selection', () => {
  it('loads choices on demand, follows the server cursor and selects only the requested project', async () => {
    api.projects.mockResolvedValueOnce({ items: [{ id: 'one', name: '支付平台' }], nextCursor: 'next', facets: {} })
    api.projects.mockResolvedValueOnce({ items: [{ id: 'two', name: '清算平台' }], nextCursor: undefined, facets: {} })
    const wrapper = mount(PptProjectPicker, { props: { modelValue: null } })
    expect(api.projects).not.toHaveBeenCalled()
    await wrapper.get('[aria-label="选择项目（可选）"]').trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '更多项目')!.trigger('click')
    await flushPromises()
    expect(api.projects).toHaveBeenLastCalledWith('', 'next')
    await wrapper.findAll('button').find(button => button.text() === '清算平台')!.trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([{ id: 'two', name: '清算平台' }])
    expect(wrapper.find('#ppt-project-choices').exists()).toBe(false)
  })
  it('keeps restored association visible and allows unassociated work when catalog loading fails', async () => {
    api.projects.mockRejectedValue(new Error('offline'))
    const wrapper = mount(PptProjectPicker, { props: { modelValue: { id: 'one', name: '支付平台' } } })
    await wrapper.get('[aria-label="关联项目：支付平台"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('也可以不关联项目继续')
    await wrapper.findAll('button').find(button => button.text().startsWith('不关联项目'))!.trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([null])
    await wrapper.setProps({ disabled: true })
    expect(wrapper.get('.ppt-project-trigger').attributes('disabled')).toBeDefined()
  })
})
