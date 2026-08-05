import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TaskAuditEvidencePanel from '@/components/TaskAuditEvidencePanel.vue'
import { api } from '@/api/client'
import type { Artifact, Attempt, JudgeRun } from '@/types/domain'

vi.mock('@/api/client', () => ({ api: { getTaskDiffPreview: vi.fn() } }))

const attempts: Attempt[] = [{
  id: 'attempt-1', ordinal: 1, stageId: 'stage-1', status: 'VERIFIED', startedAt: 'now', summary: '全部通过', errors: [],
  verifiers: [
    { id: 'process-1', name: 'PROCESS', status: 'PASS', summary: 'Process exited 0', output: '[INFO] BUILD SUCCESS', evidence: { argv: ['mvn', 'test'], exitCode: 0, output: '[INFO] BUILD SUCCESS' } },
    { id: 'diff-1', name: 'GIT_DIFF', status: 'PASS', summary: 'Git diff satisfies policy', evidence: { changedPaths: ['src/Main.java', 'src/New.java'], untrackedPaths: ['src/New.java'], violations: [] } },
  ],
}]

const artifacts: Artifact[] = [{ id: 'artifact-diff', kind: 'DIFF', title: 'worktree.diff', createdAt: 'now', content: '[]' }]
const judges: JudgeRun[] = [{ id: 'judge-1', role: 'RISK', ordinal: 1, status: 'COMPLETED', verdict: 'PASS', reason: '结论安全。\n\n## 证据\n\n1. 测试通过。', createdAt: 'now' }]

function mountPanel(directExecution = false) {
  return mount(TaskAuditEvidencePanel, {
    props: { taskId: 'task-1', attempts, artifacts, judges, directExecution },
    global: { plugins: [ElementPlus], stubs: { Icon: true, teleport: true } },
  })
}

async function openTab(wrapper: ReturnType<typeof mountPanel>, label: string) {
  const tab = wrapper.findAll('.el-tabs__item').find((item) => item.text() === label)
  expect(tab).toBeDefined()
  await tab!.trigger('click')
}

describe('TaskAuditEvidencePanel', () => {
  afterEach(() => vi.clearAllMocks())

  it('starts with structured verification and keeps persisted stdout collapsed until requested', async () => {
    const wrapper = mountPanel()

    expect(wrapper.text()).toContain('2 / 2 通过')
    await openTab(wrapper, '日志')
    expect(wrapper.text()).toContain('确定性验证日志')
    expect(wrapper.text()).toContain('mvn test')
    expect(wrapper.get('.audit-log').text()).toContain('BUILD SUCCESS')
    expect(wrapper.get('.audit-disclosure').attributes('open')).toBeUndefined()
  })

  it('uses GIT_DIFF verifier evidence when the OpenCode session patch is an empty array', async () => {
    const wrapper = mountPanel(true)
    await openTab(wrapper, '差异')

    expect(wrapper.text()).toContain('不需要连接远端 Git')
    expect(wrapper.text()).toContain('Loopper 私有基线')
    expect(wrapper.text()).toContain('src/Main.java')
    expect(wrapper.text()).toContain('src/New.java')
    expect(wrapper.text()).toContain('会话接口返回了空补丁')
    expect(wrapper.text()).not.toContain('没有检测到文件变更')
  })

  it('formats verification and judge records without exposing protocol JSON', async () => {
    const wrapper = mountPanel()
    await openTab(wrapper, '验证')

    expect(wrapper.text()).toContain('2 / 2 通过')
    expect(wrapper.text()).toContain('退出码 0')
    expect(wrapper.text()).toContain('已检查 2 个变更文件')

    await openTab(wrapper, '评审')
    expect(wrapper.text()).toContain('风险评审')
    expect(wrapper.text()).toContain('结论安全')
    expect(wrapper.text()).toContain('测试通过')
    expect(wrapper.text()).not.toContain('{"verdict"')
  })

  it('opens a dialog preview and marks added and removed lines', async () => {
    vi.mocked(api.getTaskDiffPreview).mockResolvedValue({
      path: 'src/Main.java', changeType: 'MODIFIED', truncated: false,
      patch: 'diff --git a/src/Main.java b/src/Main.java\n--- a/src/Main.java\n+++ b/src/Main.java\n@@ -1 +1 @@\n-old value\n+new value',
    })
    const wrapper = mountPanel()
    await openTab(wrapper, '差异')
    await wrapper.get('button[aria-label="预览差异 src/Main.java"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('文件差异预览'))

    expect(api.getTaskDiffPreview).toHaveBeenCalledWith('task-1', 'src/Main.java')
    expect(wrapper.get('.diff-preview-dialog').text()).toContain('src/Main.java')
    expect(wrapper.get('.preview-line.added').text()).toContain('+new value')
    expect(wrapper.get('.preview-line.removed').text()).toContain('-old value')
    expect(wrapper.get('.preview-line.hunk').text()).toContain('@@')
  })
})
