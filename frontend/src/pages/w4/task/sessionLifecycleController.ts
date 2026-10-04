import { api } from '@/api/client'
import type { SessionTodo, SessionCheckpoint, Task } from '@/types/domain'
import type { OperationOwner } from '@/foundation/contracts/receipt'
import { createW4Owner, dirtyDecision, idleCommand, pendingCommand, recoverOperation, type CommandState } from '../shared/core'
import { userFacingError } from '@/utils/displayLabels'
export type SessionLifecycleAction='refresh'|'checkpoint'|'fork'|'revert'|'summarize'
interface State {todos:SessionTodo[];checkpoints:SessionCheckpoint[];loading:boolean;error:string;notice:string;messageId:string;partId:string;dirty:boolean;draftRevision:number;command:CommandState;confirming?:{action:SessionLifecycleAction;revision:number;taskVersion:number|undefined;taskState:string;direct:boolean}}
type Body={externalMessageId?:string;messageId?:string;partId?:string;automatic?:boolean}
export function createSessionLifecycleController(task:Task,sessionId:string){
 let currentTask=task,operation:OperationOwner<Body,unknown>|undefined
 const env=createW4Owner<State>('task-session-lifecycle',`${task.id}:${sessionId}`,{todos:[],checkpoints:[],loading:false,error:'',notice:'',messageId:'',partId:'',dirty:false,draftRevision:0,command:idleCommand},s=>dirtyDecision(s.dirty,s.draftRevision)),{base,patch,ticket}=env
 const locked=()=>pendingCommand(base.getSnapshot().command)
 async function snapshot(){const [todos,checkpoints]=await Promise.all([api.getTaskSessionTodos(task.id,sessionId),api.getTaskSessionCheckpoints(task.id,sessionId)]);if(checkpoints.some(c=>c.taskId!==task.id||c.sessionId!==sessionId))throw new Error('快照不属于原会话，请重新读取。');return{todos,checkpoints}}
 async function load(){const read=ticket('snapshot');patch({loading:true});try{const value=await snapshot();if(read.current())patch({...value,error:''})}catch(cause){if(read.current())patch({error:userFacingError(cause,'无法读取持久化会话快照，请重试。')})}finally{if(read.current())patch({loading:false})}}
 const allowed=(action:SessionLifecycleAction)=>!locked()&&env.canStartWrite()&&(action!=='fork'&&action!=='revert'||currentTask.status==='PAUSED'&&!!base.getSnapshot().messageId.trim()&&(action!=='revert'||!!base.getSnapshot().partId.trim()&&currentTask.branch!=='DIRECT'))
 async function execute(action:SessionLifecycleAction){if(!allowed(action))return;const s=base.getSnapshot(),revision=s.draftRevision
  const body:Body=action==='checkpoint'?{externalMessageId:s.messageId.trim()||undefined}:action==='fork'?{messageId:s.messageId.trim()}:action==='revert'?{messageId:s.messageId.trim(),partId:s.partId.trim()}:action==='summarize'?{automatic:false}:{}
  const suffix={refresh:'todos/refresh',checkpoint:'checkpoints',fork:'fork',revert:'revert',summarize:'summarize'}[action]
  operation=env.command<Body,unknown>({label:action==='refresh'?'同步真实 todo':'会话操作',input:{endpoint:`/tasks/${encodeURIComponent(task.id)}/sessions/${encodeURIComponent(sessionId)}/${suffix}`,method:'POST',body},capability:{kind:'READ_ORIGINAL',readOriginal:async()=>{const read=ticket('snapshot'),value=await snapshot();if(read.current())patch({...value,error:''});return{kind:'UNCONFIRMED'}}},write:identity=>{
   const b=identity.body
   if(action==='refresh')return api.refreshTaskSessionTodos(task.id,sessionId)
   if(action==='checkpoint')return api.createTaskSessionCheckpoint(task.id,sessionId,b.externalMessageId)
   if(action==='fork')return api.forkTaskSession(task.id,sessionId,b.messageId!)
   if(action==='revert')return api.revertTaskSession(task.id,sessionId,b.messageId!,b.partId!)
   return api.summarizeTaskSession(task.id,sessionId,b.automatic)
  },read:async(_receipt,context)=>{const value=await snapshot();context.apply(()=>patch({...value,error:'',notice:action==='refresh'?'已从 OpenCode 同步并持久化 todo':'会话操作已完成',...(base.getSnapshot().draftRevision===revision?{dirty:false}:{}),confirming:undefined}))},changed:command=>patch({command})})
  try{await operation.execute()}catch{}
 }
 env.setStart(()=>void load())
 return Object.assign(base,{load,locked,allowed,setWriteGate:env.setWriteGate,updateTask(value:Task){if(value.id===task.id)currentTask=value},change(field:'messageId'|'partId',value:string){if(locked()||!base.capture().isCurrent())return;const s=base.getSnapshot();patch({[field]:value,dirty:true,draftRevision:s.draftRevision+1})},request(action:SessionLifecycleAction){if(!allowed(action))return;if(action==='fork'||action==='revert'){const s=base.getSnapshot();patch({confirming:{action,revision:s.draftRevision,taskVersion:currentTask.version,taskState:currentTask.status,direct:currentTask.branch==='DIRECT'}})}else void execute(action)},confirmationCurrent(){const c=base.getSnapshot().confirming;return !!c&&allowed(c.action)&&c.revision===base.getSnapshot().draftRevision&&c.taskVersion===currentTask.version&&c.taskState===currentTask.status&&c.direct===(currentTask.branch==='DIRECT')},confirm(){const c=base.getSnapshot().confirming;if(c&&this.confirmationCurrent())void execute(c.action)},cancelConfirmation(){patch({confirming:undefined})},async recover(){try{await recoverOperation(operation)}catch{}}})
}
export type SessionLifecycleController=ReturnType<typeof createSessionLifecycleController>
