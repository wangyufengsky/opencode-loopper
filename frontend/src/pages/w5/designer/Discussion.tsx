import { useState } from 'react'
import { api } from '@/api/client'
import type { DesignerActivity, TaskSessionPendingQuestion } from '@/types/domain'
import type { SkinDefinition } from '@/themes/types'
import { UiActionButton } from '@/foundation/components'
import { RichDocument } from '@/pages/w3/shared/RichDocument'
import { TokenUsageWindow } from '@/pages/w4/task/TokenUsageWindow'
import { ReadOnlyCode } from '@/pages/w3/shared/ReadOnlyCode'
import { designerActorLabel, displayLabel, statusLabel, userFacingError, errorCodeLabel } from '@/utils/displayLabels'
import { formatDateTime } from '@/utils/dateTime'
import type { DesignerController, DesignerSnapshot } from './controller'
import { timeline } from './model'
export function PendingQuestion({ pending, busy, submit }: { pending: TaskSessionPendingQuestion; busy: boolean; submit(answers: string[][]): void }) {
  const [draft, setDraft] = useState<string[][]>(pending.questions.map(() => [])), [custom, setCustom] = useState<string[]>(pending.questions.map(() => ''))
  const answers = pending.questions.map((prompt, i) => custom[i]?.trim() ? prompt.multiple ? [...(draft[i] ?? []), custom[i]!.trim()] : [custom[i]!.trim()] : draft[i] ?? [])
  return <section className="designer-card" aria-label="设计师等待回答"><h3>需要你的回答</h3><fieldset disabled={busy}>{pending.questions.map((prompt, i) => <section key={i}><h4>{prompt.header || `问题 ${i + 1}`}</h4><p>{prompt.question}</p>{prompt.options.map(option => <label className="designer-option" key={option.label}><input type={prompt.multiple ? 'checkbox' : 'radio'} name={`${pending.id}-${i}`} checked={draft[i]?.includes(option.label) ?? false} value={option.label} onChange={event => { setDraft(old => old.map((values, index) => index === i ? prompt.multiple ? event.target.checked ? [...values, option.label] : values.filter(value => value !== option.label) : [option.label] : values)); if (!prompt.multiple) setCustom(old => old.map((value, index) => index === i ? '' : value)) }} /><b>{option.label}</b><span>{option.description}</span></label>)}{prompt.custom && <label className="designer-field">自定义回答<textarea aria-label={`问题 ${i + 1} 自定义回答`} value={custom[i]} onChange={e => setCustom(old => old.map((value, index) => index === i ? e.target.value : value))} /></label>}</section>)}
    <UiActionButton actionKey="designer.recommendAnswers" busy={busy} onAction={() => { const recommended = pending.questions.map(prompt => { const option = prompt.options.find(item => /Recommended|推荐/.test(item.label)) ?? prompt.options[0]; return option ? [option.label] : [] }); if (recommended.every(item => item.length)) submit(recommended) }} />
    <UiActionButton actionKey="designer.replyQuestion" busy={busy} availability={answers.every(value => value.length) ? { kind: 'enabled' } : { kind: 'disabled', reason: '请回答所有问题' }} onAction={() => submit(answers)} />
  </fieldset></section>
}
export function Discussion({ state, owner, skin, stopAttachment }: { state: Readonly<DesignerSnapshot>; owner: DesignerController; skin: SkinDefinition; stopAttachment(id: string, filename: string): void }) {
  const session = state.session; if (!session) return null
  return <div className="designer-history" aria-label="设计讨论记录">
    {session.requirementSnapshot && <section className="designer-card" aria-label="整体需求"><h3>整体需求 · R{session.requirementSnapshot.discussionRevision}</h3><small>{session.requirementSnapshot.source === 'SERVER_ASSEMBLED' ? '服务端整理' : '历史 AI 整理'}</small><RichDocument content={session.requirementSnapshot.markdown} skin={skin} /></section>}
    {session.questionInteraction.mode === 'CHAT_FALLBACK' && <p role="status">对话回答模式：{session.questionInteraction.awaitingAnswer ? '请阅读设计师问题并直接在输入框回答。' : '当前 OpenCode 不提供选项式提问。'}</p>}
    {timeline(session, state.selectedPackage).map(entry => entry.kind === 'discussion' ? <details key={entry.key} className="designer-card"><summary>需求讨论</summary>{entry.entries.map(question => <article key={question.id}><p>{question.scope || '整体需求'} · R{question.discussionRevision}</p>{question.questions.map((prompt, i) => <section key={i}><h4>{prompt.header || `问题 ${i + 1}`}</h4><p>{prompt.question}</p><ul>{prompt.options.map(option => <li key={option.label} data-selected={prompt.answers.includes(option.label)}>{option.label} · {option.description}</li>)}</ul><p>用户最终回答：{prompt.answers.join('、') || '未记录'}</p></section>)}</article>)}</details> : entry.kind === 'system' || entry.kind === 'validators' ? <details className="designer-card" key={entry.key}><summary>{entry.kind === 'system' ? '系统记录' : '验收记录'} · {entry.messages.length}</summary>{entry.messages.map(message => <p key={message.id}><time>{formatDateTime(message.createdAt)}</time> · {entry.kind === 'validators' || message.content.includes('SYSTEM_ERROR') ? designerMessageError(message.content) : message.content}</p>)}</details> : <article className="designer-card" key={entry.key} data-designer-actor={entry.message.actor}><header><strong>{designerActorLabel(entry.message.actor)}</strong><span>{entry.message.workPackageId}</span><time>{formatDateTime(entry.message.createdAt)}</time>{entry.message.deliveryState && <small>{statusLabel(entry.message.deliveryState)}</small>}</header>{entry.message.actor === 'DESIGNER' ? <RichDocument content={entry.message.content} skin={skin} collapsible thinkingPresentation="expanded" /> : <p className="designer-plain">{['RETRYABLE_ERROR', 'TERMINAL_ERROR', 'SESSION_ERROR'].includes(entry.message.deliveryState ?? '') || entry.message.content.includes('SYSTEM_ERROR') ? designerMessageError(entry.message.content) : entry.message.content}</p>}
      {entry.message.attachments?.map(attachment => <section key={attachment.id} className="designer-attachment"><strong>{attachment.filename}</strong><small>{attachment.sizeBytes} bytes · {attachment.scopeKey} · SHA-256 {attachment.sha256}</small><UiActionButton actionKey="designer.previewAttachment" target={attachment.filename} onAction={() => { void owner.loadPreview(attachment.id) }} /><a href={api.designerAttachmentContentUrl(session.id, attachment.id)} target="_blank" rel="noopener noreferrer">打开原文件：{attachment.filename}</a>{attachment.state === 'ACTIVE' && session.state === 'REVIEWING' && <UiActionButton actionKey="designer.stopAttachment" target={attachment.filename} busy={state.command.busy} availability={['SENDING', 'UNKNOWN', 'ACCEPTED_READBACK'].includes(state.command.phase) ? { kind: 'disabled', reason: '先核对原操作。' } : { kind: 'enabled' }} onAction={() => stopAttachment(attachment.id, attachment.filename)} />}{state.previews[attachment.id] && <ReadOnlyCode content={state.previews[attachment.id]!} label={`附件预览 ${attachment.filename}`} />}</section>)}
    </article>)}
    {session.pendingQuestions?.map(pending => <PendingQuestion key={pending.id} pending={pending} busy={state.command.busy || session.autoMode.enabled && session.autoMode.state === 'ACTIVE' || ['UNKNOWN', 'ACCEPTED_READBACK'].includes(state.command.phase)} submit={answers => { void owner.replyQuestion(pending.id, answers) }} />)}
    {state.activity && !(session.questionInteraction.mode === 'CHAT_FALLBACK' && session.questionInteraction.awaitingAnswer) && session.state === 'RUNNING' && !session.compiler?.serverCompiled && <ActivityProjection activity={state.activity} skin={skin} label={`${designerActorLabel(state.activity.actor)}正在处理`} totalTokens={state.activityTokens} delta={state.activityDelta} error={state.activityError} />}
    {!state.activity && state.activityError && <p role="alert">{state.activityError}</p>}

  </div>
}

