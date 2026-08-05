import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import TaskAuditEvidencePanel from '@/components/TaskAuditEvidencePanel.vue'
import type { Artifact, Attempt, JudgeRun } from '@/types/domain'

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
    props: { attempts, artifacts, judges, directExecution },
    global: { plugins: [ElementPlus], stubs: { Icon: true } },
  })
}

async function openTab(wrapper: ReturnType<typeof mountPanel>, label: string) {
  const tab = wrapper.findAll('.el-tabs__item').find((item) => item.text() === label)
  expect(tab).toBeDefined()
  await tab!.trigger('click')
}

describe('TaskAuditEvidencePanel', () => {
  it('shows persisted verifier stdout as the task log instead of an empty artifact placeholder', () => {
    const wrapper = mountPanel()

    expect(wrapper.text()).toContain('确定性验证日志')
    expect(wrapper.text()).toContain('mvn test')
    expect(wrapper.get('.audit-log').text()).toContain('BUILD SUCCESS')
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
})
