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

describe('Projects AGENTS.md convention flow', () => {
  it('previews the AI proposal and applies it only after explicit confirmation', async () => {
    const store = useTaskStore()
    store.projects = [{ id: 'project-1', name: 'Example', rootPath: '/tmp/example', status: 'READY', updatedAt: '2026-08-05T00:00:00Z', taskCount: 0 }]
    vi.spyOn(api, 'generateProjectConvention').mockResolvedValue({
      id: 'draft-1', projectId: 'project-1', state: 'READY', operation: 'CREATE', readOnlyGeneration: true,
      content: '<!-- LOOPPER:START -->\n# Project rules\n<!-- LOOPPER:END -->\n', updatedAt: '2026-08-05T00:00:01Z',
    })
    const apply = vi.spyOn(api, 'applyProjectConvention').mockResolvedValue({
      id: 'draft-1', projectId: 'project-1', state: 'APPLIED', operation: 'CREATE', readOnlyGeneration: true,
      content: '<!-- LOOPPER:START -->\n# Project rules\n<!-- LOOPPER:END -->\n', updatedAt: '2026-08-05T00:00:02Z',
    })
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

    const generate = wrapper.find('button[aria-label="生成或更新 AGENTS.md 项目公约"]')
    expect(generate).toBeDefined()
    await generate.trigger('click')
    await flushPromises()

    expect(api.generateProjectConvention).toHaveBeenCalledWith('project-1')
    expect((wrapper.get('textarea[aria-label="AGENTS.md 完整预览"]').element as HTMLTextAreaElement).value).toContain('# Project rules')
    const confirm = wrapper.findAll('button').find((button) => button.text().includes('确认写入 AGENTS.md'))
    expect(confirm).toBeDefined()
    expect(apply).not.toHaveBeenCalled()
    await confirm!.trigger('click')
    await flushPromises()

    expect(apply).toHaveBeenCalledWith('project-1', 'draft-1')
  })
})