export function ActivityProjection({ activity, skin, label, totalTokens = activity.usage.totalTokens, delta = 0, error = '' }: { activity: DesignerActivity; skin: SkinDefinition; label: string; totalTokens?: number | null; delta?: number; error?: string }) {
  const content = (activity.parts.at(-1)?.content ?? '').replace(/<!--\s*TASK_PROFILE_ROUTER_JSON_START\s*-->[\s\S]*?(?:<!--\s*TASK_PROFILE_ROUTER_JSON_END\s*-->|$)/gi, '').trim()
  return <section className="designer-card" aria-label={label} role="status"><h3>{designerActorLabel(activity.actor)}正在处理</h3><p>{displayLabel(activity.remoteState)} · {activity.connected ? '已连接' : '正在重连'} · {formatDateTime(activity.observedAt)}</p><p>{activity.structuredStep && displayLabel(activity.structuredStep)}</p>{activity.parts.at(-1)?.label && <small>{activity.parts.at(-1)!.label}</small>}{content && <div className="designer-activity-content"><RichDocument content={content} skin={skin} thinkingPresentation="expanded" /></div>}<TokenUsageWindow totalTokens={totalTokens} delta={delta}/>{error && <p role="alert">当前角色活动暂时无法刷新：{error}</p>}</section>
}

/** Server classification from the selected work package; unknown codes do not manufacture a business decision. */
export function PackageGapNotice({code,detail}:{code?:string;detail?:string}) {
  const labels:Record<string,string>={PACKAGE_GAP_CANDIDATE_EXPRESSION:'候选表达待修正',PACKAGE_GAP_REPOSITORY_UNCONFIRMED:'仓库事实待确认',PACKAGE_GAP_VERIFICATION_TO_BUILD:'需要建设验证',PACKAGE_GAP_BUSINESS_DECISION:'业务选择待确认',PACKAGE_GAP_PROVEN_CONFLICT:'已证实约束冲突',PACKAGE_GAP_UNCONFIRMED:'尚未确认'}
  const label=code?labels[code]:undefined
  return label?<aside className="designer-card package-gap-notice" role="status" aria-label="工作包待处理事项"><strong>{label}</strong>{detail&&<p>{detail}</p>}{['PACKAGE_GAP_UNCONFIRMED','PACKAGE_GAP_REPOSITORY_UNCONFIRMED'].includes(code??'')?<p>现有依据不足以确认缺少需求或能力。请保留已知行为，补充证据或通过本地反馈说明。</p>:code==='PACKAGE_GAP_BUSINESS_DECISION'?<p>请在本地反馈中明确不同选择对应的行为，再重新设计。可按诊断中的来源回复，例如 REQ-L001=失败时回滚；系统保留原文和你的补充。</p>:null}</aside>:null
}

function designerMessageError(content: string) {
  const code = content.match(/^([A-Z][A-Z0-9_]+):/)?.[1]
  return code ? errorCodeLabel(code) : userFacingError(content)
}
