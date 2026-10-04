import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import { PageChrome, PageLink, SkinControl, useOwnerSnapshot } from '@/pages/w2/shared'
import type { W2PageProps } from '@/pages/w2/shared/types'
import { UiActionButton, UiConfirmDialog, UiContextPanel } from '@/foundation/components'
import { semanticName, type UiActionKey } from '@/foundation/semanticRegistry'
import { StageDiagram } from '@/react/diagrams/StageDiagram'
import { TemplateStepDiagram } from '@/react/diagrams/TemplateStepDiagram'
import { JudgeReviewCard } from './JudgeReviewCard'
import { TemplateBatchRecoveryPanel, TemplateSessionDiagnosticsPanel } from '@/pages/w3/templates/runs/RecoveryPanels'
import { createBatchRecoveryOwner, createDiagnosticOwner } from '@/pages/w3/templates/runs/recoveryOwners'
import { TaskJudgeApprovalPanel, TaskDecisionPanel, DirtyWorkspaceDialog, GitDiffScopeApprovalDialog, RollingPackageWorkbench } from '../actions'
import { TaskPublicationActions } from '../publication'
import { useW4Owner, CommandNotice, ReadNotice, useProtectedOwner } from '../shared/parts'
import type { TaskPanelProps } from '../shared/types'
import { createTaskDetailController, type TaskAction, type TaskDetailController } from './taskController'
import { currentTaskErrors, taskFacts, taskNextAction, taskSessionAndVerifierErrors, templatePhaseLabel } from './projections'
import { SessionMonitorPanel } from './SessionMonitorPanel'
import { createSessionMonitorController } from './sessionController'
import { ExecutionEvidencePanel, LayeredTaskError, SnapshotBatchesPanel, SnapshotReviewPartialReportPanel, TaskAuditEvidencePanel, TemplateReportsPanel, useTaskEvidence } from './TaskEvidencePanels'
import type { TaskEvidenceController } from './evidenceController'
import { displayLabel, statusLabel } from '@/utils/displayLabels'
import { formatDateTime } from '@/utils/dateTime'
import './task.css'

