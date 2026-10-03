import { useLayoutEffect,useMemo } from 'react'
import { Input } from 'antd'
import { api } from '@/api/client'
import type { DirtyWorkspaceAction,DirtyWorkspaceState,Task } from '@/types/domain'
import { UiActionButton,UiConfirmDialog } from '@/foundation/components'
import type { TaskPanelProps } from '../shared/types'
import { useW4Owner,useProtectedOwner } from '../shared/parts'
import { createPanelOwner,panelState } from './owner'
import { PanelNotice } from './parts'

export function dirtyWorkspaceActive(task:Task){return task.status==='WAITING_INPUT'&&task.waitingReasonCode==='SOURCE_BRANCH_WORKSPACE_DIRTY'&&!task.branch&&!task.worktreePath}
export function createDirtyController(taskId:string){
  const owner=createPanelOwner('task-dirty-workspace',taskId,{...panelState(),workspace:undefined as DirtyWorkspaceState|undefined,actions:{} as Record<string,DirtyWorkspaceAction>,commitMessage:'chore: 保存任务开始前的本地改动',visible:true,cancellationAvailable:true})
  async function load(){if(!owner.active()||!owner.readsAllowed())return;const ticket=owner.ticket('workspace');owner.patch({loading:true});try{const workspace=await api.getDirtyWorkspace(taskId);if(ticket.current())owner.patch({workspace,actions:Object.fromEntries(workspace.files.flatMap(file=>{const action=owner.getSnapshot().actions[file.path];return action?[[file.path,action]]:[]}))})}catch(cause){if(ticket.current())owner.error(cause)}finally{if(ticket.current())owner.patch({loading:false})}}
  function resolve(){
    const state=owner.getSnapshot(),current=state.workspace;if(!current||state.loading)return
    if(!current.clean&&!current.files.every(file=>state.actions[file.path])){owner.patch({error:'请为每个文件选择提交、暂存或移除。'});return}
    const hasCommit=current.files.some(file=>state.actions[file.path]==='COMMIT'),message=state.commitMessage.trim()
    if(hasCommit&&(!message||message.length>160)){owner.patch({error:'提交说明需为 1–160 个字符。'});return}
    const body={snapshotId:current.snapshotId,resolutions:current.files.map(file=>({path:file.path,action:state.actions[file.path]!})),...(hasCommit?{commitMessage:message}:{})}
    const run=async(captured:Readonly<typeof body>)=>{await owner.execute({label:'工作区处理',input:{endpoint:`/tasks/${taskId}/workspace-dirty/resolve`,method:'POST',body:captured},write:input=>api.resolveDirtyWorkspace(taskId,input),lookup:async()=>{const token=owner.capture();await api.getDirtyWorkspace(taskId);if(!token.isCurrent()||!owner.active())return {kind:'UNCONFIRMED'};await api.getTask(taskId);return {kind:'UNCONFIRMED'}},
      read:async(result,context)=>{if(result.task.id!==taskId)throw new Error('工作区回执与原任务不一致。');const task=await api.getTask(taskId);if(task.id!==taskId)throw new Error('任务读取与原身份不一致。');await owner.refreshParent();context.apply(()=>owner.patch({workspace:result.workspace,visible:dirtyWorkspaceActive(task),actions:{},dirty:false,error:task.status==='WAITING_INPUT'?'工作区仍有新的或未处理的改动，请重新选择后继续。':''}))},clearDraft:true})}
    if(current.files.some(file=>state.actions[file.path]==='REMOVE'))owner.confirm('确认移除所选文件？','将永久丢弃所选文件的本地改动；未跟踪文件会被删除。该操作不能由 Loopper 自动恢复。','workspace.resolve',body,run,()=>owner.getSnapshot().workspace?.snapshotId===current.snapshotId)
    else void run(body)
  }
  function cancel(){if(!owner.getSnapshot().cancellationAvailable)return;owner.confirm('确认取消任务？','取消请求会先停止远端写入者；现有本地文件保持原样。','task.cancel',{},async body=>{await owner.execute({label:'取消任务',input:{endpoint:`/tasks/${taskId}/workspace-dirty/cancel`,method:'POST',body},write:()=>api.cancelDirtyWorkspace(taskId),lookup:async()=>{const task=await api.getTask(taskId);return task.id===taskId&&['STOPPING','CANCELLED'].includes(task.status)?{kind:'ACCEPTED',receipt:task}:{kind:'UNCONFIRMED'}},read:async(result,context)=>{if(result.id!==taskId)throw new Error('取消回执与原任务不一致。');const task=await api.getTask(taskId);if(task.id!==taskId)throw new Error('取消读取不属于原任务。');await owner.refreshParent();context.apply(()=>owner.patch({visible:dirtyWorkspaceActive(task),dirty:false,error:task.status==='STOPPING'?'取消请求已保存，正在等待远端写入者停止确认':''}))}})},()=>owner.getSnapshot().cancellationAvailable)}
  owner.setStart(()=>void load());return Object.assign(owner,{load,resolve,cancel})
}
export function DirtyWorkspaceDialog(props:TaskPanelProps){
  const candidate=useMemo(()=>createDirtyController(props.task.id),[props.task.id]),owner=useProtectedOwner(candidate);const active=dirtyWorkspaceActive(props.task);owner.setEnabled(active);owner.bindParent(props.parent)
  const state=useW4Owner(props.page,owner,props.parent)
  useLayoutEffect(()=>{owner.patch({cancellationAvailable:props.task.cancellationAvailable!==false});if(active)void owner.load()},[owner,active,props.task.updatedAt,props.task.cancellationAvailable])
  if(!active&&!owner.locked()&&!state.dirty)return state.error?<section aria-label="工作区处理错误"><PanelNotice state={state} owner={owner}/></section>:null
  return <><UiConfirmDialog open={active&&state.visible||owner.locked()||state.dirty} title="发现未提交文件" confirmActionKey="workspace.resolve" busy={state.command.busy} onCancel={()=>{}} onConfirm={owner.resolve} policy={state.loading||!state.workspace||owner.locked()?{kind:'block',reason:'请先读取文件列表并核对原操作。'}:{kind:'allow'}}>
    <p>创建任务分支前，需要先明确处理当前工作区中的每个文件。Loopper 不会自动混入、隐藏或删除这些改动。</p><p>分支：{state.workspace?.branch||'读取中'}</p><UiActionButton actionKey="workspace.refresh" busy={state.loading} onAction={()=>void owner.load()}/>
    {state.workspace?.files.map(file=><section key={file.path}><p>{file.untracked?'未跟踪':`${file.indexStatus}${file.workTreeStatus}`.includes('U')?'存在冲突':'已修改'} · {file.path}{file.originalPath&&` · 来自 ${file.originalPath}`}</p><label>处理方式 {file.path}<select aria-label={`处理方式 ${file.path}`} disabled={owner.actionBlocked()} value={state.actions[file.path]||''} onChange={event=>owner.edit({actions:{...state.actions,[file.path]:event.target.value as DirtyWorkspaceAction}})}><option value="">请选择</option><option value="COMMIT">提交到当前源分支</option><option value="STASH">暂存到 Git stash</option><option value="REMOVE">移除 / 丢弃改动</option></select></label></section>)}
    {state.workspace?.clean&&<p>当前工作区已经干净，可以重新检查并继续。</p>}
    {state.workspace?.files.some(file=>state.actions[file.path]==='COMMIT')&&<label>保护提交说明<Input aria-label="保护提交说明" disabled={owner.actionBlocked()} maxLength={160} value={state.commitMessage} onChange={event=>owner.edit({commitMessage:event.target.value})}/><p>所有提交文件合并为当前源分支上的一个本地提交；不会自动推送。</p></label>}
    <p>提交：保留在源分支历史中；暂存：仅所选路径，包含未跟踪文件；移除：永久丢弃改动。</p><UiActionButton actionKey="task.cancel" availability={owner.actionBlocked()||!state.cancellationAvailable?{kind:'disabled',reason:'请先核对原操作或等待服务端允许取消。'}:{kind:'enabled'}} onAction={owner.cancel}/><PanelNotice state={state} owner={owner}/>
  </UiConfirmDialog></>
}
