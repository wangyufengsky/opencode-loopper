import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { useTaskStore } from '@/stores/taskStore'
import ProjectsView from '@/views/ProjectsView.vue'

const { routerPush } = vi.hoisted(() => ({ routerPush: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: routerPush }) }))

beforeEach(() => {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useTaskStore()
  store.usingDemo = false
  store.projects = []
  routerPush.mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})

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
  it('shows an empty current convention before starting AI design and applies only after confirmation', async () => {
    const store = useTaskStore()
    store.projects = [{ id: 'project-1', name: 'Example', rootPath: '/tmp/example', status: 'READY', updatedAt: '2026-08-05T00:00:00Z', taskCount: 0, openDesignerSessionCount: 0 }]
    vi.spyOn(api, 'getCurrentProjectConvention').mockResolvedValue({ projectId: 'project-1', exists: false, loopperManaged: false, content: '' })
    vi.spyOn(api, 'generateProjectConvention').mockResolvedValue({
      id: 'draft-1', projectId: 'project-1', state: 'READY', operation: 'CREATE', readOnlyGeneration: true,
      content: '<!-- LOOPPER:START -->\n# Project rules\n<!-- LOOPPER:END -->\n', normalizationNotice: 'AI 输出已自动规范化：WRAPPER_TOLERATED', updatedAt: '2026-08-05T00:00:01Z',
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

    const view = wrapper.find('button[aria-label="查看 AGENTS.md 项目公约"]')
    expect(view).toBeDefined()
    await view.trigger('click')
    await flushPromises()

    expect(api.getCurrentProjectConvention).toHaveBeenCalledWith('project-1')
    expect(wrapper.text()).toContain('暂无项目公约')
    expect(api.generateProjectConvention).not.toHaveBeenCalled()
    const generate = wrapper.findAll('button').find((button) => button.text().includes('新增 Loopper 公约'))
    expect(generate).toBeDefined()
    await generate!.trigger('click')
    await flushPromises()

    expect(api.generateProjectConvention).toHaveBeenCalledWith('project-1')
    expect((wrapper.get('textarea[aria-label="AGENTS.md 完整预览"]').element as HTMLTextAreaElement).value).toContain('# Project rules')
    expect(wrapper.text()).toContain('AI 输出已自动规范化：已兼容常见外层格式')
    expect(wrapper.text()).not.toContain('WRAPPER_TOLERATED')
    const confirm = wrapper.findAll('button').find((button) => button.text().includes('确认写入 AGENTS.md'))
    expect(confirm).toBeDefined()
    expect(apply).not.toHaveBeenCalled()
    await confirm!.trigger('click')
    await flushPromises()

    expect(apply).toHaveBeenCalledWith('project-1', 'draft-1')
  })

  it('shows the existing AGENTS.md without starting AI', async () => {
    const store = useTaskStore()
    store.projects = [{ id: 'project-1', name: 'Example', rootPath: '/tmp/example', status: 'READY', updatedAt: '2026-08-05T00:00:00Z', taskCount: 2, openDesignerSessionCount: 0 }]
    vi.spyOn(api, 'getCurrentProjectConvention').mockResolvedValue({ projectId: 'project-1', exists: true, loopperManaged: true, content: '# Existing rules\n' })
    const generate = vi.spyOn(api, 'generateProjectConvention')
    const wrapper = mount(ProjectsView, {
      global: { plugins: [ElementPlus], stubs: { teleport: true, PageHeader: { template: '<header><slot /><slot name="actions" /></header>' }, StatusBadge: true, Icon: true } },
    })

    await wrapper.get('button[aria-label="查看 AGENTS.md 项目公约"]').trigger('click')
    await flushPromises()

    expect((wrapper.get('textarea[aria-label="当前 AGENTS.md 项目公约"]').element as HTMLTextAreaElement).value).toContain('# Existing rules')
    expect(wrapper.text()).toContain('AI 更新 Loopper 公约')
    expect(generate).not.toHaveBeenCalled()
  })

  it('streams live AI activity and sends an explicit remote stop request', async () => {
    vi.useFakeTimers()
    const store = useTaskStore()
    store.projects = [{ id: 'project-1', name: 'Example', rootPath: '/tmp/example', status: 'READY', updatedAt: 'now', taskCount: 0, openDesignerSessionCount: 0 }]
    vi.spyOn(api, 'getCurrentProjectConvention').mockResolvedValue({ projectId: 'project-1', exists: true, loopperManaged: true, content: '# Existing\n' })
    vi.spyOn(api, 'generateProjectConvention').mockResolvedValue({
      id: 'draft-1', projectId: 'project-1', state: 'RUNNING', operation: 'UPDATE', readOnlyGeneration: true, updatedAt: 'now',
    })
    vi.spyOn(api, 'getProjectConventionDraft').mockResolvedValue({
      id: 'draft-1', projectId: 'project-1', state: 'RUNNING', operation: 'UPDATE', readOnlyGeneration: true, updatedAt: 'later',
    })
    vi.spyOn(api, 'getProjectConventionActivity').mockResolvedValue({
      actor: 'PROJECT_CONVENTION', remoteState: 'RUNNING', connected: true, observedAt: 'later',
      parts: [{ id: 'part-1', type: 'THINKING', label: '分析构建文件', content: '正在核对 Maven 模块', status: 'RUNNING' }],
      usage: { totalTokens: 321, unknownUsageCount: 0, observedAt: 'later' },
    })
    const cancel = vi.spyOn(api, 'cancelProjectConvention').mockResolvedValue({
      id: 'draft-1', projectId: 'project-1', state: 'CANCELLED', operation: 'UPDATE', readOnlyGeneration: true, error: '用户取消了项目公约生成', updatedAt: 'stopped',
    })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mount(ProjectsView, {
      global: { plugins: [ElementPlus], stubs: { teleport: true, PageHeader: { template: '<header><slot /><slot name="actions" /></header>' }, StatusBadge: true, Icon: true } },
    })

    await wrapper.get('button[aria-label="查看 AGENTS.md 项目公约"]').trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text().includes('AI 更新 Loopper 公约'))!.trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()

    expect(wrapper.text()).toContain('项目公约设计师正在处理')
    expect(wrapper.text()).toContain('正在核对 Maven 模块')
    expect(wrapper.text()).toContain('321')
    await wrapper.findAll('button').find((button) => button.text().includes('停止生成'))!.trigger('click')
    await flushPromises()
    expect(cancel).toHaveBeenCalledWith('project-1', 'draft-1')
  })
})

