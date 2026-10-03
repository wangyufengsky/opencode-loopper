import { ApiError } from '@/api/client'
import { captureDto } from '@/foundation/contracts/immutable'
import type { OperationOwner,OperationInput,OriginalLookup,ReadContext } from '@/foundation/contracts/receipt'
import type { UiActionKey } from '@/foundation/semanticRegistry'
import { createW4Owner,idleCommand,pendingCommand,recoverOperation,dirtyDecision,type CommandState } from '../shared/core'
import type { TaskParentPort } from '../shared/types'
import { userFacingError } from '@/utils/displayLabels'

export interface PanelState { error:string;loading:boolean;dirty:boolean;draftRevision:number;command:CommandState;confirmation?:{title:string;description:string;actionKey:UiActionKey;revision:number} }
export const panelState=():PanelState=>({error:'',loading:false,dirty:false,draftRevision:0,command:idleCommand})
/** Local child writer, registered in the Task's single guard graph. */
export function createPanelOwner<S extends PanelState>(domain:string,id:string,initial:S){
  const core=createW4Owner(domain,id,initial,state=>dirtyDecision(state.dirty,state.draftRevision))
  let parent:TaskParentPort|undefined,operation:OperationOwner<unknown,unknown>|undefined,confirmationRun:(()=>Promise<void>)|undefined,validConfirmation=()=>true,enabled=true
  const locked=()=>pendingCommand(core.base.getSnapshot().command)
  const error=(cause:unknown)=>core.patch({error:userFacingError(cause,'操作未完成，请核对原结果。')} as Partial<S>)
  function edit(changes:Partial<S>){if(!core.base.capture().isCurrent()||locked()||!enabled)return false;return core.patch({...changes,dirty:true,draftRevision:core.base.getSnapshot().draftRevision+1} as Partial<S>)}
  async function execute<B,R>(options:{label:string;input:OperationInput<B>;write:(body:Readonly<B>)=>Promise<R>;read:(receipt:Readonly<NoInfer<R>>,context:ReadContext)=>Promise<void>;lookup?:(body:Readonly<B>)=>Promise<OriginalLookup<NoInfer<R>>>;clearDraft?:boolean;onConflict?:()=>Promise<void>;handoffTarget?:(receipt:Readonly<NoInfer<R>>)=>string}){
    if(locked()||!core.active()||!core.canStartWrite()||!enabled)return false
    const next=core.command<B,R>({label:options.label,input:options.input,capability:options.lookup?{kind:'READ_ORIGINAL',readOriginal:identity=>options.lookup!(identity.body)}:{kind:'NONE'},write:identity=>options.write(identity.body),read:async(receipt,context)=>{await options.read(receipt,context);if(options.clearDraft)context.apply(()=>core.patch({dirty:false} as Partial<S>))},changed:command=>core.patch({command} as Partial<S>),handoffTarget:options.handoffTarget})
    operation=next as unknown as OperationOwner<unknown,unknown>
    try{await next.execute();return true}catch(cause){if(cause instanceof ApiError&&cause.status===409&&core.base.capture().isCurrent()&&options.onConflict){try{await options.onConflict()}catch(failure){error(failure)}}return false}
  }
  function confirm<B>(title:string,description:string,actionKey:UiActionKey,body:B,run:(body:Readonly<B>)=>Promise<void>,valid:()=>boolean=()=>true){
    if(!enabled||locked()||!core.active()||!core.canStartWrite()||core.base.getSnapshot().confirmation)return
    const captured=captureDto(body),revision=core.base.getSnapshot().draftRevision,token=core.base.capture()
    validConfirmation=()=>token.isCurrent()&&revision===core.base.getSnapshot().draftRevision&&valid()&&enabled&&!locked()&&core.canStartWrite()
    confirmationRun=()=>run(captured)
    core.patch({confirmation:{title,description,actionKey,revision}} as Partial<S>)
  }
  async function acceptConfirmation(){if(!validConfirmation()||!core.base.getSnapshot().confirmation)return;const run=confirmationRun;confirmationRun=undefined;core.patch({confirmation:undefined} as Partial<S>);try{await run?.()}catch(cause){error(cause)}}
  async function recover(){try{await recoverOperation(operation)}catch(cause){error(cause)}}
  const result={...core,...core.base,locked,readsAllowed:()=>enabled,actionBlocked:()=>!enabled||locked(),setEnabled:(value:boolean)=>{enabled=value},edit,execute,confirm,acceptConfirmation,recover,error,
    confirmationValid:()=>!!core.base.getSnapshot().confirmation&&validConfirmation(),
    cancelConfirmation:()=>{confirmationRun=undefined;core.patch({confirmation:undefined} as Partial<S>)},
    bindParent(port:TaskParentPort){parent=port;core.setWriteGate(()=>enabled&&!locked()&&parent!.canStartWrite(result))},
    refreshParent:async()=>{if(!core.base.capture().isCurrent())return;await parent?.refresh()},
    operationIdentity:()=>operation?.identity,prepareHandoff:()=>operation?.prepareHandoff(),
  };return result
}
