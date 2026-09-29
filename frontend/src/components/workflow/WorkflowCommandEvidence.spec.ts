import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { WorkflowCommandEvidence as Evidence, WorkflowCommandResult } from '@/types/domain'
import WorkflowCommandEvidence from './WorkflowCommandEvidence.vue'
const evidence: Evidence = { attemptId: 'private-attempt', requestSha256: null, resultSha256: null, request: null, registration: null, result: null,
  nativeReport: { files: [{ path: 'report.xml', content: '<testsuite><testcase><failure>expected 3</failure></testcase></testsuite>' }] } }
describe('原生命令证据正文', () => {
  it('分支采集使用业务说明，内部启动参数仅在主动展开后读取', async () => {
    const captured: Evidence = { ...evidence, nativeReport: undefined, request: { id: 'private-attempt', directory: '/private', argv: ['java', '-jar', '/private/helper.jar'], timeoutSeconds: 180 } }
    const view = mount(WorkflowCommandEvidence, { props: { evidence: captured, terminal: false, repository: true }, global: { stubs: { CodeMergeEditor: true } } })
    expect(view.text()).toContain('分支代码采集'); expect(view.find('code-merge-editor-stub').exists()).toBe(false)
    const details = view.findAll('details')[0]!; (details.element as HTMLDetailsElement).open = true; await details.trigger('toggle')
    expect(view.get('code-merge-editor-stub').attributes('modelvalue')).toContain('/private/helper.jar')
  })
  const result: WorkflowCommandResult = { requestSha256: 'private-sha', worker: { pid: 42, startedAt: '2026-01-01T00:00:00Z' },
    exitCode: 7, launched: true, timedOut: false, cancelled: false, outputTruncated: false, stopConfirmed: true, output: '安装缺少依赖', error: '', children: [] }
  const prepared: Evidence = { ...evidence, request: { id: 'private-attempt', directory: '/private', argv: ['npm', 'test'], timeoutSeconds: 60,
    preparations: [{ name: 'NPM_INSTALL', directory: '/private', argv: ['npm', 'ci'] }, { name: 'CHECK_ENV', directory: '/private', argv: ['node', '--version'] }] },
    result: { ...result, exitCode: null, launched: false, output: '', error: 'COMMAND_PREPARATION_FAILED', preparations: [result] } }
  it('区分准备失败、后续未执行和测试没有启动，原始身份不进入普通说明', () => {
    const view = mount(WorkflowCommandEvidence, { props: { evidence: prepared, terminal: true }, global: { stubs: { CodeMergeEditor: true } } })
    const preparation = view.get('[aria-label="依赖准备结果"]')
    expect(preparation.text()).toContain('进程退出码：7'); expect(preparation.text()).toContain('本步骤未执行')
    expect(preparation.text()).toContain('安装缺少依赖'); expect(view.text()).toContain('检查命令没有启动')
    expect(view.text()).toContain('依赖准备未完成'); expect(view.text()).not.toContain('private-'); expect(view.text()).not.toContain('NPM_INSTALL')
    expect(view.text()).toContain('总执行时限（包含依赖准备）')
  })
  it('运行中的准备不会因为没有最终回执而被显示成没有执行', () => {
    const view = mount(WorkflowCommandEvidence, { props: { evidence: { ...prepared, result: null }, terminal: false }, global: { stubs: { CodeMergeEditor: true } } })
    expect(view.get('[aria-label="依赖准备结果"]').text()).toContain('尚未取得执行结果')
    expect(view.text()).not.toContain('本步骤未执行')
  })
  it('准备取消、超时和输出不完整保持明确可见', () => {
    const view = mount(WorkflowCommandEvidence, { props: { evidence: { ...prepared, result: { ...prepared.result!, preparations: [{ ...result, cancelled: true, timedOut: true, outputTruncated: true, stopConfirmed: false }] } }, terminal: false }, global: { stubs: { CodeMergeEditor: true } } })
    const text = view.get('[aria-label="依赖准备结果"]').text()
    expect(text).toContain('准备已取消'); expect(text).toContain('准备超过执行时限'); expect(text).toContain('保存的输出不完整'); expect(text).toContain('停止尚未确认')
  })
  it('报告正文按用户展开后加载到只读编辑器', async () => {
    const view = mount(WorkflowCommandEvidence, { props: { evidence, terminal: true }, global: { stubs: { CodeMergeEditor: true } } })
    expect(view.find('code-merge-editor-stub').exists()).toBe(false); expect(view.text()).not.toContain('private-attempt')
    await view.get('button').trigger('click'); const editor = view.get('code-merge-editor-stub'); expect(editor.attributes('modelvalue')).toContain('expected 3'); expect(editor.attributes('language')).toBe('plain')
    await view.get('button').trigger('click'); expect(view.find('code-merge-editor-stub').exists()).toBe(false)
  })
  it('历史命令没有原生报告时仍可查看', () => {
    const view = mount(WorkflowCommandEvidence, { props: { evidence: { ...evidence, nativeReport: undefined }, terminal: true }, global: { stubs: { CodeMergeEditor: true } } })
    expect(view.text()).toContain('本次尝试尚未准备执行命令'); expect(view.find('button').exists()).toBe(false)
  })
  it('不把无效或不可信文件记录解释为 HTML', () => {
    const view = mount(WorkflowCommandEvidence, { props: { evidence: { ...evidence, nativeReport: { files: [null, { path: '<img src=x>', content: '<script>evil</script>' }] } }, terminal: true }, global: { stubs: { CodeMergeEditor: true } } })
    expect(view.text()).toContain('<img src=x>'); expect(view.find('img').exists()).toBe(false); expect(view.find('script').exists()).toBe(false)
  })
})
