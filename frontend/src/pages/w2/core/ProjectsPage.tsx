import { Alert, Input, Spin } from 'antd'
import { useEffect, useRef, useState } from 'react'
import { UiActionButton, UiConfirmDialog } from '@/foundation/components'
import { SemanticIcon, semanticLabel } from '@/foundation/semanticRegistry'
import { activityLabel, activityTypeLabel, statusLabel, userFacingError } from '@/utils/displayLabels'
import type { AssistEvidenceSource, Project } from '@/types/domain'
import { PageChrome, PageLink, useLeaveGuard, useOwnerSnapshot, queryString, type W2PageProps } from '../shared'
import { CoreContextPanel, CoreMarkdown, CoreStatus, DirectoryField, Field } from './CoreUi'
import { CredentialsForm } from './CredentialsForm'
import { createProjectsController, stackLabel, conventionRunning, type ProjectContext } from './projectsController'
import { useCoreOwner } from './useCoreOwner'
import './core.css'

export function ProjectsPage(props: W2PageProps) {
  const [owner] = useState(() => createProjectsController(props.legacy.task))
  const state = useCoreOwner(props, owner), task = useOwnerSnapshot(props.legacy.task)
  const [expanded, setExpanded] = useState(false), [confirmAction, setConfirmAction] = useState<'stop' | 'unmanage'>(), [switching, setSwitching] = useState<{ context: ProjectContext; project?: Project; draftRevision: number }>()
  const origin = useRef<HTMLElement | null>(null)
  const token = useRef(0)
  useLeaveGuard(props, owner.canLeave)
  useEffect(() => {
    const current = ++token.current
    void props.legacy.task.loadProjects().then(() => {
      if (current === token.current) document.getElementById(`project-${queryString(props.route, 'project')}`)?.scrollIntoView?.({ block: 'center' })
    })
    return () => { token.current++ }
  }, [props.legacy.task])
  const blocked = owner.locked() || state.picking || state.loading
  const credentialBusy = owner.credentials()?.canLeave().kind === 'BLOCK'
  const availability = blocked || credentialBusy ? { kind: 'disabled' as const, reason: '请先等待、核对或恢复原项目操作。' } : { kind: 'enabled' as const }
  function open(context: ProjectContext, project?: Project) {
    const decision = owner.canLeave()
    if (decision.kind === 'BLOCK') return
    if (decision.kind === 'CONFIRM_DISCARD') { setSwitching({ context, project, draftRevision: decision.draftRevision }); return }
    origin.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    owner.open(context, project)
  }
  const selected = state.selected, draft = state.draft, context = state.context
  const title = context === 'register' ? '登记项目根目录' : context === 'document' ? '项目文档路径' : context === 'assist' ? `${selected?.name ?? ''} · Git 与 GitLab` : context === 'convention' ? '项目公约 AGENTS.md' : selected?.name ?? '项目信息'
  const running = conventionRunning(draft), latest = state.activity?.parts[state.activity.parts.length - 1]
  const text = (label: string, value: string, change: (value: string) => void) => <Field label={label}>{id => <Input id={id} aria-label={label} value={value} disabled={blocked} onChange={event => change(event.target.value)} />}</Field>
  return <div className="core-page"><PageChrome objectKey="nav.projects" title="项目登记" actions={<>
    <UiActionButton actionKey="ui.refresh" target="项目" availability={availability} onAction={() => { void props.legacy.task.loadProjects(true) }} />
    <UiActionButton actionKey="project.register" variant="primary" availability={availability} onAction={() => open('register')} />
  </>} status={<><CoreStatus state={state} recover={owner.recover} />
    {task.error && !task.usingDemo && <Alert role="alert" type="error" title="本地服务不可用" description={<><p>{userFacingError(task.error, '无法连接本地服务')}</p><UiActionButton actionKey="settings.toggleDemo" target="查看演示数据" availability={availability} onAction={props.legacy.task.activateDemo} /></>} />}
  </>} context={<CoreContextPanel open={!!context} title={title} expanded={expanded} onExpandedChange={setExpanded} readPolicy={owner.canLeave}
    returnFocus={origin} onClose={owner.close} discard={owner.discardContext}>
    {state.loading && <Spin aria-label="正在读取项目设置" />}
    {context === 'details' && selected && <>
      <p>{selected.description}</p><p className="core-path">{selected.rootPath}</p>
      {selected.repositoryRoot && selected.repositoryRoot !== selected.rootPath && <p className="core-path">Git 仓库：{selected.repositoryRoot}</p>}
      {selected.documentPath && <p className="core-path">文档：{selected.documentPath}</p>}
      <p>{stackLabel(selected)} · {selected.stackComponentCount ?? 0} 个组件</p>
      <p>{selected.taskCount} 个任务 · {selected.openDesignerSessionCount} 个待继续设计</p>
      <div className="core-actions">
        <PageLink navigation={props.navigation} to={{ path: '/template-tasks', query: { projectId: selected.id } }}><SemanticIcon semanticKey="nav.templateTasks" />{semanticLabel('nav.templateTasks')}</PageLink>
        <UiActionButton actionKey="project.configureAssist" target={selected.name} onAction={() => open('assist', selected)} />
        <UiActionButton actionKey="project.editDocumentPath" target={selected.name} onAction={() => open('document', selected)} />
        {selected.openDesignerSessionCount > 0 && <UiActionButton actionKey="designer.continue" target={selected.name} onAction={() => { void props.navigation.go({ path: '/designs', query: { projectId: selected.id } }) }} />}
        <UiActionButton actionKey="project.readConvention" target={selected.name} onAction={() => open('convention', selected)} />
        <UiActionButton actionKey="project.unmanage" target={selected.name} variant="danger" availability={availability} onAction={() => setConfirmAction('unmanage')} />
      </div>
    </>}
    {context === 'register' && <form onSubmit={event => { event.preventDefault(); void owner.register() }}>
      {text('项目名称', state.register.name, value => owner.changeRegister('name', value))}
      <DirectoryField label="项目根路径" value={state.register.rootPath} picking={state.picking} disabled={blocked} change={value => owner.changeRegister('rootPath', value)} pick={() => { void owner.pick('rootPath') }} />
      <DirectoryField label="文档路径（可选）" value={state.register.documentPath} picking={state.picking} disabled={blocked} demo={task.usingDemo} change={value => owner.changeRegister('documentPath', value)} pick={() => { void owner.pick('registerDocument') }} />
      <Field label="说明（可选）">{id => <Input.TextArea id={id} aria-label="说明（可选）" value={state.register.description} maxLength={500} rows={3} disabled={blocked} onChange={event => owner.changeRegister('description', event.target.value)} />}</Field>
      <UiActionButton actionKey="project.register" variant="primary" availability={availability} busy={state.mutation.busy} onAction={() => { void owner.register() }} />
    </form>}
    {context === 'document' && <>
      <DirectoryField label="项目文档路径" value={state.documentPath} picking={state.picking} disabled={blocked} demo={task.usingDemo} change={owner.changeDocument} pick={() => { void owner.pick('documentPath') }} />
      <p>新建模板任务默认使用此路径，留空使用任务目录。</p>
      <UiActionButton actionKey="ui.save" target="文档路径" variant="primary" availability={availability} busy={state.mutation.busy} onAction={() => { void owner.saveDocument() }} />
    </>}
    {context === 'assist' && <>
      <div className="core-toolbar" role="tablist" aria-label="Git 与 GitLab 设置">
        <button type="button" role="tab" aria-selected={state.assistTab === 'account'} aria-controls="project-git-account" disabled={blocked || credentialBusy} onClick={() => owner.set({ assistTab: 'account' })}>Git 账号</button>
        <button type="button" role="tab" aria-selected={state.assistTab === 'gitlab'} aria-controls="project-gitlab" disabled={blocked || credentialBusy} onClick={() => owner.set({ assistTab: 'gitlab' })}>GitLab 与证据</button>
      </div>
      <section id="project-git-account" role="tabpanel" hidden={state.assistTab !== 'account'}>{owner.credentials() && <CredentialsForm owner={owner.credentials()!} />}</section>
      <section id="project-gitlab" role="tabpanel" hidden={state.assistTab !== 'gitlab'}>
        <p>支持 HTTP 与 HTTPS。<PageLink navigation={props.navigation} to="/settings">在设置中维护 GitLab 接口地址</PageLink></p>
        {state.assist && <>
          {text('GitLab 仓库', state.repository, owner.changeRepository)}
          <div className="core-actions">
            <UiActionButton actionKey="project.discoverRepository" availability={availability} onAction={() => { void owner.assistAction('discover') }} />
            <UiActionButton actionKey="project.checkRepository" availability={state.assistDirty || !state.assist.config.repository ? { kind: 'disabled', reason: '请先保存仓库绑定，再检查已保存绑定。' } : availability} onAction={() => { void owner.assistAction('check') }} />
          </div>
          <p>凭证：{state.assist.credentialConfigured ? '环境变量已配置' : '未配置，请设置环境变量并重启服务'} · {state.assistDirty ? '修改待保存' : state.assist.config.projectId ? '绑定已核验' : '绑定待核验'}</p>
          {state.assist.config.name && <p>{state.assist.config.name} · {state.assist.config.checkedAt}</p>}
          <h3>证据来源</h3>
          {state.sources.map((source, index) => <div key={index} className="core-source">
            <Field label="证据类型">{id => <select id={id} className="core-select" aria-label="证据类型" value={source.kind} disabled={blocked} onChange={event => owner.changeSource(index, { kind: event.target.value as AssistEvidenceSource['kind'] })}><option value="LOG">应用日志</option><option value="JUNIT">JUnit 报告</option></select>}</Field>
            {text('文件规则', source.pattern, value => owner.changeSource(index, { pattern: value }))}
            {source.kind === 'LOG' && <DirectoryField label="允许读取的外部日志目录" value={source.root} picking={state.picking} disabled={blocked} demo={task.usingDemo} change={root => owner.changeSource(index, { root })} pick={() => { void owner.pick(index) }} />}
            <UiActionButton actionKey="project.removeEvidenceSource" availability={availability} onAction={() => owner.removeSource(index)} />
          </div>)}
          <UiActionButton actionKey="project.addEvidenceSource" availability={state.sources.length >= 16 ? { kind: 'disabled', reason: '最多配置 16 个来源。' } : availability} onAction={owner.addSource} />
          <p className="core-field-note">配置仅供新任务使用。日志记录正式验证期间的新增内容；工具开关在辅助 MCP 工具设置中逐项启用。</p>
          <UiActionButton actionKey="ui.save" target="GitLab 与证据配置" variant="primary" availability={availability} busy={state.mutation.busy} onAction={() => { void owner.assistAction('save') }} />
        </>}
        {state.error && !state.assist && <UiActionButton actionKey="ui.retry" availability={availability} onAction={() => { void owner.retryContext() }} />}
      </section>
    </>}
    {context === 'convention' && <>
      {draft?.state === 'RUNNING' && <article role="status" aria-live="polite" aria-label="项目公约设计师正在处理">
        <h3>项目公约设计师正在处理</h3><p>{state.activity?.remoteState ? statusLabel(state.activity.remoteState) : '连接只读 AI 会话'}</p>
        <p>累计用量：{state.activity?.usage.totalTokens ?? '等待记录'}</p>
        {state.activityError && <Alert type="warning" title={state.activityError} />}
        {latest ? <><p>{activityTypeLabel(latest.type)} · {activityLabel(latest)} · {latest.status ? statusLabel(latest.status) : ''}</p><CoreMarkdown content={latest.content ?? ''} skin={props.skin} /></> : <p>{state.activity?.detail || '正在连接当前会话…'}</p>}
      </article>}
      {draft?.state === 'STOPPING' && <Alert type="info" title="正在确认只读 AI Session 已停止…" description="确认前不会把远端执行伪装成已取消。" />}
      {draft?.state === 'APPLYING' && <Alert type="info" title="正在确认写入结果…" />}
      {draft?.error && <Alert role="alert" type="error" title={userFacingError(draft.error, '项目公约操作未完成')} />}
      {draft?.normalizationNotice && <Alert role="status" type="info" title={userFacingError(draft.normalizationNotice, '内容已自动规范化')} />}
      {!draft && state.convention && (state.convention.exists ? <><p>当前项目公约 · {state.convention.loopperManaged ? '包含 Loopper 管理区块' : '现有人工公约'}</p><Input.TextArea aria-label="当前 AGENTS.md 项目公约" readOnly value={state.convention.content} autoSize={{ minRows: 10, maxRows: 26 }} /></> : <h3>暂无项目公约</h3>)}
      {draft?.content && <><p>{draft.operation === 'CREATE' ? '新建项目公约' : '更新项目公约'}</p><Input.TextArea aria-label="AGENTS.md 完整预览" readOnly value={draft.content} autoSize={{ minRows: 10, maxRows: 26 }} /></>}
      <div className="core-actions">
        {running && draft?.state !== 'APPLYING' && <UiActionButton actionKey="project.stopConvention" variant="danger" availability={availability} onAction={() => setConfirmAction('stop')} />}
        {(!draft && state.convention || draft?.state === 'FAILED' || draft?.state === 'CANCELLED') && <UiActionButton actionKey="project.generateConvention" variant="primary" availability={availability} onAction={() => { void owner.conventionAction('generate') }} />}
        {draft?.state === 'READY' && <UiActionButton actionKey="project.applyConvention" variant="primary" availability={availability} onAction={() => { void owner.conventionAction('apply') }} />}
        {state.error && !state.convention && <UiActionButton actionKey="ui.retry" availability={availability} onAction={() => { void owner.retryContext() }} />}
      </div>
    </>}
  </CoreContextPanel>}>
    <p>{task.usingDemo ? '演示数据' : '已登记项目'} · {task.projects.length} 个项目</p>
    {task.loading ? <Spin aria-label="正在读取项目" /> : task.projects.length ? <section className="core-projects">
      {task.projects.map(project => <article key={project.id} id={`project-${project.id}`} className="core-project" aria-selected={selected?.id === project.id}>
        <h2 className="core-project-title"><SemanticIcon semanticKey="object.project" />{project.name}</h2>
        <p>{project.status === 'INVALID' ? '路径不可用' : project.executionMode === 'WORKTREE' ? 'Git 分支模式' : '直接模式'}</p>
        <p className="core-path">{project.rootPath}</p><p>{project.taskCount} 个任务 · {project.openDesignerSessionCount} 个待继续设计</p>
        <p>{stackLabel(project)} · {project.stackComponentCount ?? 0} 个组件</p>
        <p className="core-path">{project.executionMode === 'WORKTREE' ? project.branch : project.executionMode === 'UNAVAILABLE' ? '目录不可访问' : '原项目目录'}</p>
        <UiActionButton actionKey="selection.select" target={project.name} expanded={selected?.id === project.id} onAction={() => open('details', project)} />
      </article>)}
    </section> : <section className="core-empty"><h2>尚未登记项目</h2><UiActionButton actionKey="project.register" onAction={() => open('register')} /></section>}
    <UiConfirmDialog open={!!switching} title="放弃当前修改？" confirmActionKey="ui.discardChanges" policy={owner.canLeave().kind === 'BLOCK' ? { kind: 'block', reason: '原操作尚未确认，请先恢复。' }
      : switching && owner.canLeave().kind === 'CONFIRM_DISCARD' && (owner.canLeave() as { draftRevision: number }).draftRevision !== switching.draftRevision ? { kind: 'block', reason: '草稿已更新，请重新确认最新修改。' } : { kind: 'allow' }}
      onCancel={() => setSwitching(undefined)} onConfirm={() => { const decision = owner.canLeave(); if (switching && decision.kind !== 'BLOCK' && (decision.kind === 'ALLOW' || decision.draftRevision === switching.draftRevision)) { const next = switching; owner.discardContext(); owner.open(next.context, next.project); setSwitching(undefined) } }}>
      当前草稿尚未保存，切换项目或配置入口将放弃这份草稿。
    </UiConfirmDialog>
    <UiConfirmDialog open={!!confirmAction} title={confirmAction === 'stop' ? '停止生成项目公约？' : '取消管理该项目？'} confirmActionKey={confirmAction === 'stop' ? 'project.stopConvention' : 'project.unmanage'}
      policy={blocked || credentialBusy ? { kind: 'block', reason: '请先等待、核对或恢复原项目操作。' } : { kind: 'allow' }} onCancel={() => setConfirmAction(undefined)}
      onConfirm={() => { if (!blocked && !credentialBusy) { const action = confirmAction; setConfirmAction(undefined); if (action === 'stop') void owner.conventionAction('stop'); else if (selected) void owner.unmanage(selected) } }}>
      {confirmAction === 'stop' ? 'Loopper 会先保存停止意图，再确认只读 AI Session 已终止；确认前状态会保持“正在停止”。'
        : `取消管理“${selected?.name ?? ''}”后，它会从项目登记列表消失；项目目录、历史任务、设计对话、LoopSpec 与执行证据都不会删除。`}
    </UiConfirmDialog>
  </PageChrome></div>
}