const actionKeys: Record<TaskAction, UiActionKey> = { start: 'task.start', pause: 'task.pause', resume: 'task.resume', cancel: 'task.cancel', retryJudges: 'task.retryJudges', retryLoop: 'task.retryLoop', reconcile: 'task.reconcileQueue', rework: 'task.redo' }
function TemplateRecovery({ task, page, parent, onSelect }: TaskPanelProps & { onSelect(key: string): void }) {
  const batch = useMemo(() => createBatchRecoveryOwner('task', task.id), [task.id]), diagnostic = useMemo(() => createDiagnosticOwner(task.id), [task.id])
  useLayoutEffect(() => { batch.setWriteGate(() => parent.canStartWrite(batch)); diagnostic.setWriteGate(() => parent.canStartWrite(diagnostic)); const a = parent.registerChild(batch), b = parent.registerChild(diagnostic); return () => { a(); b() } }, [batch, diagnostic, parent])
  const active = ['RUNNING', 'WAITING_INPUT', 'STOPPING'].includes(task.status)
  return <><TemplateBatchRecoveryPanel kind="task" id={task.id} props={page} controller={batch} active={active} revision={String(task.version)} taskStatus={task.status} /><TemplateSessionDiagnosticsPanel taskId={task.id} props={page} controller={diagnostic} active={active} onSelect={onSelect} /></>
}
export function TemplateProgress({ task, page, evidence }: TaskPanelProps & { evidence: TaskEvidenceController }) {
  const progress = task.templateProgress, total = progress?.reviewBatches == null || progress.contributorBatches == null ? null : progress.reviewBatches + progress.contributorBatches
  const completed = (progress?.completedReviews ?? 0) + (progress?.completedContributors ?? 0)
  const phase = templatePhaseLabel(task)
  const categories = progress?.snapshot?.phases ?? [{ label: '代码分析', total: progress?.reviewBatches, completed: progress?.completedReviews ?? 0 }, ...(progress?.contributorBatches ? [{ label: '人员贡献', total: progress.contributorBatches, completed: progress.completedContributors }] : [])]
  return <section className="w4-card template-progress" aria-label="执行进度"><header><h2>执行进度</h2><span>{phase}{!!progress?.repairRound && ` · 第 ${progress.repairRound} 轮返修`}</span></header>
    {!!progress?.steps?.length && <TemplateStepDiagram steps={progress.steps} />}
    <div className="w4-metrics"><div><strong>{progress?.snapshot ? completed : total ? `${Math.min(100, Math.floor(completed / total * 100))}%` : total === 0 ? '无需模型分析' : '待确定'}</strong><span>{progress?.snapshot ? '已验证审查批次' : total ? `已完成 ${completed}/${total} 个分析批次` : '采集完成后显示分析批次总数'}</span></div><div><strong>{progress?.activeBatches ?? 0}</strong><span>执行中</span></div><div><strong>{progress?.failedBatches ?? 0}</strong><span>需处理</span></div>{total !== null && <div><strong>{Math.max(0, total - completed)}</strong><span aria-label={`剩余 ${Math.max(0,total-completed)} 个`}>剩余批次</span></div>}</div>
    {categories.map(row => <p key={row.label}>{row.label} · {row.completed}/{row.total ?? '待确定'}</p>)}
    <p className="w4-muted">分析进度不代表任务最终完成；最终状态以服务端为准。</p>
    {progress?.snapshot && <div className="w4-snapshot-note"><p>{progress.snapshot.mode === 'FULL' ? '全面审查' : '日期增量审查'}{progress.snapshot.lightweight ? ' · 轻量审查 · 无问题结论不另行复核' : ` · 计划修订 ${progress.snapshot.planRevision} · 补充批次 ${progress.snapshot.supplements}`}</p><details><summary>审查版本</summary><p>{progress.snapshot.baselineSha && `基线：${progress.snapshot.baselineSha}`}</p><p>目标：{progress.snapshot.targetSha}</p></details><SnapshotBatchesPanel owner={evidence} page={page} />{progress.snapshot.lightweight && <p>分析批次固定；仅发现候选问题的批次增加复核。</p>}</div>}
    {progress?.documentPath && <footer><details><summary>{progress.documentPath.split(/[\\/]/).filter(Boolean).at(-1)}</summary><code>{progress.documentPath}</code></details><UiActionButton actionKey="ui.copy" target="输出目录" onAction={() => { void navigator.clipboard.writeText(progress.documentPath!).catch(() => {}) }} /></footer>}
    {!!task.stages?.length && <details><summary>阶段详情</summary><StageDiagram stages={task.stages} /></details>}
  </section>
}
const contextTitles = { progress: '执行进度', result: '结果摘要', sessions: '任务会话', reports: '模板报告', judges: '双评审', history: '尝试记录', evidence: '审计证据' }
export function TaskDetailPage(props: W2PageProps & { controller?: TaskDetailController }) {
  const id = typeof props.route.params.id === 'string' ? props.route.params.id : ''
  const candidate = useMemo(() => props.controller ?? createTaskDetailController(id, props.navigation), [id, props.controller, props.navigation]), owner = useProtectedOwner(candidate)
  const s = useW4Owner(props, owner), task = s.task, evidence = useTaskEvidence(owner.identity.id, props)
  // One retained Session owner follows task scope, not every incoming overview clone.
  const session = useMemo(() => createSessionMonitorController(owner.identity.id, owner.parent), [owner])
  useOwnerSnapshot(evidence)
  const sessionState = useOwnerSnapshot(session), [selected, setSelected] = useState<keyof typeof contextTitles>(), trigger = useRef<HTMLElement | null>(null)
  const select = (value: keyof typeof contextTitles) => { trigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null; setSelected(previous => previous === value ? undefined : value) }
  useLayoutEffect(() => { const release = owner.parent.registerChild(session); return release }, [owner, session])
  const facts = task && taskFacts(task, s.deliveryState), panel = task ? { task, page: props, parent: owner.parent } : undefined
  const action = (value: TaskAction, target?: string) => owner.allowed(value) && <UiActionButton key={value} actionKey={value === 'cancel' && task?.status === 'STOPPING' ? 'task.retryStop' : value === 'reconcile' && s.queue?.releaseReason === 'SESSION_WRITER_UNCONFIRMED' ? 'task.terminateQueueWriter' : actionKeys[value]} target={target} busy={s.command.busy} variant={value === 'cancel' ? 'danger' : value === 'start' ? 'primary' : 'default'} availability={owner.parent.canStartWrite() ? { kind: 'enabled' } : { kind: 'disabled', reason: '请先结清原操作或未提交草稿。' }} onAction={() => owner.request(value)} />
  return <PageChrome title={task?.title || '任务详情'} objectKey="object.task" actions={<><SkinControl {...props} /><PageLink to="/tasks" navigation={props.navigation}>返回任务</PageLink><UiActionButton actionKey="ui.refresh" target="任务" busy={s.loading} onAction={() => { void owner.refresh().catch(() => {}) }} />{task && <>{task.hasDesignHistory && !facts?.template && <PageLink to={`/tasks/${encodeURIComponent(task.id)}/design`} navigation={props.navigation}>查看设计过程</PageLink>}{['FAILED', 'CANCELLED'].includes(task.status) && <PageLink to={`/tasks/${encodeURIComponent(task.id)}/recovery`} navigation={props.navigation}>任务恢复</PageLink>}{facts?.template && <PageLink to="/template-tasks" navigation={props.navigation}>重新发起模板任务</PageLink>}{action('start')}{action('pause')}{action('resume')}{action('cancel')}{action('retryLoop')}{action('retryJudges')}{!s.reworkChild && action('rework')}</>}</>}
    status={<><ReadNotice error={s.error} loading={!task && s.loading} retry={() => { void owner.refresh().catch(() => {}) }} /><CommandNotice command={s.command} recover={() => { void owner.recover() }} /><CommandNotice command={sessionState.command} recover={() => { void session.recover() }} />{sessionState.dirty && <p className="w4-critical" role="status">回答草稿已保留。<UiActionButton actionKey="ui.open" target="原回答草稿" onAction={() => select('sessions')} /></p>}{!!sessionState.activity?.pendingQuestions.length && <p className="w4-critical" role="status">会话正在等待你的回答。<UiActionButton actionKey="ui.open" target="待回答问题" onAction={() => select('sessions')} /></p>}<ReadNotice error={sessionState.error} retry={() => { void session.load() }} /><ReadNotice error={s.auditError} loading={s.auditLoading} retry={() => { void owner.audit().catch(() => {}) }} />{task && <p><strong>{statusLabel(task.status)}</strong> · {taskNextAction(task, s.now, s.deliveryState)}</p>}{s.notices.map((notice, index) => <p role="status" key={index}>{notice}</p>)}{s.reworkChild && <div className="w4-critical" role="status">已保留派生任务，请核对原执行请求。<UiActionButton actionKey="task.open" target="原派生任务" availability={s.command.phase === 'SETTLED' ? { kind: 'enabled' } : { kind: 'disabled', reason: '请先恢复原操作。' }} onAction={() => { void owner.navigateRework() }} /><UiActionButton actionKey="task.start" target="原派生任务" availability={s.command.phase === 'SETTLED' ? { kind: 'enabled' } : { kind: 'disabled', reason: '请先恢复原执行请求。' }} onAction={() => { void owner.startRework().catch(() => {}) }} /></div>}</>}
    context={task && panel && facts && <UiContextPanel open={!!selected} title={selected ? contextTitles[selected] : '任务详情'} returnFocus={trigger} onClose={() => setSelected(undefined)}>
      {selected === 'progress' && <>{facts.template ? <TemplateProgress {...panel} evidence={evidence} /> : <><StageDiagram stages={task.stages ?? []} />{!!task.workPackages?.length && task.executionMode !== 'ROLLING_PACKAGES' && <section aria-label="工作包进度"><h2>工作包进度</h2>{task.workPackages.map(row => <article key={row.id}><h3>工作包 {row.ordinal} · {statusLabel(row.status)}</h3><p>{row.completedStages}/{row.stageCount} 阶段完成 · {row.attemptCount}/{row.attemptLimit} 尝试</p></article>)}</section>}</>}</>}
      {selected === 'result' && <section aria-label="结果摘要"><h2>结果摘要</h2><dl className="w4-facts"><div><dt>文件变更</dt><dd>{facts.changed}</dd></div><div><dt>验证通过</dt><dd>{facts.verified}</dd></div><div><dt>双评审</dt><dd>{facts.doublePass ? '均已通过' : '尚未均通过'}</dd></div></dl><details><summary>执行目录</summary><code>{task.worktreePath || '开始执行后准备'}</code></details></section>}
      {selected === 'reports' && facts.template && <><TemplateReportsPanel {...panel} owner={evidence} loadingMetadata={s.auditLoading} metadataError={s.auditError} />{task.templateProgress?.snapshot?.targetSha && task.status !== 'COMPLETED' && <SnapshotReviewPartialReportPanel page={props} owner={evidence} />}</>}
      {selected === 'judges' && facts.dual && <section aria-label="双评审"><h2>独立双评审</h2>{facts.judges.map(judge => <JudgeReviewCard key={judge.id} judge={judge} skin={props.skin} />)}{!facts.judges.length && <p>等待独立只读评审结果。</p>}</section>}
      {selected === 'history' && <section aria-label="尝试记录"><h2>尝试时间线</h2>{[...facts.attempts].sort((a, b) => b.ordinal - a.ordinal).map(attempt => <article key={attempt.id}><h3>尝试 {attempt.ordinal} · {statusLabel(attempt.status)}</h3><p>{attempt.summary}</p><time dateTime={attempt.startedAt}>{formatDateTime(attempt.startedAt)}</time></article>)}{!facts.attempts.length && <p>尚未开始尝试</p>}</section>}
      {selected === 'evidence' && <><ExecutionEvidencePanel page={props} owner={evidence} /><TaskAuditEvidencePanel {...panel} owner={evidence} /></>}
      <div hidden={selected !== 'sessions'}><SessionMonitorPanel {...panel} controller={session} /></div>
    </UiContextPanel>}>
    {!id && <p role="alert">任务地址缺少身份，请返回任务列表。</p>}
    {!task && !s.loading && !s.error && <p>未找到任务。</p>}
    {task && panel && facts && <div className="w4-task-sections">
      <section className="w4-card" aria-label="任务摘要"><h2>任务摘要</h2><p>{task.goal}</p><dl className="w4-facts"><div><dt>项目</dt><dd>{task.projectName}</dd></div><div><dt>执行分支</dt><dd>{task.status === 'PENDING_START' ? '开始执行后创建' : task.branch === 'DIRECT' ? '直接执行目录' : task.branch || '待准备'}</dd></div><div><dt>执行目录</dt><dd>{task.status === 'PENDING_START' ? '尚未分配' : task.worktreePath || '待准备'}</dd></div><div><dt>尝试</dt><dd>{task.attemptCount}/{task.maxAttempts}</dd></div>{facts.template && <div><dt>报告文件</dt><dd>{task.templateProgress?.reportCount ?? '待确定'}</dd></div>}<div><dt>实时连接</dt><dd>{s.connection === 'connected' ? '已连接' : s.connection === 'reconnecting' ? '正在恢复连接' : '尚未连接'}</dd></div></dl>{facts.template && task.status==='COMPLETED' && <p>{facts.dual ? facts.doublePass ? '报告已通过完整性校验和双评审' : '报告任务已完成，请核对持久化评审证据。' : '报告已通过程序校验并保存'}</p>}</section>
      {task.status === 'QUEUED' && <section className="w4-card" aria-label="队列状态"><h2>执行队列</h2><ReadNotice error={s.queueError} loading={s.queueLoading} retry={() => { void owner.queue().catch(() => {}) }} />{s.queue && <><p>排队位置：{s.queue.queuePosition ?? '待确认'} · {displayLabel(s.queue.leaseState)}</p>{s.queue.holderTaskId && <p>持有租约：<PageLink to={`/tasks/${encodeURIComponent(s.queue.holderTaskId)}`} navigation={props.navigation}>{s.queue.holderTaskTitle || '原任务'}</PageLink> · {statusLabel(s.queue.holderTaskState)}{s.queue.holderArchived && ' · 已归档'}</p>}{s.queue.releaseReason && <p role="alert">{displayLabel(s.queue.releaseReason)}</p>}{action('reconcile', s.queue.releaseReason === 'SESSION_WRITER_UNCONFIRMED' ? '终止遗留会话并释放' : '重新检查并释放')}</>}</section>}
      <nav className="w4-detail-selections" aria-label="任务内容">{(Object.keys(contextTitles) as Array<keyof typeof contextTitles>).filter(key => (key !== 'reports' || facts.template) && (key !== 'judges' || facts.dual)).map(key => <button key={key} type="button" data-semantic="selection.select" aria-label={semanticName('selection.select', contextTitles[key])} aria-pressed={selected === key} onClick={() => select(key)}>{contextTitles[key]}</button>)}</nav>
      {task.status === 'SUPERSEDED' && task.successorTaskId && <PageLink to={`/tasks/${encodeURIComponent(task.successorTaskId)}`} navigation={props.navigation}>查看接续任务</PageLink>}
      {facts.template && <TemplateRecovery {...panel} onSelect={key => { select('sessions'); session.requestSelect(key) }} />}
      <RollingPackageWorkbench {...panel} /><TaskDecisionPanel {...panel} /><GitDiffScopeApprovalDialog {...panel} /><TaskJudgeApprovalPanel {...panel} /><DirtyWorkspaceDialog {...panel} />
      <TaskPublicationActions {...panel} onDeliveryState={owner.setDeliveryState} />
      {taskSessionAndVerifierErrors(task).map(error => <LayeredTaskError key={error.id} error={error} taskState={task.status} owner={evidence} judges={facts.judges} />)}
      {currentTaskErrors(task).map(error => <LayeredTaskError key={error.id} error={error} taskState={task.status} owner={evidence} judges={facts.judges} />)}
    </div>}
    <UiConfirmDialog open={!!s.confirming} title={s.confirming ? ({ cancel: '取消任务', retryJudges: '重新进行双评审', retryLoop: '继续一轮', rework: '新分支重做', reconcile: '终止遗留会话并释放队列', start: '开始执行', pause: '暂停任务', resume: '恢复任务' })[s.confirming.action] : ''} confirmActionKey={s.confirming ? actionKeys[s.confirming.action] : 'task.cancel'} busy={s.command.busy} policy={owner.confirmationCurrent() ? { kind: 'allow' } : { kind: 'block', reason: '任务状态、草稿或原操作已变化，请重新核对。' }} onConfirm={owner.confirm} onCancel={owner.cancelConfirmation}>
      <p>{s.confirming?.action === 'cancel' ? '请求终止旧会话与进程。只有停止得到确认后，任务才进入取消状态；现有文件与证据保留。' : s.confirming?.action === 'retryJudges' ? '创建新的独立只读双评审，会消耗模型预算。' : s.confirming?.action === 'retryLoop' ? '显式继续原任务的一轮尝试，服务端复核停止与预算保护。' : s.confirming?.action === 'rework' ? '从原冻结基线创建新的分支任务并开始执行，原任务与证据保留。' : s.confirming?.action === 'start' ? '申请原任务的执行资源并开始执行。' : s.confirming?.action === 'pause' ? '暂停原任务，保留已有工作与审计记录。' : s.confirming?.action === 'resume' ? '恢复原任务，服务端核对停止和资源后继续。' : '先确认遗留 writer 停止，证明成立后才能释放队列租约。'}</p>
    </UiConfirmDialog>
  </PageChrome>
}
