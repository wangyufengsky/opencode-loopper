import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DesignerView from '@/views/DesignerView.vue'
import TaskProfileRouterDialog from '@/components/TaskProfileRouterDialog.vue'
import LoopSpecEditor from '@/components/LoopSpecEditor.vue'
import PendingQuestionCard from '@/components/PendingQuestionCard.vue'
import DesignerDiscussionHistory from '@/components/DesignerDiscussionHistory.vue'
import DesignerSystemMessageHistory from '@/components/DesignerSystemMessageHistory.vue'
import DesignerValidatorHistory from '@/components/DesignerValidatorHistory.vue'
import { api, ApiError } from '@/api/client'
import { useTaskStore } from '@/stores/taskStore'
import type { AppSettings, DesignerSession, LoopDraft, LoopSpec, Project, Task, TaskProfileRouterRun } from '@/types/domain'

const { routerPush, routeQuery, routeLeave } = vi.hoisted(() => ({
  routerPush: vi.fn(),
  routeQuery: {} as Record<string, string>,
  routeLeave: { callback: undefined as undefined | (() => Promise<boolean> | boolean) },
}))
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: (callback: () => Promise<boolean> | boolean) => { routeLeave.callback = callback },
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
  autoMode: { enabled: false, state: 'DISABLED', version: 0 },
  questionInteraction: { mode: 'NONE', awaitingAnswer: false },
  taskProfile: {
    state: 'FROZEN', decisionState: 'FROZEN', confirmationReady: true,
    intent: 'SOFTWARE_CHANGE', workflowTemplate: 'FULL_PACKAGE_DESIGN',
    mutationMode: 'WRITE_CODE', artifactKinds: ['SOURCE_CODE'], technologies: ['java'],
    testPolicy: 'REQUIRED', executionStrategy: 'OPEN_CODE_IMPLEMENTATION',
    rolePackId: 'software-java', rolePackVersion: 'test', confidence: 100,
    evidence: [], resolutionSource: 'TEST', decisionRequired: false, largeTaskMode: true, version: 0,
  },
  availableProfileOverrides: [],
  availableArtifactOverrides: [],
  reports: [],
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

function completedRouterRun(id = 'router-1'): TaskProfileRouterRun {
  return {
    id, state: 'COMPLETED', externalState: 'COMPLETED', createdAt: '2026-08-25T07:00:00Z',
    updatedAt: '2026-08-25T07:00:03Z', deadlineAt: '2026-08-25T07:04:00Z', retryAvailable: true,
  }
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
        DesignerCurrentActivity: { template: '<article class="designer-current-activity-stub" aria-label="当前角色正在处理" />' },
        Icon: true,
      },
    },
  })
}

