import {afterEach,vi} from 'vitest'
import {api} from '@/api/client'
import {TaskDetailPage} from '@/pages/w4/task/TaskDetailPage'
import {application,flushPromises} from './application'
import {taskFixture} from './task'
import {decision,dirty} from '@/pages/w4/actions/test-support'
import {publication} from '@/pages/w4/publication/fixtures'
import type {Task} from '@/types/domain'
export {taskFixture,flushPromises}
export function detailFixture(initial:Task){
 const tasks=new Map([[initial.id,initial]])
 vi.stubGlobal('EventSource',class {onopen=null;onmessage=null;onerror=null;close=vi.fn();constructor(readonly url:string){}})
 const overview=vi.spyOn(api,'getTaskOverview').mockImplementation(async id=>{const value=tasks.get(id);if(!value)throw new Error('没有找到原任务');return structuredClone(value)})
 const audit=vi.spyOn(api,'getTaskAudit').mockImplementation(async id=>{const t=tasks.get(id)!;return {attempts:t.attempts??[],artifacts:t.artifacts??[],errors:t.errors??[],judges:t.judges??[]}})
 vi.spyOn(api,'getTaskSessions').mockResolvedValue([])
 vi.spyOn(api,'getJudgeApproval').mockResolvedValue({available:false,approved:false,taskVersion:initial.version??3,cycleId:'cycle',cycleVersion:1,reviewBatchId:'batch'})
 vi.spyOn(api,'getGitDiffScopeApproval').mockResolvedValue(undefined)
 vi.spyOn(api,'getTaskDecision').mockImplementation(async id=>({...decision(id),taskVersion:tasks.get(id)?.version??3}))
 vi.spyOn(api,'getDirtyWorkspace').mockResolvedValue(dirty)
 vi.spyOn(api,'getTaskPublication').mockResolvedValue(publication({state:'PUSHED',deliveryState:'PUSHED'}))
 vi.spyOn(api,'templateFailedBatches').mockResolvedValue({items:[],nextCursor:undefined,facets:{}})
 vi.spyOn(api,'getTemplateSessionDiagnostics').mockResolvedValue({items:[],nextCursor:null,hasMore:false})
 const queue=vi.spyOn(api,'getTaskQueue').mockImplementation(async id=>({taskId:id,state:'QUEUED',queuePosition:1,leaseState:'RELEASE_PENDING',holderTaskId:'holder-1',holderTaskTitle:'已取消的旧任务',holderTaskState:'CANCELLED',holderArchived:true,releaseReason:'SOURCE_BRANCH_WORKSPACE_DIRTY',reconcileAvailable:true}))
 const cancel=vi.spyOn(api,'cancelTask').mockImplementation(async id=>{const next=taskFixture(id,{...tasks.get(id),status:'CANCELLED',version:(tasks.get(id)?.version??3)+1});tasks.set(id,next);return next})
 const start=vi.spyOn(api,'startTask').mockImplementation(async id=>{const next=taskFixture(id,{...tasks.get(id),status:'READY',version:(tasks.get(id)?.version??3)+1});tasks.set(id,next);return next})
 const judges=vi.spyOn(api,'retryTaskJudges').mockImplementation(async id=>{const next=taskFixture(id,{...tasks.get(id),status:'JUDGING',version:(tasks.get(id)?.version??3)+1});tasks.set(id,next);return next})
 const loop=vi.spyOn(api,'retryWaitingTaskLoop').mockImplementation(async id=>{const next=taskFixture(id,{...tasks.get(id),status:'RUNNING',version:(tasks.get(id)?.version??3)+1});tasks.set(id,next);return next})
 const rework=vi.spyOn(api,'createTaskRecovery').mockImplementation(async (id,mode)=>{tasks.set('task-rework',taskFixture('task-rework'));return {taskId:'task-rework',parentTaskId:id,mode,workspaceFingerprint:'fingerprint',writableSession:false}})
 return {tasks,overview,audit,queue,cancel,start,judges,loop,rework,async mount(){return application({initialEntries:[`/tasks/${initial.id}`],routes:[{path:'/tasks/:id',Component:TaskDetailPage}]})}}
}
afterEach(()=>vi.useRealTimers())
