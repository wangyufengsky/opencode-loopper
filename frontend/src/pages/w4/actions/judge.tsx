import { judgePanelEligible } from './eligibility'
import { useLayoutEffect,useMemo } from 'react'
import { api } from '@/api/client'
import type { JudgeApproval } from '@/types/domain'
import { UiActionButton } from '@/foundation/components'
import { createPanelOwner,panelState } from './owner'
import { useW4Owner,useProtectedOwner } from '../shared/parts'
import type { TaskPanelProps } from '../shared/types'
import { PanelNotice,ReadOriginal } from './parts'

export function createJudgeController(taskId:string){
  const owner=createPanelOwner('task-judge-approval',taskId,{...panelState(),approval:undefined as JudgeApproval|undefined})
  async function load(){if(!owner.active()||!owner.readsAllowed())return;const ticket=owner.ticket('approval');owner.patch({loading:true});try{const approval=await api.getJudgeApproval(taskId);if(ticket.current())owner.patch({approval,error:''})}catch(cause){if(ticket.current())owner.error(cause)}finally{if(ticket.current())owner.patch({loading:false})}}
  function approve(){const current=owner.getSnapshot().approval;if(!current?.available||owner.getSnapshot().loading)return
    const body={expectedTaskVersion:current.taskVersion,cycleId:current.cycleId,expectedCycleVersion:current.cycleVersion,reviewBatchId:current.reviewBatchId}
    owner.confirm('人工认定通过','AI 双评审结果将保留为参考。确认人工认定本轮通过，并继续提交、推送或合并？','judge.approve',body,async captured=>{await owner.execute({label:'人工认定',input:{endpoint:`/tasks/${taskId}/judge-approval`,method:'POST',body:captured},write:input=>api.approveJudges(taskId,input),lookup:async input=>{const result=await api.getJudgeApproval(taskId);return result.approved&&result.cycleId===input.cycleId&&result.reviewBatchId===input.reviewBatchId?{kind:'ACCEPTED',receipt:result}:{kind:'UNCONFIRMED'}},read:async(result,context)=>{if(!result.approved||result.cycleId!==captured.cycleId||result.reviewBatchId!==captured.reviewBatchId)throw new Error('人工认定回执与原评审轮次不一致。');const latest=await api.getJudgeApproval(taskId);await owner.refreshParent();context.apply(()=>owner.patch({approval:latest}))}})},()=>{const latest=owner.getSnapshot().approval;return !!latest?.available&&latest.taskVersion===body.expectedTaskVersion&&latest.cycleId===body.cycleId&&latest.cycleVersion===body.expectedCycleVersion&&latest.reviewBatchId===body.reviewBatchId})
  }
  owner.setStart(()=>void load());return Object.assign(owner,{load,approve})
}
export function TaskJudgeApprovalPanel(props:TaskPanelProps){const candidate=useMemo(()=>createJudgeController(props.task.id),[props.task.id]),owner=useProtectedOwner(candidate);const eligible=judgePanelEligible(props.task);owner.setEnabled(eligible);owner.bindParent(props.parent);const state=useW4Owner(props.page,owner,props.parent);useLayoutEffect(()=>{void owner.load()},[owner,eligible,props.task.version]);if(!eligible&&!owner.locked()&&!state.dirty&&!state.error)return null;return <section aria-label="人工认定"><p>AI 双评审仅供参考。确定性验收通过后，可以人工认定本轮通过。</p>{state.approval?.approved&&<p role="status">已由人工认定通过 · AI 原始结论保留如下</p>}{state.approval?.available&&<UiActionButton actionKey="judge.approve" busy={state.command.busy} availability={owner.actionBlocked()?{kind:'disabled',reason:'请先核对原人工认定。'}:{kind:'enabled'}} onAction={owner.approve}/>}<ReadOriginal load={owner.load}/><PanelNotice state={state} owner={owner}/></section>}
