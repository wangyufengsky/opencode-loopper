import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RoleManagementView from '@/views/RoleManagementView.vue'
import { api, ApiError } from '@/api/client'
import type { RoleCatalogItem, RoleDetail, RoleImportValidation, RoleRevision, RoleSlotBinding } from '@/types/domain'

const role: RoleCatalogItem = {
  roleId: 'builtin-designer', displayName: '设计师', description: '整理工作包设计', origin: 'BUILTIN',
  latestRevisionId: 'revision-2', latestRevisionNumber: 2, activeSlots: ['PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY'],
  groupKey: 'DESIGNER', groupLabel: '设计流程',
}
const detail: RoleDetail = { ...role }
const binding: RoleSlotBinding = {
  slot: 'PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY', profile: 'PACKAGE_DESIGN_CANDIDATE_V2_READ_ONLY',
  activeRoleId: role.roleId, activeRevisionId: role.latestRevisionId, bindingVersion: 3,
  label: '设计师 · 工作包设计', purpose: '制作工作包候选',
}
const revision: RoleRevision = {
  roleId: role.roleId, revisionId: role.latestRevisionId, revisionNumber: 2,
  contentSha256: 'a'.repeat(64), publishedAt: '2026-09-24T00:00:00Z',
  manifest: { roleId: role.roleId }, promptFragments: { package: '请完成当前工作包设计。' }, promptVariables: [],
}

function view() {
  return mount(RoleManagementView, {
    global: { plugins: [ElementPlus], stubs: {
      PageHeader: { template: '<header><slot name="actions" /></header>' },
      RouterLink: { template: '<a><slot /></a>' }, Icon: true,
    } },
  })
}

beforeEach(() => {
  vi.spyOn(api, 'getRoles').mockResolvedValue({ items: [role], nextCursor: null })
  vi.spyOn(api, 'getRole').mockResolvedValue(detail)
  vi.spyOn(api, 'getRoleSlots').mockResolvedValue([binding])
  vi.spyOn(api, 'getProjects').mockResolvedValue([])
  vi.spyOn(api, 'previewRoleSlot').mockResolvedValue({ scope: 'CONFIG_ONLY', slot: binding.slot,
    rules: [{ permission: 'read', pattern: '*', action: 'allow' }],
    mcpTools: [{ name: 'submit_package_design_v2', server: '内部候选服务' }],
    complete: false, limitations: ['实际授权在创建会话时核定'] })
  vi.spyOn(api, 'getRoleRevision').mockResolvedValue(revision)
  vi.spyOn(api, 'getRoleRevisions').mockResolvedValue({ items: [
    { revisionId: 'revision-1', revisionNumber: 1, contentSha256: 'b'.repeat(64) },
    { revisionId: 'revision-2', revisionNumber: 2, contentSha256: 'a'.repeat(64) },
  ], nextCursor: null })
  vi.spyOn(api, 'compareRoleRevisions').mockResolvedValue({ fromRevisionId: 'revision-1', toRevisionId: 'revision-2',
    changes: [{ path: 'promptFragments.package', before: '旧提示', after: '新提示' }] })
})
afterEach(() => vi.restoreAllMocks())

