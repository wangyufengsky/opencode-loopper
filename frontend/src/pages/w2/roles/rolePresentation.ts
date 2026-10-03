import type { RoleDetail, RoleImportValidation, RolePermissionPreview, RoleRevision, RoleSlotBinding } from '@/types/domain'
import { roleToolDescription } from '@/utils/rolePresentation'
export function availableRoleSlots(role: RoleDetail | null, revision: RoleRevision | null, bindings: RoleSlotBinding[]) {
  const declared = revision?.manifest.allowedSlots
  const ids = Array.isArray(declared) ? declared.filter((value): value is string => typeof value === 'string') : role?.activeSlots ?? []
  return bindings.filter(binding => ids.includes(binding.slot) || binding.activeRoleId === role?.roleId)
}
export function roleTools(preview: RolePermissionPreview | null, revision: RoleRevision | null) {
  const tools = new Map<string, { name: string; description: string; source: string; status?: string; reason?: string; required?: boolean }>()
  for (const tool of preview?.mcpTools ?? []) tools.set(tool.name, { ...tool, description: roleToolDescription(tool.name),
    source: tool.source === 'NATIVE_POLICY' ? '原生工具' : tool.source === 'SYSTEM_REQUIRED' ? '服务端必需' : tool.source === 'BUNDLED_POLICY' ? '程序内置' : '配置声明' })
  // The actual bound preview can be older than latest published configuration. Never merge them.
  if (!preview) for (const name of [...(revision?.nativeTools ?? []), ...(revision?.mcpTools ?? [])]) if (!tools.has(name))
    tools.set(name, { name, description: roleToolDescription(name), source: '配置声明', required: revision?.requiredMcpTools?.includes(name) })
  return [...tools.values()]
}
export function importChanges(preview: RoleImportValidation | null) { return [...(preview?.changes ?? []), ...(preview?.roles ?? []).flatMap(role => role.changes ?? [])] }
export function permissionMode(revision: RoleRevision) {
  return revision.manifest.runtimePolicy === 'ACCOUNTING_COMMAND' ? '固定统计命令权限（角色配置不可扩权）' : revision.permissionMode === 'INTERSECT' ? '在既有授权内收窄' : revision.permissionMode === 'BASELINE' ? '沿用既有授权' : '配置模式待核实'
}
export function diagnostic(code: string, message: string) {
  if (/[\u3400-\u9fff]/.test(message)) return message
  return ({ ROLE_SLOTS_REQUIRED: '角色必须声明支持的流程阶段。', ROLE_PERMISSION_MODE_CONFLICT: '权限模式与工具清单冲突，请收窄权限或移除工具覆盖。',
    ROLE_SLOT_UNKNOWN: '配置包引用了当前版本不支持的流程阶段。', ROLE_SLOT_DUPLICATE: '同一阶段被重复激活。', ROLE_NATIVE_TOOL_UNSAFE: '该阶段不允许所选原生工具。',
    ROLE_MCP_TOOL_UNAVAILABLE: '所选工具不属于此阶段授权范围。', ROLE_PROMPT_SLOT_UNKNOWN: '模板片段不属于支持的插槽。', ROLE_PROMPT_VARIABLE_UNSUPPORTED: '当前版本不支持此模板变量。' } as Record<string, string>)[code] ?? '配置项不符合角色合同，请检查配置包后重新校验。'
}
export function formatValue(value: unknown): string { return value === undefined ? '无' : typeof value === 'string' ? value || '空' : JSON.stringify(value, null, 2) }
