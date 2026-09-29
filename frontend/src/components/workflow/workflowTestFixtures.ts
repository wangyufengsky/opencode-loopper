import type { WorkflowPreset, WorkflowTemplate, WorkflowTemplateSummary } from '@/types/domain'
import { emptyGraph, emptyLayout, newNode } from './graph'
export function template(overrides: Partial<WorkflowTemplate> = {}): WorkflowTemplate {
  const graph = emptyGraph(); graph.nodes = [{ ...newNode('human'), id: 'review', title: '人工验收', task: '核对交付物' }]
  return { id: 'example', title: '交付流程', description: '逐步完成工作', builtin: false, archived: false, revision: 2, headRevision: 2, version: 3, layoutVersion: 4, graph, layout: emptyLayout(), diagnostics: [], sourceTemplateId: null, sourceRevision: null, ...overrides }
}
export function summary(overrides: Partial<WorkflowTemplateSummary> = {}): WorkflowTemplateSummary {
  return { id: 'example', title: '交付流程', description: '逐步完成工作', builtin: false, headRevision: 2, version: 3, createdAt: '2026-09-28T00:00:00Z', updatedAt: '2026-09-28T00:00:00Z', ...overrides }
}
export function preset(overrides: Partial<WorkflowPreset> = {}): WorkflowPreset {
  return { id: 'analysis.read', version: 1, title: '资料分析', description: '阅读资料并交付结论', roleName: '通用助手', roleRevisionNumber: 3,
    inputs: [{ name: 'material', title: '参考资料', kind: 'TEXT', required: false }],
    node: { ...newNode('free.readonly'), id: 'preset', title: '资料分析', task: '阅读输入资料，区分证据和推断，交付结论。', roleId: 'builtin.general', roleRevisionId: 'role-v3', parameters: { presetId: 'analysis.read', presetVersion: '1' } }, ...overrides }
}
export function verificationPreset(): WorkflowPreset {
  return { id: 'verification.files', version: 1, title: '交付物内容检查', description: '检查固定代码的文件内容、哈希或移除结果', roleName: null, roleRevisionNumber: null,
    inputs: [{ name: 'code', title: '待检查代码', kind: 'CODE', required: true }],
    node: { ...newNode('free.readonly'), id: 'preset', title: '交付物内容检查', kind: 'SYSTEM', moduleId: 'system.verify.files', roleId: null, roleRevisionId: null,
      task: '检查上游固定交付物并保存程序报告。',
      outputs: [{ name: 'summary', title: '检查说明', kind: 'TEXT', required: true }, { name: 'report', title: '程序检查报告', kind: 'JSON', required: true }],
      outcomes: ['PASS', 'FAIL'], completion: { kind: 'VERIFIED', criterion: '所有配置的交付物检查通过', expectedOutcome: null },
      parameters: { presetId: 'verification.files', presetVersion: '1', outcomeTitles: JSON.stringify({ PASS: '检查通过', FAIL: '检查未通过' }), verification: JSON.stringify({ version: 1, inputName: 'code', checks: [] }) } } }
}

export function commandPreset(): WorkflowPreset {
  const original = verificationPreset()
  return { ...original, id: 'verification.command', title: '运行测试或检查命令', description: '对固定代码运行直接命令并保存结果',
    node: { ...original.node, title: '运行测试或检查命令', moduleId: 'system.verify.command', task: '运行配置的检查命令并保存程序报告。',
      completion: { kind: 'VERIFIED', criterion: '命令完整执行、正常退出并满足配置的输出要求', expectedOutcome: null },
      parameters: { presetId: 'verification.command', presetVersion: '1', outcomeTitles: original.node.parameters.outcomeTitles!,
        commandVerification: JSON.stringify({ version: 1, inputName: 'code', argv: [], timeoutSeconds: 300, purpose: 'TEST', outputContains: null }) } } }
}
