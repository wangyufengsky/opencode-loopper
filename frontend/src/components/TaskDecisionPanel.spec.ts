import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
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

enableAutoUnmount(afterEach)

const confirmed = Object.assign('confirm' as const, { value: '', action: 'confirm' as const })

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

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

  it('does not present an unknown historical change count as a file count or an acceptance action', async () => {
    mocks.getTaskDecision.mockResolvedValue({ ...failedDecision,
      cycle: { ...failedDecision.cycle!, result: 'SUCCEEDED' },
      checkpoint: { ...failedDecision.checkpoint!, changedFileCount: -1 },
      availableActions: ['CONTINUE_CURRENT_TASK', 'CANCEL'],
    })
    const wrapper = mount(TaskDecisionPanel, {
      props: { taskId: 'task-1' }, global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('变更数量未确认')
    expect(wrapper.text()).not.toContain('-1 个变更文件')
    expect(wrapper.findAll('button').some(button => button.text().includes('接受结果'))).toBe(false)
    wrapper.unmount()
  })

  it('submits the frozen result versions through the dedicated cancellation endpoint', async () => {
    const info = vi.spyOn(ElMessage, 'info')
    mocks.cancelTaskDecision.mockResolvedValue({
      ...failedDecision,
      taskState: 'STOPPING',
      taskVersion: 8,
      availableActions: [],
    })
    const wrapper = mount(TaskDecisionPanel, {
      props: { taskId: 'task-1' }, global: { plugins: [ElementPlus] }, attachTo: document.body,
    })
    await flushPromises()

    const cancelButton = wrapper.findAll('button').find((button) => button.text().includes('取消任务'))
    await cancelButton!.trigger('click')
    await flushPromises()

    expect(mocks.cancelTaskDecision).toHaveBeenCalledWith('task-1', {
      expectedTaskVersion: 7,
      expectedCycleVersion: 3,
    })
    expect(info).toHaveBeenCalledWith('取消请求已保存，正在等待远端写入者停止确认')
  })
  it.each([
    ['继续当前任务', 'continueTaskDecision'],
    ['新任务继承修改', 'deriveTaskDecision'],
    ['新任务全部重做', 'deriveTaskDecision'],
    ['直接审计', 'auditTaskDecision'],
    ['接受结果', 'acceptTaskDecision'],
    ['取消任务', 'cancelTaskDecision'],
  ] as const)('invalidates %s confirmation after changing the task', async (label, method) => {
    const first: TaskDecision = { ...failedDecision, cycle: { ...failedDecision.cycle!, result: 'SUCCEEDED' },
      availableActions: [...failedDecision.availableActions, 'ACCEPT_RESULT'] }
    mocks.getTaskDecision.mockResolvedValueOnce(first).mockResolvedValueOnce({ ...first, taskId: 'task-2', taskVersion: 9 })
    const confirmation = deferred<typeof confirmed>()
    vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(confirmation.promise)
    const wrapper = mount(TaskDecisionPanel, { props: { taskId: 'task-1' }, global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('textarea').setValue('原任务补充要求')
    await wrapper.findAll('button').find(button => button.text().includes(label))!.trigger('click')
    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ taskId: 'task-2' })
    await flushPromises()
    confirmation.resolve(confirmed)
    await flushPromises()
    expect(mocks[method]).not.toHaveBeenCalled()
    expect(wrapper.emitted('reload')).toBeUndefined()
    expect(wrapper.emitted('openTask')).toBeUndefined()
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('')
  })

  it('freezes continuation versions, selected stage and supplemental requirement before confirmation', async () => {
    const snapshot: TaskDecision = { ...failedDecision, cycle: { ...failedDecision.cycle!, result: 'SUCCEEDED' },
      stages: [...failedDecision.stages, { id: 'stage-2', ordinal: 1, objective: '另一阶段', state: 'SUCCEEDED' }] }
    mocks.getTaskDecision.mockResolvedValue(snapshot)
    const confirmation = deferred<typeof confirmed>()
    vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(confirmation.promise)
    const wrapper = mount(TaskDecisionPanel, { props: { taskId: 'task-1' }, global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('textarea').setValue('  已确认范围  ')
    await wrapper.findAll('button').find(button => button.text().includes('继续当前任务'))!.trigger('click')
    await wrapper.find('textarea').setValue('尚未确认的另一个范围')
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 'stage-2')
    snapshot.taskVersion = 99
    snapshot.cycle!.version = 99
    confirmation.resolve(confirmed)
    await flushPromises()
    expect(mocks.continueTaskDecision).toHaveBeenCalledWith('task-1', {
      expectedTaskVersion: 7, expectedCycleVersion: 3, stageId: 'stage-1', supplementalRequirement: '已确认范围',
    })
  })

  it.each(['resolve', 'reject'] as const)('ignores a former task load that settles late (%s)', async settlement => {
    const old = deferred<TaskDecision>()
    mocks.getTaskDecision.mockReturnValueOnce(old.promise).mockResolvedValueOnce({ ...failedDecision,
      taskId: 'task-2', checkpoint: { ...failedDecision.checkpoint!, changedFileCount: 8 },
      cycle: { ...failedDecision.cycle!, ordinal: 8, result: 'SUCCEEDED' } })
    const wrapper = mount(TaskDecisionPanel, { props: { taskId: 'task-1' }, global: { plugins: [ElementPlus] } })
    await wrapper.setProps({ taskId: 'task-2' })
    await flushPromises()
    if (settlement === 'resolve') old.resolve(failedDecision)
    else old.reject(new Error('旧任务读取失败'))
    await flushPromises()
    expect(wrapper.text()).toContain('第 8 轮 · 执行成功')
    expect(wrapper.text()).toContain('8 个变更文件')
    expect(wrapper.text()).not.toContain('旧任务读取失败')
  })

  it('keeps a command failure visible after successfully refreshing the decision', async () => {
    mocks.cancelTaskDecision.mockRejectedValueOnce(new Error('任务结果已变化，请核对新的轮次后重试'))
    const wrapper = mount(TaskDecisionPanel, { props: { taskId: 'task-1' }, global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text().includes('取消任务'))!.trigger('click')
    await flushPromises()
    expect(mocks.getTaskDecision).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[role="alert"]').text()).toContain('任务结果已变化，请核对新的轮次后重试')
    expect(wrapper.text()).toContain('第 2 轮 · 执行失败')
  })

  it('discards confirmation after unmount without a command', async () => {
    const confirmation = deferred<typeof confirmed>()
    vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(confirmation.promise)
    const wrapper = mount(TaskDecisionPanel, { props: { taskId: 'task-1' }, global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text().includes('取消任务'))!.trigger('click')
    wrapper.unmount()
    confirmation.resolve(confirmed)
    await flushPromises()
    expect(mocks.cancelTaskDecision).not.toHaveBeenCalled()
    expect(wrapper.emitted('reload')).toBeUndefined()
  })

  it('does not navigate when a former task derivation finishes after switching tasks', async () => {
    const derivation = deferred<{ taskId: string }>()
    mocks.deriveTaskDecision.mockReturnValueOnce(derivation.promise)
    mocks.getTaskDecision.mockResolvedValueOnce(failedDecision).mockResolvedValueOnce({ ...failedDecision, taskId: 'task-2' })
    const wrapper = mount(TaskDecisionPanel, { props: { taskId: 'task-1' }, global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text().includes('新任务继承修改'))!.trigger('click')
    await flushPromises()
    expect(mocks.deriveTaskDecision).toHaveBeenCalledWith('task-1', {
      expectedTaskVersion: 7, expectedCycleVersion: 3, mode: 'INHERIT_CHANGES',
    })
    await wrapper.setProps({ taskId: 'task-2' })
    await flushPromises()
    derivation.resolve({ taskId: 'child-of-former-task' })
    await flushPromises()
    expect(wrapper.emitted('openTask')).toBeUndefined()
  })

})
