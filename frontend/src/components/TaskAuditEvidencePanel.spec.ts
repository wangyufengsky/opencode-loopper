import {mount,flushPromises} from '@/pages/w6-tests/ordinary/render'
import {panelFixture,taskFixture} from '@/pages/w6-tests/ordinary/task'
import {createTaskEvidenceController} from '@/pages/w4/task/evidenceController'

import { afterEach, describe, expect, it, vi } from 'vitest'
import {TaskAuditEvidencePanel} from '@/pages/w4/task/TaskEvidencePanels'
import { api } from '@/api/client'
import type { Artifact, Attempt } from '@/types/domain'



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

function mountPanel(directExecution=false,input=attempts){const f=panelFixture(taskFixture('task-1',{attempts:input,artifacts,branch:directExecution?'DIRECT':'task/1'})),owner=createTaskEvidenceController('task-1');f.props.page.lifecycle.retain(owner,()=>owner.retire(true));return mount(TaskAuditEvidencePanel,{props:{...f.props,owner}})}
async function openTab(wrapper:ReturnType<typeof mountPanel>,label:string){await wrapper.get(`button[aria-label="${label}"]`).trigger('click')}
describe('TaskAuditEvidencePanel', () => {
  afterEach(() => vi.clearAllMocks())

  it('starts with structured verification and keeps persisted stdout collapsed until requested', async () => {
    const wrapper = mountPanel()

    expect(wrapper.text()).toContain('2/2 通过')
    expect(wrapper.text()).toContain('执行目录 /repo/project')
    await openTab(wrapper, '日志')
    expect(wrapper.text()).toContain('验证日志与尝试交接')
    expect(wrapper.text()).not.toContain('attempt-handoff-1.json')
    expect(wrapper.text()).toContain('结构化重试交接')
    expect(wrapper.text()).toContain('mvn test')
    expect(wrapper.findAll('.w3-code-text').some((log) => log.text().includes('BUILD SUCCESS'))).toBe(true)
    expect(wrapper.get('details').attributes('open')).toBeUndefined()
  })

  it('uses the persisted task baseline snapshot without requiring a GIT_DIFF verifier', async () => {
    const processOnly: Attempt[] = [{ ...attempts[0]!, verifiers: attempts[0]!.verifiers.filter((verifier) => verifier.name === 'PROCESS') }]
    const wrapper=mountPanel(true,processOnly)
    await openTab(wrapper, '差异')

    expect(wrapper.text()).toContain('本地变更')
    expect(wrapper.text()).toContain('src/Main.java')
    expect(wrapper.text()).toContain('src/New.java')
    expect(wrapper.text()).toContain('任务基线差异快照')
    expect(wrapper.text()).not.toContain('没有检测到文件变更')
  })

  it('keeps judge review out of the audit evidence tabs', async () => {
    const wrapper = mountPanel()
    await openTab(wrapper, '验证')

    expect(wrapper.text()).toContain('2/2 通过')
    expect(wrapper.text()).toContain('退出码 0')
    expect(wrapper.text()).toContain('已检查 2 个变更文件')
    expect(wrapper.text()).toContain('验证、差异与日志')
    expect(wrapper.findAll('nav[aria-label="审计类别"] button').map((item) => item.text())).toEqual(['日志', '差异', '验证'])
    expect(wrapper.text()).not.toContain('独立双评审')
  })

  it('opens a dialog preview and marks added and removed lines', async () => {
    vi.spyOn(api,'getTaskDiffPreview').mockResolvedValue({
      path: 'src/Main.java', changeType: 'MODIFIED', truncated: false,
      patch: 'diff --git a/src/Main.java b/src/Main.java\n--- a/src/Main.java\n+++ b/src/Main.java\n@@ -1 +1 @@\n-old value\n+new value',
    })
    const wrapper = mountPanel()
    await openTab(wrapper, '差异')
    await wrapper.get('button[aria-label="预览差异 src/Main.java"]').trigger('click')
    await flushPromises();expect(wrapper.text()).toContain('文件差异：src/Main.java')

    expect(api.getTaskDiffPreview).toHaveBeenCalledWith('task-1', 'src/Main.java')
    expect(wrapper.get('.ui-context-panel').text()).toContain('src/Main.java')
    expect(wrapper.get('[data-diff-kind="added"]').text()).toContain('+new value')
    expect(wrapper.get('[data-diff-kind="removed"]').text()).toContain('-old value')
    expect(wrapper.get('[data-diff-kind="hunk"]').text()).toContain('@@')
  })
})