describe('RoleManagementView', () => {
  it('combines inherited, native and declared tools with Chinese descriptions and preserves blockers', async () => {
    vi.mocked(api.getRoleRevision).mockResolvedValue({ ...revision, nativeTools: ['read'],
      mcpTools: ['@loopper-internal/submit_package_design_v2'] })
    vi.mocked(api.previewRoleSlot).mockResolvedValue({ scope: 'CONFIG_ONLY', slot: binding.slot, complete: false,
      limitations: ['项目当前未处于托管状态'],
      rules: [{ permission: 'read', pattern: '.env', action: 'deny' }],
      mcpTools: [
        { name: 'read', source: 'NATIVE_POLICY' },
        { name: '@loopper-internal/submit_package_design_v2', source: 'SYSTEM_REQUIRED', required: true },
        { name: '@loopper-internal/ppt_export', source: 'BUNDLED_POLICY' },
      ] })
    const wrapper = view()
    await flushPromises()
    await wrapper.get('.role-item').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.detail-tabs button')[0]!.text()).toBe('描述')
    expect(wrapper.get('.slot-list').text()).toContain('工作包拆分之后')
    await wrapper.findAll('.detail-tabs button')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.tool-list li')).toHaveLength(3)
    expect(wrapper.get('.tool-list').text()).toContain('读取文件')
    expect(wrapper.get('.tool-list').text()).toContain('导出演示文稿')
    expect(wrapper.get('.tool-list').text()).toContain('程序内置')
    expect(wrapper.get('.permission-list').text()).toContain('读取文件')
    expect(wrapper.get('.permission-list').text()).toContain('.env')
    expect(wrapper.text()).toContain('项目当前未处于托管状态')
    expect(wrapper.text()).not.toContain('配置声明的原生工具')
    wrapper.unmount()
  })
  it('retries a failed next page without losing the cursor history', async () => {
    vi.mocked(api.getRoles).mockResolvedValueOnce({ items: [role], nextCursor: 'next-page' })
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce({ items: [role], nextCursor: null })
    const wrapper = view()
    await flushPromises()
    await wrapper.get('.pagination button:last-child').trigger('click')
    await flushPromises()
    expect(wrapper.get('.role-list .error-text').text()).toContain('角色列表读取失败')
    expect(wrapper.get('.pagination button:first-child').attributes('disabled')).toBeDefined()
    await wrapper.get('.role-list .error-text button').trigger('click')
    await flushPromises()
    expect(api.getRoles).toHaveBeenLastCalledWith('', 'next-page', 12)
    expect(wrapper.get('.pagination button:first-child').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('previews a published role that is not currently bound to a workflow slot', async () => {
    const unbound = { ...role, roleId: 'custom-designer', activeSlots: [] }
    vi.mocked(api.getRoles).mockResolvedValue({ items: [unbound], nextCursor: null })
    vi.mocked(api.getRole).mockResolvedValue(unbound)
    vi.mocked(api.getRoleRevision).mockResolvedValue({ ...revision, roleId: unbound.roleId,
      manifest: { roleId: unbound.roleId, allowedSlots: [binding.slot] } })
    const wrapper = view()
    await flushPromises()
    await wrapper.get('.role-item').trigger('click')
    await flushPromises()
    await wrapper.findAll('.detail-tabs button')[1]!.trigger('click')
    await flushPromises()
    expect(api.previewRoleSlot).toHaveBeenCalledWith(unbound.roleId, binding.slot, '')
    wrapper.unmount()
  })

  it('uses server search and cursors, and loads detail tabs only when opened', async () => {
    const list = vi.mocked(api.getRoles)
    list.mockResolvedValueOnce({ items: [role], nextCursor: 'next-page' })
      .mockResolvedValueOnce({ items: [role], nextCursor: null })
    const wrapper = view()
    await flushPromises()
    expect(list).toHaveBeenCalledWith('', '', 12)
    expect(api.getRoleRevision).not.toHaveBeenCalled()
    expect(api.getRoleRevisions).not.toHaveBeenCalled()
    await wrapper.get('.pagination button:last-child').trigger('click')
    await flushPromises()
    expect(list).toHaveBeenCalledWith('', 'next-page', 12)
    await wrapper.get('.role-item').trigger('click')
    await flushPromises()
    expect(wrapper.get('.detail-heading').text()).toContain('设计师')
    await wrapper.findAll('.detail-tabs button')[1]!.trigger('click')
    await flushPromises()
    expect(api.previewRoleSlot).toHaveBeenCalledWith(role.roleId, binding.slot, '')
    expect(wrapper.get('.preview-status').text()).toContain('仍需运行时核定')
    await wrapper.findAll('.detail-tabs button')[2]!.trigger('click')
    await flushPromises()
    expect(api.getRoleRevision).toHaveBeenCalledWith(role.roleId, 'revision-2')
    expect(wrapper.text()).toContain('请完成当前工作包设计。')
    await wrapper.findAll('.detail-tabs button')[3]!.trigger('click')
    await flushPromises()
    expect(api.getRoleRevisions).toHaveBeenCalledWith(role.roleId, '', 12)
    vi.mocked(api.getRoleRevision).mockResolvedValueOnce({ ...revision, revisionId: 'revision-1', revisionNumber: 1,
      promptFragments: { package: '历史静态模板' } })
    await wrapper.findAll('.history-list button')[0]!.trigger('click')
    await flushPromises()
    expect(api.getRoleRevision).toHaveBeenCalledWith(role.roleId, 'revision-1')
    expect(wrapper.get('.history-revision').text()).toContain('历史静态模板')
    await wrapper.findAll('.history-list button').find(button => button.text() === '与最新发布版本比较')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('旧提示')
    wrapper.unmount()
  })

  it('distinguishes the latest published revision from an older activated binding', async () => {
    vi.mocked(api.getRoleSlots).mockResolvedValue([{ ...binding, activeRevisionId: 'revision-1', bindingVersion: 9, purpose: 'DEFAULT' }])
    vi.mocked(api.getRoleRevision).mockImplementation(async (_roleId, revisionId) => revisionId === 'revision-1'
      ? { ...revision, revisionId, revisionNumber: 1, promptFragments: { package: '旧版角色指令' } }
      : revision)
    const wrapper = view()
    await flushPromises()
    await wrapper.get('.role-item').trigger('click')
    await flushPromises()
    expect(wrapper.get('.detail-heading').text()).toContain('最新发布版本 2')
    expect(wrapper.get('.slot-list').text()).toContain('使用其他已发布版本')
    expect(wrapper.get('.slot-list').text()).toContain(binding.label)
    expect(wrapper.get('.slot-list').text()).not.toContain('DEFAULT')
    expect(wrapper.get('.detail-section').text()).toContain('来源：内置')
    await wrapper.get('.slot-list .inline-button').trigger('click')
    await flushPromises()
    expect(api.getRoleRevision).toHaveBeenCalledWith(role.roleId, 'revision-1')
    expect(wrapper.get('.history-revision').text()).toContain('版本 1 的静态配置')
    expect(wrapper.get('.history-revision').text()).not.toContain('版本 9')
    wrapper.unmount()
  })

  it('requires inspectable differences and explicit confirmation before publishing the same ZIP', async () => {
    const preview: RoleImportValidation = { sourceSha256: 'c'.repeat(64), valid: true,
      roles: [{ roleId: role.roleId, displayName: '设计师' }],
      activations: [{ slot: binding.slot, roleId: role.roleId, expectedVersion: 3 }], diagnostics: [],
      changes: [{ path: 'promptFragments.package', before: '旧提示', after: '新提示' }],
    }
    const validate = vi.spyOn(api, 'validateRoleImport').mockResolvedValue(preview)
    const publish = vi.spyOn(api, 'publishRoleImport').mockResolvedValue({ sourceSha256: preview.sourceSha256,
      roles: [{ roleId: role.roleId, revisionId: 'revision-3', revisionNumber: 3, contentSha256: 'd'.repeat(64) }],
      bindings: [{ ...binding, activeRevisionId: 'revision-3', bindingVersion: 4 }], replayed: false })
    const wrapper = view()
    await flushPromises()
    const file = new File(['zip bytes'], 'roles.zip', { type: 'application/zip' })
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
    await input.trigger('change')
    await flushPromises()
    expect(validate).toHaveBeenCalledWith(file)
    expect(wrapper.get('.diff-panel').text()).toContain('旧提示')
    const button = wrapper.findAll('.panel-actions button').at(-1)!
    expect(button.attributes('disabled')).toBeDefined()
    await wrapper.get('.confirm-line input').setValue(true)
    await button.trigger('click')
    await flushPromises()
    expect(publish).toHaveBeenCalledWith(file, expect.objectContaining({
      sourceSha256: preview.sourceSha256,
      activations: [{ slot: binding.slot, roleId: role.roleId, expectedVersion: 3 }],
    }))
    expect(wrapper.text()).toContain('配置包已发布并激活')
    wrapper.unmount()
  })

  it('blocks publication without a field diff and revalidates after a version conflict', async () => {
    const preview: RoleImportValidation = { sourceSha256: 'c'.repeat(64), valid: true,
      roles: [{ roleId: role.roleId, displayName: '设计师' }], activations: [], diagnostics: [], changes: [] }
    const validate = vi.spyOn(api, 'validateRoleImport').mockResolvedValueOnce(preview)
      .mockResolvedValueOnce({ ...preview, changes: [{ path: 'purpose', before: '旧', after: '新' }] })
      .mockResolvedValueOnce({ ...preview, changes: [{ path: 'purpose', before: '旧', after: '新' }] })
    const publish = vi.spyOn(api, 'publishRoleImport').mockRejectedValue(new ApiError('冲突', 409))
    const wrapper = view()
    await flushPromises()
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true,
      value: [new File(['zip bytes'], 'roles.zip', { type: 'application/zip' })] })
    await input.trigger('change')
    await flushPromises()
    expect(wrapper.text()).toContain('当前不能发布')
    expect(wrapper.find('.confirm-line').exists()).toBe(false)
    await wrapper.get('.panel-actions button').trigger('click')
    await flushPromises()
    await wrapper.get('.confirm-line input').setValue(true)
    await wrapper.findAll('.panel-actions button').at(-1)!.trigger('click')
    await flushPromises()
    expect(publish).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('请重新校验同一配置包')
    expect(wrapper.find('.confirm-line').exists()).toBe(false)
    await wrapper.get('.panel-actions button').trigger('click')
    await flushPromises()
    expect(validate).toHaveBeenCalledTimes(3)
    wrapper.unmount()
  })

  it('reuses one idempotency key when a publication result is uncertain', async () => {
    vi.spyOn(api, 'validateRoleImport').mockResolvedValue({ sourceSha256: 'c'.repeat(64), valid: true,
      roles: [{ roleId: role.roleId, displayName: '设计师' }], activations: [], diagnostics: [],
      changes: [{ path: 'description', before: '旧', after: '新' }] })
    const publish = vi.spyOn(api, 'publishRoleImport')
      .mockRejectedValueOnce(new Error('connection reset'))
      .mockResolvedValueOnce({ sourceSha256: 'c'.repeat(64), roles: [], bindings: [], replayed: true })
    const wrapper = view()
    await flushPromises()
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true,
      value: [new File(['zip bytes'], 'roles.zip', { type: 'application/zip' })] })
    await input.trigger('change')
    await flushPromises()
    await wrapper.get('.confirm-line input').setValue(true)
    const button = wrapper.findAll('.panel-actions button').at(-1)!
    await button.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('发布结果未确认')
    await button.trigger('click')
    await flushPromises()
    expect(publish).toHaveBeenCalledTimes(2)
    expect(publish.mock.calls[1]?.[1].idempotencyKey).toBe(publish.mock.calls[0]?.[1].idempotencyKey)
    expect(wrapper.text()).toContain('配置已发布过')
    wrapper.unmount()
  })
})
