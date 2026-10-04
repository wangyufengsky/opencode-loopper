import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, resolveDialog, type ReactTestRoot } from '@/pages/w6-tests/workflow/react-test-root'
import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowCommandEvidence, WorkflowNode, WorkflowNodeSummary } from '@/types/domain'
import { WorkflowNodeRun } from '@/pages/w6-tests/workflow/command-panels'
import { attempt, requirement } from './workflowRunTestFixtures'
import { commandPreset, verificationPreset } from './workflowTestFixtures'
vi.mock('@/api/workflowRuns', () => ({ workflowRuns: { attempts: vi.fn(), attempt: vi.fn(), definition: vi.fn(), inputs: vi.fn(), inputContent: vi.fn(), result: vi.fn(), activity: vi.fn(), complete: vi.fn(), modelAction: vi.fn(), commandAction: vi.fn(), commandEvidence: vi.fn() } }))
const api = vi.mocked(workflowRuns); let wrapper: ReactTestRoot | undefined
const summary = (latestAttemptId = 'run'): WorkflowNodeSummary => ({ id: 'node', nodeKey: 'review', state: 'WAITING_INPUT', attemptCount: 1, latestAttemptId, version: 1, outcome: null })
beforeEach(() => { vi.resetAllMocks();  api.attempts.mockResolvedValue({ items: [attempt()] }); api.attempt.mockResolvedValue(attempt()); api.definition.mockResolvedValue(requirement().graph.nodes[0]!) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })
async function render() { wrapper = mount(WorkflowNodeRun, { props: { requirement: 'req', version: 7, node: requirement().graph.nodes[0]!, summary: summary() } }); await flushPromises() }
const keys: Record<string,string> = { '阶段报告':'workflow.partialReport', '固定输入':'workflow.fixedInput', '查看固定版本正文':'workflow.inputContent', '重新检查停止':'workflow.nodeStopCheck', '恢复原尝试':'workflow.nodeResume', '停止节点':'workflow.nodeStop', '模型日志':'workflow.modelActivity', '重试原操作':'receipt.retryOriginal', '刷新操作结果':'receipt.readOriginal', '执行记录':'workflow.commandEvidence', '刷新执行记录':'ui.refresh', '交付物':'workflow.deliverables' }; const button = (name:string) => wrapper!.findAll('button').find(item => item.attributes('data-semantic')===keys[name])!
function evidence(output = '保存的检查输出'): WorkflowCommandEvidence { return { attemptId: 'run', registration: { requestSha256: 'request-hash', worker: { pid: 123, startedAt: 'time' } }, requestSha256: 'request-hash', resultSha256: 'result-hash', request: { id: 'run', directory: '/private/inspection', argv: ['mvn', 'test'], timeoutSeconds: 300 }, result: { requestSha256: 'request-hash', worker: { pid: 123, startedAt: 'time' }, exitCode: 0, launched: true, timedOut: false, cancelled: false, outputTruncated: false, stopConfirmed: true, output, error: '', children: [] } } }
describe('node execution inspector', () => {
  it('exposes partial reports only from an accepted, stopped source and binds its selected attempt', async () => {
    api.definition.mockResolvedValue({ ...requirement().graph.nodes[0]!, kind: 'SYSTEM', moduleId: 'system.review.snapshot' })
    api.attempt.mockResolvedValue(attempt({ state: 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true }))
    await render(); expect(wrapper!.find('[aria-label="阶段审查报告"]').exists()).toBe(false)
    await button('阶段报告').trigger('click'); await flushPromises()
    expect(wrapper!.get('[aria-label="阶段审查报告"]').text()).toContain('已完成批次报告'); expect(wrapper!.owners<{nodeId?:string;getSnapshot():{selected?:string}}>().find(owner=>owner.nodeId==='review')!.getSnapshot().selected).toBe('run')
    expect(api.result).not.toHaveBeenCalled(); expect(api.activity).not.toHaveBeenCalled()
  })
  it('expands fixed input metadata before requesting an individual reference body', async () => {
    api.inputs.mockResolvedValue({ version: 2, requirementId: 'req', planRevision: 1, nodeId: 'review', objective: '目标', values: [{ name: 'draft', kind: 'TEXT', source: 'NODE', sourceId: 'producer', outputName: 'result', attemptId: 'parent', sha256: 'hash', content: null, reference: { version: 1, contentSha256: 'body', sizeBytes: 100 } }] })
    api.inputContent.mockResolvedValue({ name: 'draft', kind: 'TEXT', sha256: 'hash', text: '固定设计', offset: 0, nextOffset: null, totalLength: 4 })
    await render(); expect(api.inputs).not.toHaveBeenCalled(); await button('固定输入').trigger('click'); await flushPromises()
    expect(api.inputContent).not.toHaveBeenCalled(); expect(button('查看固定版本正文')).toBeDefined()
    await button('查看固定版本正文').trigger('click'); await flushPromises(); expect(api.inputContent.mock.calls[0]!.slice(0, 5)).toEqual(['req', 'review', 'run', 'draft', 0])
    expect(wrapper!.get('.markdown-document').text()).toBe('固定设计')
  })
  it('rechecks an uncertain stop on the original command without offering a fresh start', async () => {
    api.attempt.mockResolvedValue(attempt({ state: 'STOPPING', commandState: 'STOPPING', commandVersion: 21, suspended: true, errorCode: 'WORKFLOW_COMMAND_STOP_UNCONFIRMED' }))
    api.definition.mockResolvedValue({ ...commandPreset().node, id: 'review' }); api.commandAction.mockResolvedValue({})
    await render(); expect(button('停止节点')).toBeUndefined(); expect(button('恢复原尝试')).toBeUndefined()
    await button('重新检查停止').trigger('click'); await flushPromises()
    expect(api.commandAction.mock.calls[0]).toEqual(['req', 'review', 'run', 'resume', expect.objectContaining({ expectedVersion: 21 })])
    expect(wrapper!.text()).toContain('等待停止确认'); expect(api.modelAction).not.toHaveBeenCalled()
  })
  it('uses the native command version and replays the same recovery after a lost acknowledgement', async () => {
    api.attempt.mockResolvedValue(attempt({ state: 'RUNNING', commandState: 'RUNNING', commandVersion: 17, suspended: true, errorCode: 'WORKFLOW_COMMAND_STOP_UNCONFIRMED' })); api.definition.mockResolvedValue({ ...commandPreset().node, id: 'review' })
    api.commandAction.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({})
    await render(); expect(api.commandEvidence).not.toHaveBeenCalled(); expect(wrapper!.text()).toContain('停止证明'); expect(button('模型日志')).toBeUndefined()
    await button('恢复原尝试').trigger('click'); await flushPromises(); await button('重试原操作').trigger('click'); await flushPromises()
    expect(api.commandAction.mock.calls[0]).toEqual(['req', 'review', 'run', 'resume', expect.objectContaining({ expectedVersion: 17 })]); expect(api.commandAction.mock.calls[1]).toEqual(api.commandAction.mock.calls[0]); expect(api.modelAction).not.toHaveBeenCalled()
  })
  it('confirms native stop, keeps an accepted acknowledgement and only rereads after refresh failure', async () => {
    api.attempt.mockResolvedValue(attempt({ state: 'RUNNING', commandState: 'RUNNING', commandVersion: 19 })); api.definition.mockResolvedValue({ ...commandPreset().node, id: 'review' }); api.commandAction.mockResolvedValue({})
    await render(); await button('停止节点').trigger('click'); await resolveDialog(wrapper!, false); expect(api.commandAction).not.toHaveBeenCalled()
    api.attempt.mockRejectedValueOnce(new Error('offline')); await button('停止节点').trigger('click'); await resolveDialog(wrapper!, true); await flushPromises()
    await button('刷新操作结果').trigger('click'); await flushPromises(); expect(api.commandAction).toHaveBeenCalledTimes(1); expect(api.commandAction.mock.calls[0]![3]).toBe('stop')
  })
  it('reads command evidence only on expansion and discards an older response after a newer refresh', async () => {
    api.attempt.mockResolvedValue(attempt({ state: 'FAILED', commandState: 'FAILED', commandVersion: 8 })); api.definition.mockResolvedValue({ ...commandPreset().node, id: 'review' }); await render()
    let resolve!: (value: WorkflowCommandEvidence) => void; api.commandEvidence.mockImplementationOnce(() => new Promise(r => { resolve = r })).mockResolvedValue(evidence('最新保存的检查输出'))
    await button('执行记录').trigger('click'); await flushPromises(); await button('刷新执行记录').trigger('click'); await flushPromises()
    resolve(evidence('过期输出')); await flushPromises(); expect(wrapper!.text()).toContain('最新保存的检查输出'); expect(wrapper!.text()).not.toContain('过期输出'); expect(button('停止节点')).toBeUndefined()
  })
  it('does not carry command evidence into a different historical attempt', async () => {
    api.attempt.mockResolvedValue(attempt({ state: 'FAILED', commandState: 'FAILED', commandVersion: 8 })); api.attempts.mockResolvedValue({ items: [attempt(), attempt({ id: 'old', ordinal: 0, state: 'FAILED', commandState: 'FAILED', commandVersion: 3 })] }); api.definition.mockResolvedValue({ ...commandPreset().node, id: 'review' }); await render()
    let resolve!: (value: WorkflowCommandEvidence) => void; api.commandEvidence.mockImplementationOnce(() => new Promise(r => { resolve = r }))
    await button('执行记录').trigger('click'); api.attempt.mockResolvedValue(attempt({ id: 'old', ordinal: 0, state: 'FAILED', commandState: 'FAILED', commandVersion: 3 })); await wrapper!.get('select').setValue('old'); await flushPromises()
    resolve(evidence('另一次尝试的输出')); await flushPromises(); expect(wrapper!.text()).not.toContain('另一次尝试的输出'); expect(button('停止节点')).toBeUndefined()
  })
  it('reads bodies only on expansion and reuses immutable input while reopening it', async () => {
    await render(); expect(api.inputs).not.toHaveBeenCalled(); expect(api.result).not.toHaveBeenCalled(); expect(api.activity).not.toHaveBeenCalled()
    api.inputs.mockResolvedValue({ version: 1, requirementId: 'req', planRevision: 2, nodeId: 'review', objective: '固定目标', values: [] })
    await button('固定输入').trigger('click'); await flushPromises(); expect(wrapper!.text()).toContain('固定目标'); await button('固定输入').trigger('click'); await button('固定输入').trigger('click'); expect(api.inputs).toHaveBeenCalledTimes(1)
  })
  it('submits frozen attempt and requirement versions and replays the same human delivery on uncertain acknowledgement', async () => {
    await render(); await wrapper!.get('textarea').setValue('核对完成'); await wrapper!.findAll('textarea')[1]!.setValue('检查证据')
    api.complete.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({}); await wrapper!.get('form').trigger('submit'); await flushPromises()
    expect(api.complete.mock.calls[0]).toEqual(['req', 'review', expect.objectContaining({ expectedVersion: 7, attemptId: 'run', expectedAttemptVersion: 1, delivery: { summary: '核对完成', outcome: null, outputs: { result: { kind: 'TEXT', content: '检查证据' } } } })])
    await button('重试原操作').trigger('click'); await flushPromises(); expect(api.complete.mock.calls[1]).toEqual(api.complete.mock.calls[0]); expect(wrapper!.emitted('changed')).toHaveLength(1)
  })
  it('rereads an accepted human completion without submitting its delivery twice', async () => {
    await render(); await wrapper!.get('textarea').setValue('核对完成'); await wrapper!.findAll('textarea')[1]!.setValue('检查证据'); api.complete.mockResolvedValue({}); api.attempt.mockRejectedValueOnce(new Error('offline'))
    await wrapper!.get('form').trigger('submit'); await flushPromises(); await button('刷新操作结果').trigger('click'); await flushPromises(); expect(api.complete).toHaveBeenCalledTimes(1)
  })
  it('drops the previous attempt definition when a newer attempt becomes current', async () => {
    let resolve!: (value: WorkflowNode) => void; api.definition.mockImplementationOnce(() => new Promise(r => { resolve = r })); await render()
    api.attempt.mockResolvedValue(attempt({ id: 'new', ordinal: 2 })); api.definition.mockResolvedValue({ ...requirement().graph.nodes[0]!, task: '新的冻结任务' }); await wrapper!.setProps({ summary: summary('new') }); await flushPromises()
    resolve({ ...requirement().graph.nodes[0]!, task: '过期的冻结任务' }); await flushPromises(); expect(wrapper!.text()).toContain('新的冻结任务'); expect(wrapper!.text()).not.toContain('过期的冻结任务')
  })
  it('keeps a manually selected historical attempt when a new attempt arrives', async () => {
    api.attempts.mockResolvedValue({ items: [attempt(), attempt({ id: 'old', ordinal: 0, state: 'FAILED' })] }); await render(); api.attempt.mockResolvedValue(attempt({ id: 'old', ordinal: 0, state: 'FAILED' }))
    await wrapper!.get('select').setValue('old'); await flushPromises(); const count = api.attempt.mock.calls.length; await wrapper!.setProps({ summary: summary('new') }); await flushPromises(); expect(api.attempt).toHaveBeenCalledTimes(count); expect(wrapper!.find('form').exists()).toBe(false)
  })
  it('asks before abandoning unsubmitted human results and warns on browser unload', async () => {
    await render(); await wrapper!.get('textarea').setValue('未提交说明');
    expect((wrapper!.owners<{ canLeave(): {kind:string} }>()[0]!).canLeave().kind).not.toBe('ALLOW')
    const event = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(event); expect(event.defaultPrevented).toBe(true)
  })
  it('keeps a failed program report readable without pretending the completed attempt is still settling', async () => {
    const node = { ...verificationPreset().node, id: 'review' }
    api.attempt.mockResolvedValue(attempt({ state: 'FAILED', deliveryAccepted: true, stopConfirmed: true })); api.definition.mockResolvedValue(node)
    api.result.mockResolvedValue({ attemptId: 'run', state: 'FAILED', sha256: 'hash', delivery: { summary: '交付物检查存在未通过项。', outcome: 'FAIL', outputs: {
      report: { kind: 'JSON', content: { version: 1, checks: [{ title: '配置校验', path: 'config.json', state: 'FAIL' }] } },
    } } })
    await render(); await button('交付物').trigger('click'); await flushPromises()
    expect(wrapper!.text()).toContain('程序检查'); expect(wrapper!.text()).toContain('配置校验'); expect(wrapper!.text()).toContain('未通过')
    expect(wrapper!.text()).not.toContain('正在等待执行与资源收尾'); expect(button('模型日志')).toBeUndefined(); expect(wrapper!.find('form').exists()).toBe(false)
  })
})
