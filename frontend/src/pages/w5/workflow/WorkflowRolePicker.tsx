import { useLayoutEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import type { RoleCatalogItem, RoleRevisionSummary, WorkflowNode } from '@/types/domain'
import { UiActionButton } from '@/foundation/components'
import { userFacingError } from '@/utils/displayLabels'

export function WorkflowRolePicker({ node, disabled, onChange, onLabel }: { node: WorkflowNode; disabled?: boolean; onChange(roleId: string, revisionId: string): void; onLabel?(id: string, label: string): void }) {
  const [query, setQuery] = useState(''), [roles, setRoles] = useState<RoleCatalogItem[]>([]), [cursor, setCursor] = useState<string | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState(''), [current, setCurrent] = useState('尚未选择角色'), [revisions, setRevisions] = useState<RoleRevisionSummary[]>([]), [revisionCursor, setRevisionCursor] = useState<string | null>(null)
  const latest = useRef({ node, disabled, onChange, onLabel }), alive = useRef(false), generation = useRef({ list: 0, choice: 0, history: 0, label: 0 }), catalogueQuery = useRef('')
  latest.current = { node, disabled, onChange, onLabel }
  useLayoutEffect(() => { alive.current = true; return () => { alive.current = false; Object.keys(generation.current).forEach(key => generation.current[key as keyof typeof generation.current]++) } }, [])
  const valid = (ticket: number, original?: WorkflowNode, channel: keyof typeof generation.current = 'choice') => alive.current && ticket === generation.current[channel] && (!original || latest.current.node.id === original.id && latest.current.node.moduleId === original.moduleId && latest.current.node.moduleVersion === original.moduleVersion && latest.current.node.roleId === original.roleId && latest.current.node.roleRevisionId === original.roleRevisionId)
  async function load(more = false) {
    const ticket = ++generation.current.list; if (!alive.current) return; setBusy(true); setError(''); if (!more) { catalogueQuery.current = query; setRoles([]); setCursor(null) }
    try { const page = await api.getRoles(catalogueQuery.current, more ? cursor || '' : '', 20); if (valid(ticket, undefined, 'list')) { setRoles(previous => more ? [...previous, ...page.items] : page.items); setCursor(page.nextCursor ?? null) } }
    catch (cause) { if (valid(ticket, undefined, 'list')) setError(userFacingError(cause, '角色读取失败，请重试。')) } finally { if (valid(ticket, undefined, 'list')) setBusy(false) }
  }
  async function choose(roleId: string, revisionId?: string) {
    if (!roleId || latest.current.disabled || !alive.current) return
    const original = latest.current.node, ticket = ++generation.current.choice; setBusy(true); setError('')
    try {
      const role = await api.getRole(roleId); if (!valid(ticket, original) || latest.current.disabled) return; if (role.roleId !== roleId) throw new Error('角色读取结果与所选身份不一致。')
      const id = revisionId ?? role.latestRevisionId, revision = await api.getRoleRevision(roleId, id)
      if (!valid(ticket, original) || latest.current.disabled) return
      if (revision.roleId !== roleId || revision.revisionId !== id) throw new Error('角色版本与所选身份不一致。')
      const slot = ['free.write', 'source.test-write'].includes(original.moduleId || '') ? 'WORKFLOW_WRITE' : 'WORKFLOW_READ_ONLY'
      if (!Array.isArray(revision.manifest.allowedSlots) || !revision.manifest.allowedSlots.includes(slot) || typeof revision.manifest.workInstructions !== 'string' || !revision.manifest.workInstructions.trim()) throw new Error('这个角色版本不支持当前工作类型，请选择含工作说明的兼容版本。')
      setCurrent(`${role.displayName} · v${revision.revisionNumber}`); latest.current.onLabel?.(roleId, role.displayName); latest.current.onChange(roleId, id)
    } catch (cause) { if (valid(ticket, original)) setError(userFacingError(cause, '角色版本读取失败，请重试。')) } finally { if (valid(ticket)) setBusy(false) }
  }
  async function history(more = false) {
    const original = latest.current.node; if (!original.roleId || latest.current.disabled || !alive.current) return
    const ticket = ++generation.current.history; setBusy(true); setError('')
    try { const page = await api.getRoleRevisions(original.roleId, more ? revisionCursor || '' : '', 20); if (valid(ticket, original, 'history')) { setRevisions(previous => more ? [...previous, ...page.items] : page.items); setRevisionCursor(page.nextCursor ?? null) } }
    catch (cause) { if (valid(ticket, original, 'history')) setError(userFacingError(cause, '角色版本读取失败，请重试。')) } finally { if (valid(ticket, undefined, 'history')) setBusy(false) }
  }
  useLayoutEffect(() => { void load() }, [])
  useLayoutEffect(() => {
    const original = latest.current.node, ticket = ++generation.current.label; generation.current.choice++; generation.current.history++; setRevisions([]); setRevisionCursor(null); setBusy(false); setCurrent(original.roleId ? '已固定角色版本' : '尚未选择角色')
    if (!original.roleId || !original.roleRevisionId) return
    const roleId = original.roleId, revisionId = original.roleRevisionId
    void Promise.all([api.getRole(roleId), api.getRoleRevision(roleId, revisionId)]).then(([role, version]) => { if (valid(ticket, original, 'label') && role.roleId === roleId && version.roleId === roleId && version.revisionId === revisionId) { setCurrent(`${role.displayName} · v${version.revisionNumber}`); latest.current.onLabel?.(roleId, role.displayName) } }).catch(() => { /* Preserve frozen role IDs when the catalogue is unavailable. */ })
  }, [node.id, node.roleId, node.roleRevisionId])
  return <><fieldset className="workflow-fields workflow-role-picker" disabled={disabled || busy}><legend>执行角色</legend><p>{current}</p><div className="w2-actions"><input aria-label="搜索执行角色" placeholder="搜索角色" value={query} onChange={e => setQuery(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); void load() } }} /><UiActionButton actionKey="ui.search" target="执行角色" onAction={() => { void load() }} /></div>
    <select aria-label="选择执行角色" value="" onChange={e => { void choose(e.target.value) }}><option value="">选择角色并固定版本</option>{roles.map(role => <option key={role.roleId} value={role.roleId}>{role.displayName}</option>)}</select>
    {cursor && <UiActionButton actionKey="ui.loadMore" target="角色" onAction={() => { void load(true) }} />}{node.roleId && <UiActionButton actionKey="workflow.roleVersions" onAction={() => { void history() }} />}
    {!!revisions.length && <select aria-label="角色版本" value={node.roleRevisionId || ''} onChange={e => { if (node.roleId) void choose(node.roleId, e.target.value) }}><option value="" disabled>选择已发布版本</option>{revisions.map(version => <option key={version.revisionId} value={version.revisionId}>版本 {version.revisionNumber}</option>)}</select>}{revisionCursor && <UiActionButton actionKey="ui.loadMore" target="角色版本" onAction={() => { void history(true) }} />}
  </fieldset>{error && <p role="alert">{error}</p>}</>
}
