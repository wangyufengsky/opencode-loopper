import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox, ElSelect } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DirtyWorkspaceDialog from './DirtyWorkspaceDialog.vue'
import { useTaskStore } from '@/stores/taskStore'

const mocks = vi.hoisted(() => ({ getDirtyWorkspace: vi.fn() }))
vi.mock('@/api/client', () => ({ api: mocks }))

const dirty = {
  branch: 'main', head: 'abc123', snapshotId: 'snapshot-1', clean: false,
  files: [
    { path: 'README.md', indexStatus: ' ', workTreeStatus: 'M', untracked: false },
    { path: 'notes.txt', indexStatus: '?', workTreeStatus: '?', untracked: true },
  ],
}

describe('DirtyWorkspaceDialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mocks.getDirtyWorkspace.mockResolvedValue(dirty)
  })

  afterEach(() => {
    document.body.innerHTML = ''
    vi.restoreAllMocks()
    vi.clearAllMocks()
  })

  it('lists every dirty file and sends the explicit per-file decisions', async () => {
    const store = useTaskStore()
    const resolve = vi.spyOn(store, 'resolveDirtyWorkspace').mockResolvedValue({
      task: { id: 'task-1', status: 'READY' } as never,
      workspace: { ...dirty, branch: 'loopper/Task', snapshotId: 'snapshot-2', clean: true, files: [] },
    })
    const wrapper = mount(DirtyWorkspaceDialog, {
      props: { taskId: 'task-1', modelValue: true },
      global: { plugins: [ElementPlus] }, attachTo: document.body,
    })
    await flushPromises()

    expect(document.body.textContent).toContain('README.md')
    expect(document.body.textContent).toContain('notes.txt')
    const selects = wrapper.findAllComponents(ElSelect)
    await selects[0]!.setValue('COMMIT')
    await selects[1]!.setValue('STASH')
    const continueButton = [...document.querySelectorAll('button')]
      .find((button) => button.textContent?.includes('重新检查并继续')) as HTMLButtonElement
    continueButton.click()
    await flushPromises()

    expect(resolve).toHaveBeenCalledWith('task-1', {
      snapshotId: 'snapshot-1',
      resolutions: [
        { path: 'README.md', action: 'COMMIT' },
        { path: 'notes.txt', action: 'STASH' },
      ],
      commitMessage: 'chore: 保存任务开始前的本地改动',
    })
    expect(wrapper.emitted('update:modelValue')).toContainEqual([false])
  })

  it('requires confirmation and marks the task failed without resolving files', async () => {
    const store = useTaskStore()
    const fail = vi.spyOn(store, 'failDirtyWorkspace').mockResolvedValue({ id: 'task-1', status: 'FAILED' } as never)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    mount(DirtyWorkspaceDialog, {
      props: { taskId: 'task-1', modelValue: true },
      global: { plugins: [ElementPlus] }, attachTo: document.body,
    })
    await flushPromises()

    const cancelButton = [...document.querySelectorAll('button')]
      .find((button) => button.textContent?.includes('取消并标记任务失败')) as HTMLButtonElement
    cancelButton.click()
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('现有本地文件保持原样'),
      '取消工作区处理并终止任务？', expect.any(Object))
    expect(fail).toHaveBeenCalledWith('task-1')
  })
})
