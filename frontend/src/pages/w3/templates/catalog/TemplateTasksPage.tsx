import { Alert, Input, Tag } from 'antd'
import { useEffect, useState } from 'react'
import { UiActionButton, UiConfirmDialog } from '@/foundation/components'
import { SemanticIcon } from '@/foundation/semanticRegistry'
import { PageChrome, PageLink, queryString, useLeaveGuard, useOwnerSnapshot, useRetainedOwner } from '@/pages/w2/shared'
import type { W2PageProps } from '@/pages/w2/shared/types'
import { DirectoryField, Field } from '@/pages/w2/core/CoreUi'
import { createTemplateCatalogController } from './controller'
import { CreationRecovery } from './CreationRecovery'
import { HistoryDrawer } from './HistoryDrawer'
import './catalog.css'

export function TemplateTasksPage(props: W2PageProps) {
  const [owner] = useState(() => createTemplateCatalogController(queryString(props.route, 'projectId')))
  const s = useOwnerSnapshot(owner), creation = owner.currentCreation(), c = useOwnerSnapshot(creation)
  const [switching, setSwitching] = useState<{ id: string; revision: number }>()
  useRetainedOwner(props, owner, () => owner.retire(true)); useLeaveGuard(props, owner.canLeave)
  useEffect(() => { void owner.initialize() }, [owner])
  const blocked = owner.blocked(), definition = owner.definition(), kind = owner.kind()
  const filtered = owner.templates().filter(t => (s.category === '全部' || t.category === s.category) && `${t.title} ${t.description}`.toLowerCase().includes(s.templateQuery.toLowerCase()))
  const categories = ['全部', ...new Set(owner.templates().map(t => t.category).filter((v): v is string => !!v))]
  async function handoff() {
    const target = creation.getSnapshot().destination
    if (!target || creation.getSnapshot().startUnknown || creation.getSnapshot().busy) return
    const permit = creation.prepareHandoff()
    if (await props.navigation.go(target, false, permit)) creation.completeHandoff()
  }
  async function submit() { const id = await owner.submit(); if (id) await handoff() }
  const switchReason = blocked ? '原操作结果尚未确认，请先恢复。' : switching && switching.revision !== s.draftRevision ? '草稿已更新，请重新选择并确认。' : ''
  return <PageChrome title="任务模板" objectKey="nav.templateTasks" actions={<>
    <PageLink navigation={props.navigation} to={{ path: "/tasks", query: { type: "template" } }}>历史任务</PageLink><UiActionButton actionKey="automation.readArchive" onAction={() => { void owner.history.open() }} />
  </>} status={<>{s.error && <Alert role="alert" title={s.error} type="error" />}{s.dirty && <p role="status">模板参数尚未提交；离开前会确认。</p>}{Object.entries(owner.creations).map(([kind,child]) => <CreationRecovery key={kind} owner={child} props={props} label={kind==='document' ? '文档模板创建' : kind==='source' ? '源码模板创建' : '评审模板创建'}/>)}</>} context={<HistoryDrawer owner={owner.history} props={props} />}>
    <div className="template-catalog-react" data-w3-workspace="template-tasks"><section className="catalog-list" aria-label="模板目录">
      <Input aria-label="搜索模板" placeholder="搜索模板" value={s.templateQuery} onChange={event => owner.filter('templateQuery', event.target.value)} />
      <select aria-label="模板分类" value={s.category} onChange={event => owner.filter('category', event.target.value)}>{categories.map(value => <option key={value}>{value}</option>)}</select>
      {s.loading && <p role="status">正在读取模板目录…</p>}{filtered.map(t => <button key={t.id} type="button" className={`catalog-choice ${s.selected === t.id ? 'selected' : ''}`} aria-label={t.title} aria-pressed={s.selected === t.id} disabled={blocked} onClick={() => { if (s.selected === t.id) return; if (s.dirty || s.files.length) setSwitching({ id: t.id, revision: s.draftRevision }); else void owner.select(t.id) }}><SemanticIcon semanticKey="object.template" /><strong>{t.title}</strong><span>{t.description}</span><small>{t.category} · 版本 {t.version}</small></button>)}
      <PageLink navigation={props.navigation} to={{ path: '/requirements/new', query: { template: 'builtin.workflow.development', projectId: s.projectId || undefined } }}>新建需求开发</PageLink>
    </section><section className="catalog-inputs" aria-label="模板参数">{definition && <><h2>{definition.title}</h2><p>{definition.description}</p><p>{definition.stages?.join(' → ')}</p>{kind === 'source' && <div aria-label="任务产出">{definition.inputs?.testOutputPath ? <><Tag>单元测试</Tag><Tag>测试验证与评审</Tag></> : <><Tag>Markdown 文档</Tag><Tag>流程图</Tag><Tag>源码覆盖清单</Tag></>}</div>}<form onSubmit={event => { event.preventDefault(); void submit() }}>
      <Field label="项目">{id => <><Input id={id} aria-label="项目" value={s.projectQuery} disabled={blocked} placeholder="搜索项目" onChange={event => { void owner.projects(event.target.value) }} /><select aria-label="选择项目" value={s.projectId} disabled={blocked || s.loadingProjects} onChange={event => { void owner.project(event.target.value) }}><option value="">选择项目</option>{s.projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select>{s.projectCursor && <UiActionButton actionKey="ui.loadMore" target="项目" onAction={() => { void owner.projects(s.projectQuery, true) }} />}</>}</Field>
      {owner.needsBranch() && <Field label="分支">{id => <><Input aria-label="搜索分支" value={s.branchQuery} disabled={blocked} onChange={event => { void owner.branches(event.target.value) }} /><select id={id} aria-label="分支" value={s.branchId} disabled={blocked || s.loadingBranches || !s.projectId} onChange={event => owner.change('branchId', event.target.value)}><option value="">选择分支</option>{s.branches.map(b => <option key={b.id} value={b.id}>{b.label}</option>)}</select>{s.branchCursor && <UiActionButton actionKey="ui.loadMore" target="分支" onAction={() => { void owner.branches(s.branchQuery, true) }} />}{s.branchError && <Alert title={s.branchError} type="error" />}{!s.remoteAvailable && <p role="status">远程分支不可用，仍可选择已读取的本地分支。</p>}{s.remoteProblems.map((problem,i) => <p key={i}>{problem}</p>)}</>}</Field>}
      {definition.workflow === 'SNAPSHOT_CODE_REVIEW' && <Field label="评审模式">{id => <select id={id} aria-label="评审模式" value={s.reviewMode} disabled={blocked} onChange={event => owner.change('reviewMode', event.target.value)}><option value="DATE_INCREMENTAL">日期增量</option><option value="FULL">全量评审</option></select>}</Field>}
      {owner.needsDates() && <><Field label="开始日期">{id => <input id={id} aria-label="开始日期" type="date" value={s.startDate} disabled={blocked} onChange={event => owner.change('startDate', event.target.value)} />}</Field><Field label="结束日期" hint="包含结束日期当天 24:00 之前的提交。">{id => <input id={id} aria-label="结束日期" type="date" value={s.endDate} disabled={blocked} onChange={event => owner.change('endDate', event.target.value)} />}</Field>{owner.dateError() && <p role="alert">{owner.dateError()}</p>}</>}
      {kind === 'source' ? <><DirectoryField label="源码路径" value={s.sourcePath} picking={s.picking === 'sourcePath'} disabled={blocked || s.checking} change={value => owner.change('sourcePath', value)} pick={() => { void owner.pick('sourcePath') }} />
        {definition.inputs?.testOutputPath && <DirectoryField label="测试输出路径" value={s.testPath} picking={s.picking === 'testPath'} disabled={blocked || s.checking} change={value => owner.change('testPath', value)} pick={() => { void owner.pick('testPath') }} />}
        {definition.inputs?.documentOutputPath && <DirectoryField label="文档生成路径" value={s.documentPath} picking={s.picking === 'documentPath'} disabled={blocked || s.checking} change={value => owner.change('documentPath', value)} pick={() => { void owner.pick('documentPath') }} />}
        <Field label="补充要求">{id => <Input.TextArea id={id} aria-label="补充要求" value={s.requirements} maxLength={8000} disabled={blocked || s.checking} onChange={event => owner.change('requirements', event.target.value)} />}</Field>
        <UiActionButton actionKey="template.checkScope" busy={s.checking} availability={blocked || !s.sourcePath.trim() || !s.projectId || !!s.picking ? { kind: 'disabled', reason: '请先填写项目与源码路径，并等待当前操作。' } : { kind: 'enabled' }} onAction={() => { void owner.preview() }} />
        {s.preview && <section aria-label="处理范围"><p>目标 {s.preview.targetCount} · 排除 {s.preview.excludedCount} · 模块 {s.preview.moduleCount}</p><p>SHA-256：{s.preview.manifestSha256}</p>{s.preview.configurationProblem && <p role="alert">{s.preview.configurationProblem}</p>}{s.preview.truncated && <p>范围未完整展示，请核对清单。</p>}<details><summary>查看文件和测试配置</summary><pre>{JSON.stringify({files:s.preview.files,testProfile:s.preview.testProfile}, null, 2)}</pre></details></section>}</>
      : kind === 'document' ? <><p>支持 DOCX、Markdown、文本 PDF；最多 10 份，每份 20 MiB，总计 50 MiB。保持文件顺序。</p><input aria-label="需求文档" type="file" multiple accept=".docx,.md,.markdown,.pdf" disabled={blocked} onChange={event => { owner.chooseFiles(Array.from(event.target.files ?? [])); event.target.value = '' }} />{s.files.map((file,index) => <p key={`${index}:${file.name}`}>{index+1}. {file.name} · {file.size} bytes <UiActionButton actionKey="ui.delete" target={file.name} availability={blocked ? {kind:'disabled',reason:'原上传结果尚未确认。'} : {kind:'enabled'}} onAction={() => owner.removeFile(index)} /></p>)}{owner.fileError() && <p role="alert">{owner.fileError()}</p>}</>
      : <DirectoryField label="文档生成路径" value={s.documentPath} picking={s.picking === 'documentPath'} disabled={blocked} change={value => owner.change('documentPath', value)} pick={() => { void owner.pick('documentPath') }} />}
      <UiActionButton actionKey={kind === 'report' ? 'template.startReview' : 'template.create'} variant="primary" busy={c.busy} availability={owner.valid() ? {kind:'enabled'} : {kind:'disabled',reason:'请完成必填参数或处理原操作结果。'}} onAction={() => { void submit() }} />
    </form></>}{s.catalog && <details><summary>评分规则与模板说明</summary>{definition?.scoringVersion === 'CONTRIBUTION_SCORE_V1' && <p>数量 30 分，以有效增删行进行对数归一化；价值 25、难度 15、质量 20、维护 10 分。模型给出有证据支持的等级，系统计算总分与排名。同分同名次。</p>}<p>{s.catalog.scoreFormula}</p><p>评分版本 {s.catalog.scoringVersion} · 时区 {s.catalog.timezone}</p>{s.catalog.dimensions.map(d => <article key={d.title}><h3>{d.title} <Tag>{d.weight}</Tag></h3><ul>{d.levels.map(level => <li key={level}>{level}</li>)}</ul></article>)}<p>本流程按冻结合同分析，不将未变更代码计作新增贡献。</p></details>}</section></div>
    <UiConfirmDialog open={!!switching} title="切换模板并放弃当前参数？" confirmActionKey="ui.discardChanges" policy={switchReason ? {kind:'block',reason:switchReason} : {kind:'allow'}} onCancel={() => setSwitching(undefined)} onConfirm={() => { if (!switching || blocked || switching.revision !== s.draftRevision) return; void owner.select(switching.id, true); setSwitching(undefined) }}>未提交参数和文档不会带入另一模板。</UiConfirmDialog>
  </PageChrome>
}
