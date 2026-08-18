import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DesignerView from '@/views/DesignerView.vue'
import LoopSpecEditor from '@/components/LoopSpecEditor.vue'
import PendingQuestionCard from '@/components/PendingQuestionCard.vue'
import DesignerDiscussionHistory from '@/components/DesignerDiscussionHistory.vue'
import { api } from '@/api/client'
import { useTaskStore } from '@/stores/taskStore'
import type { AppSettings, DesignerSession, LoopDraft, LoopSpec, Project, Task } from '@/types/domain'

const { routerPush, routeQuery } = vi.hoisted(() => ({ routerPush: vi.fn(), routeQuery: {} as Record<string, string> }))
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: vi.fn(),
  useRoute: () => ({ query: routeQuery }),
  useRouter: () => ({ push: routerPush }),
}))

const project: Project = {
  id: 'project-1',
  name: 'Loopper',
  rootPath: '/tmp/loopper',
  status: 'READY',
  updatedAt: 'now',
  taskCount: 0,
  openDesignerSessionCount: 0,
}

const session: DesignerSession = {
  id: 'designer-1',
  projectId: project.id,
  projectName: project.name,
  state: 'COMPLETED',
  workflowPhase: 'COMPLETED',
  activeActor: 'SYSTEM',
  accessMode: 'READ_ONLY',
  readOnly: true,
  discussionScope: 'FINAL',
  discussionRevision: 1,
  finalConfirmationEligible: true,
  messages: [],
}

