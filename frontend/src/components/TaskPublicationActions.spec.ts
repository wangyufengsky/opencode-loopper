import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import TaskPublicationActions from './TaskPublicationActions.vue'
import type { Task, TaskPublicationStatus } from '@/types/domain'

const ready: TaskPublicationStatus = {
  state: 'READY', available: true, branch: 'loopper/task-1', remoteName: 'origin', targetBranch: 'develop',
  targetBranches: ['develop', 'main'], provider: 'GITLAB', hasChanges: true, conflictCount: 0, resolvedCount: 0,
  deliveryState: 'NOT_STARTED', deliveryFinal: false, reconciliationAvailable: false,
}
const pushed: TaskPublicationStatus = {
  ...ready, state: 'PUSHED', hasChanges: false, commitSha: 'abc123456789', commitMessage: '#3032_完善任务发布流程', upstream: 'origin/loopper/task-1', deliveryState: 'PUSHED', reconciliationAvailable: true,
}
const localReady: TaskPublicationStatus = {
  state: 'READY', available: true, branch: 'loopper/task-1', targetBranches: [], provider: 'UNKNOWN', hasChanges: true, conflictCount: 0, resolvedCount: 0,
  deliveryState: 'NOT_STARTED', deliveryFinal: false, reconciliationAvailable: false,
}
const localSynced: TaskPublicationStatus = {
  ...localReady, state: 'SYNCED_LOCAL', hasChanges: false, commitSha: 'def987654321', commitMessage: '#3032_同步到源项目', deliveryState: 'LOCAL_COMPLETED', deliveryFinal: true,
}
const mocks = vi.hoisted(() => ({
  getTaskPublication: vi.fn(),
  generateTaskCommitMessage: vi.fn(),
  publishTask: vi.fn(),
  createTaskMergeRequestDraft: vi.fn(),
  reconcileTaskPublication: vi.fn(),
  getLocalSyncConflictSession: vi.fn(),
  createLocalSyncConflictSession: vi.fn(),
  getLocalSyncConflictFiles: vi.fn(),
  getLocalSyncConflictContent: vi.fn(),
  saveLocalSyncResolution: vi.fn(),
  suggestLocalSyncResolution: vi.fn(),
  applyLocalSyncConflict: vi.fn(),
}))
const { getTaskPublication, generateTaskCommitMessage, publishTask } = mocks

