import { useEffect, useRef, useState } from 'react'
import { Alert } from 'antd'
import { UiActionButton, UiContextPanel } from '@/foundation/components'
import { SemanticIcon } from '@/foundation/semanticRegistry'
import { CoreStatus } from '@/pages/w2/core/CoreUi'
import { PageChrome, PageLink, useLeaveGuard, useOwnerSnapshot, useRetainedOwner } from '@/pages/w2/shared'
import type { W2PageProps } from '@/pages/w2/shared/types'
import { RichDocument } from '@/pages/w3/shared/RichDocument'
import { knowledgeLink } from '@/utils/knowledgeLinks'
import { splitThinkingContent } from '@/utils/thinkingContent'
import { knowledgeStateLabel } from '@/utils/displayLabels'
import userAvatar from '@/assets/knowledge-user-whale.png'
import { createKnowledgeController } from './controller'
import { KnowledgeSourcesPanel } from './SourcesPanel'
import { KnowledgeEvidence } from './Evidence'
import { KnowledgeActivity } from './Activity'
import { KnowledgeQuestion } from './Question'
import { KnowledgeUsage } from './Usage'
import { useEvidenceWidth } from './useEvidenceWidth'
import './knowledge.css'

export function KnowledgePage(props: W2PageProps) {
  const param=props.route.params.conversationId,id=typeof param==='string' ? param : ''
  const [owner]=useState(() => createKnowledgeController({routeKey:props.route.fullPath,conversationId:id})),s=useOwnerSnapshot(owner)
  const [left,setLeft]=useState(false),[right,setRight]=useState(false),[models,setModels]=useState(false)
  const workspace=useRef<HTMLDivElement>(null),timeline=useRef<HTMLDivElement>(null),composer=useRef<HTMLTextAreaElement>(null),sourceTrigger=useRef<HTMLButtonElement>(null),evidenceTrigger=useRef<HTMLButtonElement>(null),modelTrigger=useRef<HTMLButtonElement>(null)
  const split=useEvidenceWidth(workspace),lastRoute=useRef(props.route.fullPath)
  useRetainedOwner(props,owner,() => owner.retire(true));useLeaveGuard(props,owner.canLeave)
  useEffect(() => {const lease=owner.attachView();return lease},[owner])
  useEffect(() => {if(lastRoute.current!==props.route.fullPath){lastRoute.current=props.route.fullPath;setRight(false);void owner.load(id,props.route.fullPath)}},[owner,id,props.route.fullPath])
  useEffect(() => {const target=timeline.current;if(target && !right && target.scrollHeight-target.scrollTop-target.clientHeight<160)target.scrollTop=target.scrollHeight},[s.messages,right])
  const usage=s.conversation?.usage || [...s.messages].reverse().find(m => m.inputTokens!==null || m.outputTokens!==null),project=s.conversation?.projectId || s.project
  async function send() {const original=props.route.fullPath,success=await owner.send();const conversation=owner.getSnapshot().conversation;if(conversation && original===props.route.fullPath && id!==conversation.id){const permit=owner.prepareConversationHandoff();if(permit)await props.navigation.go(`/knowledge/${conversation.id}`,true,permit)}if(success)composer.current?.focus({preventScroll:true})}
  function citation(target:string,refs=s.referenceList) {evidenceTrigger.current=document.activeElement instanceof HTMLButtonElement ? document.activeElement : null;setRight(true);void owner.openCitation(target,refs)}
  function file(target:string,section=0) {evidenceTrigger.current=document.activeElement instanceof HTMLButtonElement ? document.activeElement : null;setRight(true);void owner.openFile(target,section)}
  const evidence=s.citation?.body || s.filePreview,locked=owner.locked()
  return <PageChrome title="知识库" objectKey="nav.knowledge" actions={<>
    <label>项目<select aria-label="选择项目" value={project} disabled={!!s.conversation || locked} onChange={event => {void owner.changeProject(event.target.value)}}><option value="">选择项目</option>{s.projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
    <UiActionButton actionKey="knowledge.newConversation" availability={locked ? {kind:'disabled',reason:'请先核对或恢复原操作。'} : {kind:'enabled'}} onAction={() => {if(!s.conversation && !s.text) void owner.load('');else void props.navigation.go('/knowledge')}}/>
    <PageLink navigation={props.navigation} to={{path:'/knowledge/history',query:{project:project || undefined}}}>历史对话</PageLink>
    <UiActionButton actionKey="knowledge.openSources" buttonRef={sourceTrigger} expanded={left} availability={project ? {kind:'enabled'} : {kind:'disabled',reason:'请先选择项目。'}} onAction={() => setLeft(!left)}/>
    <PageLink navigation={props.navigation} to={{path:'/projects',query:{project:project || undefined}}}>项目管理</PageLink>
  </>} status={<><CoreStatus state={s} recover={owner.recover} retryOriginal={owner.retryOriginal}/>{s.settingsError && <Alert role="alert" type="error" title={s.settingsError}/>}<UiActionButton actionKey="ui.refresh" target="知识库" busy={s.settingsLoading} onAction={() => {void owner.reload()}}/>{s.disconnected && <p role="status">实时连接中断，正在重新读取会话状态。</p>}{s.mode && s.mode!=='managed' && <p>发送问题需要受管 OpenCode。<PageLink navigation={props.navigation} to="/settings">前往设置切换运行模式</PageLink></p>}</>}>
    <div ref={workspace} className="knowledge-react-workspace" style={{'--knowledge-evidence-width':`${split.width}px`} as React.CSSProperties} data-w3-workspace="knowledge" data-sources-open={left}>
      <section className="knowledge-chat"><div ref={timeline} className="knowledge-timeline" aria-label="聊天内容">
        {s.loading && !s.messages.length ? <p role="status">正在加载会话…</p> : !s.messages.length && <div className="knowledge-welcome"><SemanticIcon semanticKey="nav.knowledge"/><p>你的项目，随时问</p><h2>让项目知识，成为答案</h2><p>从代码、文档与数据库中寻找依据。选择项目，开始一次有据可查的对话。</p><div className="knowledge-suggestions">{[['梳理核心流程','这个项目的核心流程是什么？'],['对照文档与实现','文档要求与当前代码实现有哪些差异？'],['了解数据结构','项目数据库有哪些主要业务表？']].map(([label,text]) => <button key={label} type="button" disabled={locked} onClick={() => {owner.editText(text!);composer.current?.focus({preventScroll:true})}}>{label}</button>)}</div></div>}
        {s.nextCursor && <UiActionButton actionKey="ui.loadMore" target="更早消息" busy={s.loading} onAction={() => {void owner.more()}}/>}
        {s.messages.map(message => {const segments=splitThinkingContent(message.answer),answer=segments.filter(part => part.type==='content').map(part => part.content).join('\n\n'),thinking=message.thinking || segments.filter(part => part.type==='thinking').map(part => part.content).join('\n\n');return <article key={message.id} className="knowledge-turn"><div className="knowledge-user" aria-label="你的消息"><p>{message.userText}</p><img className="knowledge-user-avatar" src={userAvatar} alt="你的头像" width={36} height={36}/></div><div className="knowledge-answer"><div className="knowledge-answer-label"><SemanticIcon semanticKey="object.conversation"/><strong>项目助手</strong><small>{message.questions?.some(q => q.state==='PENDING') ? '等待回答' : knowledgeStateLabel(message.state)}</small></div>
          <KnowledgeActivity message={message} thinking={thinking} answer={answer} skin={props.skin}/><RichDocument content={answer} skin={props.skin} allowImages={false} resolveLink={knowledgeLink} onLink={(href,event) => {if(href.startsWith('#knowledge-citation-')){event.preventDefault();citation(href.slice('#knowledge-citation-'.length),message.citations)}else if(href.startsWith('#knowledge-file-')){event.preventDefault();file(decodeURIComponent(href.slice('#knowledge-file-'.length)))}}}/>
          {message.questions?.map(question => <KnowledgeQuestion key={question.id} question={question} owner={owner} draft={s.questionDrafts[question.id]}/>)}{message.detail && <p>{message.detail}</p>}{message.state==='FAILED' && message.id===s.messages.at(-1)?.id && !owner.active() && <UiActionButton actionKey="knowledge.retryQuestion" availability={locked ? {kind:'disabled',reason:'原投递结果尚未确认。'} : {kind:'enabled'}} onAction={() => {owner.editText(message.userText);composer.current?.focus({preventScroll:true})}}/>}
          {!!message.citations.length && <UiActionButton actionKey="knowledge.openCitation" target={`${message.citations.length} 条来源`} onAction={() => citation(message.citations[0]!.id,message.citations)}/>}</div></article>})}
      </div><form className="knowledge-composer" onSubmit={event => {event.preventDefault();void send()}}><textarea ref={composer} aria-label="向项目提问" value={s.text} placeholder="关于这个项目，你想了解什么？" maxLength={24000} rows={3} disabled={locked} onChange={event => owner.editText(event.target.value)} onKeyDown={event => {if(event.key==='Enter' && !event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey && !event.nativeEvent.isComposing){event.preventDefault();void send()}}}/>
        <div className="knowledge-compose-footer"><UiActionButton actionKey="knowledge.openSources" target={`${s.conversation?.sources.length ?? s.selected.length} 项来源`} onAction={() => setLeft(true)}/>{s.conversation ? <span title={s.conversation.model}>{s.conversation.model}</span> : <UiActionButton actionKey="knowledge.selectModel" buttonRef={modelTrigger} expanded={models} availability={locked ? {kind:'disabled',reason:'原操作结果尚未确认。'} : {kind:'enabled'}} onAction={() => {setModels(!models);void owner.loadCatalog()}}/>}<span>{!s.conversation && `${s.model || '选择问答模型'}${s.model===s.defaultModel ? ' · 全局' : ''}`}</span><KnowledgeUsage usage={usage}/>{owner.active() ? <UiActionButton actionKey="knowledge.stop" busy={s.mutation.busy} availability={locked || s.conversation?.state==='STOPPING' ? {kind:'disabled',reason:'正在确认原操作或停止结果。'} : {kind:'enabled'}} onAction={() => {void owner.stop()}}/> : <UiActionButton actionKey="knowledge.send" variant="primary" busy={s.mutation.busy} availability={owner.canSend() ? {kind:'enabled'} : {kind:'disabled',reason:'请填写问题，选择可用资料和模型，并核对原操作。'}} onAction={() => {void send()}}/>}</div>
      </form></section>
      <UiContextPanel open={left} title="资料来源" returnFocus={sourceTrigger} onClose={() => setLeft(false)} closePolicy={{kind:'allow'}}><KnowledgeSourcesPanel owner={owner} props={props} open={left}/></UiContextPanel>
      <div className="knowledge-evidence-drawer"><UiContextPanel open={right} title={s.fileTarget ? '文件预览' : '引用详情'} returnFocus={evidenceTrigger} onClose={() => {split.stop(false);owner.closeEvidence();setRight(false)}} closePolicy={{kind:'allow'}} expanded>
        <div className="knowledge-split-handle" role="separator" tabIndex={0} aria-label="调整引用面板宽度" aria-orientation="vertical" aria-valuenow={split.width} aria-valuemin={320} aria-valuemax={split.maximum} onPointerDown={split.start} onPointerMove={split.move} onPointerUp={() => split.stop(true)} onPointerCancel={() => split.stop(false)} onLostPointerCapture={() => split.stop(false)} onKeyDown={split.keyboard} onDoubleClick={split.reset}/>
        {s.evidenceLoading && <p role="status">正在读取引用…</p>}{s.evidenceError && <Alert role="alert" title={s.evidenceError} type="error"/>}{s.referenceList.map(ref => <button type="button" key={ref.id} onClick={() => citation(ref.id)}>{ref.name} · {ref.location}</button>)}{evidence && <KnowledgeEvidence body={evidence} citation={s.citation?.citation} focusRange={s.focusRange} skin={props.skin}/>}
        {s.filePreview && (Number(s.filePreview.nextLine)>0 || Number(s.filePreview.nextSection)>=0) && <UiActionButton actionKey="ui.next" target="文件内容" onAction={() => {const body=s.filePreview!,target=s.fileTarget!;file(Number(body.nextLine)>0 ? `${encodeURI(target.path)}#L${body.nextLine}` : encodeURI(target.path),Number(body.nextSection ?? 0))}}/>}
      </UiContextPanel></div>
      <UiContextPanel open={models && !s.conversation} title="选择问答模型" returnFocus={modelTrigger} onClose={() => setModels(false)} closePolicy={{kind:'allow'}}><p>全局默认模型：{s.defaultModel || '尚未配置'}</p><select aria-label="问答模型" value={s.model} disabled={locked} onChange={event => owner.selectModel(event.target.value)}><option value="">选择问答模型</option>{s.defaultModel && <option value={s.defaultModel}>{s.defaultModel} · 全局默认</option>}{s.models.filter(model => model.id!==s.defaultModel).map(model => <option key={model.id} value={model.id}>{model.label || model.id}</option>)}</select>{s.catalogLoading && <p>正在读取模型目录…</p>}{s.catalogError && <Alert title={s.catalogError} type="warning"/>}<UiActionButton actionKey="ui.refresh" target="模型目录" busy={s.catalogLoading} onAction={() => {void owner.loadCatalog(true)}}/></UiContextPanel>
    </div>
  </PageChrome>
}
