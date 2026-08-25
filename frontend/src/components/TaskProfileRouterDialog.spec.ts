import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElDialog } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TaskProfileRouterDialog from '@/components/TaskProfileRouterDialog.vue'
import { api } from '@/api/client'
import type { DesignerSession } from '@/types/domain'

const baseSession: DesignerSession = {
  id: 'designer-router', projectId: 'project-1', projectName: 'Loopper', state: 'PENDING_HANDOFF',
  workflowPhase: 'ROUTING', activeActor: 'ROUTER', accessMode: 'READ_ONLY', readOnly: true,
  discussionScope: 'REQUIREMENT', discussionRevision: 0, finalConfirmationEligible: false,
  autoMode: { enabled: false, state: 'DISABLED', version: 0 },
  questionInteraction: { mode: 'NONE', awaitingAnswer: false },
  taskProfile: {
    id: 'profile-1', state: 'ROUTING', decisionState: 'ROUTING', confirmationReady: false,
    intent: 'SOFTWARE_CHANGE', workflowTemplate: 'DIRECT_SOFTWARE_DESIGN', mutationMode: 'WRITE_CODE',
    artifactKinds: ['SOURCE_CODE'], technologies: ['java'], testPolicy: 'REQUIRED',
    executionStrategy: 'OPEN_CODE_IMPLEMENTATION', rolePackId: 'software-java', rolePackVersion: 'test',
    confidence: 0, evidence: [], resolutionSource: 'ROUTER', decisionRequired: true,
    largeTaskMode: false, version: 2,
  },
  availableProfileOverrides: ['SOFTWARE_CHANGE', 'DOCUMENT_AUTHORING'],
  availableArtifactOverrides: ['SOURCE_CODE', 'MARKDOWN'], reports: [], messages: [],
}

function mountDialog(session: DesignerSession): VueWrapper {
  return mount(TaskProfileRouterDialog, {
    props: { modelValue: true, session },
    attachTo: document.body,
    global: { plugins: [ElementPlus] },
  })
}

function bodyText() {
  return document.body.textContent ?? ''
}

async function clickButton(label: string) {
  const button = [...document.body.querySelectorAll('button')]
    .find(item => item.textContent?.trim() === label) as HTMLButtonElement | undefined
  expect(button, `button ${label}`).toBeDefined()
  button!.click()
  await flushPromises()
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-08-25T07:00:30Z'))
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

describe('TaskProfileRouterDialog', () => {
  it('locks the running dialog and displays real activity, elapsed time, and provider tokens without a timeout limit', async () => {
    const session: DesignerSession = {
      ...baseSession,
      routerRun: {
        id: 'run-active', state: 'RUNNING', externalState: 'RUNNING',
        createdAt: '2026-08-25T07:00:00Z', updatedAt: '2026-08-25T07:00:20Z',
        retryAvailable: false,
      },
    }
    vi.spyOn(api, 'getDesignerActivity').mockResolvedValue({
      actor: 'ROUTER', connected: true, remoteState: 'RUNNING', observedAt: '2026-08-25T07:00:30Z', detail: '正在识别',
      parts: [{ id: 'part-1', type: 'THINKING', label: '最新思考', content: '正在分析 Maven 多模块结构' }],
      usage: { totalTokens: 15, unknownUsageCount: 0, observedAt: '2026-08-25T07:00:30Z' },
    })

    const wrapper = mountDialog(session)
    await flushPromises()

    const dialog = wrapper.getComponent(ElDialog)
    expect(dialog.props('closeOnClickModal')).toBe(false)
    expect(dialog.props('closeOnPressEscape')).toBe(false)
    expect(dialog.props('showClose')).toBe(false)
    expect(bodyText()).toContain('已用 30 秒')
    expect(bodyText()).not.toContain('上限')
    expect(bodyText()).not.toContain('超时')
    expect(bodyText()).toContain('正在分析 Maven 多模块结构')
    expect(bodyText()).toContain('15')
    expect(api.getDesignerActivity).toHaveBeenCalledWith(session.id)
    wrapper.unmount()
  })

  it('separates confidence from Java and exposes confirm, reroute, and manual modification', async () => {
    const session: DesignerSession = {
      ...baseSession,
      workflowPhase: 'ROUTING',
      taskProfile: {
        ...baseSession.taskProfile, state: 'PROVISIONAL', decisionState: 'NEEDS_CONFIRMATION',
        technologies: ['java'], confidence: 0, confirmationReady: false,
      },
      routerRun: {
        id: 'run-completed', state: 'COMPLETED', externalState: 'COMPLETED',
        createdAt: '2026-08-25T07:00:00Z', updatedAt: '2026-08-25T07:00:20Z',
        retryAvailable: true,
      },
    }
    const wrapper = mountDialog(session)
    await flushPromises()

    const fields = [...document.body.querySelectorAll('.profile-result-grid article')]
    expect(fields[0]!.textContent).toBe('识别置信度0%')
    expect(fields[1]!.textContent).toBe('技术栈java')
    await clickButton('重新识别')
    await clickButton('确认并进入设计')
    expect(wrapper.emitted('reroute')).toHaveLength(1)
    expect(wrapper.emitted('confirm')).toHaveLength(1)

    await clickButton('手动修改')
    expect(document.body.querySelectorAll('.router-profile-edit .el-select')).toHaveLength(2)
    await clickButton('保存并进入设计')
    expect(wrapper.emitted('save')?.[0]).toEqual([{
      intent: 'SOFTWARE_CHANGE', artifact: 'SOURCE_CODE', largeTaskMode: false, componentKeys: [],
    }])
    wrapper.unmount()
  })

  it('shows a comprehensible warning for a failed Router run', async () => {
    const wrapper = mountDialog({
      ...baseSession,
      taskProfile: {
        ...baseSession.taskProfile, state: 'PROVISIONAL', decisionState: 'NEEDS_CONFIRMATION',
        evidence: ['router-error=ROUTER_TIMEOUT'], resolutionSource: 'ROUTER_FALLBACK',
      },
      routerRun: {
        id: 'run-failed', state: 'FAILED', externalState: 'FAILED', errorCode: 'ROUTER_START_FAILED',
        errorDetail: '任务设置识别未能连接远端 Session', createdAt: '2026-08-25T07:00:00Z',
        updatedAt: '2026-08-25T07:04:01Z', retryAvailable: true,
      },
    })
    await flushPromises()

    expect(bodyText()).toContain('本次识别未能可靠完成')
    expect(bodyText()).toContain('任务设置识别未能连接远端 Session')
    wrapper.unmount()
  })
})
