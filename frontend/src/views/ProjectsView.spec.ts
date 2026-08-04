import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { useTaskStore } from '@/stores/taskStore'
import ProjectsView from '@/views/ProjectsView.vue'

beforeEach(() => {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useTaskStore()
  store.usingDemo = false
  store.projects = []
})

afterEach(() => vi.restoreAllMocks())

describe('Projects folder picker', () => {
  it('fills the absolute path and suggests a project name after directory selection', async () => {
    vi.spyOn(api, 'pickProjectDirectory').mockResolvedValue({ selected: true, path: '/tmp/example-project', name: 'example-project' })
    const wrapper = mount(ProjectsView, {
      global: {
        plugins: [ElementPlus],
        stubs: {
          teleport: true,
          PageHeader: { template: '<header><slot /><slot name="actions" /></header>' },
          StatusBadge: true,
          Icon: true,
        },
      },
    })

    const register = wrapper.findAll('button').find((button) => button.text().includes('登记项目'))
    expect(register).toBeDefined()
    await register!.trigger('click')
    await wrapper.get('button[aria-label="选择项目文件夹"]').trigger('click')
    await flushPromises()

    expect((wrapper.get('input[aria-label="项目根路径"]').element as HTMLInputElement).value).toBe('/tmp/example-project')
    expect((wrapper.get('input[placeholder="例如 OpenCode Loopper"]').element as HTMLInputElement).value).toBe('example-project')
  })

  it('keeps a manually entered path when the native selector is cancelled', async () => {
    vi.spyOn(api, 'pickProjectDirectory').mockResolvedValue({ selected: false })
    const wrapper = mount(ProjectsView, {
      global: {
        plugins: [ElementPlus],
        stubs: {
          teleport: true,
          PageHeader: { template: '<header><slot /><slot name="actions" /></header>' },
          StatusBadge: true,
          Icon: true,
        },
      },
    })

    const register = wrapper.findAll('button').find((button) => button.text().includes('登记项目'))
    await register!.trigger('click')
    const pathInput = wrapper.get('input[aria-label="项目根路径"]')
    await pathInput.setValue('/tmp/keep-this-path')
    await wrapper.get('button[aria-label="选择项目文件夹"]').trigger('click')
    await flushPromises()

    expect((pathInput.element as HTMLInputElement).value).toBe('/tmp/keep-this-path')
  })
})
