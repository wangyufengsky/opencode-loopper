import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from '@/api/client'
import type { RoleCatalogItem, RoleDetail, RoleImportPublication, RoleImportValidation, RoleRevision, RoleSlotBinding } from '@/types/domain'
import { semanticName } from '@/foundation/semanticRegistry'
import { deferred, flush, foundationDOM, pageFrame, pageProps } from '../workflow/page.test-support'
import { createRoleManagementController } from './roleManagementController'
import { RoleManagementPage } from './RoleManagementPage'
import { roleTools } from './rolePresentation'

const role: RoleCatalogItem = { roleId: 'builtin-designer', displayName: '设计师', description: '整理工作包设计', origin: 'BUILTIN', latestRevisionId: 'revision-2', latestRevisionNumber: 2, activeSlots: ['PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY'], groupLabel: '设计流程' }
const detail: RoleDetail = { ...role }
const binding: RoleSlotBinding = { slot: role.activeSlots[0]!, profile: role.activeSlots[0]!, activeRoleId: role.roleId, activeRevisionId: role.latestRevisionId, bindingVersion: 3, label: '设计师 · 工作包设计', purpose: '制作工作包候选' }
const revision: RoleRevision = { roleId: role.roleId, revisionId: role.latestRevisionId, revisionNumber: 2, contentSha256: 'a'.repeat(64), publishedAt: '2026-09-24T00:00:00Z', manifest: { roleId: role.roleId }, promptFragments: { package: '请完成当前工作包设计。' }, promptVariables: [] }
const validation: RoleImportValidation = { sourceSha256: 'c'.repeat(64), valid: true, roles: [{ roleId: role.roleId, displayName: role.displayName }],
  activations: [{ slot: binding.slot, roleId: role.roleId, expectedVersion: 3 }], diagnostics: [], changes: [{ path: 'promptFragments.package', before: '旧提示', after: '新提示' }] }
