import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import { UiActionButton,UiContextPanel,UiSelectableList } from '@/foundation/components'
import { PageChrome,PageLink,SkinControl,type W2PageProps } from '@/pages/w2/shared'
import { RichDocument } from '@/pages/w3/shared/RichDocument'
import { displayLabel } from '@/utils/displayLabels'
import { frozenDesignTimeline } from '@/utils/frozenDesignTimeline'
import { createHistoryController } from './controller'
import { useW4Owner,ReadNotice } from '../shared/parts'
import type { LoopSpec } from '@/types/domain'
import './history.css'
import { SemanticIcon,semanticLabel,semanticName } from '@/foundation/semanticRegistry'

export const sizeLabel=(bytes:number)=>bytes<1024*1024?`${Math.max(1,Math.round(bytes/1024))} KiB`:`${(bytes/1024/1024).toFixed(1)} MiB`
function verifierLabel(verifier:LoopSpec['stages'][number]['verifiers'][number]){return verifier.type==='PROCESS'?verifier.command.join(' '):verifier.type==='FILE_EXISTS'||verifier.type==='FILE_NOT_EXISTS'?`${displayLabel(verifier.type)} · ${verifier.path}`:verifier.type==='GIT_DIFF'?`${displayLabel(verifier.type)} · ${(verifier.allowedPaths||[]).join('，')}`:displayLabel(verifier.type)}
export function TaskDesignHistoryPage(props:W2PageProps){
  const id=String(props.route.params.id||''),owner=useMemo(()=>createHistoryController(id),[id]),previous=useRef(owner)
  useLayoutEffect(()=>{if(previous.current!==owner)previous.current.retire(true);previous.current=owner},[owner])
  const state=useW4Owner(props,owner),record=state.record,[selected,setSelected]=useState('')
  useLayoutEffect(()=>setSelected(''),[owner])
  const categories=[['snapshot','只读设计快照'],['attachments','冻结附件清单'],['requirement','已确认需求'],['packages','工作包设计历史'],['conversation','历史设计对话'],['spec','冻结任务设置']] as const
  return <PageChrome title={record?.taskTitle||'历史设计'} objectKey="object.history" actions={<><SkinControl {...props}/><PageLink navigation={props.navigation} to={`/tasks/${encodeURIComponent(id)}`}>查看任务</PageLink><PageLink navigation={props.navigation} to="/tasks">任务列表</PageLink></>} status={<ReadNotice error={state.error} loading={state.loading} retry={()=>void owner.load()}/> }>
    <section data-w4-workspace="history">{record&&<>
      <p>这是任务确认时保存的只读设计，不会追随最新设计会话。</p>
      <div className="w4-history-selection"><nav aria-label="历史内容"><UiSelectableList items={categories} selectedKey={selected} getKey={item=>item[0]} getName={item=>item[1]} renderItem={item=><><SemanticIcon semanticKey="selection.select"/><span>{item[1]}</span></>} onSelect={item=>setSelected(item[0])} labelKey="object.history"/></nav>
      <UiContextPanel open={!!selected} title={categories.find(([key])=>key===selected)?.[1]||'历史详情'} onClose={()=>setSelected('')}>
      {selected==='snapshot'&&<section><h2>只读设计快照</h2><dl><dt>登记项目</dt><dd>{record.projectName}</dd><dt>任务设置</dt><dd>{displayLabel(record.draft.status)}</dd><dt>设计会话</dt><dd>{record.designerSession?displayLabel(record.designerSession.state):'未保存设计会话'}</dd><dt>确认时间</dt><dd>{record.draft.updatedAt}</dd></dl></section>}
      {record.inheritedConversation&&<p>此任务继承了父任务的设计对话；下方任务设置是当前任务自身的冻结快照。</p>}
      {selected==='attachments'&&!!record.frozenAttachments?.length&&<section><h2>冻结附件清单</h2>{record.frozenAttachments.map(file=><article key={file.id}><strong>{file.filename}</strong><p>{sizeLabel(file.sizeBytes)} · {file.scopeKey==='REQUIREMENT'?'整体需求':'工作包'} · <code className="w4-history-sha">SHA-256 {file.sha256}</code></p><UiActionButton actionKey="history.previewAttachment" target={file.filename} busy={state.previewBusy===file.id} onAction={()=>void owner.preview(file.id)}/>{(/image\//.test(file.mediaType)||file.mediaType==='application/pdf')&&<a href={apiContent(id,file.id)} target="_blank" rel="noopener noreferrer" data-semantic="history.openOriginal" aria-label={semanticName('history.openOriginal',file.filename)}><SemanticIcon semanticKey="history.openOriginal"/>{semanticLabel('history.openOriginal')}</a>}{state.attachmentPreviews[file.id]!==undefined&&<pre>{state.attachmentPreviews[file.id]}</pre>}</article>)}</section>}
      {selected==='requirement'&&record.requirement&&<section><h2>已确认需求</h2><p>需求 R{record.requirement.revision} · 模型调用 {record.requirement.modelCallsUsed}/{record.requirement.maxModelCalls}</p><RichDocument content={record.requirement.requirementText} skin={props.skin}/></section>}
      {selected==='packages'&&!!record.workPackages?.length&&<section><h2>工作包设计历史</h2>{record.workPackages.map(pack=><article key={pack.id}><h3>第 {pack.ordinal+1} 包 · {pack.title}</h3><p>{displayLabel(pack.state)}</p><p>{pack.objective}</p><p>{pack.compilerSummary}</p><p>{pack.handoffSummary}</p></article>)}</section>}
      {selected==='conversation'&&<section><h2>历史设计对话</h2>{frozenDesignTimeline(record.designerSession?.messages||[],record.designerSession?.answeredQuestions||[]).map(item=>item.kind==='discussion'?<details className="designer-discussion-history" key={item.key}><summary>需求讨论</summary>{item.entries.map(entry=><article key={`${entry.id}:${entry.discussionRevision}`}><p>{!entry.scope||entry.scope==='REQUIREMENT'?'整体需求':'工作包'} · R{entry.discussionRevision||0} {entry.answeredAt}</p>{entry.questions.map((question,i)=><section key={i}><h3>{question.question}</h3><ul className="answered-options">{question.options.map(option=><li key={option.label} className={question.answers.includes(option.label)?'selected':''}><b>{option.label}</b> {option.description}</li>)}</ul><p>用户最终回答 · {question.answers.join('、')||'未记录'}</p></section>)}</article>)}</details>:<article key={item.key}><h3>{item.message.actor==='USER'?'你':'设计师'}</h3><time>{item.message.createdAt}</time><RichDocument content={item.message.content} skin={props.skin} collapsible={item.message.actor==='DESIGNER'}/>{item.message.attachments?.map(file=><p key={file.id}>{file.filename} · {sizeLabel(file.sizeBytes)} · {displayLabel(file.state)}</p>)}</article>)}</section>}
      {selected==='spec'&&<section><h2>冻结任务设置</h2><p>{record.draft.spec.schemaVersion}</p><h3>{record.draft.spec.goal}</h3><RichDocument content={record.draft.spec.context||''} skin={props.skin}/>{record.draft.spec.stages.map((stage,i)=><article key={i}><h3>阶段 {i+1} · {stage.objective}</h3><p>允许路径：{stage.allowedPaths.join('，')||'未指定'}</p><p>禁止路径：{stage.forbiddenPaths?.join('，')||'无'}</p><p>交付物：{stage.deliverables.join('，')}</p><ul>{stage.verifiers.map((verifier,j)=><li key={j}>{verifierLabel(verifier)}</li>)}</ul></article>)}</section>}
      </UiContextPanel></div>
    </>}</section>
  </PageChrome>
}
import { api } from '@/api/client'
const apiContent=api.taskDesignAttachmentContentUrl
