import { useSyncExternalStore } from 'react'
import { Modal } from 'antd'
import { UiActionButton } from '@/foundation/components'
import { getSkinSnapshot, subscribeSkin } from '@/themes/state'
import { useFoundationContainer } from '@/foundation/provider'
import { RichDocument } from '@/pages/w3/shared/RichDocument'
import { accountingActive, type StoryAccountingOwner } from './storyAccounting'
import type { StoryAccountingCall } from '@/types/domain'
const roles: Record<string, string> = { ROUTER: '任务识别', REQUIREMENT_DESIGNER: '需求设计', PACKAGE_DESIGNER: '工作包设计', IMPLEMENTATION: '实施', JUDGE: '评审', REVIEWER: '只读审查', DECOMPOSER: '需求拆分', COMPILER: '设计编译' }
const operations: Record<string, string> = { start: '开启', continue: '继续', complete: '完成' }
const role = (call: StoryAccountingCall) => roles[call.role] ?? '设计与执行'
const operation = (call: StoryAccountingCall) => operations[call.operation] ?? call.operation
export function StoryAccountingDialog({ owner }: { owner: StoryAccountingOwner }) {
  const state = useSyncExternalStore(owner.subscribe, owner.getSnapshot, owner.getSnapshot), container = useFoundationContainer()
  const skin = useSyncExternalStore(subscribeSkin, getSkinSnapshot, getSkinSnapshot)
  const current = state.calls.find(call => call.id === state.selectedId), running = !!current && accountingActive(current), failed = !!current && ['FAILED', 'UNKNOWN', 'CANCELLED'].includes(current.state)
  const locked=state.command.phase!=='IDLE'
  const title = current ? `${running ? '正在' : '已结束：'}${operation(current)}故事点统计${running ? '…' : ''}` : '故事点统计'
  const elapsed = current ? Math.max(0, Math.floor(((current.finishedAt ? Date.parse(current.finishedAt) : state.now) - Date.parse(current.startedAt)) / 1000)) : 0
  const status = { PREPARED: '正在等待统计结果', CANCELLING: '正在取消本次统计', SUCCEEDED: '统计已完成', FAILED: '统计失败，任务继续执行', UNKNOWN: '统计结果未知，任务继续执行', CANCELLED: '已取消本次统计，任务继续执行' }[current?.state ?? 'PREPARED']
  return <Modal open={!!current || locked} title={title} width={760} className="story-accounting-dialog" zIndex={4000} getContainer={container} destroyOnHidden maskClosable={false} keyboard={!running && !state.retrying && !locked} closable={!running && !state.retrying && !locked} onCancel={() => void owner.close()} footer={running ? <UiActionButton actionKey="accounting.cancel" variant="danger" availability={locked?{kind:'disabled',reason:'请先核对原操作'}:{kind:'enabled'}} busy={state.cancelling || current?.state === 'CANCELLING'} onAction={() => void owner.cancel()} /> : <>{failed && <UiActionButton actionKey="accounting.retry" target={current?.operation} availability={current?.retryAvailable && !locked ? { kind: 'enabled' } : { kind: 'disabled', reason: current?.retryUnavailableReason || '此统计不可重试' }} busy={state.retrying} onAction={() => void owner.retry()} />}<UiActionButton actionKey="accounting.dismiss" availability={locked?{kind:'disabled',reason:'请先核对原操作'}:{kind:'enabled'}} busy={state.retrying} onAction={() => void owner.close()} /></>}>
    {locked && <section role={state.command.phase==='UNKNOWN'?'alert':'status'} data-operation-phase={state.command.phase}><p>原统计操作 {state.command.id} {state.command.phase==='SENDING'?'正在发送':'回执待核对'}。{state.command.error}</p>{state.command.phase==='UNKNOWN'&&<UiActionButton actionKey="receipt.readOriginal" onAction={()=>void owner.recover()}/>}</section>}
    {current && <><p className="accounting-context">系统 {current.systemCode} · 故事 {current.storyCode} · {role(current)} · 已用 {elapsed} 秒</p>{state.calls.length > 1 && <select aria-label="选择统计会话" value={state.selectedId} onChange={event => owner.select(event.target.value)}>{state.calls.map((call, index) => <option key={call.id} value={call.id}>{index + 1}. {operation(call)}统计 · {role(call)} · {call.systemCode} / {call.storyCode}{accountingActive(call) ? ' · 进行中' : ''}</option>)}</select>}
      <div className="accounting-status" role="status" aria-live="polite">{running && <span className="accounting-spinner" />}{status}</div>{(state.error || current.refreshError) && <p role="alert">{state.error || current.refreshError}</p>}
      <div className="accounting-output" aria-label="统计模型输出">{current.parts.map(part => <article key={part.id}><small>{part.type === 'TOOL' ? '统计工具' : part.type === 'THINKING' ? '思考' : '模型输出'}</small>{part.content ? <RichDocument content={part.content} skin={skin} /> : <p>{part.label || '正在处理…'}</p>}</article>)}{!current.parts.length && <p>{running ? '正在等待模型输出…' : '本次统计未返回模型正文。'}</p>}</div>
      {current.detail && <p className="accounting-detail">{current.detail}</p>}{failed && current.retryUnavailableReason && <p className="accounting-hint">{current.retryUnavailableReason}</p>}{running && <p className="accounting-hint">统计会持续等待。觉得等待太久，可以取消本次统计并继续任务；已送达平台的请求无法撤回。</p>}</>}
  </Modal>
}
