import { useMemo } from 'react'
import { UiActionButton, UiConfirmDialog } from '@/foundation/components'
import { PageChrome, PageLink } from '@/pages/w2/shared'
import type { W2PageProps } from '@/pages/w2/shared/types'
import type { DocumentTemplateOverview, SourceTemplateOverview } from '@/types/domain'
import { documentTemplateStateLabel, sourceTemplateStateLabel, sourceCoverageLabel, sourceBatchStateLabel, sourceBatchFailureLabel, statusLabel } from '@/utils/displayLabels'
import { createRunController, type RunAction, type RunController } from './runController'
import { pendingCommand } from './core'
import { CommandNotice, ReadNotice, useRunOwner } from './parts'
import { DocumentSourcesPanel, DocumentRequirementsPanel, DocumentSupplementForm } from './DocumentPanels'
import { ArtifactsPanel, SourceCoveragePanel } from './ContentPanels'
import { TemplateBatchRecoveryPanel, TemplateSessionDiagnosticsPanel } from './RecoveryPanels'
import './runs.css'

type PageProps = W2PageProps & { controller?: RunController }
function ChildOperations({ owner }: { owner: RunController }) {
  return <>{owner.getSnapshot().childOperations.map(child => <section key={child.key} aria-label={`${child.label}恢复`}>
    {child.error && !pendingCommand(child.command!) && <ReadNotice error={child.error} />}
    {child.command && pendingCommand(child.command) && <CommandNotice command={child.command} recover={() => { void owner.recoverChild(child.key) }} />}
    {child.dirty && <p role="status">{child.label}尚有本地草稿，离开时将先确认保留或放弃。</p>}
  </section>)}</>
}
function runId(props: W2PageProps) { const value = props.route.params.id; return Array.isArray(value) ? value[0] ?? '' : value ?? '' }
function RunLinks({ run, props, source }: { run: DocumentTemplateOverview | SourceTemplateOverview; props: W2PageProps; source: boolean }) {
  return <>{run.taskId ? <PageLink navigation={props.navigation} to={`/tasks/${encodeURIComponent(run.taskId)}`}>打开{source ? '测试' : '开发'}执行与验收</PageLink> : run.designerId && <PageLink navigation={props.navigation} to={{ path: '/designer', query: { sessionId: run.designerId } }}>查看设计与待处理问题</PageLink>}
    {run.taskState && <p>关联执行：{statusLabel(run.taskState)}</p>}{run.taskState === 'AWAITING_DECISION' && <p role="status">{source ? '测试验收' : '开发执行'}已结束，结果仍待你处置。请打开执行详情，处理结果后再归档。</p>}</>
}
function RunActions({ owner, actions }: { owner: RunController; actions: { action: RunAction; target?: string }[] }) {
  const state = owner.getSnapshot(), blocked = pendingCommand(state.command) || owner.canLeave().kind === 'BLOCK'
  return <div className="w3-actions">{actions.map(({ action, target }) => <UiActionButton key={action} actionKey={action === 'start' ? 'template.start' : action === 'resume' ? 'template.resume' : action === 'retry' ? 'template.retryBatches' : action === 'cancel' ? 'template.cancel' : action === 'archive' ? 'task.archive' : 'task.restoreArchive'} target={target} variant={action === 'start' || action === 'resume' ? 'primary' : 'default'} busy={state.command.busy} availability={blocked || action === 'retry' && !state.selected.length ? { kind: 'disabled', reason: blocked ? '请先恢复原操作。' : '请选择失败批次。' } : { kind: 'enabled' }} onAction={() => owner.requestCommand(action)} />)}</div>
}
function ConfirmRun({ owner }: { owner: RunController }) {
  const state = owner.getSnapshot()
  return <UiConfirmDialog open={!!state.confirming} title="取消任务" confirmActionKey="template.cancel" onConfirm={owner.confirmCommand} onCancel={owner.cancelConfirmation}
    policy={state.confirming && state.confirming.version !== state.run?.version ? { kind: 'block', reason: '任务版本已变化，请重新核对取消意图。' } : { kind: 'allow' }}>
    <p>取消本次模板任务？已保存的结果和证据会保留，活动执行确认停止后才会结束。</p>
  </UiConfirmDialog>
}
export function DocumentTemplatePage(props: PageProps) {
  const id = runId(props), owner = useMemo(() => props.controller ?? createRunController('document', id), [props.controller, id]), s = useRunOwner(props, owner), run = s.run as DocumentTemplateOverview | undefined, context = s.documentContext ?? run
  const terminal = !!run && ['COMPLETED', 'CANCELLED'].includes(run.state)
  return <PageChrome title={run?.title ?? '需求任务'} objectKey="object.templateRun" actions={<><PageLink navigation={props.navigation} to={{ path: '/tasks', query: { type: 'template' } }}>历史任务</PageLink><UiActionButton actionKey="ui.refresh" busy={s.loading} onAction={() => { void owner.refresh() }} /></>}
    status={<><ReadNotice error={s.error} loading={s.loading} retry={() => { void owner.refresh() }} /><CommandNotice command={s.command} recover={() => { void owner.recover() }} /><ChildOperations owner={owner} />{run?.waitingMessage && ['WAITING_INPUT', 'STOPPING'].includes(run.state) && <p role="alert">{run.waitingMessage}</p>}{s.disconnected && !terminal && <p role="status">实时连接中断，正在通过状态接口同步进度。</p>}</>}>
    {run && <div className="w3-run-content"><section className="w3-card" aria-label="任务进度"><header><h2>{documentTemplateStateLabel(run.state)}</h2><RunActions owner={owner} actions={[...(run.canResume ? [{ action: 'resume' as const }] : []), ...(run.canCancel ? [{ action: 'cancel' as const }] : [])]} /></header>
      {!run.uploadReady && <p>文档尚未保存完整。若上传已中断，请回到模板入口选择原文件重试。</p>}
      <div className="w3-facts"><span>文档 {run.files.length} 份</span>{run.sourceKind === 'DOCUMENT_SOURCE' && <span>原文版本 {run.sourceRevision}</span>}{(run.sourceKind !== 'DOCUMENT_SOURCE' || run.requirementRevision > 0) && <span>{run.sourceKind === 'DOCUMENT_SOURCE' ? '评审条目' : '已复核需求'} {run.progress.requirements} 项</span>}{run.templateId !== 'REQUIREMENT_DEVELOPMENT' && <span>并发上限 {run.analysisConcurrency ?? 1}</span>}<span>分析尝试 {run.progress.validated}/{run.progress.attempts} 已完成</span>{!!run.progress.active && <span>{run.progress.active} 次分析处理中</span>}</div>
      {run.templateId === 'REQUIREMENT_CODE_REVIEW' && <p>{run.state === 'COMPLETED' ? '静态评审已完成；需求是否满足以逐项结论为准。' : '按功能检查冻结代码及相关依赖。'}本次未执行构建或测试。</p>}
      <RunLinks run={run} props={props} source={false} />{run.snapshotSha && <details><summary>评审代码版本</summary><code>{run.snapshotSha}</code></details>}
    </section>
    <section className="w3-card" aria-label="上传文档"><h2>文档与提取局限</h2>{run.files.map(file => <details key={file.id}><summary>{file.filename} · {file.sectionCount} 段 · {file.limitations.length} 项提取局限</summary>{file.limitations.length ? <ul>{file.limitations.map((limit, index) => <li key={index}>{limit}</li>)}</ul> : <p>解析器未报告提取局限；段落处理覆盖仍需原文复核。</p>}</details>)}</section>
    {context?.templateVersion === '3' && context.templateId === 'REQUIREMENT_CODE_REVIEW' && ['ASSESSING', 'VERIFYING'].includes(context.state) && <TemplateBatchRecoveryPanel kind="document" id={run.id} revision={JSON.stringify(context.progress)} props={props} parent={owner} />}
    <DocumentSourcesPanel run={context!} props={props} parent={owner} />
    {(context?.sourceKind !== 'DOCUMENT_SOURCE' || context.requirementRevision > 0) && <DocumentRequirementsPanel run={context!} props={props} parent={owner} />}
    {context?.templateId === 'REQUIREMENT_DEVELOPMENT' && (context.state === 'WAITING_INPUT' || !context.uploadReady && (context.sourceRevision ?? context.requirementRevision) > 0) && <DocumentSupplementForm run={context} props={props} parent={owner} updated={owner.applyRun} />}
    <ArtifactsPanel kind="document" id={run.id} revision={run.progress.reports} completed={run.state === 'COMPLETED'} props={props} parent={owner} />
    {run.taskId && <details><summary>批次运行诊断</summary><TemplateSessionDiagnosticsPanel taskId={run.taskId} active={!terminal} props={props} parent={owner} onSelect={sessionKey => { void props.navigation.go({ path: `/tasks/${encodeURIComponent(run.taskId!)}`, query: { sessionKey } }) }} /></details>}
    </div>}
    <ConfirmRun owner={owner} />
  </PageChrome>
}
export function SourceTemplatePage(props: PageProps) {
  const id = runId(props), owner = useMemo(() => props.controller ?? createRunController('source', id), [props.controller, id]), s = useRunOwner(props, owner), run = s.run as SourceTemplateOverview | undefined
  const terminal = !!run && ['COMPLETED', 'CANCELLED'].includes(run.state)
  return <PageChrome title={run?.title ?? '源码模板任务'} objectKey="object.templateRun" actions={<><PageLink navigation={props.navigation} to={{ path: '/tasks', query: { type: 'template' } }}>历史任务</PageLink><UiActionButton actionKey="ui.refresh" busy={s.loading} onAction={() => { void owner.refresh() }} /></>}
    status={<><ReadNotice error={s.error} loading={s.loading} retry={() => { void owner.refresh() }} /><CommandNotice command={s.command} recover={() => { void owner.recover() }} /><ChildOperations owner={owner} />{run?.waitingMessage && ['WAITING_INPUT', 'STOPPING'].includes(run.state) && <p role="alert">{run.waitingMessage}</p>}{s.disconnected && !terminal && <p role="status">实时连接中断，正在通过状态接口刷新。</p>}</>}>
    {run && <div className="w3-run-content"><section className="w3-card" aria-label="执行进度"><header><h2>{sourceTemplateStateLabel(run.state)}</h2><RunActions owner={owner} actions={[...(run.state === 'PENDING_START' && !run.archived ? [{ action: 'start' as const }] : []), ...(run.canResume ? [{ action: 'resume' as const }] : []), ...(!terminal ? [{ action: 'cancel' as const, target: run.state === 'STOPPING' ? '继续确认取消' : undefined }] : []), ...(run.canArchive || run.archived ? [{ action: run.archived ? 'unarchive' as const : 'archive' as const }] : [])]} /></header>
      {run.state === 'PENDING_START' && <p>尚未调用模型或创建执行任务。开始后冻结源码，关联开发任务通过正式开始申请队列与目录租约。</p>}
      <p>源码：{run.sourcePath}</p>{run.snapshot && <p>已冻结 {run.snapshot.targetCount} 个处理文件{!run.snapshot.ready && '，正文尚在保存'}</p>}
      <div className="w3-facts">{run.coverage.map(item => <span key={item.status}>{sourceCoverageLabel(item.status)} {item.count}</span>)}{run.progress.map(item => <span key={item.candidateKind + item.state}>{item.candidateKind === 'SOURCE_DESIGN_REVIEW_V1' ? '复核' : '编写'} · {sourceBatchStateLabel(item.state)} {item.count} 批</span>)}</div>
      {run.testProfile && <p>测试目录：{run.testProfile.modules.flatMap(module => module.testRoots).join('、')}</p>}{run.templateId === 'DETAILED_DESIGN_WRITING' && <p>文档位置：{run.documentPath || '任务目录'} 下的本次独立子目录</p>}
      <RunLinks run={run} props={props} source />{run.snapshot?.ready && <details><summary>冻结源码依据</summary><code>{run.snapshot.manifestSha256}</code></details>}
    </section>
    {!!s.batches.length && <section className="w3-card" aria-label="编写与复核批次"><header><h2>编写与复核批次</h2>{run.canResume && <RunActions owner={owner} actions={[{ action: 'retry' }]} />}</header><ul>{s.batches.map(batch => <li key={batch.id}>{run.canResume && batch.retryable && <label><input type="checkbox" checked={s.selected.includes(batch.id)} disabled={s.command.busy} onChange={event => owner.toggleBatch(batch.id, event.target.checked)} />选择第 {batch.ordinal + 1} 批{batch.candidateKind === 'SOURCE_DESIGN_REVIEW_V1' ? '复核' : '编写'}</label>}<p>{batch.candidateKind === 'SOURCE_DESIGN_REVIEW_V1' ? '独立复核' : '详细设计编写'} · 第 {batch.ordinal + 1} 批 · 第 {batch.attempt + 1} 次 · {sourceBatchStateLabel(batch.state)}</p>{batch.errorCode && ['FAILED', 'STOPPED'].includes(batch.state) && <p>{sourceBatchFailureLabel(batch.errorCode)}</p>}</li>)}</ul>{s.next && <UiActionButton actionKey="ui.loadMore" target="批次" busy={s.batchLoading} onAction={() => { void owner.loadBatches(true) }} />}</section>}
    <SourceCoveragePanel id={run.id} revision={JSON.stringify(run.coverage)} ready={!!run.snapshot?.ready} props={props} parent={owner} />
    {run.templateId === 'DETAILED_DESIGN_WRITING' && <ArtifactsPanel kind="source" id={run.id} revision={run.version} completed={run.state === 'COMPLETED'} props={props} parent={owner} />}
    {run.taskId && <details><summary>批次运行诊断</summary><TemplateSessionDiagnosticsPanel taskId={run.taskId} active={!terminal} props={props} parent={owner} onSelect={sessionKey => { void props.navigation.go({ path: `/tasks/${encodeURIComponent(run.taskId!)}`, query: { sessionKey } }) }} /></details>}
    </div>}
    <ConfirmRun owner={owner} />
  </PageChrome>
}