const settings: AppSettings = {
  runtime: { serverPort: 8080, openBrowser: true, allowedRoot: '/tmp', monitorDelaySeconds: 2, designerMonitorDelayMillis: 750, abortCleanupAttempts: 3 },
  openCode: { cliPath: 'opencode', mode: 'auto', baseUrl: 'http://127.0.0.1:4096', provider: 'openai', model: 'gpt-5', connectTimeoutSeconds: 5, requestTimeoutSeconds: 30, startupTimeoutSeconds: 15 },
  limits: { maxStageAttempts: 3, maxTaskAttempts: 7, sessionErrorLimit: 3, maxDurationMinutes: 120, attemptTimeoutMinutes: 45, verifierTimeoutMinutes: 10, designerTimeoutMinutes: 30 },
  retryWait: { rateLimitBaseSeconds: 60, rateLimitMaxSeconds: 300, sessionBaseSeconds: 10, sessionMaxSeconds: 60, verificationBaseSeconds: 5, verificationMaxSeconds: 30 },
  publication: { httpWebHosts: ['gitlab.spdb.com'], gitlabHost: 'gitlab.spdb.com', gitlabApiBaseUrl: 'http://gitlab.spdb.com/api/v4', connectTimeoutSeconds: 3, requestTimeoutSeconds: 10 },
  appliedLiveFields: [], restartRequiredFields: [],
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
  for (const key of Object.keys(routeQuery)) delete routeQuery[key]
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
  it('keeps the new-design page focused and restores a history session from an explicit route', async () => {
    const recoverableDraft = draftFrom({
      schemaVersion: 'v2', projectId: project.id, goal: '恢复重启前的设计', context: '', stages: [],
      limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: '7200', attemptTimeout: '1800' },
    })
    routeQuery.sessionId = 'designer-recover'
    routeQuery.projectId = project.id
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue({
      ...session, id: 'designer-recover', state: 'WAITING_INPUT', workflowPhase: 'FAILED', draft: recoverableDraft,
    })
    const wrapper = mountDesigner()
    await flushPromises()

    expect(sessionStorage.getItem('opencode-loopper.designer-workspace')).toContain('designer-recover')
    expect(wrapper.find('[aria-label="待继续设计"]').exists()).toBe(false)
    expect(wrapper.find('textarea[aria-label="发送给只读 OpenCode Designer 的消息"]').exists()).toBe(true)
  })

  it('keeps the recovery pointer when the backend is temporarily unavailable after restart', async () => {
    sessionStorage.setItem('opencode-loopper.designer-workspace', JSON.stringify({ sessionId: 'designer-recover', draftId: 'draft-1' }))
    vi.spyOn(api, 'getDesignerSession').mockRejectedValue(new Error('服务正在重启'))
    vi.spyOn(api, 'getDraft').mockResolvedValue(draftFrom({
      schemaVersion: 'v2', projectId: project.id, goal: '不会被清除', context: '', stages: [],
      limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: '7200', attemptTimeout: '1800' },
    }))
    const wrapper = mountDesigner()
    await flushPromises()

    expect(sessionStorage.getItem('opencode-loopper.designer-workspace')).toContain('designer-recover')
    expect(wrapper.text()).toContain('上次设计暂时无法恢复：服务正在重启')
    expect(wrapper.text()).toContain('服务端记录不会因短暂断线被删除')
  })

  it('does not auto-open an archived design from a stale browser recovery pointer', async () => {
    const recoverableDraft = draftFrom({
      schemaVersion: 'v2', projectId: project.id, goal: '已经归档的设计', context: '', stages: [],
      limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: '7200', attemptTimeout: '1800' },
    })
    sessionStorage.setItem('opencode-loopper.designer-workspace', JSON.stringify({ sessionId: 'designer-archived', draftId: 'draft-1' }))
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue({ ...session, id: 'designer-archived', archived: true, draft: recoverableDraft })
    vi.spyOn(api, 'getDraft').mockResolvedValue(recoverableDraft)

    const wrapper = mountDesigner()
    await flushPromises()

    expect(sessionStorage.getItem('opencode-loopper.designer-workspace')).toBeNull()
    expect(wrapper.find('textarea[aria-label="发送给只读 OpenCode Designer 的消息"]').exists()).toBe(false)
    expect(wrapper.find('textarea[aria-label="草案设计目标"]').exists()).toBe(true)
  })

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
    const discussionSession: DesignerSession = {
      ...session, state: 'REVIEWING', workflowPhase: 'DISCUSSING_REQUIREMENT',
      discussionScope: 'REQUIREMENT', finalConfirmationEligible: false,
    }
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(discussionSession)
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    const sendMessage = vi.spyOn(api, 'sendRequirementMessage').mockRejectedValueOnce(new Error('network unavailable'))
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
      messages: [{ id: 'assistant-1', role: 'ASSISTANT', actor: 'DESIGNER', content: '# 很长的设计回复\n\n正文', deliveryState: 'PERSISTED', createdAt: 'now' }],
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
    const runningSession: DesignerSession = { ...session, state: 'RUNNING', workflowPhase: 'DESIGNING', activeActor: 'DESIGNER' }
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(runningSession)
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    vi.spyOn(api, 'getDesignerSession').mockImplementation(() => new Promise(() => {}))
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('创建可靠的执行计划')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    const thinking = wrapper.get('[aria-label="Designer / 设计师正在处理"]')
    expect(thinking.text()).toContain('Designer / 设计师正在设计中')
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
    const runningSession: DesignerSession = { ...session, state: 'RUNNING', workflowPhase: 'DESIGNING', activeActor: 'DESIGNER' }
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
      sequence: 2, sessionId: runningSession.id, type: 'PARTIAL', state: 'RUNNING', workflowPhase: 'DESIGNING', activeActor: 'DESIGNER', remoteState: 'busy',
      runtimeConnected: true, content: '## 第一段回复\n\n正在分析项目。', detail: '正在接收模型回复', at: '2026-08-05T01:00:00Z',
    }) } as MessageEvent<string>)
    await flushPromises()

    expect(wrapper.get('.designer-connection-strip').text()).toContain('实时通道已连接')
    expect(wrapper.get('.designer-connection-strip').text()).toContain('OpenCode 已连接')
    expect(wrapper.get('.chat-live').text()).toContain('第一段回复')
    expect(wrapper.find('[aria-label="Designer / 设计师正在处理"]').exists()).toBe(false)

    FakeEventSource.latest?.onmessage?.({ data: JSON.stringify({
      sequence: 3, sessionId: runningSession.id, type: 'STATUS', state: 'RUNNING', workflowPhase: 'COMPILING', activeActor: 'COMPILER', remoteState: 'REPAIRING_1',
      runtimeConnected: true, content: '<!-- LOOPSPEC_COMPILATION_JSON_START -->raw-json', detail: '规范编译器正在进行第 1/2 次修复', at: '2026-08-05T01:00:01Z', structuredStep: 'REPAIRING_JSON',
    }) } as MessageEvent<string>)
    await flushPromises()

    expect(wrapper.find('.chat-live').exists()).toBe(false)
    expect(wrapper.get('[aria-label="LoopSpec Compiler / 规范编译器正在处理"]').text()).toContain('规范编译器正在进行第 1/2 次修复')
    expect(wrapper.get('.designer-connection-strip').text()).toContain('编译中 · JSON 修复')
    expect(wrapper.text()).not.toContain('raw-json')
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
    const runningSession: DesignerSession = { ...session, state: 'RUNNING', workflowPhase: 'DESIGNING', activeActor: 'DESIGNER', pendingQuestions: [pendingQuestion] }
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(runningSession)
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    vi.spyOn(api, 'getDesignerSession').mockImplementation(async () => ({
      ...runningSession,
      pendingQuestions: answered ? [] : [pendingQuestion],
      answeredQuestions: answered ? [{
        id: pendingQuestion.id, scope: 'REQUIREMENT', discussionRevision: 1, answeredAt: '2026-08-18T04:00:00Z',
        questions: pendingQuestion.questions.map((prompt) => ({ ...prompt, answers: ['新增链路'] })),
      }] : [],
    }))
    const reply = vi.spyOn(api, 'replyDesignerQuestion').mockImplementation(async () => { answered = true })
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('创建新的责任链')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    const question = wrapper.getComponent(PendingQuestionCard)
    expect(question.text()).toContain('选择实现范围')
    expect(wrapper.find('[aria-label="Designer / 设计师正在处理"]').exists()).toBe(false)
    question.vm.$emit('submit', [['新增链路']])
    await flushPromises()

    expect(reply).toHaveBeenCalledWith(runningSession.id, pendingQuestion.id, [['新增链路']])
    expect(wrapper.findComponent(PendingQuestionCard).exists()).toBe(false)
    const history = wrapper.getComponent(DesignerDiscussionHistory)
    expect(history.get('details').attributes('open')).toBeUndefined()
    expect(history.get('summary').text()).toBe('需求讨论')
    expect(history.text()).toContain('选择实现范围')
    expect(history.text()).toContain('创建新的业务责任链')
    expect(history.text()).toContain('用户最终回答')
    expect(history.text()).toContain('新增链路')
    wrapper.unmount()
  })

  it('keeps package feedback scoped and requires explicit acceptance before continuing', async () => {
    const packageSession: DesignerSession = {
      ...session, state: 'REVIEWING', workflowPhase: 'REVIEWING_PACKAGE', activeActor: 'VALIDATOR',
      requirementRevision: 1, activeWorkPackageId: 'WP-1', discussionScope: 'WP-1', discussionRevision: 2,
      finalConfirmationEligible: false,
      workPackages: [{ id: 'WP-1', ordinal: 0, title: '查询能力', objective: '交付查询结果',
        dependencies: [], state: 'REVIEWING', redesignCount: 0, compilerRepairCount: 0,
        compilerPlanningRepairCount: 0, designRevision: 3, discussionRoundCount: 1 }],
      candidate: { syncState: 'SYNCED', discussionRevision: 2, workPackageId: 'WP-1', detail: '当前候选有效' },
    }
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(packageSession)
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue(packageSession)
    const sendPackage = vi.spyOn(api, 'sendWorkPackageMessage').mockResolvedValue({
      sessionId: packageSession.id, state: 'RUNNING', persistedMessages: [], notice: 'saved',
    })
    const approve = vi.spyOn(api, 'approveWorkPackage').mockResolvedValue(undefined)
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('逐包优化查询设计')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前作用域：WP-1')
    expect(wrapper.text()).toContain('已同步 R2')

    await wrapper.get('textarea[aria-label="发送给只读 OpenCode Designer 的消息"]').setValue('只补充 WP-1 的异常边界')
    await wrapper.get('.compose-actions button').trigger('click')
    await flushPromises()
    expect(sendPackage).toHaveBeenCalledWith(packageSession.id, 'WP-1', '只补充 WP-1 的异常边界', 2, 3)

    await wrapper.findAll('button').find((button) => button.text().includes('接受 WP-1 并继续'))!.trigger('click')
    await flushPromises()
    expect(approve).toHaveBeenCalledWith(packageSession.id, 'WP-1', 2, 3)
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
        stages: [{ objective: '实现并验证 100000 位圆周率计算', implementationKind: 'JAVA_PRODUCTION', allowedPaths: ['src/**'], forbiddenPaths: ['data/**'], deliverables: ['Java 实现'], acceptanceCriteria: [{ id: 'AC-1', description: '测试验证 100000 位输出' }], verifiers: [{ type: 'PROCESS', command: ['mvn', 'test', '-Dtest=PiTest'], processPurpose: 'TEST', testTargets: ['PiTest'], criterionIds: ['AC-1'] }] }],
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
        { id: 'system-created', role: 'SYSTEM', actor: 'SYSTEM', content: 'Designer session created in read-only mode.', deliveryState: 'PENDING_HANDOFF', createdAt: 'now' },
        { id: 'system-waiting', role: 'SYSTEM', actor: 'SYSTEM', content: 'Message was handed to the read-only OpenCode Designer. Waiting to persist the actual assistant response.', deliveryState: 'PENDING_HANDOFF', createdAt: 'now' },
        { id: 'system-error', role: 'SYSTEM', actor: 'SYSTEM', content: 'SYSTEM_ERROR[SESSION]: Runtime is unavailable.', deliveryState: 'PENDING_HANDOFF', createdAt: 'now' },
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

  it('restores distinct role cards, hides compiler JSON, and exposes both recovery actions', async () => {
    const failedSession: DesignerSession = {
      ...session,
      state: 'SESSION_ERROR', workflowPhase: 'FAILED', activeActor: 'SYSTEM',
      compiler: { id: 'compiler-1', state: 'SESSION_ERROR', externalSessionState: 'FAILED', repairCount: 2, planningRepairCount: 0, designRevision: 1, workflowStep: 'FINAL_JSON' },
      messages: [
        { id: 'user', role: 'USER', actor: 'USER', content: '请设计缓存刷新任务', deliveryState: 'PERSISTED', createdAt: 'now' },
        { id: 'designer', role: 'ASSISTANT', actor: 'DESIGNER', content: '# 完整设计稿', deliveryState: 'PERSISTED', createdAt: 'now' },
        { id: 'compiler', role: 'ASSISTANT', actor: 'COMPILER', content: '已编译 1 个阶段和 2 个验收项', deliveryState: 'COMPILED', createdAt: 'now' },
        { id: 'raw', role: 'ASSISTANT', actor: 'COMPILER', content: '<!-- LOOPSPEC_COMPILATION_JSON_START -->{"loopSpec":"secret"}', deliveryState: 'COMPILED', createdAt: 'now' },
        { id: 'retry', role: 'SYSTEM', actor: 'VALIDATOR', content: '字段校验失败，可修复', deliveryState: 'RETRYABLE_ERROR', createdAt: 'now' },
        { id: 'normalized', role: 'SYSTEM', actor: 'VALIDATOR', content: '输出包装已自动规范化', deliveryState: 'NORMALIZED', createdAt: 'now' },
        { id: 'terminal', role: 'SYSTEM', actor: 'VALIDATOR', content: '工作流已停止', deliveryState: 'TERMINAL_ERROR', createdAt: 'now' },
        { id: 'system', role: 'SYSTEM', actor: 'SYSTEM', content: '服务端状态通知', deliveryState: 'PERSISTED', createdAt: 'now' },
      ],
    }
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(failedSession)
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue(failedSession)
    const retry = vi.spyOn(api, 'retryDesignerCompiler').mockResolvedValue(undefined)
    const redesign = vi.spyOn(api, 'requestDesignerRedesign').mockResolvedValue(undefined)
    const wrapper = mountDesigner()
    await flushPromises()
    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('设计缓存刷新任务')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    expect(wrapper.get('.chat-user').text()).toContain('你')
    expect(wrapper.get('.chat-designer').text()).toContain('Designer / 设计师')
    expect(wrapper.get('.chat-compiler').text()).toContain('LoopSpec Compiler / 规范编译器')
    expect(wrapper.get('.chat-system').text()).toContain('系统')
    expect(wrapper.get('.validator-retryable_error').text()).toContain('Deterministic Validator / 确定性校验器')
    expect(wrapper.get('.validator-normalized').text()).toContain('输出包装已自动规范化')
    expect(wrapper.get('.validator-terminal_error').text()).toContain('工作流已停止')
    expect(wrapper.text()).not.toContain('"loopSpec":"secret"')
    expect(wrapper.get('.designer-connection-strip').text()).toContain('Compiler 格式修复 0/2 · 语义修复 0/2')

    await wrapper.findAll('button').find((button) => button.text().includes('重新编译当前设计'))!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text().includes('让 Designer 重新设计'))!.trigger('click')
    await flushPromises()
    expect(retry).toHaveBeenCalledWith(failedSession.id)
    expect(redesign).toHaveBeenCalledWith(failedSession.id)
  })

  it('restores the Decomposer card, package rail, retry counters, and waiting-input boundary', async () => {
    const decomposedSession: DesignerSession = {
      ...session,
      state: 'WAITING_INPUT', workflowPhase: 'FAILED', activeActor: 'VALIDATOR',
      finalConfirmationEligible: false,
      requirementRevision: 3, activeWorkPackageId: 'WP-2',
      requirement: { revision: 3, state: 'WAITING_INPUT', modelCallsUsed: 9, maxModelCalls: 32, sourceDraftVersion: 4 },
      decomposition: { id: 'decomposition-3', state: 'COMPLETED', resultType: 'DECOMPOSED', repairCount: 1, planningRepairCount: 1, transportRetryCount: 0, workflowStep: 'FINAL_JSON' },
      compiler: { id: 'compiler-wp2', state: 'SESSION_ERROR', externalSessionState: 'FAILED', repairCount: 2, planningRepairCount: 2, designRevision: 1, workPackageId: 'WP-2', workflowStep: 'FINAL_JSON' },
      workPackages: [
        { id: 'WP-1', ordinal: 0, title: '查询能力', objective: '可查询结果', dependencies: [], state: 'COMPLETED', redesignCount: 0, compilerRepairCount: 0, compilerPlanningRepairCount: 0, designRevision: 1, discussionRoundCount: 0 },
        { id: 'WP-2', ordinal: 1, title: '变更能力', objective: '可变更结果', dependencies: ['WP-1'], state: 'WAITING_INPUT', redesignCount: 1, compilerRepairCount: 2, compilerPlanningRepairCount: 2, designRevision: 1, discussionRoundCount: 0, lastErrorCode: 'COMPILER_RETRY_EXHAUSTED' },
      ],
      messages: [
        { id: 'decomposer', role: 'ASSISTANT', actor: 'DECOMPOSER', content: '拆解校验通过：形成 2 个工作包。', deliveryState: 'COMPILED', requirementRevision: 3, createdAt: 'now' },
        { id: 'raw', role: 'ASSISTANT', actor: 'COMPILER', content: '<!-- LOOPSPEC_COMPILATION_JSON_START -->{"stages":["secret"]}', deliveryState: 'COMPILED', requirementRevision: 3, workPackageId: 'WP-2', createdAt: 'now' },
        { id: 'failure', role: 'SYSTEM', actor: 'VALIDATOR', content: 'COMPILER_RETRY_EXHAUSTED：需要人工恢复', deliveryState: 'TERMINAL_ERROR', requirementRevision: 3, workPackageId: 'WP-2', createdAt: 'now' },
      ],
    }
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(decomposedSession)
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue(decomposedSession)
    const retry = vi.spyOn(api, 'retryWorkPackageCompiler').mockResolvedValue(undefined)
    const redesign = vi.spyOn(api, 'redesignWorkPackage').mockResolvedValue(undefined)
    const wrapper = mountDesigner()
    await flushPromises()
    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('设计两个纵向能力')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    expect(wrapper.get('.chat-decomposer').text()).toContain('Task Decomposer / 任务拆解器')
    expect(wrapper.get('[aria-label="工作包设计轨道"]').text()).toContain('WP-1')
    expect(wrapper.get('[aria-label="工作包设计轨道"]').text()).toContain('讨论 0/5 · 设计 R1')
    expect(wrapper.get('.designer-connection-strip').text()).toContain('模型调用 9/32')
    expect(wrapper.text()).not.toContain('"stages":["secret"]')
    const confirmButton = wrapper.findAll('button').find((button) => button.text().includes('确认设计并创建任务'))!
    expect(confirmButton.attributes('disabled')).toBeDefined()

    await wrapper.findAll('button').find((button) => button.text().includes('重新编译当前包'))!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text().includes('恢复当前包设计'))!.trigger('click')
    await flushPromises()
    expect(retry).toHaveBeenCalledWith(decomposedSession.id, 'WP-2')
    expect(redesign).toHaveBeenCalledWith(decomposedSession.id, 'WP-2')
  })

  it('clears the restored workspace and local message drafts when starting over', async () => {
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue({
      ...session, state: 'REVIEWING', workflowPhase: 'DISCUSSING_REQUIREMENT',
      discussionScope: 'REQUIREMENT', finalConfirmationEligible: false,
    })
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
      stages: [{ workPackageId: 'WP-1', objective: '实现功能', implementationKind: 'JAVA_PRODUCTION', allowedPaths: ['src/**'], forbiddenPaths: [], deliverables: ['实现'], acceptanceCriteria: [{ id: 'WP-1-AC-1', description: '业务行为通过聚焦测试验证' }], verifiers: [{ type: 'PROCESS', command: ['mvn', 'test', '-Dtest=FeatureTest'], processPurpose: 'TEST', testTargets: ['FeatureTest'], criterionIds: ['WP-1-AC-1'] }] }],
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
    const confirmButton = wrapper.findAll('button').find((button) => button.text().includes('确认设计并创建任务'))
    expect(confirmButton).toBeDefined()
    await confirmButton!.trigger('click')
    await flushPromises()

    expect(api.confirmDraft).toHaveBeenCalledWith(readyDraft.id)
    expect(api.updateDraft).toHaveBeenCalledWith(readyDraft.id, expect.objectContaining({
      stages: [expect.objectContaining({ workPackageId: 'WP-1' })],
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
      stages: [{ objective: '实现', implementationKind: 'NON_JAVA', allowedPaths: [], forbiddenPaths: [], deliverables: ['实现'],
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
        criteria: [{ id: 'AC-1', description: '用户能观察到结果', verificationMode: 'MACHINE', covered: false,
          machineCovered: false, judgePlanned: false, overallPlanned: false, verifierIndexes: [] }],
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
    expect(wrapper.get('[aria-label="双重验收计划矩阵"]').text()).toContain('机器：不适用')
    expect(wrapper.get('[aria-label="双重验收计划矩阵"]').text()).toContain('BUILD')
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
    const confirmButton = wrapper.findAll('button').find((button) => button.text().includes('确认设计并创建任务'))
    await confirmButton!.trigger('click')
    await flushPromises()

    expect(updateDraft).not.toHaveBeenCalled()
    expect(api.confirmDraft).toHaveBeenCalledWith(confirmedDraft.id)
    expect(routerPush).toHaveBeenCalledWith(`/tasks/${task.id}`)
  })
})
