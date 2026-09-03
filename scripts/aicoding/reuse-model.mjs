import { randomUUID } from 'node:crypto'
import { maintenanceModel } from './mock-model.mjs'

/** Local deterministic provider; requests still run through actual OpenCode and private MCP. */
export function reuseModel(workspaces) {
  const fallback = maintenanceModel(workspaces)
  return async (body, content) => {
    const tool = (name, args) => ({ toolCalls: [{ id: `call_${randomUUID().replaceAll('-', '')}`,
      type: 'function', function: { name, arguments: JSON.stringify(args) } }] })
    if (content.includes('AICODING_NATIVE_TOOL')) return fallback(body, content)
    if (content.includes('TASK_PROFILE_ROUTER_INPUT')) return { text: JSON.stringify({
      intent: 'SOFTWARE_CHANGE', artifactKinds: ['SOURCE_CODE'], complexity: 'SIMPLE' }) }
    const following = body.messages.slice(body.messages.findLastIndex(message => message.role === 'user') + 1)
    const used = following.flatMap(message => message.tool_calls ?? []).map(call => call.function?.name)
    const names = (body.tools ?? []).map(item => item.function?.name)
    if (content.includes('Produce one compact DECOMPOSITION_PLAN_V2 candidate')) {
      if (used.some(name => name.endsWith('_submit_candidate'))) return { text: '拆包候选已提交。' }
      const segments = content.match(/Numbered immutable requirement segments:\n([\s\S]*?)\n\nComplete immutable requirement:/)[1]
      const refs = [...segments.matchAll(/^(RQ-\d+):/gm)].map(match => match[1])
      return tool(names.find(name => /^loopper_internal_.*_submit_candidate$/.test(name)), {
        runId: content.match(/\nrunId: (.+)/)[1].trim(), idempotencyKey: randomUUID(),
        expectedSubmissionRevision: Number(content.match(/expectedSubmissionRevision: (\d+)/)[1]),
        candidate: { outcome: 'READY', normalizedGoal: '实现数学函数并验证结果', globalConstraints: [],
          workPackages: ['sum', 'difference', 'product'].map((name, i) => ({ title: `${name} 数学运算`, objective: `实现 ${name} 并通过原生测试`,
            scopeIn: [`src/${name}.js`, `test/${name}.test.js`], scopeOut: [], deliverables: [`src/${name}.js`, `test/${name}.test.js`],
            acceptanceIntent: [`${name} 返回正确数学运算结果`], dependsOn: [] })),
          coverage: refs.flatMap(requirementRef => [0, 1, 2].map(targetIndex => ({ requirementRef, targetType: 'WORK_PACKAGE', targetIndex, rationale: '每个包实现数学运算的一项功能并使用相同测试约束' }))),
          designGaps: [], reason: null } })
    }
    if (content.includes('PACKAGE_DESIGN_V1 PRIVATE SUBMISSION CONTRACT')) {
      if (names.includes('question') && !used.includes('question')) return tool('question', { questions: [{ header: '包内范围',
        question: '是否按当前包的加法验收继续设计？', options: [{ label: '确认', description: '继续当前包' }, { label: '补充', description: '补充设计约束' }] }] })
      if (used.some(name => name.endsWith('_submit_candidate'))) return { text: '设计候选已提交。' }
      const exact = content.match(/exact submit_candidate tool: (\S+)/)?.[1]
      const name = names.find(name => name === exact || /^loopper_internal_.*_submit_candidate$/.test(name))
      const slot = Number(content.match(/Current package WP-(\d+)/)?.[1] ?? 1) - 1
      const operation = ['sum', 'difference', 'product'][slot]
      const candidate = { contractVersion: 'PACKAGE_DESIGN_V1', outcome: 'READY',
        requirements: [{ key: 'REQ-1', statement: 'sum(a,b) 返回两个数字之和' }],
        scenarios: [{ key: 'SC-1', title: '计算加法', precondition: '传入数字 2 和 3', action: '调用 sum(2,3)',
          observableResult: '返回 5', invariant: '不修改输入', requirementRefs: ['REQ-1'] }],
        deliverables: [
          { key: 'DEL-1', kind: 'DELIVERABLE', target: 'src/sum.js', description: '修改实现 sum 加法函数', requirementRefs: ['REQ-1'] },
          { key: 'DEL-2', kind: 'DELIVERABLE', target: 'test/sum.test.js', description: '修改 Node 原生测试验证加法结果', requirementRefs: ['REQ-1'] }],
        reviews: [], stages: [{ key: 'STAGE-1', title: '实现加法', objective: '实现并验证加法', includes: ['SC-1', 'DEL-1', 'DEL-2'], dependencies: [] }], gapCodes: [] }
      const specialized = JSON.parse(JSON.stringify(candidate).replaceAll('sum', operation).replaceAll('加法', ['加法', '减法', '乘法'][slot]).replaceAll('两个数字之和', ['两个数字之和', '两个数字之差', '两个数字之积'][slot]).replaceAll('返回 5', `返回 ${[5, -1, 6][slot]}`))
      return tool(name, { runId: content.match(/\nrunId: (.+)/)[1].trim(), idempotencyKey: randomUUID(), candidate: specialized,
        expectedSubmissionRevision: Number(content.match(/expectedSubmissionRevision: (\d+)/)[1]) })
    }
    if (content.includes('需求讨论设计师') || content.includes('Requirement Designer')) {
      if (!used.includes('question') && names.includes('question')) return tool('question', { questions: [{ header: '输入范围',
        question: '是否按数字输入实现加法并使用 Node 原生测试？', options: [{ label: '确认', description: '数字加法与原生测试' }, { label: '补充', description: '补充边界' }] }] })
      return { text: '确认按数字输入实现加法并使用 Node 原生测试。' }
    }
    if (names.includes('write')) {
      const root = workspaces.find(path => JSON.stringify(body.messages).includes(path))
      if (!root) throw Error('Implementation workspace is missing')
      const allowed = JSON.parse(content.match(/Allowed paths: (.+)/)?.[1] ?? '[]')
      const op = allowed.map(path => /src\/(sum|difference|product)\.js$/.exec(path)?.[1]).find(Boolean)
      if (!op) throw Error('Expected exact operation in the current frozen stage')
      const sign = { sum: '+', difference: '-', product: '*' }[op]
      const expected = { sum: 5, difference: -1, product: 6 }[op]
      if (!used.includes('read')) return tool('read', { filePath: `${root}/src/${op}.js` })
      const writes = used.filter(name => name === 'write').length
      if (writes === 0) return tool('write', { filePath: `${root}/src/${op}.js`, content: `export function ${op}(a, b) { return a ${sign} b }\n` })
      if (writes === 1) return tool('write', { filePath: `${root}/test/${op}.test.js`, content: `import { test } from 'node:test'; import assert from 'node:assert/strict'; import { ${op} } from '../src/${op}.js'; test('${op}', () => { assert.equal(${op}(2, 3), ${expected}); assert.equal(${op}(0, 0), 0); });\n` })
      return { text: '加法函数与原生测试已更新。' }
    }
    return fallback(body, content)
  }
}
