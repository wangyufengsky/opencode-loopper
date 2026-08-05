import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DesignerView from '@/views/DesignerView.vue'
import LoopSpecEditor from '@/components/LoopSpecEditor.vue'
import { api } from '@/api/client'
import { useTaskStore } from '@/stores/taskStore'
import type { DesignerSession, LoopDraft, LoopSpec, Project, Task } from '@/types/domain'

const { routerPush } = vi.hoisted(() => ({ routerPush: vi.fn() }))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn(), useRouter: () => ({ push: routerPush }) }))

const project: Project = {
  id: 'project-1',
  name: 'Loopper',
  rootPath: '/tmp/loopper',
  status: 'READY',
  updatedAt: 'now',
  taskCount: 0,
}

const session: DesignerSession = {
  id: 'designer-1',
  projectId: project.id,
  projectName: project.name,
  state: 'COMPLETED',
  accessMode: 'READ_ONLY',
  readOnly: true,
  messages: [],
}

function draftFrom(spec: LoopSpec): LoopDraft {
  return { id: 'draft-1', status: 'DRAFT_READY', updatedAt: 'now', spec }
}

function mountDesigner(): VueWrapper {
  return mount(DesignerView, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        PageHeader: { template: '<header><slot /><slot name="actions" /></header>' },
        StatusBadge: true,
        LayeredErrorPanel: true,
        LoopSpecEditor: true,
        Icon: true,
      },
    },
  })
}

beforeEach(() => {
  routerPush.mockReset()
  sessionStorage.clear()
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useTaskStore()
  store.usingDemo = false
  store.projects = [project]
})

afterEach(() => {
  vi.restoreAllMocks()
  sessionStorage.clear()
})

