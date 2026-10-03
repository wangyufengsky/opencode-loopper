import { useRef, useState } from 'react'
import { Input, Select } from 'antd'
import { UiActionButton, UiConfirmDialog, UiContextPanel, UiField, UiSelectableList } from '@/foundation/components'
import { semanticLabel, semanticName } from '@/foundation/semanticRegistry'
import { PageChrome } from '@/pages/w2/shared'
import type { W2PageProps } from '@/pages/w2/shared/types'
import type { RoleFieldChange, RoleRevision } from '@/types/domain'
import { RoleDiagram } from '@/react/diagrams/RoleDiagram'
import { rolePrompt } from '@/utils/rolePrompt'
import { roleToolDescription, roleToolStatus, stableToolName, workflowForSlot } from '@/utils/rolePresentation'
import { formatDateTime } from '@/utils/dateTime'
import { unresolved } from '../workflow/controllerCore'
import { CommandNotice, focusSelection, ReadNotice, usePageOwner } from '../workflow/pageParts'
import { createRoleManagementController, type RoleTab } from './roleManagementController'
import { availableRoleSlots, diagnostic, formatValue, importChanges, permissionMode, roleTools } from './rolePresentation'

function PromptDocument({ revision }: { revision: RoleRevision }) {
  const prompt = rolePrompt(revision)
  return <section aria-label="Prompt 全文">
    {typeof revision.manifest.workInstructions === 'string' && revision.manifest.workInstructions && <><h3>工作节点专业说明</h3><pre aria-label="工作节点专业说明">{revision.manifest.workInstructions}</pre></>}
    <h3>Prompt 配置全文</h3><p>静态内容合并展示 · 按工作流选用</p>
    {!!prompt.variables.length && <dl aria-label="模板变量说明">{prompt.variables.map(variable => <div key={variable.name}><dt><code>{`{${variable.name}}`}</code></dt><dd>{variable.description}</dd></div>)}</dl>}
    {prompt.text ? <pre>{prompt.text}</pre> : <p>此版本没有静态 Prompt 内容。</p>}
  </section>
}
function Changes({ changes, before = '当前', after = '导入后' }: { changes: RoleFieldChange[]; before?: string; after?: string }) {
  return <section className="w2-diff" aria-label="配置字段差异"><h3>字段差异</h3>{changes.map((change, index) => <article key={`${change.path}-${index}`}><strong>{change.path}</strong>
    <div className="w2-diff-values"><div><span>{before}</span><pre>{formatValue(change.before)}</pre></div><div><span>{after}</span><pre>{formatValue(change.after)}</pre></div></div></article>)}</section>
}
const tabs: { key: RoleTab; label: string }[] = [{ key: 'overview', label: '描述' }, { key: 'permissions', label: '权限与 MCP' }, { key: 'prompt', label: 'Prompt 模板' }, { key: 'history', label: '版本历史' }]
export function RoleManagementPage(props: W2PageProps & { controller?: ReturnType<typeof createRoleManagementController> }) {
  const [owner] = useState(() => props.controller ?? createRoleManagementController())
  const s = usePageOwner(props, owner), trigger = useRef<HTMLElement | null>(null), fileInput = useRef<HTMLInputElement>(null)
  const [replacement, setReplacement] = useState<File | null>(null), [expanded, setExpanded] = useState(false)
  const locked = unresolved(s.command), selected = s.selected, importing = s.importOpen
  const changes = importChanges(s.importPreview), tools = roleTools(s.preview, s.revision)
  const slots = availableRoleSlots(selected, s.revision, s.bindings)
  const groups = new Map<string, typeof s.rows>()
  for (const role of s.rows) { const group = role.activeSlots[0] ? workflowForSlot(role.activeSlots[0]).name : role.groupLabel || '专项角色'; groups.set(group, [...(groups.get(group) ?? []), role]) }
  const openImport = () => { focusSelection(trigger); owner.openImport(true) }
  return <PageChrome title={semanticLabel('nav.roles')} objectKey="object.role" actions={<>
    <UiActionButton actionKey="roles.import" onAction={() => { focusSelection(trigger); fileInput.current?.click() }}
      availability={locked || s.validating ? { kind: 'disabled', reason: '请先完成或恢复原配置包操作。' } : { kind: 'enabled' }} />
    <input ref={fileInput} className="ui-visually-hidden" type="file" accept=".zip,application/zip" aria-label={semanticName('roles.import')} disabled={locked || s.validating}
      onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; if (!file) return; if (owner.getFile()) setReplacement(file); else owner.chooseImport(file) }} />
  </>} status={<><CommandNotice command={s.command} recover={() => { void owner.recover() }} />
    <ReadNotice error={s.listError} busy={s.listLoading} retry={owner.retryList}>正在读取角色…</ReadNotice>
    <ReadNotice error={s.bindingsError} retry={() => { void owner.bindings() }} /><ReadNotice error={s.projectsError} retry={() => { void owner.projects() }} />
    <ReadNotice error={s.importError} busy={s.validating} retry={() => { void owner.validate() }}>正在校验配置包与现有配置…</ReadNotice>
    {s.importSuccess && <p role="status">{s.importSuccess}</p>}
    {(s.fileName || s.importSuccess) && <div className="w2-import-state">{s.fileName && <span>已选配置包：{s.fileName} · 尚未完成发布</span>}<UiActionButton actionKey="ui.open" target="角色配置导入结果" onAction={openImport} /></div>}
    <ReadNotice error={s.detailError} busy={s.detailLoading} retry={() => { void owner.select(s.selectedId) }}>正在读取角色详情…</ReadNotice>
    <ReadNotice error={s.previewError} retry={() => { void owner.preview() }} /><ReadNotice error={s.revisionError} retry={() => { void owner.revision() }} />
    <ReadNotice error={s.historyError} retry={owner.retryHistory} /><ReadNotice error={s.historicalError} retry={() => { void owner.historical(s.historicalId) }} />
    <ReadNotice error={s.comparisonError} retry={() => { if (s.comparisonId) void owner.compare(s.comparisonId) }} /><ReadNotice error={s.exportError} retry={() => { if (s.exportRetryId) void owner.exportRevision(s.exportRetryId) }} />
  </>} context={<UiContextPanel open={importing || !!s.selectedId} title={importing ? '导入角色配置' : selected?.displayName ?? '角色详情'} returnFocus={trigger} expanded={expanded} onExpandedChange={setExpanded}
    onClose={() => importing ? owner.openImport(false) : owner.clearSelection()}>
    <div className="w2-role-detail">
      {importing ? <>
        {s.fileName && <p>已选文件：{s.fileName}</p>}
        {s.importPreview && <><h3>{s.importPreview.valid ? '校验通过，待核对差异' : '校验未通过'}</h3><p>来源摘要 {s.importPreview.sourceSha256.slice(0, 12)}…</p>
          {!!s.importPreview.diagnostics.length && <ul>{s.importPreview.diagnostics.map((item, i) => <li key={i}>{item.path || '配置包'}：{diagnostic(item.code, item.message)}</li>)}</ul>}
          <h3>角色版本</h3><ul>{s.importPreview.roles.map(role => <li key={role.roleId}>{role.displayName || '未命名角色'} · {({ NEW: '新角色', UPDATED: '更新版本', UNCHANGED: '内容未变' } as Record<string, string>)[role.change ?? ''] ?? '待核对'}</li>)}</ul>
          <h3>激活绑定</h3><ul>{s.importPreview.activations.map(binding => <li key={binding.slot}>{s.bindings.find(item => item.slot === binding.slot)?.label || '角色阶段'} → {s.importPreview!.roles.find(role => role.roleId === binding.roleId)?.displayName || '导入角色'}</li>)}</ul>
          {!s.importPreview.activations.length && <p>保留现有角色绑定</p>}
          <Changes changes={changes} />{!changes.length && <p role="alert">校验结果未提供可核对的字段差异，当前不能发布。</p>}
          <p>发布只影响新建会话；当前会话和冻结任务不会因导入获得新增权限。</p>
          {s.importPreview.valid && !!changes.length && <label><input type="checkbox" checked={s.confirmed} disabled={locked || s.validating} onChange={event => owner.confirm(event.target.checked)} />我已核对角色、权限、提示模板与激活差异</label>}
        </>}
        <div className="w2-actions"><UiActionButton actionKey="roles.validate" busy={s.validating} availability={s.fileName && !locked ? { kind: 'enabled' } : { kind: 'disabled', reason: locked ? '原发布结果尚未确认，请恢复原操作。' : '请先选择配置包。' }} onAction={() => { void owner.validate() }} />
          <UiActionButton actionKey="roles.publish" variant="primary" busy={s.command.busy} availability={owner.canPublish() ? { kind: 'enabled' } : { kind: 'disabled', reason: '请完成校验并明确核对字段差异，原操作未确认时不能开始新发布。' }} onAction={() => { void owner.publish() }} />
        </div>
      </> : selected ? <>
        <p>最新发布版本 {selected.latestRevisionNumber}</p><nav className="w2-role-tabs" aria-label="角色详情分区">{tabs.map(tab => <button type="button" key={tab.key} data-semantic="selection.select" aria-label={semanticName('selection.select', tab.label)} aria-pressed={s.tab === tab.key} onClick={() => owner.tab(tab.key)}>{tab.label}</button>)}</nav>
        {s.tab === 'overview' && <><h3>角色职责</h3><p>{selected.description}</p><p>来源：{selected.origin === 'BUILTIN' ? '内置' : selected.origin === 'IMPORTED' ? '导入' : '来源待核对'}</p>
          <h3>所属工作流与位置</h3><RoleDiagram bindings={s.bindings.filter(binding => binding.activeRoleId === selected.roleId)} latestRevisionId={selected.latestRevisionId} onRevision={owner.showBindingRevision} />
          {!s.bindings.some(binding => binding.activeRoleId === selected.roleId) && <p>当前未绑定运行阶段。</p>}
        </>}
        {s.tab === 'permissions' && <><h3>工具与访问权限</h3>
          {s.revision && (!s.preview || s.preview.revisionId === s.revision.revisionId) && <p>{permissionMode(s.revision)}</p>}
          {s.revision?.manifest.runtimePolicy === 'ACCOUNTING_COMMAND' && <p>统计辅助通过独立命令运行，工具范围由服务端固定。</p>}
          <div className="w2-actions"><Select aria-label="选择角色阶段" value={s.slot || undefined} options={slots.map(binding => ({ value: binding.slot, label: binding.label || '角色阶段' }))} onChange={slot => owner.scope(slot, s.projectId)} />
            <Select aria-label="权限预览所属项目" value={s.projectId} options={[{ value: '', label: '全局配置' }, ...s.projects.map(project => ({ value: project.id, label: project.name }))]} onChange={projectId => owner.scope(s.slot, projectId)} />
            <UiActionButton actionKey="roles.previewPermissions" busy={s.previewLoading} availability={s.slot ? { kind: 'enabled' } : { kind: 'disabled', reason: '请选择角色阶段。' }} onAction={() => { void owner.preview() }} />
          </div>
          {(s.previewLoading || s.revisionLoading) && <p role="status">正在读取工具与权限…</p>}
          {s.preview && <><p>{s.preview.bindingActive === false ? '未激活配置' : '阶段绑定配置'}{s.preview.revisionNumber ? ` · 版本 ${s.preview.revisionNumber}` : ''} · {s.preview.complete ? '信息完整' : '调用条件待运行时核定'}</p><ul>{s.preview.limitations.map((text, i) => <li key={i}>{text}</li>)}</ul></>}
          <p>配置 {tools.length} 项 · 受限 {tools.filter(tool => ['ROLE_DISABLED', 'POLICY_DISABLED', 'CONFIGURATION_BLOCKED'].includes(tool.status ?? '')).length} 项 · 待核定 {tools.filter(tool => !['ROLE_DISABLED', 'POLICY_DISABLED', 'CONFIGURATION_BLOCKED'].includes(tool.status ?? '')).length} 项</p>
          <ul className="w2-role-tools">{tools.map(tool => <li key={tool.name}><strong>{tool.description}</strong><p>{stableToolName(tool.name)} · {tool.source} · {roleToolStatus(tool.status)}{tool.required ? ' · 服务端必需' : ''}</p>{tool.reason && <p>{/[\u3400-\u9fff]/.test(tool.reason) ? tool.reason : '当前调用条件尚未满足，请核对运行环境与授权。'}</p>}</li>)}</ul>
          {s.preview && <details><summary>查看权限规则</summary><ul>{s.preview.rules.map((rule, i) => <li key={i}>{rule.action === 'allow' ? '允许' : rule.action === 'ask' ? '询问' : '拒绝'} · {stableToolName(rule.permission)} · {roleToolDescription(rule.permission)} · 范围：{rule.pattern === '*' ? '全部' : rule.pattern}</li>)}</ul>{!s.preview.rules.length && <p>当前阶段没有可展示的权限规则。</p>}</details>}
        </>}
        {s.tab === 'prompt' && <>{s.revisionLoading && <p role="status">正在读取模板…</p>}{s.revision && <><p>版本 {s.revision.revisionNumber} · {formatDateTime(s.revision.publishedAt)}</p><UiActionButton actionKey="roles.export" target="最新发布版本" busy={!!s.exportId} onAction={() => { void owner.exportRevision(s.revision!.revisionId) }} /><PromptDocument revision={s.revision} /></>}</>}
        {s.tab === 'history' && <><h3>版本历史</h3>{s.historyLoading && <p role="status">正在读取历史…</p>}<ol>{s.history.map(revision => <li key={revision.revisionId}><strong>版本 {revision.revisionNumber}</strong><p>{formatDateTime(revision.publishedAt)}</p><div className="w2-actions"><UiActionButton actionKey="roles.openRevision" target={`版本 ${revision.revisionNumber}`} onAction={() => { void owner.historical(revision.revisionId) }} /><UiActionButton actionKey="roles.export" target={`版本 ${revision.revisionNumber}`} busy={!!s.exportId} onAction={() => { void owner.exportRevision(revision.revisionId) }} />{revision.revisionId !== selected.latestRevisionId ? <UiActionButton actionKey="roles.compareRevision" onAction={() => { void owner.compare(revision.revisionId) }} /> : <span>最新发布版本</span>}</div></li>)}</ol>
          {s.historyNext && <UiActionButton actionKey="ui.loadMore" target="版本历史" busy={s.historyLoading} onAction={() => { void owner.history(s.historyNext, true) }} />}
          {s.historicalLoading && <p role="status">正在读取所选版本…</p>}{s.historical && <section aria-label="所选历史配置"><h3>版本 {s.historical.revisionNumber} 的静态配置</h3><p>这是已发布的配置版本，不是历史会话的完整 Prompt 快照。</p><p>摘要 {s.historical.contentSha256} · {formatDateTime(s.historical.publishedAt)}</p><p>权限模式：{permissionMode(s.historical)}</p>{s.historical.modelPolicy && <p>模型策略：{s.historical.modelPolicy === 'INHERIT_WORKFLOW' ? '按当前流程继承' : '未知模型策略，请检查配置清单'}</p>}<p>原生工具：{s.historical.nativeTools?.map(tool => `${tool}（${roleToolDescription(tool)}）`).join('、') || '无显式清单'}</p><p>精确 MCP：{s.historical.mcpTools?.map(tool => `${tool}（${roleToolDescription(tool)}）`).join('、') || '无显式清单'}</p><PromptDocument revision={s.historical} /></section>}
          {s.comparisonLoading && <p role="status">正在读取差异…</p>}{s.comparison && <><h3>与最新发布版本的差异</h3>{!s.comparison.changes.length && <p>没有字段差异。</p>}<Changes changes={s.comparison.changes} before="历史版本" after="最新发布版本" /></>}
        </>}
      </> : s.detailLoading ? <p role="status">正在读取角色详情…</p> : null}
    </div>
  </UiContextPanel>}>
    <form className="w2-module-toolbar" onSubmit={event => { event.preventDefault(); owner.submitSearch() }}><UiField labelKey="field.query">{field => <Input {...field} aria-label="搜索角色" placeholder="搜索角色名称或用途" value={s.search} onChange={event => owner.search(event.target.value)} />}</UiField><UiActionButton actionKey="ui.search" busy={s.listLoading} onAction={owner.submitSearch} /></form>
    {[...groups].map(([group, rows]) => <section key={group} className="w2-role-group"><h2>{group}</h2><UiSelectableList items={rows} selectedKey={s.selectedId} labelKey="object.role" getKey={role => role.roleId} getName={role => role.displayName} onSelect={role => { focusSelection(trigger); owner.openImport(false); if (role.roleId === s.selectedId) owner.clearSelection(); else void owner.select(role.roleId) }} renderItem={role => <div className="w2-record"><strong>{role.displayName}</strong><span>{role.description}</span><span>{role.activeSlots.length ? `${role.activeSlots.length} 个阶段在使用` : '暂无激活阶段'} · 最新发布版本 {role.latestRevisionNumber}</span></div>} /></section>)}
    {!s.listLoading && !s.listError && !s.rows.length && <p className="w2-empty">没有匹配的角色。</p>}
    <div className="w2-actions"><UiActionButton actionKey="ui.previous" availability={s.previous.length && !s.listLoading && !s.listError ? { kind: 'enabled' } : { kind: 'disabled', reason: '当前没有可返回的上一页。' }} onAction={owner.previous} /><UiActionButton actionKey="ui.next" availability={s.nextCursor && !s.listLoading && !s.listError ? { kind: 'enabled' } : { kind: 'disabled', reason: '当前没有可读取的下一页。' }} onAction={owner.next} /></div>
    <UiConfirmDialog open={!!replacement} title="替换未发布的配置包" confirmActionKey="ui.discardChanges" policy={locked || s.validating ? { kind: 'block', reason: '原操作尚未确认，请保留配置包。' } : { kind: 'allow' }} onCancel={() => setReplacement(null)} onConfirm={() => { if (replacement && owner.discardImport()) { owner.chooseImport(replacement); setReplacement(null) } }}>当前配置包尚未发布。放弃它并校验新配置包？</UiConfirmDialog>
  </PageChrome>
}
