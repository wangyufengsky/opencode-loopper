import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import TaskPublicationActions from './TaskPublicationActions.vue'
import type { Task, TaskPublicationStatus } from '@/types/domain'

const ready: TaskPublicationStatus = {
  state: 'READY', available: true, branch: 'loopper/task-1', remoteName: 'origin', targetBranch: 'develop',
  targetBranches: ['develop', 'main'], provider: 'GITLAB', hasChanges: true,
}
const pushed: TaskPublicationStatus = {
  ...ready, state: 'PUSHED', hasChanges: false, commitSha: 'abc123456789', commitMessage: '#3032_完善任务发布流程', upstream: 'origin/loopper/task-1',
}
const mocks = vi.hoisted(() => ({
  getTaskPublication: vi.fn(),
  generateTaskCommitMessage: vi.fn(),
  publishTask: vi.fn(),
  createTaskMergeRequestDraft: vi.fn(),
}))
const { getTaskPublication, generateTaskCommitMessage, publishTask } = mocks

vi.mock('@/api/client', () => ({
  api: mocks,
}))

const task: Task = {
  id: 'task-1', projectId: 'project-1', projectName: 'fixture', title: '完善任务发布流程', goal: '成功后允许提交和创建合并请求',
  branch: 'loopper/task-1', worktreePath: '/tmp/worktree', status: 'SUCCEEDED', attemptCount: 1, maxAttempts: 3,
  createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:01:00Z',
}

describe('TaskPublicationActions', () => {
  beforeEach(() => {
    getTaskPublication.mockResolvedValue(ready)
    generateTaskCommitMessage.mockResolvedValue({ subject: '完善任务发布流程', aiGenerated: true })
    publishTask.mockResolvedValue(pushed)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  })

  afterEach(() => {
    document.body.innerHTML = ''
    vi.restoreAllMocks()
    vi.clearAllMocks()
  })

  it('generates the default subject, enforces four digits, then changes to merge branch after push', async () => {
    const wrapper = mount(TaskPublicationActions, { props: { task }, global: { plugins: [ElementPlus] }, attachTo: document.body })
    await flushPromises()

    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(generateTaskCommitMessage).toHaveBeenCalledWith('task-1')
    expect(document.body.textContent).toContain('完善任务发布流程')

    const ticket = document.querySelector('input[aria-label="4 位数字工单号"]') as HTMLInputElement
    ticket.value = '30a32'
    ticket.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    expect(ticket.value).toBe('3032')
    expect(document.body.textContent).toContain('#3032_完善任务发布流程')

    const confirm = [...document.querySelectorAll('button')].find((button) => button.textContent?.includes('确认提交并推送')) as HTMLButtonElement
    confirm.click()
    await flushPromises()

    expect(publishTask).toHaveBeenCalledWith('task-1', '#3032_完善任务发布流程')
    expect(wrapper.text()).toContain('合并分支')
  })
})
