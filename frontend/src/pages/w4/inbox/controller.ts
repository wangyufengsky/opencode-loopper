import { api } from '@/api/client'
import type { Interaction,InteractionAction } from '@/types/domain'
import type { OperationOwner } from '@/foundation/contracts/receipt'
import { userFacingError } from '@/utils/displayLabels'
import { createW4Owner,idleCommand,pendingCommand,recoverOperation,dirtyDecision,type CommandState } from '../shared/core'

type ResolveBody={action:InteractionAction;version:number;answers?:string[][]}
export interface QuestionDraft { choices:string[][];custom:string[] }
export interface InboxState { interactions:Interaction[];loading:boolean;error:string;command:CommandState;drafts:Record<string,QuestionDraft>;draftRevision:number;retained:Record<string,Interaction>;originalId:string }
export function createInboxController(){
  const owner=createW4Owner<InboxState>('inbox','inbox',{interactions:[],loading:true,error:'',command:idleCommand,drafts:{},draftRevision:0,retained:{},originalId:''},state=>dirtyDecision(Object.keys(state.drafts).length>0,state.draftRevision))
  let operation:OperationOwner<ResolveBody,Interaction>|undefined,inFlight:Promise<void>|undefined,stop=()=>{},readCycle=0,inFlightCurrent=()=>false
  const schedule=()=>{stop();if(owner.active()&&!owner.base.getSnapshot().command.busy)stop=owner.delay(()=>void refresh(),1500)}
  function refresh():Promise<void>{
    if(!owner.active())return Promise.resolve();if(inFlight&&inFlightCurrent())return inFlight
    stop();const ticket=owner.ticket('list'),cycle=++readCycle;inFlightCurrent=ticket.current;owner.patch({loading:true})
    const work=(async()=>{try{const interactions=await api.getInteractions();if(ticket.current())owner.patch({interactions,error:''})}catch(cause){if(ticket.current())owner.patch({error:userFacingError(cause,'待处理项加载失败')})}finally{if(cycle===readCycle)inFlight=undefined;if(ticket.current()){owner.patch({loading:false});schedule()}}})()
    inFlight=work;return work
  }
  const matches=(row:Interaction,original:Interaction,body:Readonly<ResolveBody>)=>row.id===original.id&&row.externalRequestId===original.externalRequestId&&row.kind===original.kind&&row.taskId===original.taskId&&row.designerSessionId===original.designerSessionId&&row.sessionId===original.sessionId&&row.version>body.version&&row.resolvedAction===body.action&&['RESOLVED','REJECTED'].includes(row.state)
  async function resolve(id:string,action:InteractionAction,answers?:string[][]){
    const state=owner.base.getSnapshot(),item=state.interactions.find(row=>row.id===id)
    if(!owner.active()||pendingCommand(state.command)||!item||item.state!=='PENDING'||item.kind==='PERMISSION'&&item.payload.hardDenied||item.kind==='QUESTION'&&!['REPLY','REJECT'].includes(action)||item.kind==='PERMISSION'&&!['ONCE','SESSION','REJECT'].includes(action))return
    if(action==='REPLY'&&(!answers||item.kind!=='QUESTION'||answers.length!==item.payload.questions.length||!answers.every(value=>value.length)))return
    const body:ResolveBody={action,version:item.version,...(answers?{answers}:{})}
    stop();owner.patch({originalId:id,retained:{...state.retained,[id]:item}})
    operation=owner.command<ResolveBody,Interaction>({label:action==='REPLY'?'提交回答':'权限决定',input:{endpoint:`/interactions/${encodeURIComponent(id)}/resolve`,method:'POST',body,versions:{version:item.version}},capability:{kind:'READ_ORIGINAL',readOriginal:async identity=>{const rows=await api.getInteractions();const row=rows.find(row=>matches(row,item,identity.body));return row?{kind:'ACCEPTED',receipt:row}:{kind:'UNCONFIRMED'}}},
      write:identity=>api.resolveInteraction(id,identity.body),changed:command=>owner.patch({command}),
      read:async(receipt,context)=>{
        // A fulfilled POST is accepted even if its DTO cannot yet be verified.
        if(receipt.id!==item.id||receipt.kind!==item.kind||receipt.externalRequestId!==item.externalRequestId)throw new Error('回执不属于原待处理事项，请核对原结果。')
        if(inFlight&&inFlightCurrent())await inFlight
        if(!context.isCurrent())return
        const rows=await api.getInteractions()
        context.apply(()=>{const drafts={...owner.base.getSnapshot().drafts};delete drafts[id];const retained={...owner.base.getSnapshot().retained};delete retained[id];owner.patch({interactions:rows,drafts,retained,originalId:''})})
      },
    })
    try{await operation.execute()}catch{/* Keep the exact keyless operation; never resend automatically. */}finally{schedule()}
  }
  function edit(id:string,draft:QuestionDraft){if(!owner.base.capture().isCurrent()||pendingCommand(owner.base.getSnapshot().command))return;const state=owner.base.getSnapshot(),item=state.interactions.find(row=>row.id===id)||state.retained[id];if(!item)return;owner.patch({drafts:{...state.drafts,[id]:draft},retained:{...state.retained,[id]:item},draftRevision:state.draftRevision+1})}
  async function recover(){try{await recoverOperation(operation)}catch{}finally{schedule()}}
  owner.setStart(()=>void refresh())
  return {...owner.base,refresh,resolve,edit,recover,discardDraft:(id:string)=>{if(pendingCommand(owner.base.getSnapshot().command))return;const drafts={...owner.base.getSnapshot().drafts};delete drafts[id];const retained={...owner.base.getSnapshot().retained};delete retained[id];owner.patch({drafts,retained,draftRevision:owner.base.getSnapshot().draftRevision+1})},operationIdentity:()=>operation?.identity}
}
