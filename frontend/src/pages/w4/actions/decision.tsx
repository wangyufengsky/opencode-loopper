import { decisionPanelEligible } from './eligibility'
import { useLayoutEffect,useMemo } from 'react'
import { Input } from 'antd'
import { api } from '@/api/client'
import type { TaskDecision,TaskDecisionAction,RecoveryDraft } from '@/types/domain'
import { UiActionButton } from '@/foundation/components'
import type { UiActionKey } from '@/foundation/semanticRegistry'
import type { TaskPanelProps } from '../shared/types'
import { useW4Owner,useProtectedOwner } from '../shared/parts'
import { panelState,createPanelOwner } from './owner'
import { PanelNotice,ReadOriginal } from './parts'

export const decisionKeys:Partial<Record<TaskDecisionAction,UiActionKey>>={CONTINUE_CURRENT_TASK:'decision.continue',DERIVE_INHERIT_CHANGES:'decision.inherit',DERIVE_REWORK_ALL:'decision.rework',READ_ONLY_AUDIT:'decision.audit',ACCEPT_RESULT:'decision.accept',CANCEL:'task.cancel'}
export function createDecisionController(taskId:string){
  const owner=createPanelOwner('task-decision',taskId,{...panelState(),decision:undefined as TaskDecision|undefined,stageId:'',supplement:'',destination:''})
  async function load(){if(!owner.active()||!owner.readsAllowed())return;const ticket=owner.ticket('decision');owner.patch({loading:true});try{const decision=await api.getTaskDecision(taskId);if(decision.taskId!==taskId)throw new Error('处置状态不属于当前任务。');if(ticket.current())owner.patch({decision,stageId:decision.stages.some(stage=>stage.id===owner.getSnapshot().stageId)?owner.getSnapshot().stageId:(decision.stages.find(stage=>stage.state==='FAILED')||decision.stages[0])?.id||''})}catch(cause){if(ticket.current())owner.error(cause)}finally{if(ticket.current())owner.patch({loading:false})}}
  function act(action:TaskDecisionAction){
    const state=owner.getSnapshot(),decision=state.decision,key=decisionKeys[action]
    if(!key||!decision?.cycle||decision.taskId!==taskId||state.loading||!decision.availableActions.includes(action))return
    const success=decision.cycle.result==='SUCCEEDED',body={expectedTaskVersion:decision.taskVersion,expectedCycleVersion:decision.cycle.version,
      ...(action==='CONTINUE_CURRENT_TASK'?{stageId:success?state.stageId:undefined,supplementalRequirement:success?state.supplement.trim():undefined}:{}),
      ...(action==='DERIVE_INHERIT_CHANGES'||action==='DERIVE_REWORK_ALL'?{mode:action==='DERIVE_INHERIT_CHANGES'?'INHERIT_CHANGES' as const:'REWORK_ALL_STAGES' as const}:{})}
    if(action==='CONTINUE_CURRENT_TASK'&&success&&(!body.stageId||!body.supplementalRequirement)){owner.patch({error:'成功后继续优化时，请选择起始阶段并填写补充要求。'});return}
    const endpoint={CONTINUE_CURRENT_TASK:'continue',DERIVE_INHERIT_CHANGES:'derive',DERIVE_REWORK_ALL:'derive',READ_ONLY_AUDIT:'audit',ACCEPT_RESULT:'accept',CANCEL:'cancel',PUBLISH:'publish'}[action]
    const description=action==='CANCEL'?'取消请求会先安全停止仍存活的写入者，再进入终态；冻结点、执行历史和审计证据仍会保留。':action==='DERIVE_INHERIT_CHANGES'?'新任务从父任务原始基线创建分支，并把冻结的当前修改作为未提交内容还原。父任务会标记为已接续。':action==='DERIVE_REWORK_ALL'?'新任务从父任务原始基线重新执行，不继承半成品。父任务会标记为已接续。':action==='READ_ONLY_AUDIT'?'将创建只读审计任务，只执行确定性验证。':action==='ACCEPT_RESULT'?'该轮没有文件变更。确认后任务进入已确认完成，执行历史保持可审计。':success?'选中阶段及其后续阶段会重新执行；历史轮次、验证和审计证据保持不变，新轮次重新计算预算。':'将从失败或中断阶段创建新的尝试和会话；已完成阶段保持成功。'
    owner.confirm('确认任务处置？',description,key,body,async captured=>{await owner.execute<typeof captured,TaskDecision|RecoveryDraft>({label:'任务处置',input:{endpoint:`/tasks/${taskId}/decision/${endpoint}`,method:'POST',body:captured},handoffTarget:result=>'parentTaskId' in result&&result.parentTaskId===taskId?`/tasks/${encodeURIComponent(result.taskId)}`:'',
      write:input=>action==='CONTINUE_CURRENT_TASK'?api.continueTaskDecision(taskId,input):action==='DERIVE_INHERIT_CHANGES'||action==='DERIVE_REWORK_ALL'?api.deriveTaskDecision(taskId,{expectedTaskVersion:input.expectedTaskVersion,expectedCycleVersion:input.expectedCycleVersion,mode:input.mode!}):action==='READ_ONLY_AUDIT'?api.auditTaskDecision(taskId,input):action==='ACCEPT_RESULT'?api.acceptTaskDecision(taskId,input):api.cancelTaskDecision(taskId,input),
      lookup:async input=>{const latest=await api.getTaskDecision(taskId);return action==='CANCEL'&&latest.taskId===taskId&&latest.taskVersion>input.expectedTaskVersion&&['STOPPING','CANCELLED'].includes(latest.taskState)?{kind:'ACCEPTED',receipt:latest}:{kind:'UNCONFIRMED'}},
      read:async(result:Readonly<TaskDecision|RecoveryDraft>,context)=>{
        if('parentTaskId' in result){if(result.parentTaskId!==taskId||!result.taskId||captured.mode&&result.mode!==captured.mode)throw new Error('派生回执不属于原任务或冻结模式。');context.apply(()=>owner.patch({destination:`/tasks/${encodeURIComponent(result.taskId)}`}));await owner.refreshParent();return}
        if(result.taskId!==taskId)throw new Error('处置回执不属于原任务。')
        const latest=await api.getTaskDecision(taskId);if(latest.taskId!==taskId)throw new Error('处置读取不属于原任务。');await owner.refreshParent();context.apply(()=>owner.patch({decision:latest,error:result.taskState==='STOPPING'?'取消请求已保存，正在等待远端写入者停止确认':'',supplement:'',dirty:false}))
      },clearDraft:true})},()=>{const current=owner.getSnapshot().decision;return current?.taskId===taskId&&current.taskVersion===body.expectedTaskVersion&&current.cycle?.version===body.expectedCycleVersion&&current.availableActions.includes(action)})
  }
  owner.setStart(()=>void load());return Object.assign(owner,{load,act})
}
export function TaskDecisionPanel(props:TaskPanelProps){
  const candidate=useMemo(()=>createDecisionController(props.task.id),[props.task.id]),owner=useProtectedOwner(candidate);const eligible=decisionPanelEligible(props.task);owner.setEnabled(eligible);owner.bindParent(props.parent);const state=useW4Owner(props.page,owner,props.parent),decision=state.decision
  useLayoutEffect(()=>{void owner.load()},[owner,eligible,props.task.version]);if(!eligible&&!owner.locked()&&!state.dirty&&!state.error)return null
  return <section aria-label="执行结束，等待你的确认"><h2>执行结束，等待你的确认</h2>{decision?.cycle&&<p>第 {decision.cycle.ordinal} 轮 · {decision.cycle.result==='SUCCEEDED'?'执行成功':'执行失败'}</p>}{decision&&<><p>{decision.checkpoint?.state==='READY'?`冻结点已验证 · ${decision.checkpoint.changedFileCount<0?'变更数量未确认':`${decision.checkpoint.changedFileCount} 个变更文件`}`:decision.checkpoint?.blockerMessage||'冻结点尚未安全就绪，继续与派生操作已禁用。'}</p>{decision.cycle?.result==='SUCCEEDED'&&decision.availableActions.includes('CONTINUE_CURRENT_TASK')&&<><label>从哪个阶段继续优化<select aria-label="从哪个阶段继续优化" disabled={owner.actionBlocked()} value={state.stageId} onChange={event=>owner.edit({stageId:event.target.value})}>{decision.stages.map(stage=><option key={stage.id} value={stage.id}>阶段 {stage.ordinal+1} · {stage.objective}</option>)}</select></label><Input.TextArea aria-label="补充要求" disabled={owner.actionBlocked()} maxLength={12000} value={state.supplement} onChange={event=>owner.edit({supplement:event.target.value})}/></>}{decision.availableActions.map(action=>decisionKeys[action]&&<UiActionButton key={action} actionKey={decisionKeys[action]!} availability={owner.actionBlocked()?{kind:'disabled',reason:'原处置尚未结清。'}:{kind:'enabled'}} onAction={()=>owner.act(action)}/>)}</>}{state.destination&&<UiActionButton actionKey="task.open" onAction={()=>{const permit=owner.prepareHandoff();if(permit)void props.page.navigation.goAccepted(state.destination,permit)}}/>}<ReadOriginal load={owner.load}/><PanelNotice state={state} owner={owner}/></section>
}
