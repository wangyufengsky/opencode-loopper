import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TaskAuditEvidencePanel from '@/components/TaskAuditEvidencePanel.vue'
import { api } from '@/api/client'
import type { Artifact, Attempt } from '@/types/domain'

vi.mock('@/api/client', () => ({ api: { getTaskDiffPreview: vi.fn() } }))

const attempts: Attempt[] = [{
  id: 'attempt-1', ordinal: 1, stageId: 'stage-1', status: 'VERIFIED', startedAt: 'now', summary: '全部通过', errors: [],
  verifiers: [
    { id: 'process-1', name: 'PROCESS', status: 'PASS', summary: 'Process exited 0', output: '[INFO] BUILD SUCCESS', evidence: { argv: ['mvn', 'test'], exitCode: 0, output: '[INFO] BUILD SUCCESS', workingDirectory: '/repo/project' } },
    { id: 'diff-1', name: 'GIT_DIFF', status: 'PASS', summary: 'Git diff satisfies policy', evidence: { changedPaths: ['src/Main.java', 'src/New.java'], untrackedPaths: ['src/New.java'], violations: [] } },
  ],
}]

const artifacts: Artifact[] = [
  { id: 'artifact-diff', kind: 'DIFF', title: 'task-diff.json', createdAt: 'now', content: '{"changedPaths":["src/Main.java","src/New.java"]}', metadata: { changedPaths: ['src/Main.java', 'src/New.java'], untrackedPaths: ['src/New.java'] } },
  { id: 'artifact-handoff', kind: 'LOG', title: 'attempt-handoff-1.json', createdAt: 'now', attemptId: 'attempt-1', content: '{"consecutiveStagnationCount":1}' },
]

function mountPanel(directExecution = false) {
  return mount(TaskAuditEvidencePanel, {
    props: { taskId: 'task-1', attempts, artifacts, directExecution },
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
    expect(wrapper.text()).toContain('执行目录 /repo/project')
    await openTab(wrapper, '日志')
    expect(wrapper.text()).toContain('确定性验证日志')
    expect(wrapper.text()).toContain('attempt-handoff-1.json')
    expect(wrapper.text()).toContain('结构化重试交接')
    expect(wrapper.text()).toContain('mvn test')
    expect(wrapper.findAll('.audit-log').some((log) => log.text().includes('BUILD SUCCESS'))).toBe(true)
    expect(wrapper.get('.audit-disclosure').attributes('open')).toBeUndefined()
  })

  it('uses the persisted task baseline snapshot without requiring a GIT_DIFF verifier', async () => {
    const processOnly: Attempt[] = [{ ...attempts[0]!, verifiers: attempts[0]!.verifiers.filter((verifier) => verifier.name === 'PROCESS') }]
    const wrapper = mount(TaskAuditEvidencePanel, {
      props: { taskId: 'task-1', attempts: processOnly, artifacts, directExecution: true },
      global: { plugins: [ElementPlus], stubs: { Icon: true, teleport: true } },
    })
    await openTab(wrapper, '差异')

    expect(wrapper.text()).toContain('不需要连接远端 Git')
    expect(wrapper.text()).toContain('Loopper 私有基线')
    expect(wrapper.text()).toContain('src/Main.java')
    expect(wrapper.text()).toContain('src/New.java')
    expect(wrapper.text()).toContain('任务基线差异快照')
    expect(wrapper.text()).not.toContain('没有检测到文件变更')
  })

  it('keeps judge review out of the audit evidence tabs', async () => {
    const wrapper = mountPanel()
    await openTab(wrapper, '验证')

    expect(wrapper.text()).toContain('2 / 2 通过')
    expect(wrapper.text()).toContain('退出码 0')
    expect(wrapper.text()).toContain('已检查 2 个变更文件')
    expect(wrapper.text()).toContain('验证、差异与日志')
    expect(wrapper.findAll('.el-tabs__item').map((item) => item.text())).toEqual(['日志', '差异', '验证'])
    expect(wrapper.text()).not.toContain('独立双评审')
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
