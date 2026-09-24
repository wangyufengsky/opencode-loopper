import type { RoleRevision } from '@/types/domain'

const variableDescriptions: Record<string, string> = {
  LOOPPER_PORT: '验证服务端口，由程序在验证时分配',
  project: '当前项目', projectName: '当前项目名称', projectId: '当前项目标识',
  requirement: '用户提出的需求', requirements: '已确认的需求内容', task: '当前任务内容',
  taskId: '当前任务标识', taskName: '当前任务名称', context: '当前工作流上下文',
  stage: '当前执行阶段', stageId: '当前阶段标识', evidence: '当前流程提供的证据',
  source: '当前任务的来源材料', language: '输出语言',
}

/** A reading view of published configuration, never an executable prompt renderer. */
export function rolePrompt(revision: RoleRevision) {
  let text = Object.entries(revision.promptFragments ?? {})
    .sort(([a], [b]) => a.localeCompare(b, 'en', { numeric: true }))
    .map(([, content]) => content).filter(content => content.trim()).join('\n\n')
  const inlineVariables = [...text.matchAll(/\{\{\s*([\p{L}_][\p{L}\p{N}_.-]*)\s*\}\}|\$\{([\p{L}_][\p{L}\p{N}_.-]*)\}/gu)]
    .map(match => match[1] ?? match[2]!)
  const variables = [...new Set([...(revision.promptVariables ?? []), ...inlineVariables]
    .map(name => name.replace(/^\$?\{\{?\s*|\s*\}\}?$/g, '')))]
    .filter(name => /^[\p{L}_][\p{L}\p{N}_.-]*$/u.test(name))
  for (const name of variables) {
    text = text.split(`\${${name}}`).join(`{${name}}`)
    text = text.replace(/\{\{\s*([\p{L}_][\p{L}\p{N}_.-]*)\s*\}\}/gu, (token, variable: string) => variable === name ? `{${name}}` : token)
  }
  return { text, variables: variables.map(name => ({ name, description: variableDescriptions[name] ?? `运行时提供的「${name}」内容` })) }
}
