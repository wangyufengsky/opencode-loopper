import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, resolveDialog } from '@/pages/w6-tests/workflow/react-test-root'
import { mountPageApplication } from '@/pages/w6-tests/workflow/application-test-root'
import { RequirementPage } from '@/pages/w5/requirements/RequirementPage'
import type { RequirementController } from '@/pages/w5/requirements/controller'
import type { NodeController } from '@/pages/w5/requirements/nodeController'
import type { UploadController } from '@/pages/w5/requirements/uploadController'
import { workflowRuns } from '@/api/workflowRuns'
import { api as clientApi } from '@/api/client'
import type { AppSettings } from '@/types/domain'
import { workflowDocuments } from '@/api/workflowDocuments'
import { newNode } from '@/components/workflow/graph'
import { workflowApi } from '@/api/workflow'
import { preset } from '@/components/workflow/workflowTestFixtures'
import { attempt, candidate, execution, requirement } from '@/components/workflow/workflowRunTestFixtures'
vi.mock('@/api/workflowRuns', () => ({ workflowRuns: { get: vi.fn(), previewTemplate: vi.fn(), saveTemplate: vi.fn(), finishStatus: vi.fn(), finish: vi.fn(), execution: vi.fn(), confirm: vi.fn(), start: vi.fn(), pause: vi.fn(), applyPlan: vi.fn(), applyCandidate: vi.fn(), candidates: vi.fn(), candidate: vi.fn(), rejectCandidate: vi.fn(), revise: vi.fn(), layout: vi.fn(), attempts:vi.fn(),attempt:vi.fn(),definition:vi.fn(),complete:vi.fn(),commandAction:vi.fn() } }))
vi.mock('@/api/workflowDocuments', () => ({ workflowDocuments: { upload: vi.fn(), get: vi.fn(), fileUrl: vi.fn(() => '/download') } }))
vi.mock('@/api/workflow', () => ({ workflowApi: { presets: vi.fn(), preset: vi.fn() } }))
const api = vi.mocked(workflowRuns); let wrapper: Awaited<ReturnType<typeof mountPageApplication>> | undefined
beforeEach(() => { vi.resetAllMocks(); vi.spyOn(clientApi, 'getSettings').mockResolvedValue({ openCode: { provider: 'configured', model: 'default' } } as AppSettings); vi.spyOn(clientApi, 'getSettingsModels').mockResolvedValue([]); api.finishStatus.mockResolvedValue({ requirementId: 'req', state: 'PLANNING', version: 7, intent: null, pending: { attempts: 0, resources: 0 } }); api.get.mockResolvedValue(requirement()); api.execution.mockResolvedValue(execution());api.attempts.mockResolvedValue({items:[attempt()]});api.attempt.mockResolvedValue(attempt());api.definition.mockResolvedValue(requirement().graph.nodes[0]!);api.start.mockResolvedValue(execution('PENDING_START').control) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })
async function render() {wrapper=await mountPageApplication(RequirementPage,'/requirements/req',['/requirements/:id','/requirements','/workflows/:id']);return wrapper}
const keys:Record<string,string>={'添加节点':'workflow.addNode','更多工具':'workflow.moreTools','预设工作模块':'ui.open','人工检查':'workflow.addNode','人工检查由你补充结果并确认':'workflow.addNode','另存为流程模板':'workflow.saveTemplate','确认保存为新流程':'workflow.saveTemplateConfirm','重试原保存操作':'receipt.retryOriginal','返回任务画布':'nav.back','自动排列':'workflow.autoLayout','确认计划':'workflow.confirmPlan','连续执行':'workflow.continuous','重试原操作':'receipt.retryOriginal','刷新操作结果':'receipt.readOriginal','执行所选节点':'workflow.single','运行至所选节点':'workflow.until','刷新状态':'ui.refresh','调整后续计划':'workflow.adjustPlan','应用计划调整':'workflow.savePlanning','候选计划':'workflow.reviewCandidate','在画布中查看':'workflow.reviewCandidate','确认并应用候选计划':'workflow.applyCandidate','退出候选预览':'ui.discardChanges','需求与资料':'workflow.flowInputs','重试默认模型':'ui.retry','上传并选用':'workflow.uploadAndSelect','改为新上传':'ui.discardChanges','添加到画布':'workflow.addNode'}
function owner():RequirementController{return [...wrapper!.application.current!.owners.keys()].find(value=>'id' in value&&'setLayout'in value) as RequirementController}
function nodeOwner():NodeController{return [...wrapper!.application.current!.owners.keys()].find(value=>'nodeId'in value&&'human'in value) as NodeController}
function uploadOwner():UploadController{return [...wrapper!.application.current!.owners.keys()].find(value=>'originalFiles'in value) as UploadController}
const button=(name:string)=>wrapper!.findAll('button').filter(node=>!node.element.closest('[hidden]')).find(node=>!!keys[name]&&node.attributes('data-semantic')===keys[name]||node.attributes('aria-label')===name)
async function clickButton(label:string){
 if(label.startsWith('关闭')){const panel=wrapper!.findAll('aside:not([hidden])').find(node=>label.includes(node.attributes('aria-label')??''))??wrapper!.get('aside:not([hidden])');await panel.get('button[data-semantic="ui.close"]').trigger('click');return}
 if(['人工检查','人工检查由你补充结果并确认','预设工作模块'].includes(label)){await wrapper!.get('button[data-semantic="workflow.addNode"]').trigger('click');await wrapper!.get(`button[aria-label="${label==='预设工作模块'?'查看：预设工作模块':'添加节点：人工检查'}"]`).trigger('click');return}
 if(label==='重试默认模型'&&!button(label)){await button('需求与资料')!.trigger('click')};if(label==='添加到画布'){await wrapper!.get('.workflow-preset-detail button[data-semantic="workflow.addNode"]').trigger('click');return};if(label==='在画布中查看'){await wrapper!.get('aside:not([hidden]) button[data-semantic="workflow.reviewCandidate"]').trigger('click');return};if(!button(label)){expect(button('更多工具'),`页面未出现更多工具，操作 ${label}：${wrapper!.text()}`).toBeDefined();await button('更多工具')!.trigger('click')};const target=button(label);expect(target,`实际操作入口：${label}`).toBeDefined();await target!.trigger('click')
}
async function waitForConnections(count: number) {
  expect(wrapper!.find('[data-canvas-runtime="react"]').exists()).toBe(true)
  // Real React Flow measures handles before rendering their edges.
  await vi.waitFor(() => expect(wrapper!.findAll('.workflow-wire')).toHaveLength(count))
}
describe('requirement canvas', () => {
  it('saves a captured draft independently and protects an uncertain template operation during navigation', async () => {
    const router = await render(); await clickButton('人工检查')
    api.previewTemplate.mockImplementation(async (_id, selection) => ({ ...selection, sourceRevision: 2, initialAvailable: false, fixedPlanningNodes: [], sha256: 'fixed', diagnostics: [] }))
    await clickButton('另存为流程模板'); await flushPromises()
    expect(api.previewTemplate.mock.calls[0]![1].graph.nodes).toHaveLength(2)
    api.saveTemplate.mockRejectedValueOnce(new Error('offline')).mockResolvedValue({ id: 'saved-template', revision: 1, version: 0, layoutVersion: 0, state: 'ACTIVE' })
    await clickButton('确认保存为新流程'); await flushPromises(); await router.navigate('/requirements')
    expect(router.router.state.location.pathname).toBe('/requirements/req'); expect(api.revise).not.toHaveBeenCalled(); expect(api.start).not.toHaveBeenCalled()
    await clickButton('重试原保存操作'); await flushPromises(); expect(api.saveTemplate.mock.calls[1]).toEqual(api.saveTemplate.mock.calls[0])
    await clickButton('返回任务画布'); expect(wrapper!.text()).toContain('未保存'); expect(wrapper!.findAll('.workflow-node')).toHaveLength(2)
  })

  it('adds a preset to the planning draft without starting or saving execution', async () => {
    vi.mocked(workflowApi.presets).mockResolvedValue({ items: [preset()], nextCursor: null }); vi.mocked(workflowApi.preset).mockResolvedValue(preset())
    await render(); await clickButton('预设工作模块'); await flushPromises(); await wrapper!.get('.workflow-preset-list button').trigger('click'); await flushPromises()
    await wrapper!.get('.workflow-preset-detail select').setValue('NODE|review|result'); await clickButton('添加到画布')
    expect(wrapper!.findAll('.workflow-node')).toHaveLength(2); await waitForConnections(1); expect(wrapper!.find('.workflow-presets').exists()).toBe(false)
    expect(api.start).not.toHaveBeenCalled(); expect(api.revise).not.toHaveBeenCalled(); expect(api.applyPlan).not.toHaveBeenCalled()
    await wrapper!.get('button[data-semantic="workflow.undo"]').trigger('click'); expect(wrapper!.findAll('.workflow-node')).toHaveLength(1)
  })
  it('confirmation does not start execution and preserves unsaved presentation', async () => {
    await render(); await clickButton('自动排列'); api.get.mockResolvedValue(requirement({ state: 'PENDING_START', version: 4 })); api.confirm.mockResolvedValue({id:'req',revision:2,version:4,layoutVersion:4,state:'PENDING_START'}); const confirmed=execution('PENDING_START');confirmed.execution.version=4;confirmed.control.version=4;api.execution.mockResolvedValue(confirmed)
    await clickButton('确认计划'); await flushPromises(); expect(api.confirm).toHaveBeenCalledOnce(); expect(api.start).not.toHaveBeenCalled(); expect(wrapper!.text()).toContain('未保存'); expect(button('连续执行')).toBeDefined()
  })
  it('requires explicit acknowledgement after reopening a checkpoint and freezes the original command on timeout', async () => {
    const snapshot = execution('PAUSED'); snapshot.control = { ...snapshot.control, configured: true, mode: 'CONTINUOUS', state: 'WAITING', version: 7, controlVersion: 4, reasonCode: 'WORKFLOW_CHECKPOINT', checkpoints: [{ attemptId: 'done', requirementId: 'req', nodeKey: 'review', createdAt: '', acknowledgedAt: null }] }
    api.get.mockResolvedValue(requirement({ state: 'PAUSED' })); api.execution.mockResolvedValue(snapshot); await render()
    expect(button('连续执行')!.attributes('disabled')).toBeDefined(); await wrapper!.get('input[type=checkbox]').setValue(true)
    api.start.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue(snapshot.control)
    await clickButton('连续执行'); await flushPromises(); expect(api.start.mock.calls[0]![1]).toMatchObject({ expectedVersion: 7, expectedControlVersion: 4, checkpointAttempts: ['done'], inputs: null })
    await clickButton('重试原操作'); await flushPromises(); expect(api.start.mock.calls[0]).toEqual(api.start.mock.calls[1])
  })
  it('does not repeat an accepted execution when status readback fails', async () => {
    api.get.mockResolvedValue(requirement({ state: 'PENDING_START' })); api.execution.mockResolvedValue(execution('PENDING_START')); await render()
    api.start.mockResolvedValue(execution('RUNNING').control); api.execution.mockRejectedValueOnce(new Error('offline'))
    await clickButton('连续执行'); await flushPromises(); expect(button('刷新操作结果')).toBeDefined()
    await clickButton('刷新操作结果'); await flushPromises(); expect(api.start).toHaveBeenCalledTimes(1)
  })
  it('sends SINGLE and UNTIL with the selected target instead of the entire graph', async () => {
    api.get.mockResolvedValue(requirement({ state: 'PENDING_START' })); api.execution.mockResolvedValue(execution('PENDING_START')); await render(); await wrapper!.get('.workflow-node').trigger('click')
    await clickButton('执行所选节点'); await flushPromises(); expect(api.start.mock.calls[0]![1]).toMatchObject({ mode: 'SINGLE', targetKey: 'review' })
    await clickButton('运行至所选节点'); await flushPromises(); expect(api.start.mock.calls[1]![1]).toMatchObject({ mode: 'UNTIL', targetKey: 'review' })
  })
  it('retains a conflicting local plan and disables execution after observing a different revision', async () => {
    await render(); await clickButton('人工检查'); const changed = execution(); changed.execution.revision = 3; api.execution.mockResolvedValue(changed)
    await clickButton('刷新状态'); await flushPromises(); expect(wrapper!.text()).toContain('当前草稿已保留'); expect(wrapper!.findAll('.workflow-node')).toHaveLength(2); expect(button('确认计划')!.attributes('disabled')).toBeDefined()
  })
  it('keeps the draft when the user rejects navigation away', async () => {
    const router=await render();await clickButton('人工检查');const navigation=router.navigate('/requirements');await flushPromises();await resolveDialog(wrapper!,false);await navigation;expect(router.router.state.location.pathname).toBe('/requirements/req');expect(wrapper!.findAll('.workflow-node')).toHaveLength(2)
  })
  it('pauses dispatch before editing, protects the running definition, and applies with the latest version', async () => {
    const value = execution('RUNNING'); value.execution.nodes = [{ id: 'run', nodeKey: 'review', state: 'ACTIVE', attemptCount: 1, latestAttemptId: 'attempt', version: 1, outcome: null }]; value.control = { ...value.control, configured: true, state: 'ACTIVE', mode: 'CONTINUOUS' }
    api.get.mockResolvedValue(requirement({ state: 'RUNNING' })); api.execution.mockResolvedValue(value); await render()
    api.pause.mockImplementation(async () => { value.execution.version = 8; value.execution.state = 'PAUSED'; value.control.state = 'PAUSED'; return value.control })
    await clickButton('调整后续计划'); await flushPromises(); expect(api.pause).toHaveBeenCalledOnce(); expect(button('连续执行')!.attributes('disabled')).toBeDefined()
    await wrapper!.get('.workflow-node').trigger('click'); expect(wrapper!.get('section[aria-label="节点设置"] fieldset').attributes('disabled')).toBeDefined()
    await clickButton('人工检查'); const applied = requirement({ state: 'PAUSED', revision: 3, version: 9,layoutVersion:5 }); api.applyPlan.mockResolvedValue({ id: 'req', revision: 3, version: 9, layoutVersion: 4, state: 'PAUSED' }); api.layout.mockResolvedValue({ id: 'req', revision: 3, version: 9, layoutVersion: 5, state: 'PAUSED' }); api.get.mockResolvedValue(applied); value.execution.revision = 3
    await clickButton('应用计划调整'); await flushPromises(); expect(api.applyPlan.mock.calls[0]![1]).toMatchObject({ expectedVersion: 8, expectedRevision: 2 }); expect(api.start).not.toHaveBeenCalled()
  })
  it('previews and edits a candidate without applying until the user explicitly confirms', async () => {
    const value = execution('PAUSED'), draft = candidate(); value.control.reasonCode = 'WORKFLOW_PLAN_REVIEW_REQUIRED'; value.execution.nodes = [{ id: 'source', nodeKey: 'review', state: 'SUCCEEDED', attemptCount: 1, latestAttemptId: draft.attemptId, version: 2, outcome: null }]
    api.get.mockResolvedValue(requirement({ state: 'PAUSED' })); api.execution.mockResolvedValue(value); api.candidates.mockResolvedValue({ items: [{ ...draft, sourceState: 'SUCCEEDED', createdAt: '' }], nextCursor: null }); api.candidate.mockResolvedValue(draft); await render()
    await clickButton('候选计划'); await flushPromises(); await wrapper!.get('aside:not([hidden]) button[data-semantic="selection.select"]').trigger('click'); await flushPromises(); await clickButton('在画布中查看'); await flushPromises()
    expect(owner().getSnapshot().proposal).not.toBeNull(); expect(api.applyCandidate).not.toHaveBeenCalled(); expect(button('连续执行')!.attributes('disabled')).toBeDefined()
    await wrapper!.get('.workflow-node[data-node-id="next"]').trigger('click'); await wrapper!.get('section[aria-label="节点设置"] textarea').setValue('检查用户修改后的成果')
    api.applyCandidate.mockResolvedValue({ id: 'req', revision: 3, version: 8, layoutVersion: 4, state: 'PAUSED' }); api.layout.mockResolvedValue({ id: 'req', revision: 3, version: 8, layoutVersion: 5, state: 'PAUSED' }); api.get.mockResolvedValue(requirement({ state: 'PAUSED', revision: 3,version:8,layoutVersion:5 })); value.execution.revision = 3
    await clickButton('确认并应用候选计划'); await flushPromises(); expect(api.applyCandidate).toHaveBeenCalledOnce(); expect(api.applyCandidate.mock.calls[0]![2].graph.nodes[1]!.task).toBe('检查用户修改后的成果'); expect(api.start).not.toHaveBeenCalled()
  })
  it('keeps historical or stale candidates read-only and returns to the effective graph on exit', async () => {
    const draft = candidate({ stale: true }); api.get.mockResolvedValue(requirement({ state: 'PAUSED' })); api.execution.mockResolvedValue(execution('PAUSED')); api.candidates.mockResolvedValue({ items: [{ ...draft, sourceState: 'SUCCEEDED', createdAt: '' }] }); api.candidate.mockResolvedValue(draft); await render()
    await clickButton('候选计划'); await flushPromises(); await wrapper!.get('aside:not([hidden]) button[data-semantic="selection.select"]').trigger('click'); await flushPromises(); await clickButton('在画布中查看'); await flushPromises()
    expect(owner().proposalReadonly()).toBe(true); expect(button('确认并应用候选计划')).toBeUndefined(); expect(wrapper!.findAll('.workflow-node')).toHaveLength(2); await clickButton('退出候选预览');await resolveDialog(wrapper!,true); expect(wrapper!.findAll('.workflow-node')).toHaveLength(1); expect(api.applyCandidate).not.toHaveBeenCalled()
  })

  it('allows preview during source cleanup but requires successful source completion before applying', async () => {
    const draft = candidate({ sourceCompleted: false }), value = execution('PAUSED'); api.get.mockResolvedValue(requirement({ state: 'PAUSED' })); api.execution.mockResolvedValue(value); api.candidates.mockResolvedValue({ items: [{ ...draft, sourceState: 'RUNNING', createdAt: '' }] }); api.candidate.mockResolvedValue(draft); await render()
    await clickButton('候选计划'); await flushPromises(); await wrapper!.get('aside:not([hidden]) button[data-semantic="selection.select"]').trigger('click'); await flushPromises(); await clickButton('在画布中查看'); await flushPromises()
    expect(button('确认并应用候选计划')!.attributes('disabled')).toBeDefined(); value.execution.nodes = [{ id: 'source', nodeKey: 'review', state: 'SUCCEEDED', attemptCount: 1, latestAttemptId: draft.attemptId, version: 2, outcome: null }]; api.execution.mockResolvedValue(structuredClone(value))
    await clickButton('刷新状态'); await flushPromises(); expect(button('确认并应用候选计划')!.attributes('disabled')).toBeUndefined(); expect(api.applyCandidate).not.toHaveBeenCalled()
  })

  it('starts without side panels or routine execution notices and keeps frozen input values read-only', async () => {
    const value = requirement({ state: 'RUNNING' }); value.graph.inputs = [{ name: 'source', title: '原文', kind: 'TEXT', required: true }]
    const snapshot = execution('RUNNING'); snapshot.control.configured = true
    api.get.mockResolvedValue(value); api.execution.mockResolvedValue(snapshot); await render()
    expect(wrapper!.find('aside:not([hidden])').exists()).toBe(false); expect(wrapper!.find('.workflow-run-controls').exists()).toBe(false)
    await clickButton('需求与资料'); expect(wrapper!.text()).toContain('公共资料已固定'); expect(wrapper!.get('textarea[aria-label="原文"]').attributes('disabled')).toBeDefined()
    await clickButton('关闭需求与资料'); expect(wrapper!.find('aside:not([hidden])').exists()).toBe(false)
  })
  it('keeps an unsent node form when context changes are rejected and does not prompt on repeated selection', async () => {
    const value=requirement({state:'PAUSED'});value.graph.nodes.push({...value.graph.nodes[0]!,id:'other',title:'后续检查'});value.graph.edges.push({id:'edge',from:'review',to:'other',outcome:null});const snapshot=execution('PAUSED');snapshot.execution.nodes=[{id:'review',nodeKey:'review',state:'WAITING_INPUT',attemptCount:1,latestAttemptId:'run',version:1,outcome:null}];api.get.mockResolvedValue(value);api.execution.mockResolvedValue(snapshot);await render();await waitForConnections(1);await wrapper!.get('.workflow-node').trigger('click');const panel=wrapper!.get('[data-node-owner="review"]').element;await wrapper!.get('[data-node-owner] form textarea').setValue('未发送的结果');await wrapper!.get('.workflow-node').trigger('click');expect(wrapper!.application.current!.dialog.getSnapshot().open).toBe(false);expect(wrapper!.findAll('[role=dialog]').filter(d=>getComputedStyle(d.element.closest('.ant-modal-wrap')??d.element).display!=='none')).toHaveLength(0)
    for(const target of [wrapper!.findAll('.workflow-node')[1]!,wrapper!.get('.workflow-wire-hit'),wrapper!.get('.workflow-canvas')]){await target.trigger('click');await resolveDialog(wrapper!,false)}
    await wrapper!.get('.workflow-canvas').trigger('keydown',{key:'Escape'});await resolveDialog(wrapper!,false);expect(wrapper!.get('[data-node-owner="review"]').element).toBe(panel);expect(wrapper!.get('.workflow-node.selected').attributes('data-node-id')).toBe('review');expect(wrapper!.get<HTMLTextAreaElement>('[data-node-owner] form textarea').element.value).toBe('未发送的结果');await clickButton('关闭节点详情');await resolveDialog(wrapper!,true);expect(wrapper!.find('[data-node-owner]').exists()).toBe(false)
  })
  it('does not dismiss a node with an unknown operation receipt, even when Escape or blank canvas is used', async () => {
    const snapshot=execution('PAUSED');snapshot.execution.nodes=[{id:'review',nodeKey:'review',state:'ACTIVE',attemptCount:1,latestAttemptId:'run',version:1,outcome:null}];api.get.mockResolvedValue(requirement({state:'PAUSED'}));api.execution.mockResolvedValue(snapshot);api.attempt.mockResolvedValue(attempt({state:'RUNNING',commandState:'RUNNING',commandVersion:2}));api.commandAction.mockRejectedValueOnce(new Error('unknown'));await render();await wrapper!.get('.workflow-node').trigger('click');const panel=wrapper!.get('[data-node-owner]').element;await wrapper!.get('button[data-semantic="workflow.nodeStop"]').trigger('click');await resolveDialog(wrapper!,true);await flushPromises();expect(nodeOwner().canLeave().kind).toBe('BLOCK');expect(wrapper!.get('aside:not([hidden]) button[data-semantic="ui.close"]').attributes('disabled')).toBeDefined();await wrapper!.get('.workflow-canvas').trigger('click');await wrapper!.get('.workflow-canvas').trigger('keydown',{key:'Escape'});expect(wrapper!.get('[data-node-owner]').element).toBe(panel);api.commandAction.mockResolvedValue({state:'STOPPING'});await vi.waitFor(()=>{const retry=wrapper!.get('button[data-semantic="receipt.retryOriginal"]');expect(retry.attributes('aria-busy')).toBe('false');expect(retry.element.classList.contains('ant-btn-loading')).toBe(false)});await wrapper!.get('button[data-semantic="receipt.retryOriginal"]').trigger('click');await flushPromises();expect(nodeOwner().canLeave(),JSON.stringify({command:nodeOwner().getSnapshot().command,calls:api.commandAction.mock.calls,buttons:wrapper!.findAll('button[data-semantic="receipt.retryOriginal"]').map(b=>({disabled:b.attributes('disabled'),hidden:!!b.element.closest('[hidden]')}))})).toEqual({kind:'ALLOW'});await clickButton('关闭节点详情');expect(owner().getSnapshot().selected).toBe('');expect(wrapper!.find('[data-node-owner]').exists()).toBe(false)
  })
  it('keeps active uploads mounted until the existing upload settles', async () => {
    const value=requirement();value.graph.inputs=[{name:'source',title:'原文',kind:'DOCUMENT',required:true}];api.get.mockResolvedValue(value);let resolve!:(value:Awaited<ReturnType<typeof workflowDocuments.upload>>)=>void;vi.mocked(workflowDocuments.upload).mockReturnValue(new Promise(done=>{resolve=done}));await render();await clickButton('需求与资料');const input=wrapper!.get('input[type=file]'),fields=wrapper!.get('section[aria-label="填写公共资料"]').element;Object.defineProperty(input.element,'files',{configurable:true,value:[new File(['original'],'需求.md')]});await input.trigger('change');await vi.waitFor(()=>expect(uploadOwner().getSnapshot().hashing).toBe(false));await clickButton('上传并选用');expect(uploadOwner().getSnapshot().command.phase).toBe('SENDING');await wrapper!.get('.workflow-canvas').trigger('click');await wrapper!.get('.workflow-node').trigger('click');expect(wrapper!.get('section[aria-label="填写公共资料"]').element).toBe(fields);expect(wrapper!.get('aside:not([hidden]) button[data-semantic="ui.close"]').attributes('disabled')).toBeDefined();resolve({id:'upload',ready:true,createdAt:'',parserVersion:'v1',resume:null,reference:{version:1,type:'UPLOADED_DOCUMENTS',uploadId:'upload',sha256:'fixed'},originals:[{filename:'需求.md',path:'original/1',sizeBytes:8,sha256:'0682c5f2076f099c34cfdd15a9e063849ed437a49677e6fcc5b4198c76575be5',representationSha256:'fixed',format:'md',sections:1,limitations:[]}]});await flushPromises();expect(uploadOwner().canLeave().kind).toBe('ALLOW');await wrapper!.get('.workflow-canvas').trigger('keydown',{key:'Escape'});expect(wrapper!.find('aside:not([hidden])').exists()).toBe(false)
  })

  it('starts directly from the clean canvas with the default model without opening settings', async () => {
    const value = requirement({ state: 'PENDING_START' }); value.graph.nodes = [newNode('free.readonly')]
    api.get.mockResolvedValue(value); api.execution.mockResolvedValue(execution('PENDING_START')); await render()
    expect(wrapper!.find('select[aria-label="执行模型"]').exists()).toBe(false)
    await clickButton('连续执行'); await flushPromises()
    expect(api.start.mock.calls[0]![1].model).toEqual({ providerId: 'configured', modelId: 'default', thinking: null })
    expect(clientApi.getSettingsModels).not.toHaveBeenCalled()
  })
  it('waits for defaults before enabling execution and retains a manual choice over a late response', async () => {
    let resolve!: (value: AppSettings) => void
    vi.mocked(clientApi.getSettings).mockImplementation(() => new Promise(done => { resolve = done }))
    const value = requirement({ state: 'PENDING_START' }); value.graph.nodes = [newNode('free.readonly')]
    api.get.mockResolvedValue(value); api.execution.mockResolvedValue(execution('PENDING_START')); await render()
    expect(button('连续执行')!.attributes('disabled')).toBeDefined(); await clickButton('连续执行'); expect(api.start).not.toHaveBeenCalled()
    await clickButton('需求与资料'); vi.mocked(clientApi.getSettingsModels).mockResolvedValue([{id:'user/manual',provider:'user',model:'manual',label:'用户模型'}]);await wrapper!.get('aside:not([hidden]) select').trigger('focus');await flushPromises();await wrapper!.get('aside:not([hidden]) select').setValue('user/manual')
    resolve({ openCode: { provider: 'configured', model: 'late' } } as AppSettings); await flushPromises(); expect(api.start).not.toHaveBeenCalled()
    await clickButton('连续执行'); await flushPromises()
    expect(api.start.mock.calls[0]![1].model).toEqual({ providerId: 'user', modelId: 'manual', thinking: null })
  })
  it('uses persisted execution settings and keeps failures recoverable without forcing a model on a human-only execution scope', async () => {
    const value = requirement({ state: 'PENDING_START' }); value.graph.nodes.push(newNode('free.readonly'))
    const snapshot = execution('PENDING_START'); snapshot.control.model = { providerId: 'saved', modelId: 'frozen', thinking: null }
    api.get.mockResolvedValue(value); api.execution.mockResolvedValue(snapshot); await render()
    expect(clientApi.getSettings).not.toHaveBeenCalled(); await clickButton('连续执行'); await flushPromises(); expect(api.start.mock.calls[0]![1].model).toEqual(snapshot.control.model)
    wrapper!.unmount(); wrapper = undefined; snapshot.control.model = null; vi.mocked(clientApi.getSettings).mockRejectedValueOnce(new Error('offline')); await render()
    expect(wrapper!.text()).toContain('默认执行模型暂时无法读取'); expect(wrapper!.find('aside:not([hidden])').exists()).toBe(false)
    await wrapper!.get('.workflow-node').trigger('click'); await clickButton('执行所选节点'); await flushPromises()
    expect(api.start.mock.calls.at(-1)![1]).toMatchObject({ mode: 'SINGLE', targetKey: 'review', model: null })
    await clickButton('重试默认模型'); await flushPromises(); expect(wrapper!.text()).not.toContain('默认执行模型暂时无法读取')
  })
  it('discards the previous default response when the requirement route changes', async () => {
    const resolves: Array<(value: AppSettings) => void> = []
    vi.mocked(clientApi.getSettings).mockImplementation(() => new Promise(done => resolves.push(done)))
    const value = requirement({ state: 'PENDING_START' }); value.graph.nodes = [newNode('free.readonly')]
    api.get.mockImplementation(async id=>({...value,id}));api.execution.mockImplementation(async id=>{const snapshot=execution('PENDING_START');snapshot.execution.id=id;snapshot.control.id=id;return snapshot}); const router = await render()
    await clickButton('连续执行'); await router.navigate('/requirements/next'); await flushPromises()
    resolves[0]!({ openCode: { provider: 'old', model: 'old' } } as AppSettings); await flushPromises(); expect(api.start).not.toHaveBeenCalled()
    resolves[1]!({ openCode: { provider: 'next', model: 'next' } } as AppSettings); await flushPromises()
    await clickButton('连续执行'); await flushPromises(); expect(api.start.mock.calls[0]).toMatchObject(['next', { model: { providerId: 'next', modelId: 'next' } }])
  })
  it('keeps failed uploads, original files and request identity through Escape, close, navigation and execution attempts', async () => {
    const value = requirement({ state: 'PENDING_START' }); value.graph.inputs = [{ name: 'document', title: '原文', kind: 'DOCUMENT', required: false }]
    api.get.mockResolvedValue(value); api.execution.mockResolvedValue(execution('PENDING_START')); vi.mocked(workflowDocuments.upload).mockRejectedValue(new Error('连接中断'))
    const router=await render();await clickButton('需求与资料');const document=wrapper!.get('section[aria-label="原文"]').element,input=wrapper!.get('input[type=file]'),file=new File(['original'],'需求.md');Object.defineProperty(input.element,'files',{configurable:true,value:[file]});await input.trigger('change');await vi.waitFor(()=>expect(uploadOwner().getSnapshot().hashing).toBe(false));await clickButton('上传并选用');await flushPromises();const original=vi.mocked(workflowDocuments.upload).mock.calls[0];expect(original?.[2][0]).toBe(file);expect(wrapper!.get('aside:not([hidden]) button[data-semantic="ui.close"]').attributes('disabled')).toBeDefined();expect(wrapper!.get('button[data-semantic="receipt.retryOriginal"]').attributes('disabled')).toBeUndefined();await wrapper!.get('.workflow-canvas').trigger('keydown',{key:'Escape'});await wrapper!.get('.workflow-canvas').trigger('click');await router.navigate('/requirements');expect(router.router.state.location.pathname).toBe('/requirements/req');expect(wrapper!.get('section[aria-label="原文"]').element).toBe(document);expect(owner().executable()).toBe(false);expect(api.start).not.toHaveBeenCalled();expect(wrapper!.text()).toContain('连接中断');await wrapper!.get('button[data-semantic="receipt.retryOriginal"]').trigger('click');await flushPromises();expect(vi.mocked(workflowDocuments.upload).mock.calls[1]).toEqual(original);expect(wrapper!.find('button[data-semantic="ui.discardChanges"]').exists()).toBe(false);expect(uploadOwner().originalFiles()[0]).toBe(file);expect(owner().canLeave().kind).toBe('BLOCK');vi.mocked(workflowDocuments.upload).mockResolvedValue({id:'upload',ready:true,createdAt:'',parserVersion:'v1',resume:null,reference:{version:1,type:'UPLOADED_DOCUMENTS',uploadId:'upload',sha256:'fixed'},originals:[{filename:'需求.md',path:'original/1',sizeBytes:8,sha256:'0682c5f2076f099c34cfdd15a9e063849ed437a49677e6fcc5b4198c76575be5',representationSha256:'fixed',format:'md',sections:1,limitations:[]}]});await wrapper!.get('button[data-semantic="receipt.retryOriginal"]').trigger('click');await flushPromises();expect(vi.mocked(workflowDocuments.upload).mock.calls[2]).toEqual(original);await clickButton('关闭需求与资料');expect(wrapper!.find('aside:not([hidden])').exists()).toBe(false);expect(button('连续执行')!.attributes('disabled')).toBeUndefined()
  })

  it.each(['HUMAN', 'SYSTEM'] as const)('does not wait for a default model to execute a SINGLE %s target', async kind => {
    vi.mocked(clientApi.getSettings).mockReturnValue(new Promise(() => undefined))
    const value = requirement({ state: 'PENDING_START' }); value.graph.nodes[0]!.kind = kind; value.graph.nodes.push(newNode('free.readonly'))
    api.get.mockResolvedValue(value); api.execution.mockResolvedValue(execution('PENDING_START')); await render()
    expect(button('连续执行')!.attributes('disabled')).toBeDefined(); await wrapper!.get('.workflow-node').trigger('click')
    expect(button('执行所选节点')!.attributes('disabled')).toBeUndefined(); await clickButton('执行所选节点'); await flushPromises()
    expect(api.start.mock.calls[0]![1]).toMatchObject({ mode: 'SINGLE', targetKey: 'review', model: null })
  })

})
