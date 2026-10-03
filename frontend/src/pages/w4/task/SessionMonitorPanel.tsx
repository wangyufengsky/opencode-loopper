import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import { UiActionButton, UiConfirmDialog } from '@/foundation/components'
import { semanticName } from '@/foundation/semanticRegistry'
import type { TaskPanelProps } from '../shared/types'
import { CommandNotice, ReadNotice, useProtectedOwner, useW4Owner } from '../shared/parts'
import { createSessionMonitorController, sessionIsActive, type SessionMonitorController } from './sessionController'
import { activityLabel, displayLabel, sessionLabel } from '@/utils/displayLabels'
import { templateSessionDetail, templateSessionTitle } from '@/utils/templateSessionLabels'
import type { TaskSessionTodo } from '@/types/domain'
import { sessionPermissionLabel, sessionPermissionActionLabel } from './projections'

export function SessionTodoCard({ rows, capability, detail, truncated, expanded, onExpand }: { rows: TaskSessionTodo[]; capability: string; detail?: string; truncated: boolean; expanded: boolean; onExpand(): void }) {
  const completed = rows.filter(row => row.status === 'COMPLETED').length, current = rows.find(row => row.status === 'IN_PROGRESS') ?? rows.find(row => row.status === 'PENDING') ?? rows.at(-1)
  return <section className="w4-todo-card" aria-label="实施计划" data-todo-authority="projection">
    <header><h3>实施计划</h3><span>{completed} / {rows.length} 项已完成</span></header>
    <p className="w4-muted">OpenCode Todo 只反映会话计划，任务与阶段以服务端状态为准。</p>
    {capability !== 'AVAILABLE' && <p>{detail || '当前会话尚无可用的 Todo 投影。'}</p>}
    {current && <p><strong>{current.content}</strong> · {displayLabel(current.status)}{current.priority && ` · ${displayLabel(current.priority)}`}</p>}
    {rows.length > 1 && <UiActionButton actionKey={expanded ? 'ui.collapse' : 'ui.expand'} target="其余实施项" expanded={expanded} onAction={onExpand} />}
    {expanded && <ol className="w4-todo-list">{rows.filter(row => row.id !== current?.id).map(row => <li key={row.id}>{row.content} · {displayLabel(row.status)}</li>)}</ol>}
    {truncated && <p>Todo 列表已截断；这里只展示已读取的实施项。</p>}
  </section>
}

