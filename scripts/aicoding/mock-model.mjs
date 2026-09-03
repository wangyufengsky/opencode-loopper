import { randomUUID } from 'node:crypto'

/** Deterministic local provider. All file changes go through real OpenCode tools. */
export function maintenanceModel(workspaces) {
  return async (body, content) => {
    const tool = (name, args) => ({ toolCalls: [{ id: `call_${randomUUID().replaceAll('-', '')}`,
      type: 'function', function: { name, arguments: JSON.stringify(args) } }] })
    if (content.includes('AICODING_NATIVE_TOOL')) {
      const receipt = body.messages.slice(body.messages.findLastIndex(message => message.role === 'user') + 1).findLast(message => message.role === 'tool')
      if (receipt) return { text: receipt.content }
      const [, operation, args] = content.match(/AICODING_NATIVE_TOOL (\w+) ([^\n]+)/)
      const name = `aicoding_story_${operation}`
      if (!(body.tools ?? []).some(item => item.function?.name === name)) {
        return { text: `ACCOUNTING_TOOL_NOT_EXPOSED: ${name}` }
      }
      return tool(name, JSON.parse(args))
    }
    if (content.includes('TASK_PROFILE_ROUTER_INPUT')) return { text: JSON.stringify({
      intent: 'LOCAL_MAINTENANCE', artifactKinds: ['CONFIGURATION'], complexity: 'SIMPLE',
    }) }
    if (content.includes('需求评审员') || content.includes('风险评审员')) {
      if (content.includes('JUDGE_DECISION_V1 PRIVATE SUBMISSION CONTRACT')) {
        if (body.messages.at(-1)?.role === 'tool') return { text: '已提交评审候选。' }
        const exact = content.match(/candidate by calling `([^`]+)`/)?.[1]
        const name = (body.tools ?? []).map(item => item.function?.name).find(name => name === exact || name.endsWith(exact))
        const evidence = JSON.parse(content.match(/frozenEvidenceCatalog: (.+)/)[1])
        const ids = Array.isArray(evidence) ? evidence.map(row => row.id) : (evidence.entries ?? evidence.items ?? []).map(row => row.id)
        return tool(name, { runId: content.match(/\nrunId: (.+)/)[1].trim(), idempotencyKey: randomUUID(),
          expectedSubmissionRevision: Number(content.match(/expectedSubmissionRevision: (\d+)/)[1]),
          candidate: { contractVersion: 'JUDGE_DECISION_V1', role: content.match(/- role: "([^"]+)"/)[1],
            verdict: 'PASS', reason: '配置修改符合确认需求；冻结差异证明目标配置已更新且未修改其他内容。', evidenceIds: ids } })
      }
      return { text: JSON.stringify({ verdict: 'PASS', reason: '配置修改符合确认需求。\n## 证据\n1. 已持久化差异显示 feature.enabled 从 false 变为 true，未修改其他文件。' }) }
    }
    const lastUser = body.messages.findLastIndex(message => message.role === 'user')
    const following = body.messages.slice(lastUser + 1)
    const used = following.flatMap(message => message.tool_calls ?? []).map(call => call.function?.name)
    const names = (body.tools ?? []).map(item => item.function?.name)
    if (content.includes('需求讨论设计师') || content.includes('Requirement Designer')) {
      if (!used.includes('question') && names.includes('question')) return tool('question', {
        questions: [{ header: '维护范围', question: '是否仅修改指定配置项并保留其他内容？',
          options: [{ label: '确认（推荐）', description: '按确认稿执行' }, { label: '继续讨论', description: '补充修改范围' }] }],
      })
      return { text: '本地配置维护：修改 `config.properties`，将 feature.enabled 从 false 改为 true，保留其他配置内容。' }
    }
    const all = JSON.stringify(body.messages)
    const root = workspaces.find(path => all.includes(path))
    if (root && names.includes('write')) {
      if (!used.includes('read')) return tool('read', { filePath: `${root}/config.properties` })
      if (!used.includes('write')) return tool('write', { filePath: `${root}/config.properties`,
        content: 'feature.enabled=true\nkeep.value=unchanged\n' })
      return { text: '指定配置已更新，其他内容保持不变。' }
    }
    return { text: 'BUSINESS_RESULT_OK' }
  }
}
