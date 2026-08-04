import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DesignerView from '@/views/DesignerView.vue'
import { api } from '@/api/client'
import { useTaskStore } from '@/stores/taskStore'
import type { DesignerSession, LoopDraft, LoopSpec, Project } from '@/types/domain'

vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))

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

    expect(createSession).toHaveBeenCalledWith(project.id, initialGoal)
    expect(createDraft.mock.calls[0]?.[0].goal).toBe(initialGoal)
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
})
