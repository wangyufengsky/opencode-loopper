import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DesignerView from '@/views/DesignerView.vue'
import LoopSpecEditor from '@/components/LoopSpecEditor.vue'
import PendingQuestionCard from '@/components/PendingQuestionCard.vue'
import { api } from '@/api/client'
import { useTaskStore } from '@/stores/taskStore'
import type { AppSettings, DesignerSession, LoopDraft, LoopSpec, Project, Task } from '@/types/domain'

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

const settings: AppSettings = {
  cliPath: 'opencode', allowedRoot: '/tmp', provider: 'openai', model: 'gpt-5',
  maxTaskAttempts: 7, timeoutMinutes: 45, autoApprove: false,
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
  vi.spyOn(api, 'getSettings').mockResolvedValue(settings)
  vi.spyOn(api, 'validateDraft').mockImplementation(async (spec) => ({
    valid: true, schemaVersion: spec.schemaVersion, legacy: spec.schemaVersion === 'v1', errors: [], stageAssessments: [],
  }))
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

describe('Designer draft composer', () => {
  it('starts a structured brief from a quick template and persists it locally', async () => {
    const wrapper = mountDesigner()
    await flushPromises()

    const repairTemplate = wrapper.findAll('.brief-template-row button').find((button) => button.text().includes('修复问题'))
    expect(repairTemplate).toBeDefined()
    await repairTemplate!.trigger('click')

    const value = (wrapper.get('textarea[aria-label="草案设计目标"]').element as HTMLTextAreaElement).value
    expect(value).toContain('当前现象：')
    expect(value).toContain('验收方式：')
    expect(sessionStorage.getItem('opencode-loopper.designer-draft-prompt')).toBe(value)
  })

  it('asks before a quick template overwrites the current draft', async () => {
    const confirmation = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = mountDesigner()
    await flushPromises()
    const input = wrapper.get('textarea[aria-label="草案设计目标"]')
    await input.setValue('保留这段现有草稿')
    const repairTemplate = wrapper.findAll('.brief-template-row button').find((button) => button.text().includes('修复问题'))!

    await repairTemplate.trigger('click')
    await flushPromises()

    expect(confirmation).toHaveBeenCalled()
    expect((input.element as HTMLTextAreaElement).value).toBe('保留这段现有草稿')
  })

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
    expect(createDraft.mock.calls[0]?.[0].schemaVersion).toBe('v2')
    expect(createDraft.mock.calls[0]?.[0].stages[0]).toMatchObject({ allowedPaths: [], forbiddenPaths: [], verifiers: [] })
    expect(createDraft.mock.calls[0]?.[0].limits).toMatchObject({ maxTaskAttempts: 7, attemptTimeout: 'PT45M' })
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

  it('keeps the reply composer immediately after the naturally growing message history', async () => {
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue({
      ...session,
      messages: [{ id: 'assistant-1', role: 'ASSISTANT', content: '# 很长的设计回复\n\n正文', deliveryState: 'PERSISTED', createdAt: 'now' }],
    })
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('检查回复框布局')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    const conversation = wrapper.get('.designer-conversation')
    expect(conversation.element.children).toHaveLength(2)
    expect(conversation.element.children[0]?.classList.contains('chat-history')).toBe(true)
    expect(conversation.element.children[1]?.classList.contains('chat-compose')).toBe(true)
    expect(wrapper.get('textarea[aria-label="发送给只读 OpenCode Designer 的消息"]').attributes('rows')).toBe('10')
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
    expect(thinking.text()).toContain('连接暂时中断，正在恢复并继续等待真实回复')

    wrapper.unmount()
  })

  it('renders streamed Designer Markdown and live connection state before completion', async () => {
    class FakeEventSource {
      static latest?: FakeEventSource
      onopen?: () => void
      onmessage?: (message: MessageEvent<string>) => void
      onerror?: () => void
      constructor(readonly url: string) { FakeEventSource.latest = this }
      close = vi.fn()
    }
    vi.stubGlobal('EventSource', FakeEventSource)
    const runningSession: DesignerSession = { ...session, state: 'RUNNING' }
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(runningSession)
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    vi.spyOn(api, 'getDesignerSession').mockImplementation(() => new Promise(() => {}))
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('创建可靠的执行计划')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()
    FakeEventSource.latest?.onopen?.()
    FakeEventSource.latest?.onmessage?.({ data: JSON.stringify({
      sequence: 2, sessionId: runningSession.id, type: 'PARTIAL', state: 'RUNNING', remoteState: 'busy',
      runtimeConnected: true, content: '## 第一段回复\n\n正在分析项目。', detail: '正在接收模型回复', at: '2026-08-05T01:00:00Z',
    }) } as MessageEvent<string>)
    await flushPromises()

    expect(wrapper.get('.designer-connection-strip').text()).toContain('实时通道已连接')
    expect(wrapper.get('.designer-connection-strip').text()).toContain('OpenCode 已连接')
    expect(wrapper.get('.chat-live').text()).toContain('第一段回复')
    expect(wrapper.find('[aria-label="Agent 正在思考，等待 AI 回复"]').exists()).toBe(false)

    FakeEventSource.latest?.onmessage?.({ data: JSON.stringify({
      sequence: 3, sessionId: runningSession.id, type: 'STATUS', state: 'RUNNING', remoteState: 'REPAIRING_LOOPSPEC_1',
      runtimeConnected: true, content: '', detail: 'LoopSpec 校验失败，正在自动纠正；不会生成代码或创建 Task。', at: '2026-08-05T01:00:01Z',
    }) } as MessageEvent<string>)
    await flushPromises()

    expect(wrapper.find('.chat-live').exists()).toBe(false)
    expect(wrapper.get('[aria-label="正在自动纠正 LoopSpec"]').text()).toContain('不会生成代码或创建 Task')
    wrapper.unmount()
  })

  it('renders pending Designer questions and submits their answers before continuing', async () => {
    let answered = false
    const pendingQuestion = {
      id: 'question-1', questions: [{
        question: '选择实现范围', header: '范围', multiple: false, custom: false,
        options: [{ label: '新增链路', description: '创建新的业务责任链' }],
      }],
    }
    const runningSession: DesignerSession = { ...session, state: 'RUNNING', pendingQuestions: [pendingQuestion] }
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(runningSession)
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    vi.spyOn(api, 'getDesignerSession').mockImplementation(async () => ({
      ...runningSession, pendingQuestions: answered ? [] : [pendingQuestion],
    }))
    const reply = vi.spyOn(api, 'replyDesignerQuestion').mockImplementation(async () => { answered = true })
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('创建新的责任链')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    const question = wrapper.getComponent(PendingQuestionCard)
    expect(question.text()).toContain('选择实现范围')
    expect(wrapper.find('[aria-label="Agent 正在思考，等待 AI 回复"]').exists()).toBe(false)
    question.vm.$emit('submit', [['新增链路']])
    await flushPromises()

    expect(reply).toHaveBeenCalledWith(runningSession.id, pendingQuestion.id, [['新增链路']])
    expect(wrapper.findComponent(PendingQuestionCard).exists()).toBe(false)
    wrapper.unmount()
  })

  it('replaces the right-side LoopSpec when the completed Designer session returns its bound draft', async () => {
    vi.useFakeTimers()
    try {
      const runningSession: DesignerSession = { ...session, state: 'RUNNING' }
      const generatedSpec: LoopSpec = {
        schemaVersion: 'v2', projectId: project.id,
        goal: '帮我实现一个可以精确算出圆周率小数点后10万位的java代码',
        context: '使用 BigDecimal 与可验证的高精度算法。',
        stages: [{ objective: '实现并验证 100000 位圆周率计算', allowedPaths: ['src/**'], forbiddenPaths: ['data/**'], deliverables: ['Java 实现'], acceptanceCriteria: [{ id: 'AC-1', description: '测试验证 100000 位输出' }], verifiers: [{ type: 'PROCESS', command: ['mvn', 'test', '-Dtest=PiTest'], processPurpose: 'TEST', testTargets: ['PiTest'], criterionIds: ['AC-1'] }] }],
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
      schemaVersion: 'v2', projectId: project.id, goal: '交接到任务控制台', context: '只在登记项目目录的任务分支修改。',
      stages: [{ objective: '实现功能', allowedPaths: ['src/**'], forbiddenPaths: [], deliverables: ['实现'], acceptanceCriteria: [{ id: 'AC-1', description: '聚焦测试通过' }], verifiers: [{ type: 'PROCESS', command: ['mvn', 'test', '-Dtest=FeatureTest'], processPurpose: 'TEST', testTargets: ['FeatureTest'], criterionIds: ['AC-1'] }] }],
      limits: { maxStageAttempts: 3, maxTaskAttempts: 12, sessionErrorLimit: 4, stagnationLimit: 5, maxDuration: '7200', attemptTimeout: '1800', verifierTimeout: '420' },
      model: { providerId: 'provider-1', modelId: 'model-1', thinking: false },
      sessionPolicy: { reuseHealthySession: false, createFreshOnVerifierFailure: false },
      nextAttemptPromptTemplate: '人工继续时处理 ${failureSummary}',
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
    expect(api.updateDraft).toHaveBeenCalledWith(readyDraft.id, expect.objectContaining({
      limits: expect.objectContaining({ sessionErrorLimit: 4, stagnationLimit: 5, verifierTimeout: '420' }),
      model: { providerId: 'provider-1', modelId: 'model-1', thinking: false },
      sessionPolicy: { reuseHealthySession: false, createFreshOnVerifierFailure: false },
      nextAttemptPromptTemplate: '人工继续时处理 ${failureSummary}',
    }))
    expect(useTaskStore().tasks).toContainEqual(failedTask)
    expect(routerPush).toHaveBeenCalledWith(`/tasks/${failedTask.id}`)
  })

  it('renders the coverage matrix and blocks save when a v2 criterion is uncovered', async () => {
    const invalidSpec: LoopSpec = {
      schemaVersion: 'v2', projectId: project.id, goal: '严格验收', context: '',
      stages: [{ objective: '实现', allowedPaths: [], forbiddenPaths: [], deliverables: ['实现'],
        acceptanceCriteria: [{ id: 'AC-1', description: '用户能观察到结果' }],
        verifiers: [{ type: 'PROCESS', command: ['mvn', 'package'], processPurpose: 'BUILD', criterionIds: ['AC-1'] }] }],
      limits: { maxStageAttempts: 3, maxTaskAttempts: 12, maxDuration: '7200', attemptTimeout: '1800' },
    }
    const readyDraft = draftFrom(invalidSpec)
    vi.spyOn(api, 'createDraft').mockResolvedValue(readyDraft)
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue({ ...session, draft: readyDraft })
    vi.mocked(api.validateDraft).mockResolvedValueOnce({
      valid: false, schemaVersion: 'v2', legacy: false,
      errors: ['stages[0].acceptanceCriteria[AC-1]: no valid BEHAVIOR verifier covers this criterion'],
      stageAssessments: [{ stageIndex: 0,
        criteria: [{ id: 'AC-1', description: '用户能观察到结果', covered: false, verifierIndexes: [] }],
        verifiers: [{ index: 0, type: 'PROCESS', category: 'BUILD', blocking: true, criterionIds: ['AC-1'], reason: 'compile/build/static-quality command' }],
      }],
    })
    const update = vi.spyOn(api, 'updateDraft')
    const wrapper = mountDesigner()
    await flushPromises()
    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue(invalidSpec.goal)
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    const save = wrapper.findAll('button').find((button) => button.text().includes('保存'))!
    await save.trigger('click')
    await flushPromises()

    expect(update).not.toHaveBeenCalled()
    expect(wrapper.get('[aria-label="验收条件覆盖矩阵"]').text()).toContain('未覆盖')
    expect(wrapper.get('[aria-label="验收条件覆盖矩阵"]').text()).toContain('BUILD')
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
