import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import TaskDecisionPanel from './TaskDecisionPanel.vue'
import type { TaskDecision } from '@/types/domain'

const mocks = vi.hoisted(() => ({
  getTaskDecision: vi.fn(),
  continueTaskDecision: vi.fn(),
  deriveTaskDecision: vi.fn(),
  auditTaskDecision: vi.fn(),
  acceptTaskDecision: vi.fn(),
  cancelTaskDecision: vi.fn(),
}))

vi.mock('@/api/client', () => ({ api: mocks }))

const failedDecision: TaskDecision = {
  taskId: 'task-1',
  taskState: 'AWAITING_DECISION',
  taskVersion: 7,
  cycle: {
    id: 'cycle-2', ordinal: 2, kind: 'CONTINUE_FAILED', result: 'FAILED', version: 3,
    authorizedAt: '2026-08-18T01:00:00Z',
    startedAt: '2026-08-18T01:00:00Z', endedAt: '2026-08-18T01:01:00Z',
  },
  checkpoint: { id: 'checkpoint-2', state: 'READY', changedFileCount: 4, updatedAt: '2026-08-18T01:01:00Z', version: 2 },
  stages: [{ id: 'stage-1', ordinal: 0, objective: '修复接口', state: 'FAILED' }],
  availableActions: ['CONTINUE_CURRENT_TASK', 'DERIVE_INHERIT_CHANGES', 'DERIVE_REWORK_ALL', 'READ_ONLY_AUDIT', 'CANCEL'],
}

describe('TaskDecisionPanel', () => {
  beforeEach(() => {
    mocks.getTaskDecision.mockResolvedValue(failedDecision)
    mocks.continueTaskDecision.mockResolvedValue(failedDecision)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  })

  afterEach(() => {
    document.body.innerHTML = ''
    vi.restoreAllMocks()
    vi.clearAllMocks()
  })

  it('shows every safe failure disposition and starts a fresh execution cycle on the same task', async () => {
    const wrapper = mount(TaskDecisionPanel, {
      props: { taskId: 'task-1' }, global: { plugins: [ElementPlus] }, attachTo: document.body,
    })
    await flushPromises()

    expect(wrapper.text()).toContain('第 2 轮 · 执行失败')
    expect(wrapper.text()).toContain('新任务继承修改')
    expect(wrapper.text()).toContain('新任务全部重做')
    expect(wrapper.text()).toContain('直接审计')

    const continueButton = wrapper.findAll('button').find((button) => button.text().includes('继续当前任务'))
    await continueButton!.trigger('click')
    await flushPromises()

    expect(mocks.continueTaskDecision).toHaveBeenCalledWith('task-1', {
      expectedTaskVersion: 7,
      expectedCycleVersion: 3,
      stageId: undefined,
      supplementalRequirement: undefined,
    })
  })

  it('requires a supplemental requirement before continuing a successful result', async () => {
    mocks.getTaskDecision.mockResolvedValue({
      ...failedDecision,
      cycle: { ...failedDecision.cycle!, result: 'SUCCEEDED', kind: 'CONTINUE_SUCCESS' },
      availableActions: ['CONTINUE_CURRENT_TASK', 'CANCEL'],
    })
    const wrapper = mount(TaskDecisionPanel, {
      props: { taskId: 'task-1' }, global: { plugins: [ElementPlus] }, attachTo: document.body,
    })
    await flushPromises()

    const continueButton = wrapper.findAll('button').find((button) => button.text().includes('继续当前任务'))
    await continueButton!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('请选择起始阶段并填写补充要求')
    expect(mocks.continueTaskDecision).not.toHaveBeenCalled()
  })
})
