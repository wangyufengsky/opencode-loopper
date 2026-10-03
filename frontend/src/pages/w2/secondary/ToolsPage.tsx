import { useEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import type { McpPolicyCatalog, McpServerInfo, McpToolPolicy, SkillDocument, SkillSummary } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import { UiActionButton, UiConfirmDialog, UiContextPanel } from '@/foundation/components'
import { PageChrome, useLeaveGuard } from '../shared'
import type { W2PageProps } from '../shared/types'
import { MutationFeedback, ReadFeedback } from './feedback'
import { useLatestRead, useMutationOwner } from './state'
import { SkillMarkdown } from './SkillMarkdown'
import { confirmsPolicy } from './recovery'
import type { ReadContext } from '@/foundation/contracts/receipt'
import './secondary.css'

const auxiliary: McpServerInfo = { id: '@loopper-assist', name: 'Loopper 内网辅助工具', status: 'unknown', type: 'remote' }
const statusLabels: Record<string, string> = { connected: '已连接', disabled: '已停用', failed: '连接失败', needs_auth: '等待授权', needs_client_registration: '等待注册', unknown: '状态未知' }
export function ToolsPage(props: W2PageProps) {
  const [project, setProject] = useState(''), projectRef = useRef(project), [tab, setTab] = useState('tools'), [search, setSearch] = useState('')
  const [server, setServer] = useState<McpServerInfo | null>(null), [catalog, setCatalog] = useState<McpPolicyCatalog | null>(null), [catalogError, setCatalogError] = useState(''), [catalogBusy, setCatalogBusy] = useState(false), [disableSource, setDisableSource] = useState(false)
  const [skill, setSkill] = useState<SkillSummary | null>(null), [document, setDocument] = useState<SkillDocument | null>(null), [documentBusy, setDocumentBusy] = useState(false), [documentError, setDocumentError] = useState(''), [raw, setRaw] = useState(false), trigger = useRef<HTMLElement | null>(null)
  const catalogs = useRef<Record<string, McpPolicyCatalog>>({})
  const generation = useRef(0), docGeneration = useRef(0), mounted = useRef(true)
  const command = useMutationOwner(props); useLeaveGuard(props, () => command.owner.canLeave())
  const projects = useLatestRead(api.getProjects, [] as Awaited<ReturnType<typeof api.getProjects>>)
  const servers = useLatestRead(() => api.getMcpServers(projectRef.current), { servers: [] as McpServerInfo[], checkedAt: '', complete: true })
  const skills = useLatestRead(() => api.getSkills(projectRef.current), { skills: [] as SkillSummary[], checkedAt: '', complete: true })
  useEffect(() => { mounted.current = true; void projects.reload(); return () => { mounted.current = false; generation.current++; docGeneration.current++ } }, [])
  useEffect(() => {
    projectRef.current = project; catalogs.current = {}; generation.current++; docGeneration.current++; setServer(null); setCatalog(null); setCatalogError(''); setCatalogBusy(false); setSkill(null); setDocument(null); setDocumentError(''); setDocumentBusy(false); setSearch('')
    if (tab === 'tools') void servers.reload(); else void skills.reload()
  }, [project, tab])
  const loadCatalog = async (item: McpServerInfo, context?: ReadContext) => {
    if (context && !context.isCurrent()) return
    const ticket = ++generation.current, originalProject = projectRef.current
    setServer(item); setCatalog(null); setCatalogError(''); setCatalogBusy(true)
    try { const result = await api.getMcpToolPolicies(originalProject, item.id); if (mounted.current && ticket === generation.current && (!context || context.isCurrent())) { catalogs.current[item.id] = result; setCatalog(result); return true } }
    catch (failure) { if (mounted.current && ticket === generation.current && (!context || context.isCurrent())) setCatalogError(userFacingError(failure, '工具读取失败，请重试')) }
    finally { if (mounted.current && ticket === generation.current && (!context || context.isCurrent())) setCatalogBusy(false) }
    return false
  }
  const openSkill = async (item: SkillSummary) => {
    const ticket = ++docGeneration.current, originalProject = projectRef.current
    setSkill(item); setDocument(null); setDocumentError(''); setDocumentBusy(true); setRaw(false)
    try { const result = await api.getSkillDocument(originalProject, item.name); if (mounted.current && ticket === docGeneration.current) setDocument(result) }
    catch (failure) { if (mounted.current && ticket === docGeneration.current) setDocumentError(userFacingError(failure, '文档读取失败，请重试')) }
    finally { if (mounted.current && ticket === docGeneration.current) setDocumentBusy(false) }
  }
  const update = (row: McpToolPolicy, value: number) => {
    if (!server || !catalog?.complete || !row.configurable || command.blocked) return
    const original = server, originalProject = project
    void command.owner.run('/runtime/tool-policies', { projectId: originalProject, serverId: original.id, toolName: row.name, enabled: value, version: originalProject ? row.projectVersion : row.globalVersion }, body => api.updateMcpToolPolicy(body), async (_receipt, context) => { if (context.isCurrent() && originalProject === projectRef.current) { const read = await loadCatalog(original, context); if (context.isCurrent() && !read) throw new Error('原权限写入已接受，但清单未能读取，请仅核对原结果') } }, 'PUT', { kind: 'READ_ORIGINAL', readOriginal: async identity => confirmsPolicy(await api.getMcpToolPolicies(originalProject, original.id), originalProject, [{ name: identity.body.toolName, enabled: identity.body.enabled, version: identity.body.version }]) })
  }
  const allServers = [...new Map([auxiliary, ...servers.value.servers].map(item => [item.id, item])).values()]
  const needle = search.trim().toLocaleLowerCase()
  const visibleServers = allServers.filter(item => !needle || item.name.toLocaleLowerCase().includes(needle) || (catalogs.current[item.id]?.tools.some(tool => `${tool.name} ${tool.description ?? ''}`.toLocaleLowerCase().includes(needle))))
  const visibleSkills = (skills.loading ? [] : skills.value.skills).filter(item => `${item.name} ${item.description}`.toLocaleLowerCase().includes(needle))
  const canDisable = server && !server.id.startsWith('@loopper-') && catalog?.complete && catalog.tools.length > 0 && catalog.tools.every(row => row.configurable)
  return <PageChrome title="工具与 Skill" objectKey="nav.tools" actions={<UiActionButton actionKey="tools.refresh" busy={tab === 'tools' ? servers.loading : skills.loading} onAction={() => { if (tab === 'tools') void servers.reload(); else { setSkill(null); setDocument(null); docGeneration.current++; void skills.reload() } }} />}
    status={<><MutationFeedback owner={command.owner} /><ReadFeedback error={tab === 'tools' ? servers.error : skills.error} loading={tab === 'tools' ? servers.loading : skills.loading} retry={() => { void (tab === 'tools' ? servers.reload() : skills.reload()) }} />{tab === 'tools' && !servers.value.complete && <p role="alert">MCP 服务数量超过单次展示上限，当前列表未完整加载</p>}{tab === 'skills' && !skills.value.complete && <p role="status">Skill 数量超过单次展示上限，当前为部分结果。</p>}</>}
    context={<><UiContextPanel open={!!server || !!skill} title={server?.name ?? skill?.name ?? '工具'} returnFocus={trigger} closePolicy={command.blocked ? { kind: 'block', reason: '原权限修改尚未确认，请先核对结果' } : { kind: 'allow' }} onClose={() => { generation.current++; docGeneration.current++; setServer(null); setSkill(null); setDocument(null) }}>
      {server && <><ReadFeedback error={catalogError || (catalog && !catalog.complete ? catalog.detail || '清单不完整，暂不能修改权限' : '')} loading={catalogBusy} retry={() => { void loadCatalog(server) }} />{canDisable && <UiActionButton actionKey="tools.disableSource" variant="danger" availability={command.blocked ? { kind: 'disabled', reason: '请先核对原操作' } : { kind: 'enabled' }} onAction={() => setDisableSource(true)} />}
        {catalog?.tools.map(row => <article key={row.name} className="w2-secondary-rule policy"><strong>{row.name}</strong><p>{row.description}</p><p>{row.writes ? '写入任务产物' : server.id === '@loopper-assist' ? '只读' : '按角色授权调用'} · {row.enabled ? '启用' : '停用'} · {row.source === 'SYSTEM' ? '系统必需' : row.source === 'PROJECT' ? '项目配置' : '全局默认'}</p>
          {!row.configurable || !catalog.complete ? <span>{row.source === 'SYSTEM' ? '系统必需，不可关闭' : '清单不完整，不可配置'}</span> : project ? <label>{row.name} 工具策略<select aria-label={`${row.name} 工具策略`} value={row.projectOverride === 'INHERIT' ? -1 : row.projectOverride === 'ENABLED' ? 1 : 0} disabled={command.blocked} onChange={event => update(row, Number(event.target.value))}><option value={-1}>继承全局</option><option value={1}>启用</option><option value={0}>停用</option></select></label> : <label><input type="checkbox" checked={row.globalEnabled} aria-label={`${row.name} 工具策略`} disabled={command.blocked} onChange={event => update(row, event.target.checked ? 1 : 0)} />全局默认</label>}
        </article>)}{catalog?.complete && !catalog.tools.length && <p>此服务未提供工具。</p>}</>}
      {skill && <section className="skill-document" aria-label="Skill 文档"><p>{skill.location}</p><ReadFeedback error={documentError} loading={documentBusy} retry={() => { void openSkill(skill) }} />{document && <><UiActionButton actionKey={raw ? 'tools.previewSkill' : 'tools.rawSkill'} onAction={() => setRaw(!raw)} />{!document.content ? <p>此 Skill 的 Markdown 正文为空。</p> : raw ? <pre className="w2-secondary-source skill-source" tabIndex={0} aria-label="Markdown 源文">{document.content.split('\n').map((line, index) => `${index + 1}  ${line}`).join('\n')}</pre> : <SkillMarkdown content={document.content} skin={props.skin} />}</>}</section>}
    </UiContextPanel><UiConfirmDialog open={disableSource && !!server} title="停用 MCP 来源" confirmActionKey="tools.disableSource" policy={command.blocked || !canDisable ? { kind: 'block', reason: '请先核对完整清单和原操作' } : { kind: 'allow' }} onCancel={() => setDisableSource(false)} onConfirm={() => { if (!server || !catalog || !canDisable || command.blocked) return; const original = server, originalProject = project; setDisableSource(false); void command.owner.run('/runtime/tool-policies/disable-source', { projectId: project, serverId: server.id, tools: catalog.tools.map(row => ({ toolName: row.name, version: project ? row.projectVersion : row.globalVersion })) }, body => api.disableMcpSource(body as Parameters<typeof api.disableMcpSource>[0]), async (_receipt, context) => { if (context.isCurrent() && originalProject === projectRef.current) { const read = await loadCatalog(original, context); if (context.isCurrent() && !read) throw new Error('原权限写入已接受，但清单未能读取，请仅核对原结果') } }, 'POST', { kind: 'READ_ORIGINAL', readOriginal: async identity => confirmsPolicy(await api.getMcpToolPolicies(originalProject, original.id), originalProject, identity.body.tools.map(row => ({ name: row.toolName, version: row.version, enabled: 0 }))) }) }}>仅影响新建会话；已运行会话保持冻结权限。{project ? '仅当前项目生效。' : '项目单独启用的配置保留。'}</UiConfirmDialog></>}>
    <div className="w2-secondary-grid"><div className="w2-secondary-toolbar"><label>工具所属项目<select value={project} disabled={command.blocked} onChange={event => setProject(event.target.value)}><option value="">全局运行环境</option>{projects.value.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label>{tab === 'tools' ? '搜索 MCP 和已读取工具' : '搜索 Skill'}<input value={search} onChange={event => setSearch(event.target.value)} placeholder="名称或说明" /></label></div>
      <div role="tablist" aria-label="工具类别" onKeyDown={event => { if (command.blocked || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return; event.preventDefault(); const next = event.key === 'Home' ? 'tools' : event.key === 'End' ? 'skills' : tab === 'tools' ? 'skills' : 'tools'; setTab(next); (event.currentTarget.querySelector(`#tab-${next}`) as HTMLElement)?.focus() }}><button id="tab-tools" role="tab" tabIndex={tab === 'tools' ? 0 : -1} aria-selected={tab === 'tools'} disabled={command.blocked} onClick={() => setTab('tools')}>工具</button><button id="tab-skills" role="tab" tabIndex={tab === 'skills' ? 0 : -1} aria-selected={tab === 'skills'} disabled={command.blocked} onClick={() => setTab('skills')}>技能（Skill）</button></div>
      <p className="w2-secondary-muted">全局设置作为默认值，项目设置可覆盖。只影响新建会话；已运行会话保持冻结权限。</p>
      {tab === 'tools' ? <><ul className="w2-secondary-list" aria-label="MCP 服务列表">{visibleServers.map(item => <li key={item.id}><button className="w2-secondary-select tool-server" aria-pressed={server?.id === item.id} disabled={command.blocked} onClick={event => { trigger.current = event.currentTarget; void loadCatalog(item) }}><strong>{item.name}</strong><small>{statusLabels[item.status] ?? '状态未知'} · {item.type === 'local' ? '本地' : item.type === 'remote' ? '远程' : ''}</small></button></li>)}</ul>{!servers.loading && !servers.error && !visibleServers.length && <p>没有匹配的服务或已读取工具</p>}{servers.value.checkedAt && <p className="w2-secondary-muted">最近读取：{new Date(servers.value.checkedAt).toLocaleString('zh-CN')}</p>}</>
        : <><ul className="w2-secondary-list" aria-label="Skill 列表">{visibleSkills.map(item => <li key={item.name}><button className="w2-secondary-select skill-card" aria-pressed={skill?.name === item.name} onClick={event => { trigger.current = event.currentTarget; void openSkill(item) }}><strong>{item.name}</strong><small>{item.description || '未提供说明'}</small></button></li>)}</ul>{!skills.loading && !skills.error && !visibleSkills.length && <p>{search ? '没有匹配的 Skill' : '当前范围暂无 Skill'}</p>}{skills.value.checkedAt && <p>最近读取：{new Date(skills.value.checkedAt).toLocaleString('zh-CN')}</p>}</>}
    </div>
  </PageChrome>
}
