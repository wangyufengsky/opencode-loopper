import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import GitDiffScopeApprovalDialog from './GitDiffScopeApprovalDialog.vue'

const mocks = vi.hoisted(() => ({
  getGitDiffScopeApproval: vi.fn(),
  getGitDiffScopeApprovalPreview: vi.fn(),
  resolveGitDiffScopeApproval: vi.fn(),
}))
vi.mock('@/api/client', () => ({ api: mocks }))

const approval = {
  requestId: 'approval-1', taskId: 'task-1', stageId: 'stage-1', attemptId: 'attempt-1', taskVersion: 7,
  files: [{ path: 'src/Main.java', changeType: 'MODIFIED', patchSha256: 'patch-sha' }],
}

describe('GitDiffScopeApprovalDialog', () => {
  beforeEach(() => {
    mocks.getGitDiffScopeApproval.mockResolvedValue(approval)
    mocks.getGitDiffScopeApprovalPreview.mockResolvedValue({
      path: 'src/Main.java', changeType: 'MODIFIED', truncated: false,
      patch: 'diff --git a/src/Main.java b/src/Main.java\n--- a/src/Main.java\n+++ b/src/Main.java\n@@ -10,2 +10,2 @@\n-old value\n+new value\n context',
    })
    mocks.resolveGitDiffScopeApproval.mockResolvedValue({ id: 'task-1', status: 'JUDGING' })
  })

  afterEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
  })

  it('opens automatically and shows exact old/new lines before submitting the content-bound decision', async () => {
    mocks.getGitDiffScopeApproval.mockResolvedValueOnce(approval).mockResolvedValueOnce(undefined)
    const wrapper = mount(GitDiffScopeApprovalDialog, {
      props: { taskId: 'task-1', active: true },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await flushPromises()

    expect(document.body.textContent).toContain('决定是否接受既有文件的越界修改')
    expect(document.body.textContent).toContain('src/Main.java')
    expect(document.querySelector('.diff-line.removed')?.textContent).toContain('10-old value')
    expect(document.querySelector('.diff-line.added')?.textContent).toContain('10+new value')
    expect(document.querySelector('.diff-line.hunk')?.textContent).toContain('@@ -10,2 +10,2 @@')

    const submit = [...document.querySelectorAll('button')]
      .find((button) => button.textContent?.includes('按以上决定继续验证')) as HTMLButtonElement
    expect(submit.disabled).toBe(true)
    ;(document.querySelector('[data-decision="ALLOW"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(submit.disabled).toBe(false)
    submit.click()
    await flushPromises()

    expect(mocks.resolveGitDiffScopeApproval).toHaveBeenCalledWith('task-1', 'approval-1', {
      expectedTaskVersion: 7,
      decisions: [{ path: 'src/Main.java', action: 'ALLOW', patchSha256: 'patch-sha' }],
    })
    expect(wrapper.emitted('resolved')).toHaveLength(1)
  })

  it('keeps a persistent card after the user closes the popup', async () => {
    const wrapper = mount(GitDiffScopeApprovalDialog, {
      props: { taskId: 'task-1', active: true },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await flushPromises()

    const later = [...document.querySelectorAll('button')]
      .find((button) => button.textContent?.includes('稍后处理')) as HTMLButtonElement
    later.click()
    await flushPromises()

    expect(wrapper.text()).toContain('任务未被判定失败')
    expect(wrapper.text()).toContain('查看差异并决定')
  })
})
