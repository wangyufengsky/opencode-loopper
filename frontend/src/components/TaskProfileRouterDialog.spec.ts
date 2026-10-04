import {designerPage} from '@/pages/w6-tests/knowledge-ppt-template/designer-page'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {api} from '@/api/client'
import {mockDesigner} from '@/pages/w5/designer/test-support'
import type {DesignerSession} from '@/types/domain'
vi.mock('@/api/client',async original=>({...await original<typeof import('@/api/client')>(),subscribeDesignerEvents:vi.fn()}))
const baseSession: DesignerSession = {
  id: 'designer-router', projectId: 'project-1', projectName: 'Loopper', state: 'PENDING_HANDOFF',
  workflowPhase: 'ROUTING', activeActor: 'ROUTER', accessMode: 'READ_ONLY', readOnly: true,
  discussionScope: 'REQUIREMENT', discussionRevision: 0, finalConfirmationEligible: false,
  autoMode: { enabled: false, state: 'DISABLED', version: 0 },
  questionInteraction: { mode: 'NONE', awaitingAnswer: false },
  taskProfile: {
    id: 'profile-1', state: 'ROUTING', decisionState: 'ROUTING', confirmationReady: false,
    intent: 'SOFTWARE_CHANGE', workflowTemplate: 'DIRECT_SOFTWARE_DESIGN', mutationMode: 'WRITE_CODE',
    artifactKinds: ['SOURCE_CODE'], technologies: ['java'], testPolicy: 'REQUIRED',
    executionStrategy: 'OPEN_CODE_IMPLEMENTATION', rolePackId: 'software-java', rolePackVersion: 'test',
    confidence: 0, evidence: [], resolutionSource: 'ROUTER', decisionRequired: true,
    largeTaskMode: false, version: 2,
  },
  availableProfileOverrides: ['SOFTWARE_CHANGE', 'DOCUMENT_AUTHORING'],
  availableArtifactOverrides: ['SOURCE_CODE', 'MARKDOWN'], reports: [], messages: [],
}