export function SessionMonitorPanel({ task, page, parent, controller }: TaskPanelProps & { controller?: SessionMonitorController }) {
  const candidate = useMemo(() => controller ?? createSessionMonitorController(task.id, parent), [controller, task.id, parent]), owner = useProtectedOwner(candidate)
  const s = useW4Owner(page, owner, parent), output = useRef<HTMLDivElement>(null)
  const [discarding, setDiscarding] = useState<number>(), [overflow, setOverflow] = useState<string[]>([])
  const parts = s.activity?.parts ?? [], active = sessionIsActive(s.activity)
  const questions = [...(s.activity?.pendingQuestions ?? [])]
  for (const row of Object.values(s.draftQuestions)) if (!questions.some(item => item.id === row.id)) questions.push(row)
  const visibleQuestions = questions.map(row => s.draftQuestions[row.id] ?? row)
  useLayoutEffect(() => {
    const host = output.current; if (!host) return
    let current = true
    const measure = () => {
      if (!current) return
      const ids = [...host.querySelectorAll<HTMLElement>('[data-activity-part]')].filter(node => node.scrollHeight > node.clientHeight + 1).map(node => node.dataset.activityPart!)
      setOverflow(previous => JSON.stringify(previous) === JSON.stringify(ids) ? previous : ids)
    }
    const observer = new ResizeObserver(measure)
    for (const node of host.querySelectorAll<HTMLElement>('[data-activity-part]')) observer.observe(node)
    measure()
    if (s.follow) host.scrollTop = host.scrollHeight
    return () => { current = false; observer.disconnect() }
  }, [s.activity, s.expanded, s.follow])
  const selected = s.sessions.find(row => row.key === s.selected) ?? s.activity?.session
  const todo = s.activity && selected?.kind === 'IMPLEMENTATION' && <SessionTodoCard rows={s.activity.todos} capability={s.activity.todoCapability} detail={s.activity.todoDetail} truncated={s.activity.todoTruncated} expanded={s.todoExpanded} onExpand={owner.toggleTodo} />
  const locked = owner.locked()
  const select = owner.requestSelect
  return <section className="w4-card w4-session-monitor" aria-label="任务会话" data-owner-id={owner.identity.id}>
    <header><h2>任务会话</h2><div className="w4-actions"><span>{active ? '会话进行中' : '读取已记录活动'}</span><UiActionButton actionKey="ui.refresh" target="会话活动" busy={s.loading} onAction={() => { void owner.load() }} /></div></header>
    <CommandNotice command={s.command} recover={() => { void owner.recover() }} />
    {s.dirty && <div className="w4-critical" role="status">回答草稿尚未提交。切换会话或离开前需要确认。<UiActionButton actionKey="ui.cancelEditing" target="回答草稿" availability={locked ? { kind: 'disabled', reason: '原回答尚未确认，不能丢弃。' } : { kind: 'enabled' }} onAction={() => setDiscarding(s.draftRevision)} /></div>}
    <ReadNotice error={s.error} retry={() => { void owner.load() }} />
    {!s.loading && !s.sessions.length && <p>暂无任务会话，执行后会显示真实模型活动。</p>}
    {(!!s.sessions.length || !!s.activity || !!visibleQuestions.length) && <div className="w4-session-layout"><nav aria-label="会话列表">{s.sessions.map(row => <button type="button" key={row.key} aria-label={semanticName('selection.select', templateSessionTitle(row) ?? sessionLabel(row))} aria-pressed={s.selected === row.key} disabled={locked} onClick={() => select(row.key)}><strong>{templateSessionTitle(row) ?? sessionLabel(row)}</strong><small>{templateSessionDetail(row) ?? row.stageObjective ?? '任务会话'}</small><span>{displayLabel(row.state)}</span></button>)}</nav>
      <div className="w4-session-content"><header><h3>{selected ? templateSessionTitle(selected) ?? sessionLabel(selected) : '选择会话'}</h3><label><input type="checkbox" checked={s.follow} onChange={event => owner.setFollow(event.target.checked)} />跟随最新输出</label><div className="w4-token-window" role="status" aria-label="累计 Token"><span>{s.totalTokens === null ? '用量待读取' : `${s.totalTokens.toLocaleString()} Token`}</span>{s.tokenDelta > 0 && <b className="w4-token-delta">+{s.tokenDelta.toLocaleString()}</b>}</div></header>
        {!visibleQuestions.length && todo}
        <div className="w4-output-scroll" ref={output} tabIndex={0} aria-label="模型输出">
          {!!visibleQuestions.length && <div className="w4-question-stack">{visibleQuestions.map(row => <section key={row.id} className="w4-question" aria-label="待回答问题">
            <h3>等待你的回答</h3>{!owner.questionCurrent(row.id) && <p role="alert">服务端问题已变化，原草稿仍保留；请核对原问题，不会自动提交到新问题。</p>}
            <fieldset disabled={locked || !owner.questionCurrent(row.id)}>{row.questions.map((prompt, index) => <div key={index}><h4>{prompt.header}</h4><p>{prompt.question}</p>{prompt.options.map(option => <label key={option.label}><input type={prompt.multiple ? 'checkbox' : 'radio'} name={`${row.id}-${index}`} checked={!!s.answers[row.id]?.[index]?.includes(option.label)} onChange={() => owner.choose(row.id, index, option.label)} />{option.label}<small>{option.description}</small></label>)}{prompt.custom && <label>补充回答<textarea aria-label={`${prompt.header}补充回答`} value={s.custom[row.id]?.[index] ?? ''} onChange={event => owner.customAnswer(row.id, index, event.target.value)} /></label>}</div>)}</fieldset>
            <div className="w4-actions"><UiActionButton actionKey="inbox.answer" busy={s.command.busy} availability={owner.canSubmit(row.id) ? { kind: 'enabled' } : { kind: 'disabled', reason: '请完整回答原问题，并先结清其它操作。' }} onAction={() => { void owner.submit(row.id) }} /><UiActionButton actionKey="inbox.reject" variant="danger" availability={locked || !owner.questionCurrent(row.id) || !parent.canStartWrite(owner) ? { kind: 'disabled', reason: '请先核对原问题或原操作。' } : { kind: 'enabled' }} onAction={() => owner.requestReject(row.id)} /></div>
          </section>)}</div>}
          {!!visibleQuestions.length && todo}
          {active && !visibleQuestions.length && !parts.some(part => part.type === 'OUTPUT') && <p role="status">模型正在处理，等待真实输出…</p>}
          {!active && !parts.length && <p>{s.activity?.detail || '该会话没有记录模型输出。'}</p>}
          {parts.map(part => <article className="w4-activity" key={part.id}><header><strong>{activityLabel(part)}</strong><span>{part.status && displayLabel(part.status)}</span></header><pre className={s.expanded.includes(part.id) ? 'is-expanded' : ''} data-activity-part={part.id}>{part.content}</pre>{(overflow.includes(part.id) || s.expanded.includes(part.id)) && <UiActionButton actionKey={s.expanded.includes(part.id) ? 'ui.collapse' : 'ui.expand'} target={activityLabel(part)} expanded={s.expanded.includes(part.id)} onAction={() => owner.expand(part.id)} />}</article>)}
        </div>
        {selected && <section className="w4-role-summary"><UiActionButton actionKey={s.roleOpened ? 'ui.collapse' : 'ui.expand'} target="冻结角色权限" expanded={s.roleOpened} onAction={() => { void owner.toggleRole() }} />
          {s.roleOpened && <><ReadNotice error={s.roleError} loading={s.roleLoading} retry={() => { void owner.toggleRole(); void owner.toggleRole() }} />{s.role && (s.role.configured ? <><p>角色与权限来自本会话的冻结快照。</p><ul>{s.role.permissions.map((row, index) => <li key={index}>{sessionPermissionLabel(row.permission)} · {row.pattern} · {sessionPermissionActionLabel(row.action)}</li>)}</ul><details><summary>技术标识</summary><pre>{JSON.stringify({ roleId: s.role.roleId, revisionId: s.role.revisionId, revisionSha256: s.role.revisionSha256, permissionSha256: s.role.permissionSha256, slot: s.role.slot, adapterProfile: s.role.adapterProfile, adapterVersion: s.role.adapterVersion, permissions: s.role.permissions }, null, 2)}</pre></details></> : <p>历史会话未保存角色快照，现有执行权限保持不变。</p>)}</>}
        </section>}
      </div>
    </div>}
    <UiConfirmDialog open={!!s.switching} title="切换会话" confirmActionKey="ui.discardChanges" policy={s.switching && !locked && s.switching.revision === s.draftRevision ? { kind: 'allow' } : { kind: 'block', reason: '草稿或原操作已经变化，请重新确认。' }} onCancel={owner.cancelSelect} onConfirm={owner.confirmSelect}><p>切换会话会放弃尚未提交的回答草稿。</p></UiConfirmDialog>
    <UiConfirmDialog open={discarding !== undefined} title="放弃回答草稿" confirmActionKey="ui.discardChanges" policy={!locked && discarding === s.draftRevision ? { kind: 'allow' } : { kind: 'block', reason: '草稿或原操作已经变化，请重新确认。' }} onCancel={() => setDiscarding(undefined)} onConfirm={() => { if (discarding !== undefined) owner.discard(discarding); setDiscarding(undefined) }}><p>仅放弃本地草稿，不取消任务或会话。</p></UiConfirmDialog>
    <UiConfirmDialog open={!!s.confirming} title="拒绝当前问题" confirmActionKey="inbox.reject" policy={!locked && parent.canStartWrite(owner) ? { kind: 'allow' } : { kind: 'block', reason: '原操作尚未结清。' }} onCancel={owner.cancelReject} onConfirm={() => { if (s.confirming) void owner.submit(s.confirming, true) }}><p>拒绝后本会话会继续处理拒绝结果；请先核对当前问题。</p></UiConfirmDialog>
  </section>
}