vi.mock('@/api/client', () => ({
  api: mocks,
}))
vi.mock('./CodeMergeEditor.vue', () => ({
  default: { props: ['modelValue', 'readonly', 'ariaLabel', 'language', 'changedLines', 'conflictLines', 'activeConflictLines'], emits: ['update:modelValue'], template: '<textarea :aria-label="ariaLabel" :data-language="language" :readonly="readonly" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
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
    mocks.getLocalSyncConflictSession.mockReset()
    mocks.createLocalSyncConflictSession.mockReset()
    mocks.getLocalSyncConflictFiles.mockReset()
    mocks.getLocalSyncConflictContent.mockReset()
    mocks.saveLocalSyncResolution.mockReset()
    mocks.suggestLocalSyncResolution.mockReset()
    mocks.applyLocalSyncConflict.mockReset()
    mocks.createTaskMergeRequestDraft.mockReset()
    mocks.reconcileTaskPublication.mockReset()
    mocks.reconcileTaskPublication.mockResolvedValue(pushed)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
  })

  afterEach(() => {
    document.body.innerHTML = ''
    vi.restoreAllMocks()
    vi.clearAllMocks()
  })

  it('generates the default subject, enforces four digits, then offers a merge request after push', async () => {
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
    expect(wrapper.text()).toContain('创建合并请求')
  })

  it('opens the merge request dialog directly from the pushed-state button', async () => {
    getTaskPublication.mockResolvedValue(pushed)
    const wrapper = mount(TaskPublicationActions, { props: { task }, global: { plugins: [ElementPlus] }, attachTo: document.body })
    await flushPromises()

    const create = wrapper.findAll('button').find((button) => button.text().includes('创建合并请求'))
    expect(create).toBeDefined()
    await create!.trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('前往创建合并请求')
    expect(document.body.textContent).toContain('loopper/task-1')
    expect(document.body.textContent).toContain('develop')
  })

  it('keeps the merge request action available after a successful task is confirmed completed', async () => {
    getTaskPublication.mockResolvedValue(pushed)
    const completed = { ...task, status: 'COMPLETED' as const, executionResult: 'SUCCEEDED' as const }
    const wrapper = mount(TaskPublicationActions, { props: { task: completed }, global: { plugins: [ElementPlus] }, attachTo: document.body })
    await flushPromises()

    expect(getTaskPublication).toHaveBeenCalledWith('task-1')
    expect(wrapper.text()).toContain('创建合并请求')
  })

  it('shows an immutable merged result without another create action', async () => {
    getTaskPublication.mockResolvedValue({ ...pushed, state: 'MERGED', deliveryState: 'MERGED', deliveryFinal: true,
      mergeRequest: { provider: 'GITLAB', iid: 1686, url: 'http://gitlab.example/group/project/-/merge_requests/1686', state: 'merged' } })
    const wrapper = mount(TaskPublicationActions, { props: { task }, global: { plugins: [ElementPlus] }, attachTo: document.body })
    await flushPromises()

    expect(wrapper.text()).toContain('交付：已合并')
    expect(wrapper.text()).toContain('已合并')
    expect(wrapper.text()).not.toContain('创建合并请求')
  })

  it('checks on entry and focus with a thirty-second cooldown', async () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(100_000)
    const listeners = vi.spyOn(window, 'addEventListener')
    getTaskPublication.mockResolvedValue(pushed)
    const wrapper = mount(TaskPublicationActions, { props: { task }, global: { plugins: [ElementPlus] }, attachTo: document.body })
    await flushPromises()
    expect(mocks.reconcileTaskPublication).toHaveBeenCalledTimes(1)
    const focus = listeners.mock.calls.find(([type]) => type === 'focus')?.[1] as EventListener

    focus(new Event('focus'))
    await flushPromises()
    expect(mocks.reconcileTaskPublication).toHaveBeenCalledTimes(1)

    now.mockReturnValue(130_001)
    focus(new Event('focus'))
    await flushPromises()
    expect(mocks.reconcileTaskPublication).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('shows opened and closed merge request actions', async () => {
    const opened: TaskPublicationStatus = { ...pushed, state: 'MERGE_REQUEST_OPENED', deliveryState: 'MERGE_REQUEST_OPENED',
      mergeRequest: { provider: 'GITLAB', iid: 1686, url: 'http://gitlab.example/group/project/-/merge_requests/1686', state: 'opened' } }
    getTaskPublication.mockResolvedValue(opened)
    mocks.reconcileTaskPublication.mockResolvedValue(opened)
    const wrapper = mount(TaskPublicationActions, { props: { task }, global: { plugins: [ElementPlus] }, attachTo: document.body })
    await flushPromises()
    expect(wrapper.text()).toContain('查看合并请求')
    expect(wrapper.text()).toContain('检查合并状态')

    const closed: TaskPublicationStatus = { ...opened, state: 'MERGE_REQUEST_CLOSED', deliveryState: 'MERGE_REQUEST_CLOSED',
      mergeRequest: { ...opened.mergeRequest!, state: 'closed' } }
    mocks.reconcileTaskPublication.mockResolvedValue(closed)
    const check = wrapper.findAll('button').find((button) => button.text().includes('检查合并状态'))
    await check!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('合并请求已关闭')
    expect(wrapper.text()).toContain('重新打开创建页')
  })

  it('explains unavailable GitLab credentials and GitHub reconciliation', async () => {
    getTaskPublication.mockResolvedValue({ ...pushed, reconciliationAvailable: false })
    const gitLab = mount(TaskPublicationActions, { props: { task }, global: { plugins: [ElementPlus] }, attachTo: document.body })
    await flushPromises()
    expect(gitLab.text()).toContain('无法自动确认合并')
    gitLab.unmount()

    getTaskPublication.mockResolvedValue({ ...pushed, provider: 'GITHUB', reconciliationAvailable: false })
    const github = mount(TaskPublicationActions, { props: { task }, global: { plugins: [ElementPlus] }, attachTo: document.body })
    await flushPromises()
    expect(github.text()).toContain('GitHub 合并状态暂未接入自动确认')
  })

  it('explains local task branch submission when no remote exists', async () => {
    getTaskPublication.mockResolvedValue(localReady)
    publishTask.mockResolvedValue(localSynced)
    const wrapper = mount(TaskPublicationActions, { props: { task }, global: { plugins: [ElementPlus] }, attachTo: document.body })
    await flushPromises()

    expect(wrapper.text()).toContain('提交本地任务分支')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    const ticket = document.querySelector('input[aria-label="4 位数字工单号"]') as HTMLInputElement
    ticket.value = '3032'
    ticket.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    const confirm = [...document.querySelectorAll('button')]
      .find((button) => button.textContent?.includes('提交本地分支')) as HTMLButtonElement
    confirm.click()
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('原项目目录中当前任务分支'), '确认提交本地任务分支？', expect.any(Object))
    expect(publishTask).toHaveBeenCalledWith('task-1', '#3032_完善任务发布流程')
    expect(wrapper.text()).toContain('已提交本地任务分支')
  })

  it('opens the conflict center, keeps apply disabled, then enables it after an explicit resolution', async () => {
    const conflictPublication: TaskPublicationStatus = {
      ...localReady, state: 'LOCAL_SYNC_CONFLICT', hasChanges: false, commitSha: 'abc123',
      conflictSessionId: 'session-1', conflictCount: 1, resolvedCount: 0,
    }
    const openSession = { id: 'session-1', taskId: 'task-1', state: 'OPEN', sourceRoot: '/tmp/source', sourceHead: '1234567890abcdef', taskCommit: 'abc123', baselineCommit: 'base', conflictCount: 1, resolvedCount: 0, createdAt: 'now', updatedAt: 'now', version: 0 }
    const readySession = { ...openSession, state: 'READY', resolvedCount: 1, version: 1 }
    const unresolved = { path: 'Main.java', sourcePath: 'Main.java', taskPath: 'Main.java', changeType: 'MODIFY', contentType: 'TEXT', resolved: false, hasAiSuggestion: false, baseHash: 'basehash', sourceHash: 'sourcehash', taskHash: 'taskhash', version: 0 }
    const content = { path: 'Main.java', contentType: 'TEXT', baseContent: 'base', sourceContent: 'source', taskContent: 'task', mergedContent: '<<<<<<<', baseHash: 'basehash', sourceHash: 'sourcehash', taskHash: 'taskhash', aiEligible: true, version: 0 }
    getTaskPublication.mockResolvedValue(conflictPublication)
    mocks.getLocalSyncConflictSession.mockResolvedValueOnce(openSession).mockResolvedValue(readySession)
    mocks.getLocalSyncConflictFiles.mockResolvedValueOnce([unresolved]).mockResolvedValue([{ ...unresolved, resolved: true, resolution: 'SOURCE', version: 1 }])
    mocks.getLocalSyncConflictContent.mockResolvedValueOnce(content).mockResolvedValue({ ...content, resolution: 'SOURCE', version: 1 })
    mocks.saveLocalSyncResolution.mockResolvedValue({ ...content, resolution: 'SOURCE', version: 1 })

    const wrapper = mount(TaskPublicationActions, { props: { task }, global: { plugins: [ElementPlus] }, attachTo: document.body })
    await flushPromises()
    expect(wrapper.text()).toContain('解决同步冲突（1）')
    await wrapper.get('button').trigger('click')
    await flushPromises()

    const applyBefore = [...document.querySelectorAll('button')].find((button) => button.textContent?.includes('确认合并并同步')) as HTMLButtonElement
    expect(applyBefore.disabled).toBe(true)
    const sourceChoice = [...document.querySelectorAll('button')].find((button) => button.textContent?.includes('采用源项目')) as HTMLButtonElement
    sourceChoice.click()
    await flushPromises()

    expect(mocks.saveLocalSyncResolution).toHaveBeenCalledWith('task-1', 'session-1', expect.objectContaining({ path: 'Main.java', resolution: 'SOURCE', expectedVersion: 0 }))
    const applyAfter = [...document.querySelectorAll('button')].find((button) => button.textContent?.includes('确认合并并同步')) as HTMLButtonElement
    expect(applyAfter.disabled).toBe(false)
  })

  it('keeps AI output as an unselected suggestion until the user loads and saves it', async () => {
    const conflictPublication: TaskPublicationStatus = { ...localReady, state: 'LOCAL_SYNC_CONFLICT', hasChanges: false, conflictSessionId: 'session-ai', conflictCount: 1, resolvedCount: 0 }
    const session = { id: 'session-ai', taskId: 'task-1', state: 'OPEN', sourceRoot: '/tmp/source', sourceHead: '1234567890abcdef', taskCommit: 'task', baselineCommit: 'base', conflictCount: 1, resolvedCount: 0, createdAt: 'now', updatedAt: 'now', version: 0 }
    const file = { path: 'Main.java', sourcePath: 'Main.java', taskPath: 'Main.java', changeType: 'MODIFY', contentType: 'TEXT', resolved: false, hasAiSuggestion: false, baseHash: 'basehash', sourceHash: 'sourcehash', taskHash: 'taskhash', version: 0 }
    const content = { path: 'Main.java', contentType: 'TEXT', baseContent: 'base', sourceContent: 'source', taskContent: 'task', mergedContent: 'manual', baseHash: 'basehash', sourceHash: 'sourcehash', taskHash: 'taskhash', aiEligible: true, version: 0 }
    getTaskPublication.mockResolvedValue(conflictPublication)
    mocks.getLocalSyncConflictSession.mockResolvedValue(session)
    mocks.getLocalSyncConflictFiles.mockResolvedValue([file])
    mocks.getLocalSyncConflictContent.mockResolvedValue(content)
    mocks.suggestLocalSyncResolution.mockResolvedValue({ path: 'Main.java', suggestion: 'AI MERGED', automaticallySelected: false, version: 1 })

    const wrapper = mount(TaskPublicationActions, { props: { task }, global: { plugins: [ElementPlus] }, attachTo: document.body })
    await flushPromises()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    const ai = [...document.querySelectorAll('button')].find((button) => button.textContent?.includes('AI 建议')) as HTMLButtonElement
    ai.click()
    await flushPromises()

    expect(document.body.textContent).toContain('AI MERGED')
    expect(mocks.saveLocalSyncResolution).not.toHaveBeenCalled()
    const load = [...document.querySelectorAll('button')].find((button) => button.textContent?.includes('载入编辑器')) as HTMLButtonElement
    load.click()
    await flushPromises()
    expect((document.querySelector('textarea[aria-label="合并结果编辑器"]') as HTMLTextAreaElement).value).toBe('AI MERGED')
    expect(mocks.saveLocalSyncResolution).not.toHaveBeenCalled()
  })

  it('blocks unresolved git markers and shows verifier output after rollback', async () => {
    const conflictPublication: TaskPublicationStatus = { ...localReady, state: 'LOCAL_SYNC_CONFLICT', hasChanges: false, conflictSessionId: 'session-markers', conflictCount: 1, resolvedCount: 1 }
    const session = {
      id: 'session-markers', taskId: 'task-1', state: 'ROLLED_BACK', sourceRoot: '/tmp/source', sourceHead: '1234567890abcdef',
      taskCommit: 'task', baselineCommit: 'base', conflictCount: 1, resolvedCount: 1, errorMessage: '发布验证失败，已启动自动恢复：PROCESS[0:0] Process exited 1',
      verificationEvidence: JSON.stringify({ passed: false, checks: [{ type: 'PROCESS', path: '0:0', passed: false, summary: 'Process exited 1', evidence: { output: '[ERROR] COMPILATION ERROR' } }] }),
      createdAt: 'now', updatedAt: 'now', version: 2,
    }
    const file = { path: 'Main.java', sourcePath: 'Main.java', taskPath: 'Main.java', changeType: 'MODIFY', contentType: 'TEXT', resolution: 'MANUAL', resolved: true, hasAiSuggestion: false, baseHash: 'basehash', sourceHash: 'sourcehash', taskHash: 'taskhash', version: 1 }
    const markers = '<<<<<<< 源项目\nsource\n=======\ntask\n>>>>>>> 任务\n'
    const content = { path: 'Main.java', contentType: 'TEXT', baseContent: 'base', sourceContent: 'source', taskContent: 'task', mergedContent: markers, resolution: 'MANUAL', baseHash: 'basehash', sourceHash: 'sourcehash', taskHash: 'taskhash', aiEligible: true, version: 1 }
    getTaskPublication.mockResolvedValue(conflictPublication)
    mocks.getLocalSyncConflictSession.mockResolvedValue(session)
    mocks.getLocalSyncConflictFiles.mockResolvedValue([file])
    mocks.getLocalSyncConflictContent.mockResolvedValue(content)

    const wrapper = mount(TaskPublicationActions, { props: { task }, global: { plugins: [ElementPlus] }, attachTo: document.body })
    await flushPromises()
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('合并结果仍有 Git 冲突标记')
    expect(document.body.textContent).toContain('[ERROR] COMPILATION ERROR')
    expect(document.querySelectorAll('textarea[aria-label$="内容"], textarea[aria-label="合并结果编辑器"]')).toHaveLength(3)
    expect((document.querySelector('textarea[aria-label="合并结果编辑器"]') as HTMLTextAreaElement).dataset.language).toBe('java')
    const acceptBlock = [...document.querySelectorAll('button')].find((button) => button.textContent?.trim() === '本段采用源项目') as HTMLButtonElement
    acceptBlock.click()
    await flushPromises()
    expect((document.querySelector('textarea[aria-label="合并结果编辑器"]') as HTMLTextAreaElement).value).toBe('source\n')
    expect(document.body.textContent).not.toContain('合并结果仍有 Git 冲突标记')
    const save = [...document.querySelectorAll('button')].find((button) => button.textContent?.includes('保存手工合并')) as HTMLButtonElement
    save.click()
    await flushPromises()
    expect(mocks.saveLocalSyncResolution).toHaveBeenCalledWith('task-1', 'session-markers', expect.objectContaining({ resolution: 'MANUAL', content: 'source\n' }))
  })

  it('shows stale and rolled-back states as recoverable actions', async () => {
    const conflictPublication: TaskPublicationStatus = { ...localReady, state: 'LOCAL_SYNC_CONFLICT', hasChanges: false, conflictSessionId: 'session-stale', conflictCount: 1, resolvedCount: 1 }
    const stale = { id: 'session-stale', taskId: 'task-1', state: 'STALE', sourceRoot: '/tmp/source', sourceHead: '1234567890abcdef', taskCommit: 'task', baselineCommit: 'base', conflictCount: 1, resolvedCount: 1, errorMessage: 'source changed', createdAt: 'now', updatedAt: 'now', version: 2 }
    getTaskPublication.mockResolvedValue(conflictPublication)
    mocks.getLocalSyncConflictSession.mockResolvedValue(stale)
    mocks.getLocalSyncConflictFiles.mockResolvedValue([])

    const wrapper = mount(TaskPublicationActions, { props: { task }, global: { plugins: [ElementPlus] }, attachTo: document.body })
    await flushPromises()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('会话已过期')
    expect(document.body.textContent).toContain('刷新预检')
  })
})