describe('Projects management', () => {
  it('shows the persisted project stack summary without triggering a filesystem refresh', () => {
    const store = useTaskStore()
    store.projects = [{
      id: 'project-1', name: 'Example', rootPath: '/tmp/example', status: 'READY', updatedAt: 'now',
      taskCount: 0, openDesignerSessionCount: 0, stackProfileState: 'READY',
      stackTechnologyFamilies: ['java'], stackComponentCount: 2, stackAnalyzedAt: 'now',
    }]
    const stackProfile = vi.spyOn(api, 'getProjectStackProfile')

    const wrapper = mount(ProjectsView, {
      global: { plugins: [ElementPlus], stubs: { teleport: true, PageHeader: { template: '<header><slot /><slot name="actions" /></header>' }, StatusBadge: true, Icon: true } },
    })

    expect(wrapper.text()).toContain('Java')
    expect(wrapper.text()).toContain('2 个组件')
    expect(stackProfile).not.toHaveBeenCalled()
  })

  it('keeps Task count separate and opens the persistent Designer recovery list', async () => {
    const store = useTaskStore()
    store.projects = [{
      id: 'project-1', name: 'Example', rootPath: '/tmp/example', status: 'READY', updatedAt: 'now',
      taskCount: 0, openDesignerSessionCount: 1,
    }]
    const wrapper = mount(ProjectsView, {
      global: { plugins: [ElementPlus], stubs: { teleport: true, PageHeader: { template: '<header><slot /><slot name="actions" /></header>' }, StatusBadge: true, Icon: true } },
    })

    expect(wrapper.text()).toContain('0 个任务 · 1 个待继续设计')
    await wrapper.get('button[aria-label="继续项目设计"]').trigger('click')

    expect(routerPush).toHaveBeenCalledWith({ path: '/designs', query: { projectId: 'project-1' } })
  })

  it('shows the real Git task-branch or direct execution mode', () => {
    const store = useTaskStore()
    store.projects = [
      { id: 'git-project', name: 'Git project', rootPath: '/tmp/git', description: 'Isolated changes', status: 'READY', executionMode: 'WORKTREE', branch: 'main', updatedAt: 'now', taskCount: 2, openDesignerSessionCount: 0 },
      { id: 'plain-project', name: 'Plain project', rootPath: '/tmp/plain', status: 'NEEDS_GIT', executionMode: 'DIRECT', updatedAt: 'now', taskCount: 1, openDesignerSessionCount: 0 },
    ]

    const wrapper = mount(ProjectsView, {
      global: { plugins: [ElementPlus], stubs: { teleport: true, PageHeader: { template: '<header><slot /><slot name="actions" /></header>' }, Icon: true } },
    })

    expect(wrapper.text()).toContain('Git 分支模式')
    expect(wrapper.text()).toContain('main')
    expect(wrapper.text()).toContain('Isolated changes')
    expect(wrapper.text()).toContain('直接模式')
    expect(wrapper.text()).toContain('原项目目录')
    expect(wrapper.text()).not.toContain('no git head')
  })

  it('cancels management without deleting the project history from the UI contract', async () => {
    const store = useTaskStore()
    store.projects = [{ id: 'project-1', name: 'Example', rootPath: '/tmp/example', status: 'READY', updatedAt: '2026-08-05T00:00:00Z', taskCount: 4, openDesignerSessionCount: 0 }]
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const cancel = vi.spyOn(api, 'cancelProjectManagement').mockResolvedValue(undefined)
    const wrapper = mount(ProjectsView, {
      global: { plugins: [ElementPlus], stubs: { teleport: true, PageHeader: { template: '<header><slot /><slot name="actions" /></header>' }, StatusBadge: true, Icon: true } },
    })

    await wrapper.get('button[aria-label="取消管理该项目"]').trigger('click')
    await flushPromises()

    expect(cancel).toHaveBeenCalledWith('project-1')
    expect(store.projects).toHaveLength(0)
  })
})