const publication: RoleImportPublication = { sourceSha256: validation.sourceSha256, roles: [], bindings: [], replayed: false }
beforeEach(() => {
  foundationDOM()
  vi.spyOn(api, 'getRoles').mockResolvedValue({ items: [role] })
  vi.spyOn(api, 'getRole').mockResolvedValue(detail)
  vi.spyOn(api, 'getRoleSlots').mockResolvedValue([binding])
  vi.spyOn(api, 'getProjects').mockResolvedValue([])
  vi.spyOn(api, 'getRoleRevision').mockResolvedValue(revision)
  vi.spyOn(api, 'getRoleRevisions').mockResolvedValue({ items: [{ revisionId: 'revision-1', revisionNumber: 1, contentSha256: 'b'.repeat(64) }, revision] })
  vi.spyOn(api, 'previewRoleSlot').mockResolvedValue({ scope: 'CONFIG_ONLY', slot: binding.slot, rules: [], mcpTools: [], complete: false, limitations: ['实际授权在创建会话时核定'] })
  vi.spyOn(api, 'compareRoleRevisions').mockResolvedValue({ fromRevisionId: 'revision-1', toRevisionId: 'revision-2', changes: validation.changes! })
  vi.spyOn(api, 'validateRoleImport').mockResolvedValue(validation)
  vi.spyOn(api, 'publishRoleImport').mockResolvedValue(publication)
})
afterEach(async () => { cleanup(); await flush(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
async function mountedOwner() { const owner = createRoleManagementController({ key: () => 'original-key' }), release = owner.attachView(); await flush(); return { owner, close() { release(); owner.retire(true) } } }

describe('W2 role React production contract', () => {
  it('keeps detail selected-only and renders the unchanged true React role flow', async () => {
    vi.mocked(api.getRoleSlots).mockResolvedValue([{ ...binding, activeRevisionId: 'revision-1', bindingVersion: 9 }])
    const owner = createRoleManagementController(), root = render(pageFrame(<RoleManagementPage {...pageProps('/roles')} controller={owner} />))
    const select = await screen.findByRole('button', { name: '选择：设计师' }); expect(screen.queryByRole('complementary')).toBeNull(); select.focus(); fireEvent.click(select)
    await waitFor(() => expect(root.container.querySelector('[data-canvas-runtime="react"][data-canvas-kind="roles"]')).toBeTruthy())
    expect(screen.getByText('使用其他已发布版本')).toBeTruthy(); expect(screen.getByText('来源：内置')).toBeTruthy()
    expect(api.getRoleRevision).not.toHaveBeenCalled(); fireEvent.click(screen.getByRole('button', { name: '查看此阶段使用的版本' }))
    await waitFor(() => expect(api.getRoleRevision).toHaveBeenCalledWith(role.roleId, 'revision-1'))
    expect(screen.getByRole('heading', { name: '版本历史' })).toBeTruthy(); fireEvent.keyDown(screen.getByRole('complementary'), { key: 'Escape' }); expect(screen.queryByRole('complementary')).toBeNull(); expect(document.activeElement).toBe(select)
    root.unmount(); owner.retire(true)
  })
  it('reports runtime/policy blockers without treating configuration as usable', async () => {
    vi.mocked(api.previewRoleSlot).mockResolvedValue({ scope: 'CONFIG_ONLY', slot: binding.slot, rules: [], complete: false, limitations: [], mcpTools: [
      { name: 'bash', status: 'ROLE_DISABLED' }, { name: 'read', status: 'SCOPE_REQUIRED' }, { name: 'external_search', status: 'DISCOVERY_REQUIRED' },
      { name: '@loopper-assist/search_knowledge', status: 'POLICY_DISABLED', reason: '所选项目已关闭此工具。' }, { name: '@loopper-internal/submit_package_design_v2', status: 'RUNTIME_UNAVAILABLE' },
    ] })
    const owner = createRoleManagementController(), root = render(pageFrame(<RoleManagementPage {...pageProps('/roles')} controller={owner} />))
    fireEvent.click(await screen.findByRole('button', { name: '选择：设计师' })); await waitFor(() => expect(owner.getSnapshot().selected).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: '选择：权限与 MCP' })); await screen.findByText('配置 5 项 · 受限 2 项 · 待核定 3 项')
    for (const text of ['角色未授权', '策略已关闭', '运行环境未就绪', '待任务授权', '待工具发现']) expect(root.container.querySelector('.w2-role-tools')?.textContent).toContain(text)
    expect(root.container.querySelector('.w2-role-tools')?.textContent).not.toContain('可用'); expect(screen.getByText('所选项目已关闭此工具。')).toBeTruthy()
    root.unmount(); owner.retire(true)
  })
  it('does not merge newer declarations into the actual older bound preview', async () => {
    vi.mocked(api.getRoleRevision).mockResolvedValue({ ...revision, nativeTools: ['bash'], permissionMode: 'INTERSECT' })
    vi.mocked(api.previewRoleSlot).mockResolvedValue({ scope: 'CONFIG_ONLY', slot: binding.slot, revisionId: 'revision-1', revisionNumber: 1, bindingActive: true, rules: [{ permission: 'read', pattern: '*', action: 'allow' }], mcpTools: [{ name: 'read', source: 'NATIVE_POLICY' }], complete: false, limitations: [] })
    const { owner, close } = await mountedOwner(); await owner.select(role.roleId); owner.tab('permissions'); await flush()
    expect(roleTools(owner.getSnapshot().preview, owner.getSnapshot().revision).map(tool => tool.name)).toEqual(['read']); expect(owner.getSnapshot().preview?.revisionNumber).toBe(1); close()
  })
  it('loads allowed stages for an unbound published role and preserves project-scoped previews', async () => {
    vi.mocked(api.getRole).mockResolvedValue({ ...detail, activeSlots: [] }); vi.mocked(api.getRoleSlots).mockResolvedValue([{ ...binding, activeRoleId: 'other' }]); vi.mocked(api.getRoleRevision).mockResolvedValue({ ...revision, manifest: { allowedSlots: [binding.slot] } })
    const { owner, close } = await mountedOwner(); await owner.select(role.roleId); owner.tab('permissions'); await flush()
    expect(owner.getSnapshot().slot).toBe(binding.slot); expect(api.previewRoleSlot).toHaveBeenCalledWith(role.roleId, binding.slot, '')
    owner.scope(binding.slot, 'selected-project'); await flush(); expect(api.previewRoleSlot).toHaveBeenLastCalledWith(role.roleId, binding.slot, 'selected-project'); close()
  })
  it('requests prompt/history lazily, renders variables as static text and compares old-to-latest', async () => {
    vi.mocked(api.getRoleRevision).mockResolvedValue({ ...revision, promptFragments: { '2': '第二段 ${projectName}', '1': '第一段 {{task}}' }, manifest: { workInstructions: '节点专业说明' } })
    const owner = createRoleManagementController(), root = render(pageFrame(<RoleManagementPage {...pageProps('/roles')} controller={owner} />))
    fireEvent.click(await screen.findByRole('button', { name: '选择：设计师' })); await waitFor(() => expect(owner.getSnapshot().selected).toBeTruthy())
    expect(api.getRoleRevision).not.toHaveBeenCalled(); expect(api.getRoleRevisions).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '选择：Prompt 模板' })); await screen.findByText(/第一段 \{task\}/); expect(screen.getByText('节点专业说明')).toBeTruthy(); expect(screen.getByText('当前项目名称')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '选择：版本历史' })); await waitFor(() => expect(api.getRoleRevisions).toHaveBeenCalledWith(role.roleId, '', 12))
    fireEvent.click(screen.getByRole('button', { name: semanticName('roles.compareRevision') })); await screen.findByText('旧提示'); expect(api.compareRoleRevisions).toHaveBeenCalledWith(role.roleId, 'revision-1', 'revision-2')
    root.unmount(); owner.retire(true)
  })
  it('retries the exact failed next cursor without corrupting previous history', async () => {
    vi.mocked(api.getRoles).mockResolvedValueOnce({ items: [role], nextCursor: 'page2' }).mockRejectedValueOnce(new Error('failed')).mockResolvedValueOnce({ items: [role], nextCursor: null }).mockResolvedValue({ items: [role], nextCursor: 'page2' })
    const { owner, close } = await mountedOwner(); owner.next(); await flush(); expect(owner.getSnapshot().previous).toEqual([])
    owner.retryList(); await flush(); expect(api.getRoles).toHaveBeenNthCalledWith(3, '', 'page2', 12); expect(owner.getSnapshot().previous).toEqual([''])
    owner.previous(); await flush(); expect(api.getRoles).toHaveBeenLastCalledWith('', '', 12); expect(owner.getSnapshot().previous).toEqual([]); close()
  })
  it('requires exact ZIP, inspectable diff and explicit confirmation; pending and unknown stay visible after closing context', async () => {
    const sent = deferred<RoleImportPublication>(); vi.mocked(api.publishRoleImport).mockReturnValueOnce(sent.promise).mockResolvedValueOnce({ ...publication, replayed: true })
    const owner = createRoleManagementController({ key: () => 'original-key' }), root = render(pageFrame(<RoleManagementPage {...pageProps('/roles')} controller={owner} />))
    await screen.findByRole('button', { name: '选择：设计师' }); const file = new File(['zip bytes'], 'roles.zip', { type: 'application/zip' })
    fireEvent.change(screen.getByLabelText(semanticName('roles.import'), { selector: 'input' }), { target: { files: [file] } }); await screen.findByText('旧提示')
    const publish = screen.getByRole('button', { name: semanticName('roles.publish') }) as HTMLButtonElement
    expect(publish.disabled).toBe(true); expect(api.publishRoleImport).not.toHaveBeenCalled(); fireEvent.click(screen.getByRole('checkbox')); expect(publish.disabled).toBe(false)
    fireEvent.click(publish); await act(flush); expect(api.publishRoleImport).toHaveBeenCalledWith(file, { sourceSha256: validation.sourceSha256, idempotencyKey: 'original-key', activations: [{ slot: binding.slot, roleId: role.roleId, expectedVersion: 3 }] })
    expect((screen.getByLabelText(semanticName('roles.import'), { selector: 'input' }) as HTMLInputElement).disabled).toBe(true); expect(owner.canLeave().kind).toBe('BLOCK')
    await act(async () => { sent.reject(new Error('unknown')); await flush() }); expect(owner.getFile()).toBe(file); expect(owner.canLeave().kind).toBe('BLOCK')
    fireEvent.click(screen.getByRole('button', { name: '关闭面板：导入角色配置' })); expect(screen.queryByRole('complementary')).toBeNull(); expect(screen.getByText(/结果尚未确认，请先恢复/)).toBeTruthy(); expect(screen.getByText(/已选配置包：roles.zip/)).toBeTruthy()
    await act(async () => { await owner.recover() }); expect(api.publishRoleImport).toHaveBeenCalledTimes(2); expect(vi.mocked(api.publishRoleImport).mock.calls[1]).toEqual(vi.mocked(api.publishRoleImport).mock.calls[0]); expect(screen.getByText('配置已发布过，当前结果已核对。')).toBeTruthy()
    root.unmount(); owner.retire(true)
  })
  it('no diff blocks publish, 409 preserves the File and requires new validation/confirmation', async () => {
    vi.mocked(api.validateRoleImport).mockResolvedValueOnce({ ...validation, changes: [] }).mockResolvedValue(validation)
    vi.mocked(api.publishRoleImport).mockRejectedValue(new ApiError('版本冲突', 409))
    const { owner, close } = await mountedOwner(), file = new File(['zip'], 'roles.zip'); owner.chooseImport(file); await flush(); owner.confirm(true); expect(owner.canPublish()).toBe(false)
    await owner.validate(); owner.confirm(true); expect(owner.canPublish()).toBe(true); await owner.publish()
    expect(owner.getFile()).toBe(file); expect(owner.getSnapshot().importPreview).toBeNull(); expect(owner.getSnapshot().confirmed).toBe(false); expect(owner.getSnapshot().importError).toContain('重新校验同一配置包'); expect(owner.canLeave().kind).toBe('CONFIRM_DISCARD')
    await owner.validate(); expect(api.validateRoleImport).toHaveBeenCalledTimes(3); expect(owner.getSnapshot().confirmed).toBe(false); expect(owner.canPublish()).toBe(false); close()
  })
  it('accepted publication read failure only retries reads and retains the original File until complete', async () => {
    vi.mocked(api.getRoleSlots).mockResolvedValueOnce([binding]).mockRejectedValueOnce(new Error('read failed')).mockResolvedValue([binding])
    const owner = createRoleManagementController({ key: () => 'original-key' }), detach = owner.attachView(); await flush()
    const file = new File(['zip'], 'roles.zip'); owner.chooseImport(file); await flush(); owner.confirm(true); await owner.publish()
    expect(owner.getSnapshot().command.phase).toBe('ACCEPTED_READBACK'); expect(owner.canLeave().kind).toBe('BLOCK'); expect(owner.getFile()).toBe(file)
    await owner.recover(); expect(api.publishRoleImport).toHaveBeenCalledTimes(1); expect(owner.getFile()).toBeNull(); expect(owner.canLeave().kind).toBe('ALLOW'); detach(); owner.retire(true)
  })
  it('unknown publish prevents file replacement, discard and revalidation, including a detached/remounted view', async () => {
    vi.mocked(api.publishRoleImport).mockRejectedValue(new Error('unknown'))
    const owner = createRoleManagementController({ key: () => 'original-key' }), detach = owner.attachView(); await flush()
    const file = new File(['zip'], 'roles.zip'); owner.chooseImport(file); await flush(); owner.confirm(true); await owner.publish()
    const identity = owner.getOperation()!.identity
    expect(owner.chooseImport(new File(['other'], 'other.zip'))).toBe(false); expect(owner.discardImport()).toBe(false); await owner.validate(); expect(api.validateRoleImport).toHaveBeenCalledTimes(1)
    detach(); expect(owner.viewCount()).toBe(0)
    const remount = owner.attachView(); await flush()
    expect(api.publishRoleImport).toHaveBeenCalledTimes(1); expect(owner.getOperation()!.identity).toBe(identity); expect(identity.files[0]).toBe(file)
    expect(owner.canLeave().kind).toBe('BLOCK'); remount(); owner.retire(true)
  })
  it('late detail and list success after last detach cannot project', async () => {
    const pending = deferred<RoleDetail>(); vi.mocked(api.getRole).mockReturnValueOnce(pending.promise).mockResolvedValue({ ...detail, roleId: 'B', displayName: 'B角色' })
    const { owner, close } = await mountedOwner(); const first = owner.select('A'); await owner.select('B'); pending.resolve({ ...detail, roleId: 'A' }); await first
    expect(owner.getSnapshot().selected?.roleId).toBe('B'); close()
    const delayed = deferred<Awaited<ReturnType<typeof api.getRoles>>>(); vi.mocked(api.getRoles).mockReturnValue(delayed.promise)
    const retired = createRoleManagementController(), detach = retired.attachView(); await flush(); detach(); const before = retired.getSnapshot(); delayed.resolve({ items: [role] }); await flush(); expect(retired.getSnapshot()).toBe(before); retired.retire(true)
  })
  it('owns exported URL cleanup in the view lease and never downloads a late retired blob', async () => {
    const releaseURL = vi.fn(), download = vi.fn((_blob: Blob, _name: string, own: (release: () => void) => () => void) => { own(releaseURL) })
    const blob = new Blob(['zip']), exportAPI = vi.spyOn(api, 'exportRoleRevision').mockResolvedValue(blob)
    const owner = createRoleManagementController({ download }), detach = owner.attachView(); await flush(); await owner.select(role.roleId); await owner.exportRevision('revision-1')
    expect(download).toHaveBeenCalledWith(blob, `${role.roleId}-vrevision-1.zip`, expect.any(Function)); detach(); expect(releaseURL).toHaveBeenCalledTimes(1); owner.retire(true)
    const late = deferred<Blob>(); exportAPI.mockReturnValue(late.promise); const other = createRoleManagementController({ download }), release = other.attachView(); await flush(); await other.select(role.roleId); const pending = other.exportRevision('revision-1'); release(); late.resolve(blob); await pending; expect(download).toHaveBeenCalledTimes(1); other.retire(true)
  })
  it('StrictMode owns one read lease and does not validate/publish automatically', async () => {
    const owner = createRoleManagementController(), root = render(pageFrame(<StrictMode><RoleManagementPage {...pageProps('/roles')} controller={owner} /></StrictMode>))
    await act(flush); expect(api.getRoles).toHaveBeenCalledTimes(1); expect(api.validateRoleImport).not.toHaveBeenCalled(); expect(api.publishRoleImport).not.toHaveBeenCalled(); root.unmount(); expect(owner.viewCount()).toBe(0); owner.retire(true)
  })
  it('a late known rejection after forced retirement starts no new binding read or projection', async () => {
    const sent = deferred<RoleImportPublication>(); vi.mocked(api.publishRoleImport).mockReturnValue(sent.promise)
    const { owner, close } = await mountedOwner(); owner.chooseImport(new File(['zip'], 'roles.zip')); await flush(); owner.confirm(true)
    const pending = owner.publish(); await flush(); close(); const before = owner.getSnapshot()
    sent.reject(new ApiError('版本冲突', 409)); await pending; await flush()
    expect(api.getRoleSlots).toHaveBeenCalledTimes(1); expect(owner.getSnapshot()).toBe(before)
  })
})