beforeEach(()=>{vi.restoreAllMocks();vi.useFakeTimers();vi.setSystemTime(new Date('2026-08-25T07:00:30Z'));mockDesigner(baseSession)})
afterEach(()=>{vi.useRealTimers();vi.restoreAllMocks()})
describe('TaskProfileRouterDialog',()=>{
 it('locks the running dialog and displays real activity, elapsed time, and provider tokens without a timeout limit',async()=>{
 const session:DesignerSession={...baseSession,routerRun:{id:'run-active',state:'RUNNING',externalState:'RUNNING',createdAt:'2026-08-25T07:00:00Z',updatedAt:'2026-08-25T07:00:20Z',retryAvailable:false}};vi.mocked(api.getDesignerSession).mockResolvedValue(session);vi.spyOn(api,'getDesignerActivity').mockResolvedValue({actor:'ROUTER',connected:true,remoteState:'RUNNING',observedAt:'2026-08-25T07:00:30Z',detail:'正在识别',parts:[{id:'part-1',type:'THINKING',label:'最新思考',content:'正在分析 Maven 多模块结构'}],usage:{totalTokens:15,unknownUsageCount:0,observedAt:'2026-08-25T07:00:30Z'}});const c=await designerPage(session);const dialog=c.body().get('[role="dialog"]');expect(dialog.get('button[data-semantic="designer.confirmProfile"]').element.matches(':disabled')).toBe(true);await dialog.trigger('keydown',{key:'Escape'});expect(c.owner.getSnapshot().routerOpen).toBe(true);expect(dialog.text()).toContain('已用 30 秒');expect(dialog.text()).not.toContain('上限');expect(dialog.text()).not.toContain('超时');expect(dialog.text()).toContain('正在分析 Maven 多模块结构');expect(dialog.text()).toContain('15');const cancel=vi.spyOn(api,'cancelDesignerTaskProfileRouting').mockResolvedValue(session.taskProfile);await c.button('designer.cancelRouter');expect(cancel).toHaveBeenCalledWith(session.id,'run-active');expect(api.getDesignerActivity).toHaveBeenCalledWith(session.id)
 })
 it('opens manual settings after the server confirms Router cancellation',async()=>{
 const session:DesignerSession={...baseSession,taskProfile:{...baseSession.taskProfile,id:'profile-manual',state:'PROVISIONAL',decisionState:'NEEDS_CONFIRMATION',resolutionSource:'USER_SELECTION_PENDING',evidence:['router-error=ROUTER_USER_CANCELLED:用户已取消 AI 任务设置识别']},routerRun:{id:'run-cancelled',state:'SUPERSEDED',externalState:'ABORTED',errorCode:'ROUTER_USER_CANCELLED',errorDetail:'用户已取消 AI 任务设置识别，请手动选择任务设置',createdAt:'2026-08-25T07:00:00Z',updatedAt:'2026-08-25T07:00:20Z',retryAvailable:false}};vi.mocked(api.getDesignerSession).mockResolvedValue(session);const c=await designerPage(session);expect(c.body().text()).toContain('已取消 AI 识别，请手动选择任务设置');expect(c.body().text()).not.toContain('本次识别未能可靠完成');await c.body().get('[role="dialog"] button[data-semantic="designer.editSettings"]').trigger('click');expect(c.body().get('select[aria-label="任务类型"]').exists()).toBe(true);expect(c.body().get('button[data-semantic="designer.save"]').exists()).toBe(true);expect(c.owner.getSnapshot().command.phase).toBe('IDLE')
 })
 it('shows unavailable confidence separately from Java and exposes all decisions',async()=>{
 const session:DesignerSession={...baseSession,taskProfile:{...baseSession.taskProfile,state:'PROVISIONAL',decisionState:'NEEDS_CONFIRMATION',technologies:['java'],confidence:0,confidenceAvailable:false,confirmationReady:false},routerRun:{id:'run-completed',state:'COMPLETED',externalState:'COMPLETED',createdAt:'2026-08-25T07:00:00Z',updatedAt:'2026-08-25T07:00:20Z',retryAvailable:true}};vi.mocked(api.getDesignerSession).mockResolvedValue(session);const c=await designerPage(session);const fields=c.body().get('[aria-label="任务设置识别字段"]');expect(fields.findAll('article')[0]!.text()).toBe('识别置信度未产生');expect(fields.findAll('article')[1]!.text()).toBe('技术栈java');const reroute=vi.spyOn(api,'rerouteDesignerTaskProfile').mockResolvedValue(session.routerRun!),confirm=vi.spyOn(api,'confirmDesignerTaskProfile').mockResolvedValue(session.taskProfile);await c.button('designer.rerouteProfile');await c.confirm('designer.confirmProfile');expect(reroute).toHaveBeenCalledTimes(1);expect(confirm).toHaveBeenCalledTimes(1);await c.body().get('[role="dialog"] button[data-semantic="designer.editSettings"]').trigger('click');expect(c.body().findAll('select[aria-label="任务类型"],select[aria-label="交付物类型"]')).toHaveLength(2);vi.spyOn(api,'previewDesignerTaskProfileUpdate').mockResolvedValue({selectionChanged:true,updateRequired:true,sessionRestartRequired:false,targetWorkflowTemplate:'DIRECT_SOFTWARE_DESIGN'});const save=vi.spyOn(api,'updateDesignerTaskProfile').mockResolvedValue(session.taskProfile);await c.button('designer.save');expect(save).toHaveBeenCalledWith(session.id,'SOFTWARE_CHANGE','SOURCE_CODE',2,false,[])
 })
 it('shows a comprehensible warning for a failed Router run',async()=>{
 const session:DesignerSession={...baseSession,taskProfile:{...baseSession.taskProfile,state:'PROVISIONAL',decisionState:'NEEDS_CONFIRMATION',evidence:['router-error=ROUTER_TIMEOUT'],resolutionSource:'ROUTER_FALLBACK',confidence:0,confidenceAvailable:false},routerRun:{id:'run-failed',state:'FAILED',externalState:'FAILED',errorCode:'ROUTER_START_FAILED',errorDetail:'任务设置识别未能连接远端 Session',createdAt:'2026-08-25T07:00:00Z',updatedAt:'2026-08-25T07:04:01Z',retryAvailable:true}};vi.mocked(api.getDesignerSession).mockResolvedValue(session);const c=await designerPage(session);expect(c.body().text()).toContain('识别置信度未产生');expect(c.body().text()).toContain('本次识别未能可靠完成');expect(c.body().text()).toContain('任务设置识别未能连接远端 Session')
 })
})
