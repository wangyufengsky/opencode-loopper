import { useEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import type { DatabaseConnection, DatabaseConnectionInput, DatabaseProbe } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import { UiActionButton, UiConfirmDialog, UiContextPanel, UiDisclosure } from '@/foundation/components'
import { PageChrome, useLeaveGuard } from '../shared'
import type { W2PageProps } from '../shared/types'
import { databaseBody, databaseDraft, databaseTypeLabels, databaseUrlExamples, changeDatabaseType } from './databaseDraft'
import { ReadFeedback, MutationFeedback } from './feedback'
import { useLatestRead, useMutationOwner } from './state'
import { confirmsDatabase } from './recovery'
import './secondary.css'

function ProbeResult({ result }: { result: DatabaseProbe }) {
  return <div className="w2-secondary-status" role="status"><strong>{result.connected ? '连接成功' : '连接未成功'} · {result.sessionReadOnly ? '只读标记已确认' : result.readOnlyEnforced ? '只读权限已检查' : '只读控制未通过'}</strong><p>{result.serverProduct} {result.serverVersion}</p><p>{result.detail}</p><small>完整兼容性：待现场版本联调</small></div>
}
export function DatabasePage(props: W2PageProps) {
  const [filters, setFilters] = useState({ query: '', type: '', state: 'AVAILABLE' }), applied = useRef(filters), cursor = useRef<string | undefined>(undefined), more = useRef(false)
  const [selected, setSelected] = useState<DatabaseConnection | null>(null), [editing, setEditing] = useState(false), [archive, setArchive] = useState(false), trigger = useRef<HTMLElement | null>(null)
  const [form, setForm] = useState(() => databaseDraft(null)), [password, setPassword] = useState(''), [schemas, setSchemas] = useState(''), [dirty, setDirty] = useState(false), draftRevision = useRef(0)
  const [probe, setProbe] = useState<DatabaseProbe | null>(null), [testing, setTesting] = useState(false), [probeError, setProbeError] = useState(''), [validation, setValidation] = useState(''), [advanced, setAdvanced] = useState(false)
  const probeResults = useRef(new Map<string, {version:number;result:DatabaseProbe|null;error:string}>())
  const probeSequence = useRef(0), mounted = useRef(true)
  const command = useMutationOwner(props)
  useLeaveGuard(props, () => { const risk = command.owner.canLeave(); return risk.kind !== 'ALLOW' ? risk : dirty ? { kind: 'CONFIRM_DISCARD', description: '数据库连接仍有未保存输入，是否放弃后离开？', draftRevision: draftRevision.current } : { kind: 'ALLOW' } })
  const list = useLatestRead(() => api.getDatabaseConnections(more.current ? cursor.current : undefined, applied.current), { items: [] as DatabaseConnection[], nextCursor: undefined as string | undefined, facets: {} })
  const metadata = useLatestRead(async () => { const [types, drivers, projects] = await Promise.all([api.getDatabaseTypes(), api.getDatabaseDrivers(), api.getProjects()]); return { types, drivers, projects } }, { types: [] as Awaited<ReturnType<typeof api.getDatabaseTypes>>, drivers: [] as Awaited<ReturnType<typeof api.getDatabaseDrivers>>, projects: [] as Awaited<ReturnType<typeof api.getProjects>> })
  const load = async (append = false) => { more.current = append; const old = list.value.items, result = await list.reload(result => append ? { ...result, items: [...old, ...result.items] } : result); if (result) list.apply(result, () => { cursor.current = result.nextCursor }); return result }
  useEffect(() => { mounted.current = true; void load(); void metadata.reload(); return () => { mounted.current = false; probeSequence.current++ } }, [])
  const invalidateProbe = () => { draftRevision.current++; probeSequence.current++; setProbe(null); setProbeError(''); setDirty(true) }
  const change = (next: DatabaseConnectionInput) => { if (command.blocked) return; invalidateProbe(); setForm(next) }
  const begin = (row: DatabaseConnection | null, element?: HTMLElement) => {
    if (command.blocked || dirty) return
    setSelected(row); setForm(databaseDraft(row)); setPassword(''); setSchemas(row?.config.schemas.join(', ') ?? '')
    setDirty(false); setEditing(true); setProbe(null); setValidation(''); setProbeError(''); probeSequence.current++
    if (element) trigger.current = element
  }
  const test = async () => {
    if (testing || command.snapshot.busy) return
    const original = !editing && selected ? {id:selected.id,version:selected.version} : null
    const ticket = ++probeSequence.current; setTesting(true); setProbe(null); setProbeError('')
    try {
      const result = editing ? await api.testDatabaseDraft(selected?.id ?? null, databaseBody(form, password, schemas, metadata.value.types, !!selected)) : selected ? await api.testDatabaseConnection(selected.id) : null
      if (mounted.current && ticket === probeSequence.current) { if(original)probeResults.current.set(original.id,{version:original.version,result,error:''});setProbe(result) }
    } catch (failure) { if (mounted.current && ticket === probeSequence.current) { const error=userFacingError(failure, '连接检查失败，请检查只读账号、地址和网络');if(original)probeResults.current.set(original.id,{version:original.version,result:null,error});setProbeError(error) } }
    finally { if (mounted.current) setTesting(false) }
  }
  const lookup = async (id: string | null, body: Readonly<DatabaseConnectionInput>) => {
    let cursor: string | undefined
    const visited = new Set<string>()
    do {
      const page = await api.getDatabaseConnections(cursor, { state: 'ALL' })
      const row = page.items.find(item => item.id === id)
      if (row) return confirmsDatabase(row, body) ? { kind: 'ACCEPTED' as const, receipt: row } : { kind: 'UNCONFIRMED' as const }
      cursor = page.nextCursor
      if (cursor && visited.has(cursor)) break
      if (cursor) visited.add(cursor)
    } while (cursor && mounted.current)
    return { kind: 'UNCONFIRMED' as const }
  }
  const save = () => {
    if (command.blocked || testing) return
    let body: DatabaseConnectionInput
    try { body = databaseBody(form, password, schemas, metadata.value.types, !!selected); setValidation('') } catch (failure) { setValidation(userFacingError(failure)); return }
    const id = selected?.id ?? null
    void command.owner.run(`/database-connections${id ? `/${encodeURIComponent(id)}` : ''}`, body, value => api.saveDatabaseConnection(id, value as DatabaseConnectionInput), async (_receipt, context) => {
      if (!context.isCurrent()) return
      const result = await load()
      if (!context.isCurrent()) return
      if (!result) throw new Error('原写入已接受，但连接列表未能读取，请仅重读原结果')
      context.apply(() => { setPassword(''); setDirty(false); setEditing(false); setSelected(null) })
    }, id ? 'PUT' : 'POST', { kind: 'READ_ORIGINAL', readOriginal: original => lookup(id, original.body) })
  }
  const update = (row: DatabaseConnection, archived: boolean) => {
    if (command.blocked) return
    const body: DatabaseConnectionInput = { name: row.name, config: { ...row.config }, projectIds: [...row.projectIds], version: row.version, password: null, enabled: archived ? false : !row.enabled, archived: archived || row.archived }
    void command.owner.run(`/database-connections/${encodeURIComponent(row.id)}`, body, value => api.saveDatabaseConnection(row.id, value as DatabaseConnectionInput), async (_receipt, context) => { if (!context.isCurrent()) return; const result = await load(); if (!context.isCurrent()) return; if (!result) throw new Error('原写入已接受，但连接列表未能读取，请仅重读原结果'); context.apply(() => setSelected(null)) }, 'PUT', { kind: 'READ_ORIGINAL', readOriginal: original => lookup(row.id, original.body) })
  }
  const profile = metadata.value.types.find(type => type.type === form.config.type)
  const closePolicy = command.blocked ? { kind: 'block' as const, reason: '原操作尚未确认，请先等待或核对' } : testing ? { kind: 'block' as const, reason: '正在检查连接，请等待检查完成' } : dirty ? { kind: 'confirm' as const, reason: '连接输入尚未保存，是否放弃？' } : { kind: 'allow' as const }
  const close = () => { probeSequence.current++; setSelected(null); setEditing(false); setProbe(null) }
  return <PageChrome title="数据库" objectKey="nav.databases" actions={<><UiActionButton actionKey="ui.refresh" busy={list.loading} onAction={() => { void load(); void metadata.reload() }} /><UiActionButton actionKey="database.create" variant="primary" availability={!metadata.value.types.length || command.blocked || dirty ? { kind: 'disabled', reason: '请先读取类型清单并处理当前输入' } : { kind: 'enabled' }} onAction={() => begin(null)} /></>}
    status={<><MutationFeedback owner={command.owner} /><ReadFeedback error={list.error || metadata.error} loading={list.loading && !list.value.items.length} retry={() => { void load(); void metadata.reload() }} />{dirty && <p role="status">连接输入尚未保存</p>}{(validation || probeError) && <p role="alert">{validation || probeError}</p>}</>}
    context={<><UiContextPanel open={!!selected || editing} title={editing ? selected ? '编辑数据库连接' : '新增数据库连接' : selected?.name ?? '连接'} returnFocus={trigger} closePolicy={closePolicy} onClose={close} onConfirmClose={() => { setDirty(false); setPassword(''); close() }}>
      {editing ? <form className="w2-secondary-fields connection-form" onSubmit={event => { event.preventDefault(); save() }}>
        <label>连接名称<input value={form.name} maxLength={100} disabled={command.blocked} onChange={event => change({ ...form, name: event.target.value })} /></label>
        <label>数据库类型<select value={form.config.type} disabled={command.blocked} onChange={event => change(changeDatabaseType(form, event.target.value as DatabaseConnectionInput['config']['type']))}>{metadata.value.types.map(type => <option key={type.id} value={type.type}>{type.label}</option>)}{!profile && <option value={form.config.type}>{databaseTypeLabels[form.config.type]}</option>}</select></label>
        <p className="w2-secondary-muted">{profile ? `已内置 ${profile.label} 驱动 · ${profile.binaries[0]?.filename}` : '此历史类型暂不支持新增或修改连接配置'}</p>
        {selected && profile && selected.config.driverProfile !== profile.id && <p>测试和保存将使用上述驱动；保存后供新会话使用，历史任务保留原驱动。</p>}
        <label>JDBC URL<textarea value={form.config.jdbcUrl ?? ''} disabled={command.blocked} placeholder={databaseUrlExamples[form.config.type]} onChange={event => change({ ...form, config: { ...form.config, jdbcUrl: event.target.value } })} /></label>
        {form.config.type === 'SQLSERVER' && <p>使用仅有 SELECT 权限的 SQL Server 账号；允许的 schema 通常为 dbo。TLS 默认验证服务器证书。</p>}
        <label>用户名<input value={form.config.username} disabled={command.blocked} autoComplete="off" onChange={event => change({ ...form, config: { ...form.config, username: event.target.value } })} /></label>
        <label>{selected ? '新密码' : '密码'}<input type="password" value={password} disabled={command.blocked} autoComplete="new-password" placeholder={selected ? '留空保留原密码' : '使用数据库只读账号的密码'} onChange={event => { if (!command.blocked) { invalidateProbe(); setPassword(event.target.value) } }} /></label>
        <label>{form.config.type === 'MYSQL' ? '允许访问的数据库' : '允许访问的 schema'}<input value={schemas} disabled={command.blocked} onChange={event => { if (!command.blocked) { invalidateProbe(); setSchemas(event.target.value) } }} /></label>
        <fieldset disabled={command.blocked}><legend>绑定项目</legend>{metadata.value.projects.map(project => <label key={project.id}><input type="checkbox" checked={form.projectIds.includes(project.id)} onChange={event => change({ ...form, projectIds: event.target.checked ? [...form.projectIds, project.id] : form.projectIds.filter(id => id !== project.id) })} />{project.name}</label>)}</fieldset>
        <p className="w2-secondary-muted">未绑定项目时，任务无法发现此连接。</p>
        <UiDisclosure titleKey="section.advanced" open={advanced} onOpenChange={setAdvanced}><label>查询超时（秒）<input type="number" min={1} max={30} value={form.config.timeoutSeconds} disabled={command.blocked} onChange={event => change({ ...form, config: { ...form.config, timeoutSeconds: Number(event.target.value) } })} /></label><label>最多返回行数<input type="number" min={1} max={1000} value={form.config.maxRows} disabled={command.blocked} onChange={event => change({ ...form, config: { ...form.config, maxRows: Number(event.target.value) } })} /></label></UiDisclosure>
        <label><input type="checkbox" checked={form.enabled} disabled={command.blocked} onChange={event => change({ ...form, enabled: event.target.checked })} />启用连接，供绑定项目的新会话使用</label>
        <div className="w2-secondary-actions"><UiActionButton actionKey="database.test" busy={testing} availability={command.blocked || !profile ? { kind: 'disabled', reason: '请先确认配置与原操作' } : { kind: 'enabled' }} onAction={() => { void test() }} /><UiActionButton actionKey="ui.save" variant="primary" busy={command.snapshot.busy} availability={testing || command.blocked || !profile ? { kind: 'disabled', reason: '请等待连接检查或确认当前配置' } : { kind: 'enabled' }} onAction={save} /></div>
      </form> : selected && <div className="w2-secondary-grid"><p>{selected.archived ? '已归档' : selected.enabled ? '已启用' : '已停用'} · {databaseTypeLabels[selected.config.type]}</p><dl className="w2-secondary-definition"><div><dt>访问地址</dt><dd>{selected.config.jdbcUrl || `${selected.config.host}:${selected.config.port}/${selected.config.database}`}</dd></div><div><dt>用户名</dt><dd>{selected.config.username}</dd></div><div><dt>凭据</dt><dd>{selected.passwordConfigured ? '已配置' : '未配置'}</dd></div><div><dt>驱动</dt><dd>{selected.config.driverProfile ? '内置驱动' : metadata.value.drivers.some(driver => driver.filename === selected.config.driverFile) ? '驱动已安装' : '历史驱动缺失'}</dd></div><div><dt>授权项目</dt><dd>{selected.projectIds.map(id => metadata.value.projects.find(project => project.id === id)?.name || '已登记项目').join('、') || '尚未绑定项目，任务暂时无法使用此连接。'}</dd></div><div><dt>允许范围</dt><dd>{selected.config.schemas.join('、')}</dd></div><div><dt>查询限额</dt><dd>{selected.config.maxRows} 行 / {selected.config.timeoutSeconds} 秒</dd></div></dl>
        <div className="w2-secondary-actions"><UiActionButton actionKey="database.edit" availability={selected.archived || !metadata.value.types.some(type => type.type === selected.config.type) || command.blocked ? { kind: 'disabled', reason: '历史类型或归档连接不能编辑' } : { kind: 'enabled' }} onAction={() => begin(selected)} /><UiActionButton actionKey="database.test" busy={testing} onAction={() => { void test() }} />{!selected.archived && <><UiActionButton actionKey={selected.enabled ? 'database.disable' : 'database.enable'} availability={!selected.enabled && !metadata.value.types.some(type => type.type === selected.config.type) || command.blocked ? { kind: 'disabled', reason: '请确认类型和原操作' } : { kind: 'enabled' }} onAction={() => update(selected, false)} /><UiActionButton actionKey="database.archive" variant="danger" availability={command.blocked ? { kind: 'disabled', reason: '请核对原操作' } : { kind: 'enabled' }} onAction={() => setArchive(true)} /></>}</div><p>配置变更供新会话使用，历史任务保留冻结配置。</p>
      </div>}
      {probe && <ProbeResult result={probe} />}<small>完整兼容性：待现场版本联调</small>
    </UiContextPanel><UiConfirmDialog open={archive && !!selected} title="归档连接" confirmActionKey="database.archive" policy={command.blocked ? { kind: 'block', reason: '请先核对原操作' } : { kind: 'allow' }} onCancel={() => setArchive(false)} onConfirm={() => { if (selected) update(selected, true); setArchive(false) }}>归档后新任务不能发现此连接，已冻结任务仍使用原授权。</UiConfirmDialog></>}>
    <div className="w2-secondary-grid"><p className="w2-secondary-muted">统一管理连接、项目授权与只读访问范围。</p><form className="w2-secondary-toolbar" onSubmit={event => { event.preventDefault(); applied.current = { ...filters }; void load() }}>
      <label>搜索数据库<input value={filters.query} placeholder="连接名称或主机" onChange={event => setFilters({ ...filters, query: event.target.value })} /></label><label>筛选数据库类型<select value={filters.type} onChange={event => { const next = { ...filters, type: event.target.value }; setFilters(next); applied.current = next; void load() }}><option value="">全部类型</option>{Object.entries(databaseTypeLabels).map(([type, label]) => <option key={type} value={type}>{label}</option>)}</select></label><label>筛选连接状态<select value={filters.state} onChange={event => { const next = { ...filters, state: event.target.value }; setFilters(next); applied.current = next; void load() }}>{[['AVAILABLE', '未归档'], ['ENABLED', '已启用'], ['DISABLED', '已停用'], ['ARCHIVED', '已归档'], ['ALL', '全部状态']].map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label><button type="submit">搜索</button>
    </form><ul className="w2-secondary-list" aria-label="数据库连接目录">{list.value.items.map(row => <li key={row.id}><button className="w2-secondary-select" aria-pressed={selected?.id === row.id} disabled={dirty || command.blocked} onClick={event => { trigger.current = event.currentTarget; setSelected(row); setEditing(false); const cached=probeResults.current.get(row.id);setProbe(cached?.version===row.version?cached.result:null);setProbeError(cached?.version===row.version?cached.error:'');setValidation('');probeSequence.current++ }}><strong>{row.name}</strong><small>{databaseTypeLabels[row.config.type]} · {row.config.database} · {row.archived ? '已归档' : row.enabled ? '已启用' : '已停用'}</small></button></li>)}</ul>{!list.loading && !list.error && !list.value.items.length && <p className="w2-secondary-empty">{filters.query || filters.type || filters.state !== 'AVAILABLE' ? '没有匹配的连接' : '连接你的第一套数据库'}</p>}{list.value.nextCursor && <UiActionButton actionKey="ui.loadMore" busy={list.loading} onAction={() => { void load(true) }} />}</div>
  </PageChrome>
}
