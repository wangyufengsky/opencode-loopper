import { useLayoutEffect, useRef, useState } from 'react'
import { UiActionButton } from '@/foundation/components'
import { RichDocument } from '../shared/RichDocument'
import { splitThinkingContent } from '@/utils/thinkingContent'
import { pptToolLabel } from '@/utils/displayLabels'
import type { W2PageProps } from '@/pages/w2/shared'
import type { PptScope } from '@/types/ppt'
import { studioActive, type PptStudioController, type StudioSnapshot } from './controller'

export function PptChat({ owner, state: s, scope, scopeLabel, ready, disabled, skin, onDocumentScope }: {
  owner: PptStudioController; state: Readonly<StudioSnapshot>; scope: PptScope; scopeLabel: string; ready: boolean
  disabled: boolean; skin: W2PageProps['skin']; onDocumentScope(): void
}) {
  const timeline = useRef<HTMLDivElement>(null), following = useRef(true)
  const [history, setHistory] = useState(false), [unseen, setUnseen] = useState(false)
  const questions = s.agent?.questions.filter(question => question.state === 'PENDING') ?? []
  const active = studioActive(s), interrupted = !!s.generation?.canResume
  const canSend = !!s.chat.trim() && !active && !s.busy && !s.pending && !disabled && (ready || !s.generation || interrupted)
  const canConfirm = !ready && !s.generation && s.messages.at(-1)?.state === 'COMPLETED' && !s.chat.trim() && !active && !s.busy && !s.pending && !questions.length && !disabled
  const latest = () => { if (timeline.current) timeline.current.scrollTop = timeline.current.scrollHeight; following.current = true; setUnseen(false) }
  const scrollIdentity = JSON.stringify([s.messages.map(message => [message.id, message.answer, message.thinking, message.detail, message.calls]), questions])
  useLayoutEffect(() => { if (following.current) latest(); else setUnseen(true) }, [scrollIdentity])
  const send = () => { if (canSend) void (interrupted ? owner.resume(s.chat.trim()) : owner.send(s.chat.trim(), scope)) }
  const stopped = s.agent?.state === 'STOPPING' || s.generation?.state === 'STOPPING'
  const generic = '本轮模型已完成；制作结果以作品检查和作业状态为准'
  return <section className={`w3-ppt-chat${ready ? '' : ' w3-ppt-chat-spacious'}`} aria-label="PPT 助手">
    <div ref={timeline} className="ppt-chat-timeline" onScroll={() => { const element = timeline.current; if (element) { following.current = element.scrollHeight - element.clientHeight - element.scrollTop < 80; if (following.current) setUnseen(false) } }}>
      {!history && s.messages.length > 3 && <UiActionButton actionKey="ui.open" target="之前的讨论" onAction={() => { following.current = false; setHistory(true) }} />}
      {history && s.messageCursor && <UiActionButton actionKey="ui.loadMore" target="讨论消息" onAction={() => void owner.more('messages')} />}
      {(history ? s.messages : s.messages.slice(-3)).map(message => {
        const parts = splitThinkingContent(message.answer), thinking = message.thinking || parts.filter(part => part.type === 'thinking').map(part => part.content).join('\n\n'), answer = parts.filter(part => part.type === 'content').map(part => part.content).join('\n\n')
        const long = answer.length > 600 && message.state !== 'FAILED'
        return <article key={message.id} className="w3-ppt-message" data-message-state={message.state}>
          {message.text && <p className="ppt-user-message">{message.text}</p>}
          {thinking && <details aria-label="思考"><summary>思考</summary><RichDocument content={thinking} skin={skin} /></details>}
          {!!message.calls?.length && <details aria-label="工具调用"><summary>工具调用 · {message.calls.length}</summary>{message.calls.map(call => <p key={call.id}>{pptToolLabel(call.tool)} · {call.state}<br />{call.detail}</p>)}</details>}
          {!answer && !thinking && !message.calls?.length && ['RUNNING', 'SENDING', 'PREPARED'].includes(message.state) && <p role="status" className="knowledge-waiting">正在思考…</p>}
          {answer && (long ? <details className="ppt-long-reply"><summary>{answer.trim().split(/\n\s*\n/).at(-1)?.slice(0, 300) || '查看完整回复'}</summary><div className="ppt-agent-message"><RichDocument content={answer} skin={skin} /></div></details> : <div className="ppt-agent-message"><RichDocument content={answer} skin={skin} /></div>)}
          {message.failure && <p role="alert">{message.failure.detail}</p>}
          {message.detail && message.detail !== generic && <p className="ppt-notice">{message.detail}</p>}
        </article>
      })}
      {s.agent?.detail && s.agent.detail !== generic && !s.messages.some(message => message.detail === s.agent?.detail) && <p className="ppt-notice">{s.agent.detail}</p>}
      {questions.map(question => {
        const confirmation = question.kind === 'REQUIREMENTS_CONFIRMATION', answer = s.answers[question.id] ?? ''
        const blocked = disabled || s.busy || !!s.pending || s.agent?.state !== 'WAITING_INPUT'
        const reply = (confirmed = false) => { const value = confirmed ? '确认以上需求，请开始设计' : answer.trim(); if (value && !blocked) void owner.reply(question, value, confirmation ? confirmed : undefined) }
        return <form key={question.id} className={`ppt-question${confirmation ? ' ppt-requirements-confirmation' : ''}`} onSubmit={event => { event.preventDefault(); reply() }}>
          <h3>{confirmation ? '需求已整理好' : question.prompt}</h3>
          {confirmation && <RichDocument content={question.prompt} skin={skin} />}
          <fieldset disabled={blocked}>
            {question.options.map(option => <label key={option}><input type="radio" name={question.id} checked={answer === option} onChange={() => owner.setAnswer(question.id, option)} />{option}</label>)}
            <label>{confirmation ? '补充或修改需求' : '你的回答'}<textarea value={answer} rows={2} onChange={event => owner.setAnswer(question.id, event.target.value)} /></label>
            {confirmation && <UiActionButton actionKey="ppt.confirm" availability={blocked || !!answer.trim() ? { kind: 'disabled', reason: '请先发送补充意见，再确认最新需求。' } : { kind: 'enabled' }} onAction={() => reply(true)} />}
            <UiActionButton actionKey="ppt.send" target={confirmation ? '补充意见，继续沟通' : '回答并继续'} availability={!answer.trim() || blocked ? { kind: 'disabled', reason: '请填写回答并等待原操作完成。' } : { kind: 'enabled' }} onAction={() => reply()} />
          </fieldset>
        </form>
      })}
    </div>
    {unseen && <UiActionButton actionKey="ui.next" target={questions.length ? '有信息需要你补充' : '回到最新回复'} onAction={latest} />}
    {questions.length ? active && <UiActionButton actionKey="ppt.stop" availability={s.busy || !!s.pending || stopped ? { kind: 'disabled', reason: '正在暂停或核对原操作。' } : { kind: 'enabled' }} onAction={() => void owner.stop()} /> : <form className="ppt-composer" onSubmit={event => { event.preventDefault(); send() }}>
      {ready && <div className="ppt-scope-chip"><span>{scopeLabel}</span>{scope.kind !== 'DOCUMENT' && <UiActionButton actionKey="ui.clearSearch" target="改为修改整份演示文稿" iconOnly onAction={onDocumentScope} />}</div>}
      <textarea aria-label="向 PPT 助手发送要求" maxLength={24000} rows={3} value={s.chat} disabled={disabled || !!s.pending || s.busy}
        placeholder={ready ? '告诉我怎么改…' : '补充你的要求，或告诉我从哪里开始…'} onChange={event => owner.setChat(event.target.value)} onKeyDown={event => { if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) { event.preventDefault(); send() } }} />
      <footer><span>{active ? stopped ? '正在暂停' : '助手正在处理' : ready ? '想改哪里，直接告诉我' : '随时补充；准备好后确认需求并执行。'}</span>
        {active ? <UiActionButton actionKey="ppt.stop" availability={s.busy || !!s.pending || stopped ? { kind: 'disabled', reason: '正在暂停或核对原操作。' } : { kind: 'enabled' }} onAction={() => void owner.stop()} /> : <>
          {canConfirm && <UiActionButton actionKey="ppt.confirm" variant="primary" onAction={() => void owner.confirmRequirements()} />}
          <UiActionButton actionKey={interrupted ? 'ppt.resume' : 'ppt.send'} variant="primary" availability={canSend ? { kind: 'enabled' } : { kind: 'disabled', reason: '请填写要求并等待原操作完成。' }} onAction={send} />
        </>}
      </footer>
    </form>}
  </section>
}
