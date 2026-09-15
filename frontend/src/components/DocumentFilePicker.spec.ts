import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import DocumentFilePicker from './DocumentFilePicker.vue'

const files = [new File(['需求'], '很长的需求文档名称.md'), new File(['PDF'], '验收.pdf')]
describe('DocumentFilePicker', () => {
  it('shows the full selected filenames and removes only the requested file', async () => {
    const wrapper = mount(DocumentFilePicker, { props: { modelValue: files }, global: { plugins: [ElementPlus], stubs: { Icon: true } } })
    expect(wrapper.get('[role="status"]').text()).toContain('已选 2 份文档')
    expect(wrapper.get('.file-list').text()).toContain(files[0]!.name)
    await wrapper.get('[aria-label="移除 验收.pdf"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toEqual([[files.slice(0, 1)]])
  })
  it('keeps the current list when selection is cancelled and accepts a replacement selection', async () => {
    const wrapper = mount(DocumentFilePicker, { props: { modelValue: files }, global: { plugins: [ElementPlus], stubs: { Icon: true } } })
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [] })
    await input.trigger('change')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    Object.defineProperty(input.element, 'files', { configurable: true, value: [files[1]] })
    await input.trigger('change')
    expect(wrapper.emitted('update:modelValue')).toEqual([[[files[1]]]])
  })
  it('disables choosing and removing files while a submission is in progress', () => {
    const wrapper = mount(DocumentFilePicker, { props: { modelValue: files, disabled: true }, global: { plugins: [ElementPlus], stubs: { Icon: true } } })
    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('button').every(button => button.attributes('disabled') !== undefined)).toBe(true)
  })
})
