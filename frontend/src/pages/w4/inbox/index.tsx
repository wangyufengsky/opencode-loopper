import { useMemo,useState } from 'react'
import { Input } from 'antd'
import type { Interaction } from '@/types/domain'
import { UiActionButton,UiContextPanel } from '@/foundation/components'
import { PageChrome,SkinControl,type W2PageProps } from '@/pages/w2/shared'
import { displayLabel } from '@/utils/displayLabels'
import { createInboxController,type QuestionDraft } from './controller'
import { useW4Owner,CommandNotice,ReadNotice,closePolicy } from '../shared/parts'
import './inbox.css'
import { pendingCommand } from '../shared/core'

export function InboxPage(props:W2PageProps){
  const owner=useMemo(()=>createInboxController(),[]),state=useW4Owner(props,owner)
  const locked=pendingCommand(state.command),[selected,setSelected]=useState(''),rows=[...state.interactions,...Object.values(state.retained).filter(item=>!state.interactions.some(row=>row.id===item.id)&&(state.drafts[item.id]||state.originalId===item.id))],current=rows.find(item=>item.id===selected),missing=!!current&&!state.interactions.some(item=>item.id===current.id)
  return <PageChrome title="待处理中心" objectKey="nav.inbox" actions={<><SkinControl {...props}/><UiActionButton actionKey="ui.refresh" busy={state.loading} onAction={()=>void owner.refresh()}/></>} status={<><CommandNotice command={state.command} recover={()=>void owner.recover()}/><ReadNotice loading={state.loading} error={state.error}/></>}>
    <p>{state.interactions.filter(item=>item.state==='PENDING').length} 项等待处理</p>
    {!state.loading&&!state.interactions.length&&<p>目前没有待处理项</p>}
    <div className="w4-inbox-selection"><div className="w4-inbox-list">{rows.map(item=><article key={item.id} className="w4-inbox-item"><h2>{item.kind==='QUESTION'?'OpenCode 需要你的回答':item.payload.title||displayLabel(item.payload.permission)}</h2><p>{item.taskId?'任务执行':'设计会话'} · {displayLabel(item.state)}</p>{item.kind==='PERMISSION'&&item.payload.hardDenied&&<p role="alert">{item.payload.hardDenyReason||'此操作不能由运行会话授权。'}</p>}<UiActionButton actionKey="selection.select" target={item.kind==='QUESTION'?'OpenCode 需要你的回答':item.payload.title||displayLabel(item.payload.permission)} availability={selected!==item.id&&owner.canLeave().kind!=='ALLOW'?{kind:'disabled',reason:'请先保留或处理当前草稿及原操作。'}:{kind:'enabled'}} onAction={()=>setSelected(item.id)}/></article>)}</div>
    <UiContextPanel open={!!current} title={current?.kind==='QUESTION'?'OpenCode 需要你的回答':current?.payload.title||'待处理详情'} closePolicy={closePolicy(owner)} onClose={()=>setSelected('')} onConfirmClose={()=>{owner.discardDraft(selected);setSelected('')}}>
      {missing&&<p role="alert">服务端列表暂未返回原事项。原输入和操作身份已保留，请读取原结果后再处理。</p>}
      {current?.kind==='QUESTION'&&current.state==='PENDING'?<InboxQuestion item={current} draft={state.drafts[current.id]} locked={locked||missing} edit={draft=>owner.edit(current.id,draft)} submit={answers=>void owner.resolve(current.id,'REPLY',answers)} reject={()=>void owner.resolve(current.id,'REJECT')}/>:
      current?.kind==='PERMISSION'&&<><p>{displayLabel(current.payload.permission)}</p><pre>{current.payload.patterns.join('\n')}</pre>{current.state==='PENDING'&&!current.payload.hardDenied&&<div><UiActionButton actionKey="inbox.reject" availability={locked||missing?{kind:'disabled',reason:'请先核对原操作结果。'}:{kind:'enabled'}} onAction={()=>void owner.resolve(current.id,'REJECT')}/><UiActionButton actionKey="inbox.allowOnce" availability={locked||missing?{kind:'disabled',reason:'请先核对原操作结果。'}:{kind:'enabled'}} onAction={()=>void owner.resolve(current.id,'ONCE')}/><UiActionButton actionKey="inbox.allowSession" availability={locked||missing?{kind:'disabled',reason:'请先核对原操作结果。'}:{kind:'enabled'}} onAction={()=>void owner.resolve(current.id,'SESSION')}/></div>}</>}
    </UiContextPanel></div>
  </PageChrome>
}
function InboxQuestion({item,draft,locked,edit,submit,reject}:{item:Extract<Interaction,{kind:'QUESTION'}>;draft?:QuestionDraft;locked:boolean;edit:(draft:QuestionDraft)=>void;submit:(answers:string[][])=>void;reject:()=>void}){
  const current=draft||{choices:item.payload.questions.map(()=>[]),custom:item.payload.questions.map(()=>'')}
  const answers=current.choices.map((values,i)=>current.custom[i]?.trim()?item.payload.questions[i]?.multiple?[...values,current.custom[i]!.trim()]:[current.custom[i]!.trim()]:values)
  return <section aria-label="设计师等待回答">{item.payload.questions.map((question,i)=><section key={i}><p>{question.header||`问题 ${i+1}`}</p><h3>{question.question}</h3>{question.options.map(option=><label key={option.label}><input type={question.multiple?'checkbox':'radio'} name={`${item.id}-${i}`} checked={current.choices[i]?.includes(option.label)||false} disabled={locked} onChange={event=>{const choices=current.choices.map((values,index)=>index!==i?values:question.multiple?event.target.checked?[...values,option.label]:values.filter(value=>value!==option.label):[option.label]);edit({choices,custom:current.custom.map((value,index)=>index===i?'':value)})}}/>{option.label} <small>{option.description}</small></label>)}{question.custom&&<Input.TextArea aria-label={`问题 ${i+1} 自定义回答`} disabled={locked} value={current.custom[i]} onChange={event=>edit({...current,custom:current.custom.map((value,index)=>index===i?event.target.value:value)})}/>}</section>)}
    <UiActionButton actionKey="inbox.recommend" availability={locked?{kind:'disabled',reason:'请先核对原操作。'}:{kind:'enabled'}} onAction={()=>{const recommended=item.payload.questions.map(question=>{const option=question.options.find(option=>/\(Recommended\)|（推荐）|推荐/.test(option.label))||question.options[0];return option?[option.label]:[]});if(recommended.every(value=>value.length))submit(recommended)}}/>
    <UiActionButton actionKey="inbox.reject" availability={locked?{kind:'disabled',reason:'请先核对原操作。'}:{kind:'enabled'}} onAction={reject}/><UiActionButton actionKey="inbox.answer" availability={locked||!answers.every(value=>value.length)?{kind:'disabled',reason:'请回答每个问题，并先核对原操作。'}:{kind:'enabled'}} onAction={()=>submit(answers)}/>
  </section>
}