describe('Designer draft composer', () => {
  it('restores the initial goal and submits it as both the session message and LoopSpec goal', async () => {
    let wrapper = mountDesigner()
    await flushPromises()

    const initialGoal = 'Task 错误退出任务；Session 错误保留上下文并继续 loop。'
    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue(initialGoal)
    expect(sessionStorage.getItem('opencode-loopper.designer-draft-prompt')).toBe(initialGoal)

    wrapper.unmount()
    wrapper = mountDesigner()
    await flushPromises()
    expect((wrapper.get('textarea[aria-label="草案设计目标"]').element as HTMLTextAreaElement).value).toBe(initialGoal)

    const createSession = vi.spyOn(api, 'createDesignerSession').mockResolvedValue(session)
    const createDraft = vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    expect(createSession).toHaveBeenCalledWith(project.id, 'draft-1', initialGoal)
    expect(createDraft.mock.calls[0]?.[0].goal).toBe(initialGoal)
    expect(createDraft.mock.calls[0]?.[0].stages[0]).toMatchObject({ allowedPaths: [], forbiddenPaths: [], verifiers: [] })
    expect(sessionStorage.getItem('opencode-loopper.designer-draft-prompt')).toBeNull()
    expect(wrapper.find('textarea[aria-label="发送给只读 OpenCode Designer 的消息"]').exists()).toBe(true)
  })

  it('keeps an unsent follow-up after a request failure and clears it only after persistence succeeds', async () => {
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(session)
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    const sendMessage = vi.spyOn(api, 'sendDesignerMessage').mockRejectedValueOnce(new Error('network unavailable'))
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('创建可靠的执行计划')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    const followUp = '补充验收：前端测试和 Maven clean verify 必须通过。'
    const messageInput = wrapper.get('textarea[aria-label="发送给只读 OpenCode Designer 的消息"]')
    await messageInput.setValue(followUp)
    await wrapper.get('.compose-actions button').trigger('click')
    await flushPromises()

    expect((messageInput.element as HTMLTextAreaElement).value).toBe(followUp)
    expect(sessionStorage.getItem('opencode-loopper.designer-message-draft')).toBe(followUp)

    sendMessage.mockResolvedValueOnce({ sessionId: session.id, state: 'COMPLETED', persistedMessages: [], notice: 'saved' })
    await wrapper.get('.compose-actions button').trigger('click')
    await flushPromises()

    expect((messageInput.element as HTMLTextAreaElement).value).toBe('')
    expect(sessionStorage.getItem('opencode-loopper.designer-message-draft')).toBeNull()
  })

  it('shows an animated thinking state only while the Designer request is running', async () => {
    const runningSession: DesignerSession = { ...session, state: 'RUNNING' }
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(runningSession)
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    vi.spyOn(api, 'getDesignerSession').mockImplementation(() => new Promise(() => {}))
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('创建可靠的执行计划')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    const thinking = wrapper.get('[aria-label="Agent 正在思考，等待 AI 回复"]')
    expect(thinking.text()).toContain('Agent 正在思考')
    expect(thinking.text()).toContain('正在读取项目上下文并组织设计文档')

    wrapper.unmount()
  })

  it('replaces the right-side LoopSpec when the completed Designer session returns its bound draft', async () => {
    vi.useFakeTimers()
    try {
      const runningSession: DesignerSession = { ...session, state: 'RUNNING' }
      const generatedSpec: LoopSpec = {
        schemaVersion: 'v1', projectId: project.id,
        goal: '帮我实现一个可以精确算出圆周率小数点后10万位的java代码',
        context: '使用 BigDecimal 与可验证的高精度算法。',
        stages: [{ objective: '实现并验证 100000 位圆周率计算', allowedPaths: ['src/**'], forbiddenPaths: ['data/**'], deliverables: ['Java 实现'], verifiers: [{ type: 'GIT_DIFF', requireChanges: true }] }],
        limits: { maxStageAttempts: 3, maxTaskAttempts: 12, maxDuration: '7200', attemptTimeout: '1800' },
      }
      const synchronizedDraft = { ...draftFrom(generatedSpec), updatedAt: 'later' }
      const completedSession: DesignerSession = { ...session, state: 'COMPLETED', draft: synchronizedDraft }
      vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
      vi.spyOn(api, 'createDesignerSession').mockResolvedValue(runningSession)
      vi.spyOn(api, 'getDesignerSession').mockResolvedValue(completedSession)
      const wrapper = mountDesigner()
      await flushPromises()

      await wrapper.get('textarea[aria-label="草案设计目标"]').setValue(generatedSpec.goal)
      await wrapper.get('.create-draft-button').trigger('click')
      await flushPromises()
      await vi.runOnlyPendingTimersAsync()
      await flushPromises()

      expect(wrapper.findComponent(LoopSpecEditor).props('modelValue')).toContain('实现并验证 100000 位圆周率计算')
      expect(wrapper.text()).toContain('1 个阶段')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('hides informational handoff notices but keeps pending runtime errors visible', async () => {
    const sessionWithNotices: DesignerSession = {
      ...session,
      messages: [
        { id: 'system-created', role: 'SYSTEM', content: 'Designer session created in read-only mode.', deliveryState: 'PENDING_HANDOFF', createdAt: 'now' },
        { id: 'system-waiting', role: 'SYSTEM', content: 'Message was handed to the read-only OpenCode Designer. Waiting to persist the actual assistant response.', deliveryState: 'PENDING_HANDOFF', createdAt: 'now' },
        { id: 'system-error', role: 'SYSTEM', content: 'SYSTEM_ERROR[SESSION]: Runtime is unavailable.', deliveryState: 'PENDING_HANDOFF', createdAt: 'now' },
      ],
    }
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(sessionWithNotices)
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('创建可靠的执行计划')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('Designer session created in read-only mode.')
    expect(wrapper.text()).not.toContain('Message was handed to the read-only OpenCode Designer.')
    expect(wrapper.text()).toContain('SYSTEM_ERROR[SESSION]: Runtime is unavailable.')
  })

  it('clears the restored workspace and local message drafts when starting over', async () => {
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(session)
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    const confirmation = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('这是需要清理的旧设计')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()
    await wrapper.get('textarea[aria-label="发送给只读 OpenCode Designer 的消息"]').setValue('未发送的旧补充')

    expect(sessionStorage.getItem('opencode-loopper.designer-workspace')).toContain(session.id)
    expect(sessionStorage.getItem('opencode-loopper.designer-message-draft')).toBe('未发送的旧补充')

    await wrapper.get('.restart-designer-button').trigger('click')
    await flushPromises()

    expect(confirmation).toHaveBeenCalled()
    expect(sessionStorage.getItem('opencode-loopper.designer-workspace')).toBeNull()
    expect(sessionStorage.getItem('opencode-loopper.designer-draft-prompt')).toBeNull()
    expect(sessionStorage.getItem('opencode-loopper.designer-message-draft')).toBeNull()
    expect(wrapper.find('textarea[aria-label="发送给只读 OpenCode Designer 的消息"]').exists()).toBe(false)
    expect(wrapper.find('textarea[aria-label="草案设计目标"]').exists()).toBe(true)
  })

  it('loads the confirmed Task into the store and opens its detail even when worktree preparation failed', async () => {
    const loopSpec: LoopSpec = {
      schemaVersion: 'v1', projectId: project.id, goal: '交接到任务控制台', context: '只在隔离 worktree 修改。',
      stages: [{ objective: '实现功能', allowedPaths: ['src/**'], forbiddenPaths: [], deliverables: ['实现'], verifiers: [{ type: 'GIT_DIFF', requireChanges: true }] }],
      limits: { maxStageAttempts: 3, maxTaskAttempts: 12, maxDuration: '7200', attemptTimeout: '1800' },
    }
    const readyDraft = draftFrom(loopSpec)
    const confirmedDraft: LoopDraft = { ...readyDraft, status: 'CONFIRMED', updatedAt: 'confirmed' }
    const failedTask: Task = {
      id: 'task-1', projectId: project.id, projectName: project.name, title: loopSpec.goal, goal: loopSpec.goal,
      branch: '', worktreePath: '', status: 'FAILED', attemptCount: 0, maxAttempts: 12, createdAt: 'now', updatedAt: 'now',
      errors: [{ id: 'error-1', layer: 'TASK', code: 'GIT_HEAD_UNAVAILABLE', message: "Cannot resolve the project's Git HEAD", retryable: false, occurredAt: 'now' }],
    }
    vi.spyOn(api, 'createDraft').mockResolvedValue(readyDraft)
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue({ ...session, draft: readyDraft })
    vi.spyOn(api, 'updateDraft').mockResolvedValue(readyDraft)
    vi.spyOn(api, 'confirmDraft').mockResolvedValue({ taskId: failedTask.id })
    vi.spyOn(api, 'getDraft').mockResolvedValue(confirmedDraft)
    vi.spyOn(api, 'getTask').mockResolvedValue(failedTask)
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue(loopSpec.goal)
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()
    const confirmButton = wrapper.findAll('button').find((button) => button.text().includes('确认并交接'))
    expect(confirmButton).toBeDefined()
    await confirmButton!.trigger('click')
    await flushPromises()

    expect(api.confirmDraft).toHaveBeenCalledWith(readyDraft.id)
    expect(useTaskStore().tasks).toContainEqual(failedTask)
    expect(routerPush).toHaveBeenCalledWith(`/tasks/${failedTask.id}`)
  })

  it('reopens an already confirmed draft idempotently without trying to modify the immutable LoopSpec', async () => {
    const loopSpec: LoopSpec = {
      schemaVersion: 'v1', projectId: project.id, goal: '重新打开已确认交接', context: '',
      stages: [{ objective: '实现功能', allowedPaths: ['src/**'], forbiddenPaths: [], deliverables: ['实现'], verifiers: [{ type: 'GIT_DIFF', requireChanges: true }] }],
      limits: { maxStageAttempts: 3, maxTaskAttempts: 12, maxDuration: '7200', attemptTimeout: '1800' },
    }
    const readyDraft = draftFrom(loopSpec)
    const confirmedDraft: LoopDraft = { ...readyDraft, status: 'CONFIRMED' }
    const task: Task = {
      id: 'task-existing', projectId: project.id, projectName: project.name, title: loopSpec.goal, goal: loopSpec.goal,
      branch: 'loopper/task-existing', worktreePath: '/tmp/task-existing', status: 'READY', attemptCount: 0, maxAttempts: 12,
      createdAt: 'now', updatedAt: 'now', errors: [],
    }
    vi.spyOn(api, 'createDraft').mockResolvedValue(readyDraft)
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue({ ...session, draft: confirmedDraft })
    const updateDraft = vi.spyOn(api, 'updateDraft')
    vi.spyOn(api, 'confirmDraft').mockResolvedValue({ taskId: task.id })
    vi.spyOn(api, 'getDraft').mockResolvedValue(confirmedDraft)
    vi.spyOn(api, 'getTask').mockResolvedValue(task)
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue(loopSpec.goal)
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()
    const confirmButton = wrapper.findAll('button').find((button) => button.text().includes('确认并交接'))
    await confirmButton!.trigger('click')
    await flushPromises()

    expect(updateDraft).not.toHaveBeenCalled()
    expect(api.confirmDraft).toHaveBeenCalledWith(confirmedDraft.id)
    expect(routerPush).toHaveBeenCalledWith(`/tasks/${task.id}`)
  })
})
