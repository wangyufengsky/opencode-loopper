import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import DirectoryPathInput from './DirectoryPathInput.vue'

afterEach(() => vi.restoreAllMocks())
function setup() {
  return mount(DirectoryPathInput, { props: { modelValue: 'reports', label: '文档生成路径', scopeKey: 'p1' },
    global: { plugins: [ElementPlus], stubs: { Icon: true } } })
}
describe('Directory path input', () => {
  it('fills a chosen absolute path and reports pending state', async () => {
    vi.spyOn(api, 'pickProjectDirectory').mockResolvedValue({ selected: true, path: '/tmp/报告 目录' })
    const wrapper = setup()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('update:modelValue')).toEqual([['/tmp/报告 目录']])
    expect(wrapper.emitted('update:picking')).toEqual([[true], [false]])
    wrapper.unmount()
  })
  it('keeps the entered path after cancellation or failure and allows manual input', async () => {
    vi.spyOn(api, 'pickProjectDirectory').mockResolvedValueOnce({ selected: false }).mockRejectedValueOnce(new Error('无法打开系统选择器'))
    const wrapper = setup()
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('无法打开系统选择器')
    expect(wrapper.get('input').element.value).toBe('reports')
    await wrapper.get('input').setValue('manual/reports')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['manual/reports'])
    wrapper.unmount()
  })
  it('ignores selection after the project changes or the component closes', async () => {
    let finish!: (value: { selected: boolean; path: string }) => void
    vi.spyOn(api, 'pickProjectDirectory').mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const wrapper = setup()
    await wrapper.get('button').trigger('click')
    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
    await wrapper.setProps({ scopeKey: 'p2', modelValue: 'new-project/reports' })
    finish({ selected: true, path: '/old-project' }); await flushPromises()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    await wrapper.get('button').trigger('click')
    wrapper.unmount()
    finish({ selected: true, path: '/closed-form' }); await flushPromises()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
  it('ignores a late selection after the path is replaced externally', async () => {
    let finish!: (value: { selected: boolean; path: string }) => void
    vi.spyOn(api, 'pickProjectDirectory').mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const wrapper = setup()
    await wrapper.get('button').trigger('click')
    await wrapper.setProps({ modelValue: 'updated/reports' })
    finish({ selected: true, path: '/stale' }); await flushPromises()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    wrapper.unmount()
  })
})
