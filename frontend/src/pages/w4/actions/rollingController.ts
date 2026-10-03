import { api } from '@/api/client'
import type { RollingPackageWorkbench,RollingPackageDetail,RollingPlanPackage,RollingPlanProposal } from '@/types/domain'
import { createPanelOwner,panelState } from './owner'
import type { UiActionKey } from '@/foundation/semanticRegistry'
import type { ReadContext } from '@/foundation/contracts/receipt'

export type PackageAction='approve'|'start'|'redesign'|'resume'|'checkpoint'|'discuss'
const capabilityKeys={approve:'canApproveDesign',start:'canStartPackage',redesign:'canRedesignPackage',resume:'canResumeDesign',checkpoint:'canRetryPackage',discuss:'canDiscuss'} as const
type PlanVersions={expectedTaskVersion:number;expectedPackageVersion:number;expectedDiscussionRevision:number;expectedDesignRevision:number;expectedPackageRunId:string}
export function pretty(value?:string){if(!value)return '尚未形成';try{return JSON.stringify(JSON.parse(value),null,2)}catch{return value}}
export function createRollingController(taskId:string){
  const owner=createPanelOwner('task-rolling-packages',taskId,{...panelState(),workbench:undefined as RollingPackageWorkbench|undefined,detail:undefined as RollingPackageDetail|undefined,selectedId:'',feedback:'',replanOpen:false,planDraft:[] as RollingPlanPackage[],correctionTitle:'',correctionObjective:'',correctionOpen:false,proposal:undefined as RollingPlanProposal|undefined,proposalVersions:undefined as PlanVersions|undefined,proposalDraftRevision:0})
  const selected=()=>owner.getSnapshot().workbench?.packages.find(run=>run.id===owner.getSnapshot().selectedId)
  const current=()=>owner.getSnapshot().workbench?.currentPackageRunId===owner.getSnapshot().selectedId
  function versions(){const wb=owner.getSnapshot().workbench,run=selected();if(!wb||!run)return;return {expectedTaskVersion:wb.taskVersion,expectedPackageVersion:run.version,expectedDiscussionRevision:run.discussionRevision,expectedDesignRevision:run.designRevision}}
  function anchorVersions(){const v=versions(),run=selected();return v&&run?{...v,expectedPackageRunId:run.id}:undefined}
  async function fetchSelected(preferred?:string,isCurrent=()=>owner.active()&&owner.base.capture().isCurrent()){
    const wb=await api.getRollingPackageWorkbench(taskId);if(!isCurrent())return;if(wb.taskId!==taskId)throw new Error('工作台不属于原任务。')
    const requested=preferred||owner.getSnapshot().selectedId||wb.currentPackageRunId||wb.packages[0]?.id||'',id=wb.packages.some(run=>run.id===requested)?requested:wb.packages[0]?.id||''
    const detail=id?await api.getRollingPackageDetail(taskId,id):undefined;if(!isCurrent())return;if(detail&&detail.packageRun.id!==id)throw new Error('工作包详情与原选择不一致。')
    return {workbench:wb,selectedId:id,detail}
  }
  async function load(preferred?:string){if(!owner.active()||!owner.readsAllowed())return;const ticket=owner.ticket('workbench');owner.patch({loading:true});try{const state=await fetchSelected(preferred,ticket.current);if(state&&ticket.current())owner.patch(state)}catch(cause){if(ticket.current())owner.error(cause)}finally{if(ticket.current())owner.patch({loading:false})}}
  async function reconcileConflict(preferred?:string){await load(preferred);await owner.refreshParent();if(owner.base.capture().isCurrent())owner.error(new Error('工作包状态已刷新；原操作结果仍需核对，请保留原版本与正文。'))}
  async function refreshAfter(context:ReadContext,preferred?:string){const state=await fetchSelected(preferred,()=>context.isCurrent()&&owner.active());if(!context.isCurrent())return;if(!state)throw new Error('原工作包读取视图已离开，请恢复原读取。');await owner.refreshParent();context.apply(()=>owner.patch(state))}
  async function act(action:PackageAction){
    const state=owner.getSnapshot(),run=selected(),body=versions();if(!current()||!run||!body||!state.workbench?.packageCapabilities?.[capabilityKeys[action]]||action==='checkpoint'&&run.waitingReasonCode!=='PACKAGE_CHECKPOINT_BLOCKED')return
    if(action==='discuss'&&!state.feedback.trim())return
    const input=action==='discuss'?{...body,content:state.feedback.trim()}:body
    const endpoint={approve:'approve-design',start:'start',redesign:'redesign',resume:'resume-design',checkpoint:'retry-checkpoint',discuss:'messages'}[action]
    await owner.execute({onConflict:()=>reconcileConflict(run.id),label:'工作包操作',input:{endpoint:`/tasks/${taskId}/packages/${run.id}/${endpoint}`,method:'POST',body:input},lookup:async()=>{await api.getRollingPackageWorkbench(taskId);return {kind:'UNCONFIRMED'}},
      write:captured=>action==='approve'?api.approveRollingPackageDesign(taskId,run.id,captured):action==='start'?api.startRollingPackage(taskId,run.id,captured):action==='redesign'?api.redesignRollingPackage(taskId,run.id,captured):action==='resume'?api.resumeRollingPackageDesign(taskId,run.id,captured):action==='checkpoint'?api.retryRollingPackageCheckpoint(taskId,run.id,captured):api.discussRollingPackage(taskId,run.id,{...captured,content:'content' in captured?String(captured.content):''}),
      read:async(_receipt,context)=>{await refreshAfter(context,run.id);if(action==='discuss')context.apply(()=>owner.patch({feedback:'',dirty:false}))},clearDraft:action==='discuss'})
  }
  function failure(action:'CONTINUE_CANDIDATE'|'REDESIGN_FROM_PREVIOUS'|'ABANDON_TASK'){
    const run=selected(),v=versions();if(!run||!v||!current()||run.waitingReasonCode!=='PACKAGE_EXECUTION_FAILED')return
    const key:UiActionKey=action==='CONTINUE_CANDIDATE'?'rolling.failureContinue':action==='REDESIGN_FROM_PREVIOUS'?'rolling.failureRedesign':'task.cancel'
    owner.confirm('确认失败包处置？',action==='CONTINUE_CANDIDATE'?'从失败候选 Checkpoint 继续实现，失败变更和历史 Attempt 会保留。':action==='REDESIGN_FROM_PREVIOUS'?'回到上一成功事实点重新设计当前包。':'取消整个任务并保留现有文件与审计证据。',key,{...v,action},async body=>{await owner.execute({onConflict:()=>reconcileConflict(run.id),label:'失败包处置',input:{endpoint:`/tasks/${taskId}/packages/${run.id}/continue-failure`,method:'POST',body},write:input=>api.resolveRollingPackageFailure(taskId,run.id,input),lookup:async()=>{await api.getRollingPackageWorkbench(taskId);return {kind:'UNCONFIRMED'}},read:async(_receipt,context)=>refreshAfter(context,run.id)})},()=>{const latest=versions();return !!latest&&latest.expectedTaskVersion===v.expectedTaskVersion&&latest.expectedPackageVersion===v.expectedPackageVersion&&latest.expectedDiscussionRevision===v.expectedDiscussionRevision&&latest.expectedDesignRevision===v.expectedDesignRevision})
  }
  function openReplan(){if(owner.locked()||!current()||!owner.getSnapshot().workbench?.packageCapabilities?.canReplanRemaining)return;const packages=owner.getSnapshot().workbench!.packages.filter(run=>!['FACT_FROZEN','SUPERSEDED','CANCELLED'].includes(run.state));owner.patch({replanOpen:true});owner.edit({planDraft:packages.map(run=>({packageKey:run.packageKey,title:run.title,objective:run.title,sourcePackageRunId:run.id,sourcePackageRunIds:[run.id],dependencies:[...run.dependencies],requirementRefs:[]}))})}
  function editPlan(index:number,changes:Partial<RollingPlanPackage>){owner.edit({planDraft:owner.getSnapshot().planDraft.map((item,i)=>i===index?{...item,...changes}:item)})}
  function transformPlan(action:'up'|'down'|'add'|'split'|'merge'|'delete',index=0){const plan:RollingPlanPackage[]=owner.getSnapshot().planDraft.map(item=>({...item,dependencies:[...item.dependencies],requirementRefs:[...item.requirementRefs],sourcePackageRunIds:item.sourcePackageRunIds?[...item.sourcePackageRunIds]:undefined}));const item=plan[index]
    if(action==='add')plan.push({packageKey:`NEW-${plan.length+1}`,title:'新增工作包',objective:'描述新增工作包目标',dependencies:[],requirementRefs:[]})
    if(item){if(action==='delete')plan.splice(index,1);if(action==='split'&&plan.length<6)plan.splice(index+1,0,{...item,packageKey:`${item.packageKey}-B`,title:`${item.title}（拆分）`});if(action==='merge'&&index>0){const target=plan[index-1]!;target.title+=` + ${item.title}`;target.objective+=`\n${item.objective}`;target.sourcePackageRunIds=[...new Set([...(target.sourcePackageRunIds||[target.sourcePackageRunId].filter((v):v is string=>!!v)),...(item.sourcePackageRunIds||[item.sourcePackageRunId].filter((v):v is string=>!!v))])];target.dependencies=[...new Set([...target.dependencies,...item.dependencies])];target.requirementRefs=[...new Set([...target.requirementRefs,...item.requirementRefs])];plan.splice(index,1)}if(action==='up'||action==='down'){const next=index+(action==='up'?-1:1);if(next>=0&&next<plan.length){plan.splice(index,1);plan.splice(next,0,item)}}}
    owner.edit({planDraft:plan})
  }
  async function readProposal(receipt:Readonly<RollingPlanProposal>,context:ReadContext,anchor:NonNullable<ReturnType<typeof anchorVersions>>,revision:number){
    if(!receipt.id)throw new Error('计划回执缺少原身份。')
    let proposal=receipt
    for(let attempt=0;attempt<900;attempt++){
      const revisions=await api.getRollingPlanRevisions(taskId);if(!context.isCurrent()||!owner.active())return
      proposal=revisions.find(value=>value.id===receipt.id)||proposal
      if(proposal.state!=='GENERATING')break
      await new Promise<void>((resolve,reject)=>{let settled=false,release=()=>{};const timer=setTimeout(()=>{settled=true;release();resolve()},1000);release=owner.own(()=>{clearTimeout(timer);if(!settled){settled=true;reject(new Error('原计划读取视图已离开，请恢复原读取。'))}})})
    }
    if(proposal.state!=='PROPOSED')throw new Error(proposal.state==='FAILED'?proposal.lastErrorDetail||'AI 建议生成失败，请保留原计划。':'AI 建议尚未生成完成，请稍后读取原建议。')
    context.apply(()=>owner.patch({proposal:{...proposal},proposalVersions:anchor,proposalDraftRevision:revision}))
  }
  async function propose(kind:'manual'|'ai'|'correction'){
    const state=owner.getSnapshot(),anchor=anchorVersions(),run=selected();if(!anchor||!run||!state.workbench)return
    if(kind==='correction'?(run.state!=='FACT_FROZEN'||!state.workbench.packageCapabilities.canAddCorrectionPackage):(!current()||!state.workbench.packageCapabilities.canReplanRemaining))return
    if(kind==='manual'&&!state.planDraft.length||kind==='correction'&&(!state.correctionTitle.trim()||!state.correctionObjective.trim()))return
    const input=kind==='manual'?{...anchor,packages:state.planDraft}:kind==='correction'?{expectedTaskVersion:anchor.expectedTaskVersion,expectedPackageVersion:anchor.expectedPackageVersion,expectedDiscussionRevision:anchor.expectedDiscussionRevision,expectedDesignRevision:anchor.expectedDesignRevision,correctionOfPackageRunId:run.id,title:state.correctionTitle.trim(),objective:state.correctionObjective.trim()}:anchor
    const revision=state.draftRevision
    await owner.execute({onConflict:()=>reconcileConflict(run.id),label:'计划影响预览',input:{endpoint:`/tasks/${taskId}/packages/${kind==='correction'?'corrections':kind==='ai'?'plan-revisions/suggest':'plan-revisions'}`,method:'POST',body:input},lookup:async()=>{await api.getRollingPlanRevisions(taskId);return {kind:'UNCONFIRMED'}},
      write:body=>kind==='manual'?api.proposeRollingPlan(taskId,{...anchor,packages:'packages' in body?body.packages:[]}):kind==='ai'?api.suggestRollingPlan(taskId,anchor):api.addRollingCorrection(taskId,{...anchor,correctionOfPackageRunId:run.id,title:'title' in body?String(body.title):'',objective:'objective' in body?String(body.objective):''}),
      read:(receipt,context)=>readProposal(receipt,context,anchor,revision)})
  }
  function confirmPlan(){const state=owner.getSnapshot(),proposal=state.proposal,v=state.proposalVersions;if(!proposal||!v||proposal.state!=='PROPOSED'||state.draftRevision!==state.proposalDraftRevision)return
    const body={...v,expectedProposalVersion:proposal.version}
    owner.confirm(`确认计划 R${proposal.revision}？`,`服务端影响预览：\n${pretty(proposal.impactJson)}`,'rolling.confirmPlan',body,async captured=>{await owner.execute({onConflict:()=>reconcileConflict(),label:'确认计划',input:{endpoint:`/tasks/${taskId}/packages/plan-revisions/${proposal.id}/confirm`,method:'POST',body:captured},write:input=>api.confirmRollingPlan(taskId,proposal.id,input),lookup:async()=>{const result=(await api.getRollingPlanRevisions(taskId)).find(value=>value.id===proposal.id);return result?.state==='ACTIVE'?{kind:'ACCEPTED',receipt:result}:{kind:'UNCONFIRMED'}},read:async(result,context)=>{if(result.id!==proposal.id||result.state!=='ACTIVE')throw new Error('计划确认回执与原预览不一致。');await refreshAfter(context);context.apply(()=>owner.patch({proposal:undefined,replanOpen:false,correctionOpen:false,dirty:false}))},clearDraft:true})},()=>owner.getSnapshot().proposal?.id===proposal.id&&owner.getSnapshot().draftRevision===state.proposalDraftRevision)
  }
  owner.setStart(()=>void load());return Object.assign(owner,{load,selected,current,act,failure,openReplan,editPlan,transformPlan,propose,confirmPlan})
}