beforeEach(() => {
  routerPush.mockReset()
  routeLeave.callback = undefined
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
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

describe('Designer draft composer', () => {
  it('keeps polling a reviewing session until profile rerouting finishes and then enables single-package design', async () => {
    vi.useFakeTimers()
    routeQuery.sessionId = 'designer-routing-profile'
    const routing: DesignerSession = {
      ...session, id: 'designer-routing-profile', state: 'REVIEWING', workflowPhase: 'DISCUSSING_REQUIREMENT',
      discussionScope: 'REQUIREMENT', finalConfirmationEligible: false,
      taskProfile: {
        ...session.taskProfile, id: undefined, state: 'ROUTING', decisionState: 'ROUTING', confirmationReady: false,
        resolutionSource: 'ROUTER', evidence: [], version: 0,
      },
      draft: draftFrom({
        schemaVersion: 'v2', projectId: project.id, goal: '重算画像', context: '', stages: [],
        limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: '7200', attemptTimeout: '1800' },
      }),
    }
    const confirmed: DesignerSession = {
      ...routing,
      taskProfile: {
        ...session.taskProfile, id: 'profile-2', state: 'PROVISIONAL', decisionState: 'CONFIRMED',
        confirmationReady: true, workflowTemplate: 'DIRECT_SOFTWARE_DESIGN', largeTaskMode: false,
        resolutionSource: 'USER_CONFIRMED_CARRIED_FORWARD', version: 0,
      },
    }
    const getSession = vi.spyOn(api, 'getDesignerSession')
      .mockResolvedValueOnce(routing)
      .mockResolvedValueOnce(confirmed)

    const wrapper = mountDesigner()
    await flushPromises()
    expect(wrapper.get('[aria-label="任务设置与设计流程"]').text()).toContain('任务设置识别中')
    const startWhileRouting = wrapper.findAll('button').find(button => button.text().includes('任务设置识别中'))!
    expect(startWhileRouting.attributes('disabled')).toBeDefined()

    await vi.advanceTimersByTimeAsync(0)
    await flushPromises()

    expect(getSession).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[aria-label="任务设置与设计流程"]').text()).toContain('已沿用人工确认')
    const start = wrapper.findAll('button').find(button => button.text().includes('开始单包设计'))!
    expect(start.attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('invalidates an in-flight poll when the Designer view unmounts', async () => {
    vi.useFakeTimers()
    routeQuery.sessionId = 'designer-unmount-poll'
    const running: DesignerSession = {
      ...session, id: 'designer-unmount-poll', state: 'RUNNING', workflowPhase: 'DESIGNING',
      activeActor: 'DESIGNER', discussionScope: 'WP-1', finalConfirmationEligible: false,
      draft: draftFrom({ schemaVersion: 'v2', projectId: project.id, goal: '验证卸载轮询', context: '', stages: [],
        limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: '7200', attemptTimeout: '1800' } }),
    }
    let rejectRefresh!: (reason?: unknown) => void
    const pendingRefresh = new Promise<DesignerSession>((_resolve, reject) => { rejectRefresh = reject })
    const getSession = vi.spyOn(api, 'getDesignerSession')
      .mockResolvedValueOnce(running)
      .mockReturnValueOnce(pendingRefresh)
    const warning = vi.spyOn(ElMessage, 'warning')

    const wrapper = mountDesigner()
    await flushPromises()
    vi.advanceTimersByTime(0)
    await flushPromises()
    expect(getSession).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    rejectRefresh(new Error('view already unmounted'))
    await flushPromises()
    vi.advanceTimersByTime(12000)
    await flushPromises()

    expect(warning).not.toHaveBeenCalled()
    expect(getSession).toHaveBeenCalledTimes(2)
  })

  it('cancels the active Router through the server and refreshes into manual selection', async () => {
    vi.useFakeTimers()
    routeQuery.sessionId = 'designer-cancel-router'
    const routing: DesignerSession = {
      ...session, id: 'designer-cancel-router', state: 'PENDING_HANDOFF', workflowPhase: 'ROUTING',
      activeActor: 'ROUTER', discussionScope: 'REQUIREMENT', discussionRevision: 0,
      finalConfirmationEligible: false,
      taskProfile: {
        ...session.taskProfile, id: undefined, state: 'ROUTING', decisionState: 'ROUTING', confirmationReady: false,
        resolutionSource: 'ROUTER', decisionRequired: true, evidence: [], largeTaskMode: false, version: 0,
      },
      routerRun: {
        id: 'router-active', state: 'RUNNING', externalState: 'RUNNING',
        createdAt: '2026-08-25T07:00:00Z', updatedAt: '2026-08-25T07:00:01Z', retryAvailable: false,
      },
      availableProfileOverrides: ['SOFTWARE_CHANGE'], availableArtifactOverrides: ['SOURCE_CODE'],
      draft: draftFrom({
        schemaVersion: 'v2', projectId: project.id, goal: '取消识别并手动设置', context: '', stages: [],
        limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: '7200', attemptTimeout: '1800' },
      }),
    }
    const manual: DesignerSession = {
      ...routing,
      taskProfile: {
        ...routing.taskProfile, id: 'profile-manual', state: 'PROVISIONAL', decisionState: 'NEEDS_CONFIRMATION',
        resolutionSource: 'USER_SELECTION_PENDING', evidence: ['router-error=ROUTER_USER_CANCELLED:用户取消'], version: 0,
      },
      routerRun: {
        ...routing.routerRun!, state: 'SUPERSEDED', externalState: 'ABORTED',
        errorCode: 'ROUTER_USER_CANCELLED', errorDetail: '用户已取消 AI 任务设置识别，请手动选择任务设置',
      },
    }
    const getSession = vi.spyOn(api, 'getDesignerSession')
      .mockResolvedValueOnce(routing)
      .mockResolvedValueOnce(manual)
    const cancel = vi.spyOn(api, 'cancelDesignerTaskProfileRouting').mockResolvedValue(manual.taskProfile)
    const wrapper = mountDesigner()
    await flushPromises()

    wrapper.getComponent(TaskProfileRouterDialog).vm.$emit('cancel')
    await flushPromises()

    expect(cancel).toHaveBeenCalledWith(routing.id, 'router-active')
    expect(getSession).toHaveBeenCalledTimes(2)
    expect(wrapper.getComponent(TaskProfileRouterDialog).props('session').routerRun?.errorCode)
      .toBe('ROUTER_USER_CANCELLED')
    expect(wrapper.getComponent(TaskProfileRouterDialog).props('modelValue')).toBe(true)
    wrapper.unmount()
  })

  it('shows changed profile choices and confirms the new recommendation explicitly', async () => {
    routeQuery.sessionId = 'designer-changed-profile'
    const changed: DesignerSession = {
      ...session, id: 'designer-changed-profile', state: 'REVIEWING', workflowPhase: 'DISCUSSING_REQUIREMENT',
      discussionScope: 'REQUIREMENT', finalConfirmationEligible: false,
      taskProfile: {
        ...session.taskProfile, id: 'profile-new', state: 'PROVISIONAL', decisionState: 'NEEDS_CONFIRMATION',
        confirmationReady: false, decisionRequired: true, intent: 'DOCUMENT_AUTHORING',
        artifactKinds: ['MARKDOWN'], workflowTemplate: 'DIRECT_ARTIFACT', mutationMode: 'WRITE_FILES',
        previousConfirmedChoice: {
          intent: 'SOFTWARE_CHANGE', primaryArtifactKind: 'SOURCE_CODE',
          workflowTemplate: 'DIRECT_SOFTWARE_DESIGN', mutationMode: 'WRITE_CODE',
          largeTaskMode: false, resolutionSource: 'USER_CONFIRMED',
        },
        version: 7,
      },
      availableProfileOverrides: ['SOFTWARE_CHANGE', 'DOCUMENT_AUTHORING'],
      availableArtifactOverrides: ['SOURCE_CODE', 'MARKDOWN'],
      routerRun: completedRouterRun('router-changed'),
      draft: draftFrom({
        schemaVersion: 'v2', projectId: project.id, goal: '画像变化', context: '', stages: [],
        limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: '7200', attemptTimeout: '1800' },
      }),
    }
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue(changed)
    const confirmProfile = vi.spyOn(api, 'confirmDesignerTaskProfile').mockResolvedValue({
      ...changed.taskProfile, decisionState: 'CONFIRMED', confirmationReady: true,
      decisionRequired: false, resolutionSource: 'USER_CONFIRMED',
    })
    const previewUpdate = vi.spyOn(api, 'previewDesignerTaskProfileUpdate')
    const restartConfirmation = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mountDesigner()
    await flushPromises()

    const card = wrapper.get('[aria-label="任务设置与设计流程"]')
    expect(card.text()).toContain('识别结果有变化')
    expect(card.text()).toContain('原设置：软件变更 · 源代码 · 默认单包设计')
    expect(card.text()).toContain('本次识别结果：文档编写 · Markdown 文档 · 直接制品')
    expect(wrapper.findAll('button').find(button => button.text().includes('请先确认任务设置'))!.attributes('disabled')).toBeDefined()
    expect(card.find('.profile-override').exists()).toBe(false)

    wrapper.getComponent(TaskProfileRouterDialog).vm.$emit('confirm')
    await flushPromises()
    expect(confirmProfile).toHaveBeenCalledWith(changed.id, 7)
    expect(previewUpdate).not.toHaveBeenCalled()
    expect(restartConfirmation).not.toHaveBeenCalled()
  })

  it('refreshes an authoritative profile conflict without showing a red error', async () => {
    routeQuery.sessionId = 'designer-profile-conflict'
    const changed: DesignerSession = {
      ...session, id: 'designer-profile-conflict', state: 'REVIEWING', workflowPhase: 'DISCUSSING_REQUIREMENT',
      discussionScope: 'REQUIREMENT', finalConfirmationEligible: false,
      taskProfile: {
        ...session.taskProfile, id: 'profile-conflict', state: 'PROVISIONAL', decisionState: 'NEEDS_CONFIRMATION',
        confirmationReady: false, decisionRequired: true, version: 3,
        previousConfirmedChoice: {
          intent: 'SOFTWARE_CHANGE', primaryArtifactKind: 'SOURCE_CODE',
          workflowTemplate: 'DIRECT_SOFTWARE_DESIGN', mutationMode: 'WRITE_CODE',
          largeTaskMode: false, resolutionSource: 'USER_CONFIRMED',
        },
      },
      availableProfileOverrides: ['SOFTWARE_CHANGE'], availableArtifactOverrides: ['SOURCE_CODE'],
      routerRun: completedRouterRun('router-conflict'),
      draft: draftFrom({
        schemaVersion: 'v2', projectId: project.id, goal: '并发画像', context: '', stages: [],
        limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: '7200', attemptTimeout: '1800' },
      }),
    }
    const getSession = vi.spyOn(api, 'getDesignerSession').mockResolvedValue(changed)
    vi.spyOn(api, 'previewDesignerTaskProfileUpdate').mockResolvedValue({
      selectionChanged: true, updateRequired: true, sessionRestartRequired: true,
      targetWorkflowTemplate: 'DIRECT_SOFTWARE_DESIGN',
    })
    vi.spyOn(api, 'updateDesignerTaskProfile')
      .mockRejectedValue(new ApiError('任务设置已变化', 409, { code: 'TASK_PROFILE_VERSION_CONFLICT' }))
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const info = vi.spyOn(ElMessage, 'info')
    const error = vi.spyOn(ElMessage, 'error')
    const wrapper = mountDesigner()
    await flushPromises()

    wrapper.getComponent(TaskProfileRouterDialog).vm.$emit('save', {
      intent: 'SOFTWARE_CHANGE', artifact: 'SOURCE_CODE', largeTaskMode: false, componentKeys: [],
    })
    await flushPromises()

    expect(getSession).toHaveBeenCalledTimes(2)
    expect(info).toHaveBeenCalledWith('任务设置刚刚发生变化，已刷新最新结果')
    expect(error).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('shows task profile summaries and override options in Chinese while preserving enum values', async () => {
    routeQuery.sessionId = 'designer-profile'
    const profileSession: DesignerSession = {
      ...session,
      id: 'designer-profile',
      state: 'REVIEWING',
      workflowPhase: 'DISCUSSING_REQUIREMENT',
      discussionScope: 'REQUIREMENT',
      finalConfirmationEligible: false,
      taskProfile: {
        ...session.taskProfile,
        state: 'PROVISIONAL',
        decisionState: 'NEEDS_CONFIRMATION',
        confirmationReady: false,
        decisionRequired: true,
        previousConfirmedChoice: undefined,
        confidence: 90,
        confidenceAvailable: true,
        resolutionSource: 'AI_ROUTER',
      },
      availableProfileOverrides: ['SOFTWARE_CHANGE', 'DOCUMENT_AUTHORING', 'READ_ONLY_REVIEW'],
      availableArtifactOverrides: ['SOURCE_CODE', 'PYTHON_SCRIPT', 'DOCX'],
      routerRun: completedRouterRun('router-summary'),
      draft: draftFrom({
        schemaVersion: 'v2', projectId: project.id, goal: '验证中文任务画像', context: '', stages: [],
        limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: '7200', attemptTimeout: '1800' },
      }),
    }
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue(profileSession)

    const wrapper = mountDesigner()
    await flushPromises()

    const profileCard = wrapper.get('[aria-label="任务设置与设计流程"]')
    expect(profileCard.text()).toContain('任务设置 · 软件变更')
    expect(profileCard.text()).toContain('识别置信度 90%')
    expect(profileCard.text()).toContain('技术栈 java')
    expect(profileCard.text()).toContain('流程 完整分包设计 · 执行 OpenCode 实施 · 测试 必须测试')
    expect(profileCard.text()).toContain('请确认')
    expect(profileCard.text()).toContain('查看识别结果')
    expect(wrapper.find('[aria-label="覆盖任务类型"]').exists()).toBe(false)
    expect(wrapper.getComponent(TaskProfileRouterDialog).props('modelValue')).toBe(true)
  })

  it('asks for a component only when a multi-stack software task is ambiguous', async () => {
    routeQuery.sessionId = 'designer-component-profile'
    const componentSession: DesignerSession = {
      ...session,
      id: 'designer-component-profile', state: 'REVIEWING', workflowPhase: 'DISCUSSING_REQUIREMENT',
      discussionScope: 'REQUIREMENT', finalConfirmationEligible: false,
      taskProfile: {
        ...session.taskProfile, id: 'profile-components', state: 'PROVISIONAL',
        decisionState: 'NEEDS_CONFIRMATION', confirmationReady: false, decisionRequired: true,
        workflowTemplate: 'DIRECT_SOFTWARE_DESIGN', largeTaskMode: false, version: 3,
        technologies: [], componentKeys: [], componentSelectionRequired: true,
        candidateComponents: [
          { key: 'backend', relativeRoot: '.', technologyFamilies: ['java'], technologies: ['java'], buildTools: ['maven'], testFrameworks: ['junit'], manifestSources: ['pom.xml'] },
          { key: 'frontend', relativeRoot: 'frontend', technologyFamilies: ['node'], technologies: ['node'], buildTools: ['npm'], testFrameworks: ['vitest'], manifestSources: ['frontend/package.json'] },
        ],
      },
      availableProfileOverrides: ['SOFTWARE_CHANGE'], availableArtifactOverrides: ['SOURCE_CODE'],
      routerRun: completedRouterRun('router-components'),
      draft: draftFrom({
        schemaVersion: 'v2', projectId: project.id, goal: '选择受影响组件', context: '', stages: [],
        limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: '7200', attemptTimeout: '1800' },
      }),
    }
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue(componentSession)
    vi.spyOn(api, 'previewDesignerTaskProfileUpdate').mockResolvedValue({
      selectionChanged: true, updateRequired: true, sessionRestartRequired: false,
      targetWorkflowTemplate: 'DIRECT_SOFTWARE_DESIGN',
    })
    const update = vi.spyOn(api, 'updateDesignerTaskProfile').mockResolvedValue({
      ...componentSession.taskProfile, technologies: ['java'], componentKeys: ['backend'],
      componentSelectionRequired: false, decisionRequired: false, confirmationReady: true,
    })

    const wrapper = mountDesigner()
    await flushPromises()

    const routerDialog = wrapper.getComponent(TaskProfileRouterDialog)
    expect(routerDialog.props('modelValue')).toBe(true)
    routerDialog.vm.$emit('save', {
      intent: 'SOFTWARE_CHANGE', artifact: 'SOURCE_CODE', largeTaskMode: false, componentKeys: ['frontend'],
    })
    await flushPromises()

    expect(update).toHaveBeenCalledWith(
      componentSession.id, 'SOFTWARE_CHANGE', 'SOURCE_CODE', 3, false, ['frontend'],
    )
  })

  it('defaults software design to one package and only enables decomposition through the explicit switch', async () => {
    routeQuery.sessionId = 'designer-direct-profile'
    const directSession: DesignerSession = {
      ...session,
      id: 'designer-direct-profile', state: 'REVIEWING', workflowPhase: 'DISCUSSING_REQUIREMENT',
      discussionScope: 'REQUIREMENT', finalConfirmationEligible: false,
      taskProfile: {
        ...session.taskProfile, id: 'profile-direct', state: 'PROVISIONAL',
        workflowTemplate: 'DIRECT_SOFTWARE_DESIGN', largeTaskMode: false, version: 4,
      },
      availableProfileOverrides: ['SOFTWARE_CHANGE'], availableArtifactOverrides: ['SOURCE_CODE'],
      draft: draftFrom({
        schemaVersion: 'v2', projectId: project.id, goal: '默认单包', context: '', stages: [],
        limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: '7200', attemptTimeout: '1800' },
      }),
    }
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue(directSession)
    vi.spyOn(api, 'previewDesignerTaskProfileUpdate').mockResolvedValue({
      selectionChanged: true, updateRequired: true, sessionRestartRequired: true,
      targetWorkflowTemplate: 'FULL_PACKAGE_DESIGN',
    })
    const update = vi.spyOn(api, 'updateDesignerTaskProfile').mockResolvedValue({
      ...directSession.taskProfile, workflowTemplate: 'FULL_PACKAGE_DESIGN', largeTaskMode: true,
    })
    const confirmation = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    const wrapper = mountDesigner()
    await flushPromises()

    expect(wrapper.get('[aria-label="Designer 流程"]').text()).toContain('1需求讨论2单包设计3规范编译')
    expect(wrapper.get('[aria-label="任务设置与设计流程"]').text()).toContain('流程 默认单包设计')
    expect(wrapper.find('[aria-label="大型任务模式"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('需求已明确，开始单包设计')

    await wrapper.findAll('button').find(button => button.text().includes('修改设置'))!.trigger('click')
    expect(wrapper.get('[aria-label="大型任务模式"]').classes()).not.toContain('is-checked')
    await wrapper.get('[aria-label="大型任务模式"]').trigger('click')
    await wrapper.findAll('button').find(button => button.text().includes('保存设置'))!.trigger('click')
    await flushPromises()
    expect(confirmation).toHaveBeenCalledWith(
      expect.stringContaining('停止当前远端设计会话'), '需要重新开始当前设计',
      expect.objectContaining({ confirmButtonText: '停止当前设计并重新开始' }),
    )
    expect(update).toHaveBeenCalledWith(directSession.id, 'SOFTWARE_CHANGE', 'SOURCE_CODE', 4, true, [])
  })

  it('renders the authoritative requirement snapshot separately and excludes its audit message from system history', async () => {
    routeQuery.sessionId = 'designer-server-snapshot'
    const snapshotMarkdown = '# 需求快照\n\n## 讨论 1\n\n原始需求保持原样'
    const snapshotSession: DesignerSession = {
      ...session,
      id: 'designer-server-snapshot', state: 'REVIEWING', workflowPhase: 'DISCUSSING_REQUIREMENT',
      discussionScope: 'REQUIREMENT', finalConfirmationEligible: false,
      draft: draftFrom({ schemaVersion: 'v2', projectId: project.id, goal: '原样需求快照', context: '', stages: [],
        limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: '7200', attemptTimeout: '1800' } }),
      requirementSnapshot: {
        discussionRevision: 2, source: 'SERVER_ASSEMBLED', markdown: snapshotMarkdown,
        updatedAt: '2026-08-20T08:00:00Z',
      },
      messages: [
        { id: 'snapshot-source', role: 'SYSTEM', actor: 'SYSTEM', content: snapshotMarkdown,
          deliveryState: 'SERVER_REQUIREMENT_SNAPSHOT', createdAt: '2026-08-20T08:00:00Z' },
        { id: 'normal-system', role: 'SYSTEM', actor: 'SYSTEM', content: '画像重算完成',
          deliveryState: 'PERSISTED', createdAt: '2026-08-20T08:01:00Z' },
      ],
    }
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue(snapshotSession)

    const wrapper = mountDesigner()
    await flushPromises()

    const snapshotCard = wrapper.get('[aria-label="需求快照"]')
    expect(snapshotCard.text()).toContain('需求快照 · 讨论第 2 版')
    expect(snapshotCard.text()).toContain('服务端原样生成')
    expect(snapshotCard.text()).toContain('原始需求保持原样')
    const systemHistory = wrapper.getComponent(DesignerSystemMessageHistory)
    expect(systemHistory.props('entries').map(entry => entry.id)).toEqual(['normal-system'])
    expect(systemHistory.text()).not.toContain('原始需求保持原样')
  })

  it('labels a historical AI requirement snapshot without presenting it as a server assembly', async () => {
    routeQuery.sessionId = 'designer-ai-snapshot'
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue({
      ...session,
      id: 'designer-ai-snapshot', state: 'REVIEWING', workflowPhase: 'DISCUSSING_REQUIREMENT',
      discussionScope: 'REQUIREMENT', finalConfirmationEligible: false,
      draft: draftFrom({ schemaVersion: 'v2', projectId: project.id, goal: '历史需求', context: '', stages: [],
        limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: '7200', attemptTimeout: '1800' } }),
      requirementSnapshot: {
        discussionRevision: 1, source: 'AI_ASSEMBLED', markdown: '# 历史需求稿',
        updatedAt: '2026-08-19T08:00:00Z',
      },
    })

    const wrapper = mountDesigner()
    await flushPromises()

    const snapshotCard = wrapper.get('[aria-label="需求快照"]')
    expect(snapshotCard.text()).toContain('历史 AI 生成')
    expect(snapshotCard.text()).not.toContain('服务端原样生成')
  })

  it('hides the package approval rail in direct mode and exposes only the explicit large-task recovery', async () => {
    routeQuery.sessionId = 'designer-direct-overflow'
    const overflow: DesignerSession = {
      ...session,
      id: 'designer-direct-overflow', state: 'WAITING_INPUT', workflowPhase: 'FAILED',
      activeActor: 'VALIDATOR', discussionScope: 'WP-1', discussionRevision: 3,
      taskProfile: { ...session.taskProfile, id: 'profile-overflow', workflowTemplate: 'DIRECT_SOFTWARE_DESIGN', largeTaskMode: false, version: 2 },
      workPackages: [{ id: 'WP-1', ordinal: 0, title: '默认单包设计', objective: '完整需求', dependencies: [], state: 'WAITING_INPUT', redesignCount: 0, compilerRepairCount: 0, compilerPlanningRepairCount: 0, designRevision: 1, discussionRoundCount: 0, lastErrorCode: 'LARGE_TASK_MODE_REQUIRED' }],
      messages: [{ id: 'overflow', role: 'SYSTEM', actor: 'VALIDATOR', content: 'LARGE_TASK_MODE_REQUIRED：无法容纳在 1–6 个 Stage 中', deliveryState: 'TERMINAL_ERROR', requirementRevision: 1, workPackageId: 'WP-1', createdAt: 'now' }],
      finalConfirmationEligible: false,
      draft: draftFrom({ schemaVersion: 'v2', projectId: project.id, goal: '超限', context: '', stages: [], limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: '7200', attemptTimeout: '1800' } }),
    }
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue(overflow)
    const enable = vi.spyOn(api, 'enableDesignerLargeTaskMode').mockResolvedValue({
      ...overflow.taskProfile, workflowTemplate: 'FULL_PACKAGE_DESIGN', largeTaskMode: true,
    })

    const wrapper = mountDesigner()
    await flushPromises()

    expect(wrapper.find('[aria-label="工作包设计轨道"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('普通任务无法安全容纳当前设计')
    expect(wrapper.text()).toContain('改用大型任务')
    expect(wrapper.text()).not.toContain('重新编译当前包')
    await wrapper.findAll('button').find(button => button.text().includes('改用大型任务'))!.trigger('click')
    await flushPromises()
    expect(enable).toHaveBeenCalledWith(overflow.id, 3, 2)
  })

  it('shows server-owned MCP candidate facts without restoring the package rail in direct mode', async () => {
    routeQuery.sessionId = 'designer-direct-mcp-candidate'
    const directCandidate: DesignerSession = {
      ...session,
      id: 'designer-direct-mcp-candidate', state: 'REVIEWING', workflowPhase: 'FINAL_REVIEW',
      activeWorkPackageId: 'WP-1', discussionScope: 'WP-1', finalConfirmationEligible: true,
      taskProfile: { ...session.taskProfile, id: 'profile-direct-candidate', workflowTemplate: 'DIRECT_SOFTWARE_DESIGN', largeTaskMode: false, version: 2 },
      draft: draftFrom({ schemaVersion: 'v2', projectId: project.id, goal: '默认单包候选', context: '', stages: [],
        limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: '7200', attemptTimeout: '1800' } }),
      workPackages: [{
        id: 'WP-1', ordinal: 0, title: '默认单包设计', objective: '完成单包设计', dependencies: [],
        state: 'APPROVED', redesignCount: 0, compilerRepairCount: 0, compilerPlanningRepairCount: 0,
        designRevision: 1, discussionRoundCount: 0, approvedDesignRevision: 1,
        candidateRunState: 'ACCEPTED', candidateSessions: 1, candidateSubmissions: 2,
        compilationSource: 'MCP_ACCEPTED', serverCompiled: true,
      }],
    }
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue(directCandidate)

    const wrapper = mountDesigner()
    await flushPromises()

    expect(wrapper.find('[aria-label="工作包设计轨道"]').exists()).toBe(false)
    const summary = wrapper.get('[aria-label="默认单包候选状态"]').text()
    expect(summary).toContain('候选状态：已接受')
    expect(summary).toContain('MCP 候选已接受')
    expect(summary).toContain('服务端已编译')
    expect(summary).toContain('候选会话 1')
    expect(summary).toContain('候选修正 2')
  })

  it('shows the persisted Markdown fallback reason in the direct-mode candidate summary', async () => {
    routeQuery.sessionId = 'designer-direct-markdown-fallback'
    const directFallback: DesignerSession = {
      ...session,
      id: 'designer-direct-markdown-fallback', state: 'WAITING_INPUT', workflowPhase: 'FAILED',
      activeWorkPackageId: 'WP-1', discussionScope: 'WP-1', finalConfirmationEligible: false,
      taskProfile: { ...session.taskProfile, id: 'profile-direct-fallback', workflowTemplate: 'DIRECT_SOFTWARE_DESIGN', largeTaskMode: false, version: 2 },
      draft: draftFrom({ schemaVersion: 'v2', projectId: project.id, goal: '默认单包兜底', context: '', stages: [],
        limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: '7200', attemptTimeout: '1800' } }),
      workPackages: [{
        id: 'WP-1', ordinal: 0, title: '默认单包设计', objective: '完成单包设计', dependencies: [],
        state: 'WAITING_INPUT', redesignCount: 0, compilerRepairCount: 0, compilerPlanningRepairCount: 0,
        designRevision: 1, discussionRoundCount: 0,
        candidateRunState: 'CLOSED', candidateSessions: 1, candidateSubmissions: 0,
        compilationSource: 'MARKDOWN_FALLBACK', fallbackReason: 'MODEL_COMPLETED_WITHOUT_SUBMISSION', serverCompiled: false,
      }],
    }
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue(directFallback)

    const wrapper = mountDesigner()
    await flushPromises()

    const summary = wrapper.get('[aria-label="默认单包候选状态"]').text()
    expect(summary).toContain('候选状态：已关闭')
    expect(summary).toContain('Markdown 兜底')
    expect(summary).toContain('兜底原因：模型结束但未提交候选')
    expect(summary).toContain('候选会话 1')
    expect(summary).not.toContain('候选修正')
  })

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
    expect(wrapper.find('textarea[aria-label="发送给只读设计师的消息"]').exists()).toBe(true)
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
    expect(wrapper.text()).not.toContain('服务端记录不会因短暂断线被删除')
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
    expect(wrapper.find('textarea[aria-label="发送给只读设计师的消息"]').exists()).toBe(false)
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

    expect(createSession).toHaveBeenCalledWith(project.id, 'draft-1', initialGoal, false)
    expect(createDraft.mock.calls[0]?.[0].goal).toBe(initialGoal)
    expect(createDraft.mock.calls[0]?.[0].schemaVersion).toBe('v2')
    expect(createDraft.mock.calls[0]?.[0].stages[0]).toMatchObject({ allowedPaths: [], forbiddenPaths: [], verifiers: [] })
    expect(createDraft.mock.calls[0]?.[0].limits).toMatchObject({ maxTaskAttempts: 7, attemptTimeout: 'PT45M' })
    expect(sessionStorage.getItem('opencode-loopper.designer-draft-prompt')).toBeNull()
    expect(wrapper.find('textarea[aria-label="发送给只读设计师的消息"]').exists()).toBe(true)
  })

  it('shows staged files in a standalone context card and hides it after the last file is removed', async () => {
    const createContextTurn = vi.spyOn(api, 'createDesignerContextTurn')
    const wrapper = mountDesigner()
    await flushPromises()
    const file = new File(['acceptance'], 'acceptance.txt', { type: 'text/plain' })

    expect(wrapper.find('[data-testid="initial-attachment-card"]').exists()).toBe(false)
    await wrapper.get('#main-content').trigger('drop', { dataTransfer: { files: [file] } })
    await flushPromises()

    const card = wrapper.get('[data-testid="initial-attachment-card"]')
    expect(card.text()).toContain('文件上下文')
    expect(card.text()).toContain('整体需求 · 1 个文件')
    expect(card.get('[data-testid="initial-attachment-file"]').text()).toContain('acceptance.txt')
    expect(card.get('[data-testid="initial-attachment-file"]').text()).toContain('TXT 文件')
    expect(card.text()).toContain('最多 10 个，单个 20 MiB')
    expect(createContextTurn).not.toHaveBeenCalled()

    await card.get('[aria-label="移除 acceptance.txt"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="initial-attachment-card"]').exists()).toBe(false)
  })

  it('requires risk confirmation before creating an auto-mode design', async () => {
    const confirmation = vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => Promise.resolve(undefined as never))
    const createSession = vi.spyOn(api, 'createDesignerSession').mockResolvedValue({
      ...session, autoMode: { enabled: true, state: 'ACTIVE', version: 0 },
    })
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('.designer-auto-create .el-switch').trigger('click')
    await flushPromises()
    expect(confirmation).toHaveBeenCalledWith(expect.stringContaining('自动采用需求分析师识别的任务设置和设计答案'), '授权全自动设计？', expect.any(Object))

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('自动完成设计并启动任务')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    expect(createSession).toHaveBeenCalledWith(project.id, 'draft-1', '自动完成设计并启动任务', true)
    expect(wrapper.text()).toContain('全自动模式')
  })

  it('shows that auto mode will adopt a low-confidence task profile recommendation without manual override', async () => {
    routeQuery.sessionId = 'designer-profile-wait'
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue({
      ...session,
      id: 'designer-profile-wait',
      state: 'REVIEWING',
      workflowPhase: 'DISCUSSING_REQUIREMENT',
      discussionScope: 'REQUIREMENT',
      finalConfirmationEligible: false,
      autoMode: { enabled: true, state: 'ACTIVE', version: 2, lastAction: 'PROFILE_DECISION_WAITING' },
      taskProfile: {
        ...session.taskProfile,
        id: 'profile-wait',
        state: 'PROVISIONAL',
        confidence: 70,
        decisionRequired: true,
      },
      draft: draftFrom({
        schemaVersion: 'v2', projectId: project.id, goal: '验证全自动画像推荐', context: '', stages: [],
        limits: { maxStageAttempts: 3, maxTaskAttempts: 7, maxDuration: '7200', attemptTimeout: '1800' },
      }),
    })

    const wrapper = mountDesigner()
    await flushPromises()

    expect(wrapper.text()).toContain('全自动模式')
    expect(wrapper.text()).not.toContain('无需人工覆盖；需求确认前仍可主动调整')
    expect(wrapper.text()).not.toContain('全自动模式已阻断')
    expect(wrapper.find('[aria-label="任务设置与设计流程"] .profile-actions').exists()).toBe(true)
    expect(wrapper.find('[aria-label="任务设置与设计流程"] .profile-override').exists()).toBe(false)
  })

  it('keeps auto mode off when the creation warning is cancelled', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('.designer-auto-create .el-switch').trigger('click')
    await flushPromises()

    expect(wrapper.get('.designer-auto-create .el-switch').classes()).not.toContain('is-checked')
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
    const messageInput = wrapper.get('textarea[aria-label="发送给只读设计师的消息"]')
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

  it('keeps follow-up text and staged files after an atomic context-turn failure', async () => {
    const discussionSession: DesignerSession = {
      ...session, state: 'REVIEWING', workflowPhase: 'DISCUSSING_REQUIREMENT',
      discussionScope: 'REQUIREMENT', finalConfirmationEligible: false,
    }
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(discussionSession)
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    const sendContext = vi.spyOn(api, 'sendDesignerContextTurn').mockRejectedValue(new Error('parse failed'))
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('创建可靠的执行计划')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()
    const file = new File(['context'], 'context.txt', { type: 'text/plain' })
    await wrapper.get('#main-content').trigger('drop', { dataTransfer: { files: [file] } })
    const input = wrapper.get('textarea[aria-label="发送给只读设计师的消息"]')
    await input.setValue('请结合文件继续设计')
    await wrapper.get('.compose-actions button').trigger('click')
    await flushPromises()

    expect(sendContext).toHaveBeenCalledWith(discussionSession.id, expect.objectContaining({
      content: '请结合文件继续设计', scopeKey: 'REQUIREMENT',
    }), [file])
    expect((input.element as HTMLTextAreaElement).value).toBe('请结合文件继续设计')
    const attachmentCard = wrapper.get('[data-testid="message-attachment-card"]')
    expect(attachmentCard.text()).toContain('整体需求 · 1 个文件')
    expect(attachmentCard.text()).toContain('发送失败会保留当前文字和文件')
    expect(attachmentCard.get('[data-testid="message-attachment-file"]').text()).toContain('context.txt')
  })

  it('keeps the chat answer composer available in auto mode when native question is unsupported', async () => {
    let answered = false
    const fallbackSession: DesignerSession = {
      ...session,
      state: 'RUNNING', workflowPhase: 'DISCUSSING_REQUIREMENT', activeActor: 'DESIGNER',
      discussionScope: 'REQUIREMENT', discussionRevision: 1, finalConfirmationEligible: false,
      autoMode: { enabled: true, state: 'ACTIVE', version: 1 },
      questionInteraction: { mode: 'CHAT_FALLBACK', awaitingAnswer: true },
      messages: [{ id: 'chat-question', role: 'ASSISTANT', actor: 'DESIGNER',
        content: '1. 失败时保留旧值还是清空？', deliveryState: 'CHAT_QUESTION', createdAt: 'now' }],
    }
    const reviewedSession: DesignerSession = {
      ...fallbackSession, state: 'REVIEWING',
      questionInteraction: { mode: 'CHAT_FALLBACK', awaitingAnswer: false },
    }
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(fallbackSession)
    vi.spyOn(api, 'getDesignerSession').mockImplementation(async () => answered ? reviewedSession : fallbackSession)
    const send = vi.spyOn(api, 'sendRequirementMessage').mockImplementation(async () => {
      answered = true
      return { sessionId: fallbackSession.id, state: 'REVIEWING', persistedMessages: [], notice: '回答已保存' }
    })
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('实现可恢复的缓存刷新')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    expect(wrapper.get('.chat-question-compat').text()).toContain('对话回答模式')
    expect(wrapper.text()).toContain('失败时保留旧值还是清空')
    expect(wrapper.find('.designer-current-activity-stub').exists()).toBe(false)
    const input = wrapper.get('textarea[aria-label="发送给只读设计师的消息"]')
    expect(input.attributes('disabled')).toBeUndefined()
    expect(input.attributes('placeholder')).toContain('直接回答')
    await input.setValue('保留旧值并记录错误')
    expect(wrapper.get('.compose-actions button').text()).toContain('提交回答')
    await wrapper.get('.compose-actions button').trigger('click')
    await flushPromises()

    expect(send).toHaveBeenCalledWith(fallbackSession.id, '保留旧值并记录错误', 1)
    wrapper.unmount()
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
    expect(wrapper.get('textarea[aria-label="发送给只读设计师的消息"]').attributes('rows')).toBe('10')
  })

  it('places the current role activity inside the message list only while work is running', async () => {
    const runningSession: DesignerSession = { ...session, state: 'RUNNING', workflowPhase: 'DESIGNING', activeActor: 'DESIGNER' }
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(runningSession)
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    vi.spyOn(api, 'getDesignerSession').mockImplementation(() => new Promise(() => {}))
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('创建可靠的执行计划')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    const activity = wrapper.get('.designer-current-activity-stub')
    expect(activity.element.parentElement?.classList.contains('chat-history')).toBe(true)
    expect(wrapper.findAll('.designer-current-activity-stub')).toHaveLength(1)

    wrapper.unmount()
  })

  it('uses the inline current-role activity for every actor and never renders raw SSE role output', async () => {
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
    expect(wrapper.find('.chat-live').exists()).toBe(false)
    expect(wrapper.get('.designer-current-activity-stub').element.parentElement?.classList.contains('chat-history')).toBe(true)

    FakeEventSource.latest?.onmessage?.({ data: JSON.stringify({
      sequence: 3, sessionId: runningSession.id, type: 'STATUS', state: 'RUNNING', workflowPhase: 'COMPILING', activeActor: 'COMPILER', remoteState: 'REPAIRING_1',
      runtimeConnected: true, content: '<!-- LOOPSPEC_COMPILATION_JSON_START -->raw-json', detail: '规范工程师正在进行第 1/2 次修复', at: '2026-08-05T01:00:01Z', structuredStep: 'REPAIRING_JSON',
    }) } as MessageEvent<string>)
    await flushPromises()

    expect(wrapper.find('.chat-live').exists()).toBe(false)
    expect(wrapper.find('.designer-current-activity-stub').exists()).toBe(true)
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
    expect(wrapper.find('.designer-current-activity-stub').exists()).toBe(false)
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

  it('places each discussion round immediately before its matching design snapshot', async () => {
    const discussionSession: DesignerSession = {
      ...session,
      answeredQuestions: [
        {
          id: 'question-r2', scope: 'WP-1', discussionRevision: 2, designMessageId: 'design-r2', answeredAt: '2026-08-18T05:00:00Z',
          questions: [{ question: '第二稿如何补充？', header: '第二稿', multiple: false, custom: false,
            options: [{ label: '补充边界', description: '增加异常路径' }], answers: ['补充边界'] }],
        },
        {
          id: 'question-r1', scope: 'WP-1', discussionRevision: 1, designMessageId: 'design-r1', answeredAt: '2026-08-18T04:00:00Z',
          questions: [{ question: '第一稿采用哪种范围？', header: '第一稿', multiple: false, custom: false,
            options: [{ label: '最小范围', description: '先覆盖核心路径' }], answers: ['最小范围'] }],
        },
      ],
      messages: [
        { id: 'user', role: 'USER', actor: 'USER', content: '设计缓存刷新任务', deliveryState: 'PERSISTED', createdAt: '2026-08-18T03:00:00Z' },
        { id: 'design-r1', role: 'ASSISTANT', actor: 'DESIGNER', content: '# 第一份设计稿', deliveryState: 'PERSISTED', workPackageId: 'WP-1', createdAt: '2026-08-18T04:01:00Z' },
        { id: 'design-r2', role: 'ASSISTANT', actor: 'DESIGNER', content: '# 第二份设计稿', deliveryState: 'PERSISTED', workPackageId: 'WP-1', createdAt: '2026-08-18T05:01:00Z' },
      ],
    }
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(discussionSession)
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue(discussionSession)
    const wrapper = mountDesigner()
    await flushPromises()
    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('设计缓存刷新任务')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    const histories = wrapper.findAllComponents(DesignerDiscussionHistory)
    const designs = wrapper.findAll('.chat-designer')
    const children = Array.from(wrapper.get('.chat-history').element.children)
    expect(histories).toHaveLength(2)
    expect(designs).toHaveLength(2)
    const firstHistory = histories.at(0)!
    const secondHistory = histories.at(1)!
    const firstDesign = designs.at(0)!
    const secondDesign = designs.at(1)!
    expect(firstHistory.get('details').attributes('open')).toBeUndefined()
    expect(firstHistory.text()).toContain('第一稿采用哪种范围？')
    expect(secondHistory.text()).toContain('第二稿如何补充？')
    expect(children.indexOf(firstHistory.element)).toBeLessThan(children.indexOf(firstDesign.element))
    expect(children.indexOf(firstDesign.element)).toBeLessThan(children.indexOf(secondHistory.element))
    expect(children.indexOf(secondHistory.element)).toBeLessThan(children.indexOf(secondDesign.element))
  })

  it('keeps requirement discussion anchored before its hidden server snapshot as later messages arrive', async () => {
    const discussionSession: DesignerSession = {
      ...session,
      answeredQuestions: [{
        id: 'question-r1', scope: 'REQUIREMENT', discussionRevision: 1,
        designMessageId: 'requirement-snapshot-r1', answeredAt: '2026-08-18T04:00:00Z',
        questions: [{ question: '需要覆盖哪些边界？', header: '需求范围', multiple: false, custom: false,
          options: [{ label: '并发与恢复', description: '覆盖并发和失败恢复' }], answers: ['并发与恢复'] }],
      }],
      messages: [
        { id: 'user', role: 'USER', actor: 'USER', content: '设计缓存刷新任务', deliveryState: 'PERSISTED', createdAt: '2026-08-18T03:00:00Z' },
        { id: 'requirement-snapshot-r1', role: 'SYSTEM', actor: 'SYSTEM', content: '# 权威需求快照',
          deliveryState: 'SERVER_REQUIREMENT_SNAPSHOT', createdAt: '2026-08-18T04:01:00Z' },
        { id: 'system-after-r1', role: 'SYSTEM', actor: 'SYSTEM', content: '任务设置已确认',
          deliveryState: 'PERSISTED', createdAt: '2026-08-18T04:02:00Z' },
        { id: 'system-later', role: 'SYSTEM', actor: 'SYSTEM', content: '后续设计流程已完成',
          deliveryState: 'PERSISTED', createdAt: '2026-08-18T05:00:00Z' },
      ],
    }
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(discussionSession)
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue(discussionSession)
    const wrapper = mountDesigner()
    await flushPromises()
    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('设计缓存刷新任务')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    const history = wrapper.getComponent(DesignerDiscussionHistory)
    const systemHistory = wrapper.getComponent(DesignerSystemMessageHistory)
    const children = Array.from(wrapper.get('.chat-history').element.children)
    expect(wrapper.text()).not.toContain('权威需求快照')
    expect(history.text()).toContain('需要覆盖哪些边界？')
    expect(systemHistory.text()).toContain('任务设置已确认')
    expect(systemHistory.text()).toContain('后续设计流程已完成')
    expect(children.indexOf(history.element)).toBeLessThan(children.indexOf(systemHistory.element))
  })

  it('keeps package feedback scoped and requires explicit acceptance before continuing', async () => {
    const packageSession: DesignerSession = {
      ...session, state: 'REVIEWING', workflowPhase: 'REVIEWING_PACKAGE', activeActor: 'VALIDATOR',
      requirementRevision: 1, activeWorkPackageId: 'WP-1', discussionScope: 'WP-1', discussionRevision: 2,
      finalConfirmationEligible: false,
      workPackages: [{ id: 'WP-1', ordinal: 0, title: '查询能力', objective: '交付查询结果',
        dependencies: [], state: 'REVIEWING', redesignCount: 0, compilerRepairCount: 0,
        compilerPlanningRepairCount: 0, designRevision: 3, discussionRoundCount: 1,
        rolePackId: 'software-python', rolePackVersion: '2026-08-dynamic-v4',
        testPolicy: 'OPTIONAL', executionStrategy: 'OPEN_CODE_IMPLEMENTATION', technologies: ['python'] }],
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
    expect(wrapper.text()).toContain('当前作用域：工作包 1')
    expect(wrapper.text()).toContain('已同步')
    expect(wrapper.get('[aria-label="工作包设计轨道"]').text()).toContain('Python 软件开发 · python · 可选测试')

    await wrapper.get('textarea[aria-label="发送给只读设计师的消息"]').setValue('只补充 WP-1 的异常边界')
    await wrapper.get('.compose-actions button').trigger('click')
    await flushPromises()
    expect(sendPackage).toHaveBeenCalledWith(packageSession.id, 'WP-1', '只补充 WP-1 的异常边界', 2, 3)

    await wrapper.findAll('button').find((button) => button.text().includes('接受工作包 1并继续'))!.trigger('click')
    await flushPromises()
    expect(approve).toHaveBeenCalledWith(packageSession.id, 'WP-1', 2, 3)
    wrapper.unmount()
  })

  it('shows acceptance intent coverage without exposing internal fact or capability ids', async () => {
    const acceptanceSession: DesignerSession = {
      ...session, state: 'RUNNING', workflowPhase: 'COMPILING', activeActor: 'COMPILER',
      requirementRevision: 1, activeWorkPackageId: 'WP-1', discussionScope: 'WP-1', finalConfirmationEligible: false,
      workPackages: [{ id: 'WP-1', ordinal: 0, title: 'PIN 转换', objective: '交付 PIN 转换行为',
        dependencies: [], state: 'COMPILING', redesignCount: 0, compilerRepairCount: 0,
        compilerPlanningRepairCount: 0, designRevision: 1, discussionRoundCount: 0,
        acceptancePlanning: { state: 'EXTRACTED', bindingSource: 'AI_DISAMBIGUATION_V6',
          routingReasons: ['存在尚未归属阶段的验收事实'], factCount: 7, scenarioCount: 4, automatedCount: 3,
          bothCount: 0, judgeCount: 0, unresolvedCount: 1,
          mutationObligationCount: 2, resolvedMutationObligationCount: 1,
            unresolvedMutationObligationCount: 1, pathConservation: 'BLOCKED',
            mutationBindingReasons: [
              'config/external-adapter.yml 可由多个阶段承接，需要在阶段表中明确引用：适配配置、回退配置',
            ],
          scenarios: [
            { title: 'pinBlock 路径缺失', coverage: 'AUTOMATED', capabilities: ['MAVEN · PinTransTest'] },
            { title: '特殊渠道类型', coverage: 'UNRESOLVED', capabilities: [] },
          ], issues: ['VERIFICATION_CAPABILITY_UNAVAILABLE:[6]', 'MUTATION_PATH_SCOPE_CONFLICT',
            'AMBIGUOUS_MUTATION_PATH_SCOPE'] } }],
    }
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(acceptanceSession)
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue(acceptanceSession)
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('设计 PIN 转换')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    const card = wrapper.get('[aria-label="验收意图识别"]')
    expect(card.text()).toContain('4 个场景 · 7 项设计事实')
    expect(card.text()).toContain('规范工程师辅助消歧')
    expect(card.text()).toContain('机器 3')
    expect(card.text()).toContain('待覆盖 1')
    expect(card.text()).toContain('路径待归属 1')
    expect(card.text()).toContain('config/external-adapter.yml')
    expect(card.text()).toContain('适配配置、回退配置')
    expect(card.text()).toContain('pinBlock 路径缺失')
    expect(card.text()).toContain('MAVEN · PinTransTest')
    expect(card.text()).toContain('部分验收场景缺少可执行的验证能力')
    expect(card.text()).toContain('必改路径与禁止修改范围冲突')
    expect(card.text()).toContain('同一路径的修改与保持不变要求冲突')
    expect(card.text()).not.toContain('VERIFICATION_CAPABILITY_UNAVAILABLE')
    expect(card.text()).not.toContain('MUTATION_PATH_SCOPE_CONFLICT')
    expect(card.text()).not.toContain('AMBIGUOUS_MUTATION_PATH_SCOPE')
    expect(card.text()).not.toContain('[6]')
    expect(card.findAll('.acceptance-intent-issue').length).toBeGreaterThan(0)
    expect(card.find('.acceptance-history-details').exists()).toBe(false)
  })

  it('labels server direct compilation and does not mount remote activity polling', async () => {
    const serverSession: DesignerSession = {
      ...session, state: 'RUNNING', workflowPhase: 'COMPILING', activeActor: 'COMPILER',
      requirementRevision: 1, activeWorkPackageId: 'WP-1', discussionScope: 'WP-1', finalConfirmationEligible: false,
      compiler: { id: 'compiler-server', state: 'RUNNING', externalSessionState: 'SERVER_DIRECT', repairCount: 0,
        designRevision: 1, workPackageId: 'WP-1', workflowStep: 'SERVER_COMPILING', planningRepairCount: 0,
        formatRepairCount: 0, semanticRepairCount: 0, serverCompiled: true },
      workPackages: [{ id: 'WP-1', ordinal: 0, title: '服务端编译', objective: '直接编译完整阶段',
        dependencies: [], state: 'COMPILING', redesignCount: 0, compilerRepairCount: 0,
        compilerPlanningRepairCount: 0, designRevision: 1, discussionRoundCount: 0,
        acceptancePlanning: { state: 'COMPILED', bindingSource: 'SERVER_STAGE_HINTS',
          routingReasons: ['验收绑定需要补充设计', '验收绑定需要补充设计'],
          factCount: 2, scenarioCount: 1, automatedCount: 1, bothCount: 0, judgeCount: 0,
          unresolvedCount: 0, mutationObligationCount: 2, resolvedMutationObligationCount: 2,
          unresolvedMutationObligationCount: 0, pathConservation: 'CONSERVED', mutationBindingReasons: [
            'src/main/java/example/Registry.java：阶段负责路径声明，归属阶段：阶段 1：注册',
            'src/main/java/example/Scheduler.java：唯一阶段路径覆盖，归属阶段：阶段 2：调度',
          ],
          scenarios: [{ title: '成功路径', coverage: 'AUTOMATED', capabilities: ['Vitest'] }],
          issues: [] } }],
    }
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(serverSession)
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue(serverSession)
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('设计服务端快速编译')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('服务端直接编译')
    expect(wrapper.find('.designer-current-activity-stub').exists()).toBe(false)
    const card = wrapper.get('[aria-label="验收意图识别"]')
    expect(card.text()).toContain('已确定性编译')
    expect(card.findAll('.warning')).toHaveLength(0)
    expect(card.get('.acceptance-proof-details summary').text()).toContain('已证明路径归属 2 条')
    expect(card.findAll('.acceptance-intent-proof')).toHaveLength(2)
    expect(card.get('.acceptance-history-details summary').text()).toContain('历史消歧说明 1 条')
    expect(card.get('.acceptance-history-details summary').text()).toContain('当前已解决')
    expect(card.find('.acceptance-intent-issue').exists()).toBe(false)
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
    expect(wrapper.text()).not.toContain('SYSTEM_ERROR')
    const systemHistory = wrapper.getComponent(DesignerSystemMessageHistory)
    expect(systemHistory.get('details').attributes('open')).toBeUndefined()
    expect(systemHistory.get('summary').text()).toContain('系统消息 1 条')
    expect(systemHistory.classes()).toContain('error')
    expect(systemHistory.text()).toContain('错误，请按页面提示处理后重试')
  })

  it('collects consecutive system notices into one disclosure even when their scope metadata changes', async () => {
    const sessionWithGroupedNotices: DesignerSession = {
      ...session,
      messages: [
        { id: 'user', role: 'USER', actor: 'USER', content: '请设计事件系统', deliveryState: 'PERSISTED', requirementRevision: 1, createdAt: '2026-08-19T08:00:00Z' },
        { id: 'system-1', role: 'SYSTEM', actor: 'SYSTEM', content: '全自动模式已授权', deliveryState: 'PERSISTED', requirementRevision: 1, createdAt: '2026-08-19T08:01:00Z' },
        { id: 'system-2', role: 'SYSTEM', actor: 'SYSTEM', content: '任务画像已生成', deliveryState: 'PERSISTED', requirementRevision: 1, createdAt: '2026-08-19T08:02:00Z' },
        { id: 'designer', role: 'ASSISTANT', actor: 'DESIGNER', content: '# 完整需求稿', deliveryState: 'PERSISTED', requirementRevision: 1, createdAt: '2026-08-19T08:03:00Z' },
        { id: 'system-3', role: 'SYSTEM', actor: 'SYSTEM', content: '正在重新计算任务画像', deliveryState: 'PERSISTED', requirementRevision: 2, createdAt: '2026-08-19T08:04:00Z' },
        { id: 'system-4', role: 'SYSTEM', actor: 'SYSTEM', content: '整体需求已确认', deliveryState: 'PERSISTED', requirementRevision: 3, workPackageId: 'WP-1', createdAt: '2026-08-19T08:05:00Z' },
      ],
    }
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(sessionWithGroupedNotices)
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('设计事件系统')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    const histories = wrapper.findAllComponents(DesignerSystemMessageHistory)
    expect(histories).toHaveLength(2)
    expect(histories.map((history) => history.props('entries').map((entry) => entry.id))).toEqual([
      ['system-1', 'system-2'], ['system-3', 'system-4'],
    ])
    expect(histories.map((history) => history.get('summary').text())).toEqual([
      '系统消息 2 条', '系统消息 2 条',
    ])
    expect(histories.every((history) => history.get('details').attributes('open') === undefined)).toBe(true)

    await histories[0]!.get('summary').trigger('click')
    expect(histories[0]!.get('details').attributes('open')).toBe('')
    expect(histories[0]!.text()).toContain('全自动模式已授权')
    expect(histories[0]!.text()).toContain('任务画像已生成')
    expect(histories[0]!.text()).not.toContain('正在重新计算任务画像')
  })

  it('restores distinct role cards, hides compiler JSON, and exposes both recovery actions', async () => {
    const failedSession: DesignerSession = {
      ...session,
      state: 'SESSION_ERROR', workflowPhase: 'FAILED', activeActor: 'SYSTEM',
      compiler: { id: 'compiler-1', state: 'SESSION_ERROR', externalSessionState: 'FAILED', repairCount: 2, planningRepairCount: 0, designRevision: 1, workflowStep: 'FINAL_JSON' },
      answeredQuestions: [{
        id: 'question-1', scope: 'REQUIREMENT', discussionRevision: 1, answeredAt: '2026-08-18T04:00:00Z',
        questions: [{
          question: '选择缓存刷新范围', header: '范围', multiple: false, custom: false,
          options: [{ label: '完整刷新', description: '覆盖全部缓存条目' }], answers: ['完整刷新'],
        }],
      }],
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
    expect(wrapper.get('.chat-designer').text()).toContain('设计师')
    expect(wrapper.get('.chat-compiler').text()).toContain('规范工程师')
    const systemHistory = wrapper.getComponent(DesignerSystemMessageHistory)
    expect(systemHistory.get('details').attributes('open')).toBeUndefined()
    expect(systemHistory.get('summary').text()).toContain('系统消息')
    expect(systemHistory.text()).toContain('服务端状态通知')
    const discussion = wrapper.getComponent(DesignerDiscussionHistory)
    const historyChildren = Array.from(wrapper.get('.chat-history').element.children)
    expect(historyChildren.indexOf(discussion.element)).toBeLessThan(historyChildren.indexOf(wrapper.get('.chat-designer').element))
    const validatorHistory = wrapper.getComponent(DesignerValidatorHistory)
    expect(wrapper.findAllComponents(DesignerValidatorHistory)).toHaveLength(1)
    expect(validatorHistory.get('details').attributes('open')).toBeUndefined()
    expect(validatorHistory.get('summary').text()).toContain('确定性校验')
    expect(validatorHistory.get('summary').text()).toContain('3 条')
    expect(wrapper.find('.chat-validator').exists()).toBe(false)
    expect(wrapper.get('.validator-retryable_error').text()).toContain('字段校验失败，可修复')
    expect(wrapper.get('.validator-normalized').text()).toContain('输出包装已自动规范化')
    expect(wrapper.get('.validator-terminal_error').text()).toContain('工作流已停止')
    expect(wrapper.text()).not.toContain('"loopSpec":"secret"')
    expect(wrapper.get('.designer-connection-strip').text()).toContain('规范编译修复 0/2 · 语义修复 0/2')

    await wrapper.findAll('button').find((button) => button.text().includes('重新编译当前设计'))!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text().includes('让设计师重新设计'))!.trigger('click')
    await flushPromises()
    expect(retry).toHaveBeenCalledWith(failedSession.id)
    expect(redesign).toHaveBeenCalledWith(failedSession.id)
  })

  it('does not expose compiler recovery actions when requirement questioning failed before compilation', async () => {
    const failedQuestionSession: DesignerSession = {
      ...session,
      state: 'WAITING_INPUT', workflowPhase: 'FAILED', activeActor: 'SYSTEM',
      finalConfirmationEligible: false,
      compiler: undefined,
      messages: [{
        id: 'terminal-question', role: 'SYSTEM', actor: 'SYSTEM',
        content: 'DESIGN_QUESTION_REQUIRED: Designer completed without asking the required question',
        deliveryState: 'TERMINAL_ERROR', createdAt: 'now',
      }],
    }
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(failedQuestionSession)
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue(failedQuestionSession)
    const wrapper = mountDesigner()
    await flushPromises()
    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('讨论缓存刷新策略')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('设计前需要先回答问题')
    expect(wrapper.text()).not.toContain('重新编译当前设计')
    expect(wrapper.text()).not.toContain('让设计师重新设计')
    expect(wrapper.text()).toContain('清理工作区')
  })

  it('restores the Decomposer card, package rail, retry counters, and waiting-input boundary', async () => {
    const decomposedSession: DesignerSession = {
      ...session,
      state: 'WAITING_INPUT', workflowPhase: 'FAILED', activeActor: 'VALIDATOR',
      finalConfirmationEligible: false,
      requirementRevision: 3, activeWorkPackageId: 'WP-2',
      requirement: { revision: 3, state: 'WAITING_INPUT', modelCallsUsed: 9, maxModelCalls: 32, sourceDraftVersion: 4 },
      decomposition: { id: 'decomposition-3', state: 'COMPLETED', resultType: 'DECOMPOSED', repairCount: 1, planningRepairCount: 1, transportRetryCount: 0, workflowStep: 'FINAL_JSON', candidateSessions: 2, candidateSubmissions: 5 },
      compiler: { id: 'compiler-wp2', state: 'SESSION_ERROR', externalSessionState: 'FAILED', repairCount: 2, planningRepairCount: 2, designRevision: 1, workPackageId: 'WP-2', workflowStep: 'FINAL_JSON' },
      workPackages: [
        { id: 'WP-1', ordinal: 0, title: '查询能力', objective: '可查询结果', dependencies: [], state: 'COMPLETED', redesignCount: 0, compilerRepairCount: 0, compilerPlanningRepairCount: 0, designRevision: 1, discussionRoundCount: 0, compilationSource: 'MARKDOWN_FALLBACK', fallbackReason: 'FEATURE_DISABLED', serverCompiled: false },
        { id: 'WP-2', ordinal: 1, title: '变更能力', objective: '可变更结果', dependencies: ['WP-1'], state: 'WAITING_INPUT', redesignCount: 1, compilerRepairCount: 2, compilerPlanningRepairCount: 2, designRevision: 1, discussionRoundCount: 0, lastErrorCode: 'COMPILER_RETRY_EXHAUSTED', candidateRunState: 'ACCEPTED', candidateSessions: 1, candidateSubmissions: 2, compilationSource: 'MCP_ACCEPTED', serverCompiled: true },
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

    expect(wrapper.get('.chat-decomposer').text()).toContain('任务规划师')
    expect(wrapper.get('[aria-label="工作包设计轨道"]').text()).toContain('工作包 1')
    expect(wrapper.get('[aria-label="工作包设计轨道"]').text()).not.toContain('WP-1')
    expect(wrapper.get('[aria-label="工作包设计轨道"]').text()).toContain('讨论 0/5 · 设计 R1')
    const statusStrip = wrapper.get('.designer-connection-strip').text()
    expect(statusStrip).toContain('模型调用 9/32')
    expect(statusStrip).toContain('候选 Session 2')
    expect(statusStrip).toContain('候选提交 5')
    expect(statusStrip).not.toContain('模型调用 14/32')
    const packageRail = wrapper.get('[aria-label="工作包设计轨道"]').text()
    expect(packageRail).toContain('Markdown 兜底')
    expect(packageRail).toContain('兜底原因：功能未启用')
    expect(packageRail).toContain('MCP 候选已接受')
    expect(packageRail).toContain('服务端已编译')
    expect(packageRail).toContain('候选状态：已接受')
    expect(packageRail).toContain('候选会话 1')
    expect(packageRail).toContain('候选修正 2')
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

  it('blocks unchanged recompilation and opens targeted package feedback for mutation ownership gaps', async () => {
    const mutationGapSession: DesignerSession = {
      ...session,
      state: 'WAITING_INPUT', workflowPhase: 'FAILED', activeActor: 'SYSTEM',
      finalConfirmationEligible: false,
      requirementRevision: 2, activeWorkPackageId: 'WP-1', discussionScope: 'WP-1', discussionRevision: 4,
      workPackages: [{
        id: 'WP-1', ordinal: 0, title: '定时任务框架', objective: '交付单包多阶段定时任务框架',
        dependencies: [], state: 'WAITING_INPUT', redesignCount: 0, compilerRepairCount: 0,
        compilerPlanningRepairCount: 0, designRevision: 2, discussionRoundCount: 0,
        lastErrorCode: 'DESIGN_INCOMPLETE',
        acceptancePlanning: {
          state: 'FAILED', bindingSource: 'SERVER_STAGE_HINTS', routingReasons: ['存在尚未归属阶段的验收事实'],
          factCount: 47, scenarioCount: 18, automatedCount: 16, bothCount: 0, judgeCount: 2,
          unresolvedCount: 0, mutationObligationCount: 24, resolvedMutationObligationCount: 0,
          unresolvedMutationObligationCount: 24, pathConservation: 'BLOCKED',
          mutationBindingReasons: [
            'src/main/java/com/spdb/upfs/schedule/ScheduleSceneEnum.java：必改路径没有可证明的阶段归属',
          ],
          scenarios: [], issues: ['REQUIRED_MUTATION_PATH_UNASSIGNED'],
        },
      }],
      messages: [{
        id: 'mutation-gap', role: 'SYSTEM', actor: 'SYSTEM',
        content: '工作流等待人工处理：必改路径没有可证明的阶段归属',
        deliveryState: 'DESIGN_INCOMPLETE', requirementRevision: 2, workPackageId: 'WP-1', createdAt: 'now',
      }],
    }
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue(mutationGapSession)
    vi.spyOn(api, 'getDesignerSession').mockResolvedValue(mutationGapSession)
    const retry = vi.spyOn(api, 'retryWorkPackageCompiler').mockResolvedValue(undefined)
    const redesign = vi.spyOn(api, 'redesignWorkPackage').mockResolvedValue(undefined)
    const sendPackage = vi.spyOn(api, 'sendWorkPackageMessage').mockResolvedValue({
      sessionId: mutationGapSession.id, state: 'RUNNING', persistedMessages: [], notice: 'saved',
    })
    const wrapper = mountDesigner()
    await flushPromises()
    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('设计一套定时任务框架')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()

    const retryButton = wrapper.findAll('button')
      .find((button) => button.text().includes('重新编译当前包'))!
    const redesignButton = wrapper.findAll('button')
      .find((button) => button.text().includes('恢复当前包设计'))!
    expect(retryButton.attributes('disabled')).toBeDefined()
    expect(redesignButton.attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).toContain('当前设计稿未变化，不能原样重编译')
    expect(wrapper.text()).toContain('补充阶段负责路径')

    const input = wrapper.get('textarea[aria-label="发送给只读设计师的消息"]')
    expect(input.attributes('disabled')).toBeUndefined()
    await input.setValue('请将 ScheduleSceneEnum.java 明确归入任务定义阶段')
    await wrapper.get('.compose-actions button').trigger('click')
    await flushPromises()

    expect(sendPackage).toHaveBeenCalledWith(mutationGapSession.id, 'WP-1',
      '请将 ScheduleSceneEnum.java 明确归入任务定义阶段', 4, 2)
    expect(retry).not.toHaveBeenCalled()
    expect(redesign).not.toHaveBeenCalled()
  })

  it('clears the restored workspace and local message drafts when starting over', async () => {
    vi.spyOn(api, 'createDesignerSession').mockResolvedValue({
      ...session, state: 'REVIEWING', workflowPhase: 'DISCUSSING_REQUIREMENT',
      discussionScope: 'REQUIREMENT', finalConfirmationEligible: false,
    })
    vi.spyOn(api, 'createDraft').mockImplementation(async (spec) => draftFrom(spec))
    const stop = vi.spyOn(api, 'stopDesignerSession').mockResolvedValue({
      stopStatus: 'CANCELLED', archived: true, stoppedSessions: 1, failedSessions: 0, pendingFinalizations: 0,
    })
    const confirmation = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mountDesigner()
    await flushPromises()

    await wrapper.get('textarea[aria-label="草案设计目标"]').setValue('这是需要清理的旧设计')
    await wrapper.get('.create-draft-button').trigger('click')
    await flushPromises()
    await wrapper.get('textarea[aria-label="发送给只读设计师的消息"]').setValue('未发送的旧补充')

    expect(sessionStorage.getItem('opencode-loopper.designer-workspace')).toContain(session.id)
    expect(sessionStorage.getItem('opencode-loopper.designer-message-draft')).toBe('未发送的旧补充')

    await wrapper.get('.restart-designer-button').trigger('click')
    await flushPromises()

    expect(confirmation).toHaveBeenCalled()
    expect(stop).toHaveBeenCalledWith(session.id)
    expect(sessionStorage.getItem('opencode-loopper.designer-workspace')).toBeNull()
    expect(sessionStorage.getItem('opencode-loopper.designer-draft-prompt')).toBeNull()
    expect(sessionStorage.getItem('opencode-loopper.designer-message-draft')).toBeNull()
    expect(wrapper.find('textarea[aria-label="发送给只读设计师的消息"]').exists()).toBe(false)
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
    const leaveConfirmation = vi.spyOn(ElMessageBox, 'confirm')
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
    expect(sessionStorage.getItem('opencode-loopper.designer-workspace')).toBeNull()
    expect(await routeLeave.callback?.()).toBe(true)
    expect(leaveConfirmation).not.toHaveBeenCalled()
  })

  it('renders the coverage matrix and blocks save when a v2 criterion is uncovered', async () => {
    const criterionDescription = '当构造点位于 catch 块且目标调用未传递 Throwable 时，执行链路仍应保留原始异常语义和消息文本'
    const invalidSpec: LoopSpec = {
      schemaVersion: 'v2', projectId: project.id, goal: '严格验收', context: '',
      stages: [{ objective: '实现', implementationKind: 'NON_JAVA', allowedPaths: [], forbiddenPaths: [], deliverables: ['实现'],
        acceptanceCriteria: [{ id: 'AC-1', description: criterionDescription }],
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
        criteria: [{ id: 'AC-1', description: criterionDescription, verificationMode: 'MACHINE', covered: false,
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
    const matrix = wrapper.get('[aria-label="双重验收计划矩阵"]')
    const criterionRow = matrix.get('.matrix-criterion-row')
    expect(criterionRow.element.children).toHaveLength(2)
    expect(criterionRow.get('.matrix-criterion-description').text()).toBe(criterionDescription)
    expect(criterionRow.get('.matrix-criterion-statuses').findAll('em, b')).toHaveLength(3)
    expect(matrix.text()).toContain('机器：不适用')
    expect(matrix.text()).toContain('BUILD')
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
