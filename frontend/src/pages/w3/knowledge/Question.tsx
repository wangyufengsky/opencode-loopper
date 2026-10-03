import { useId } from 'react'
import { Input } from 'antd'
import { UiActionButton } from '@/foundation/components'
import type { KnowledgeQuestion as QuestionDTO } from '@/types/domain'
import type { KnowledgeController, QuestionDraft } from './controller'

export function KnowledgeQuestion({question,owner,draft}:{question:QuestionDTO;owner:KnowledgeController;draft?:QuestionDraft}) {
  const id=useId(),current=draft || {choices:question.questions.map(() => []),custom:question.questions.map(() => '')}
  const answers=question.questions.map((q,i) => current.custom[i]?.trim() ? q.multiple ? [...current.choices[i]!,current.custom[i]!.trim()] : [current.custom[i]!.trim()] : current.choices[i]!)
  const locked=owner.locked()
  function choose(index:number,label:string,multiple:boolean) { const choices=current.choices.map(a => [...a]),custom=[...current.custom]; const existing=choices[index] || [];choices[index]=multiple ? existing.includes(label) ? existing.filter(v => v !== label) : [...existing,label] : [label]; if (!multiple) custom[index]=''; owner.editQuestion(question,{choices,custom}) }
  return <section className="knowledge-question" aria-label="助手提问"><h3>{question.state === 'ANSWERED' ? '已回答' : question.state === 'CLOSED' ? '本轮已结束' : '需要你的回答'}</h3>
    {question.state === 'PENDING' ? <form onSubmit={event => {event.preventDefault();void owner.reply(question)}}>{question.questions.map((prompt,i) => <fieldset key={i} disabled={locked}><legend>{prompt.question}</legend>{prompt.options.map(option => <label key={option.label}><input type={prompt.multiple ? 'checkbox' : 'radio'} name={`${id}-${i}`} checked={current.choices[i]?.includes(option.label) || false} onChange={() => choose(i,option.label,prompt.multiple)}/><span>{option.label}<small>{option.description}</small></span></label>)}{prompt.custom && <Input.TextArea aria-label={`问题 ${i+1} 的自定义回答`} rows={2} maxLength={24000} value={current.custom[i]} onChange={event => {const custom=[...current.custom];custom[i]=event.target.value;owner.editQuestion(question,{choices:current.choices,custom})}}/>}</fieldset>)}<UiActionButton actionKey="knowledge.reply" busy={owner.getSnapshot().mutation.busy} availability={locked || answers.some(a => !a.length) ? {kind:'disabled',reason:'请完整回答，并先处理原操作结果。'} : {kind:'enabled'}} onAction={() => {void owner.reply(question)}}/></form>
    : <>{question.questions.map((prompt,i) => <div key={i}><p>{prompt.question}</p>{(question.answers[i]?.length || 0)>0 && <p>{question.answers[i]!.join('；')}</p>}</div>)}{question.state === 'UNKNOWN' && <p role="status">回答投递结果待核对，未重复发送。请核对或恢复原回答。</p>}{['PREPARED','SENDING'].includes(question.state) && <p role="status">正在提交回答…</p>}</>}
  </section>
}
