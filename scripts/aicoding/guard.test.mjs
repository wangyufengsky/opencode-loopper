import { test } from 'node:test'
import assert from 'node:assert/strict'
import { LoopperAccountingGuard } from '../../src/main/resources/opencode/loopper-accounting-guard.mjs'

test('native guard removes accounting context and rejects its business tools by exact parent identity', async () => {
  const user = id => ({ info: { id, role: 'user', sessionID: 's' }, parts: [] })
  const answer = (id, parentID, callID) => ({ info: { id, role: 'assistant', parentID, sessionID: 's' },
    parts: callID ? [{ type: 'tool', callID }] : [] })
  const rows = [user('msg_loopper_aicoding_1'), answer('a', 'msg_loopper_aicoding_1', 'forbidden'),
    user('business'), answer('b', 'business', 'allowed')]
  const guard = await LoopperAccountingGuard({ client: { session: { messages: async () => ({ data: rows }) } }, directory: '/tmp' })
  const business = { messages: [...rows] }
  await guard['experimental.chat.messages.transform']({}, business)
  assert.deepEqual(business.messages.map(row => row.info.id), ['business', 'b'])
  await assert.rejects(() => guard['tool.execute.before']({ sessionID: 's', tool: 'write', callID: 'forbidden' }), /ACCOUNTING_TOOL_DENIED/)
  await guard['tool.execute.before']({ sessionID: 's', tool: 'write', callID: 'allowed' })
  await guard['tool.execute.before']({ sessionID: 's', tool: 'aicoding_complete', callID: 'forbidden' })
  const statistics = { messages: [...rows, user('msg_loopper_aicoding_2')] }
  await guard['experimental.chat.messages.transform']({}, statistics)
  assert.deepEqual(statistics.messages.map(row => row.info.id), ['msg_loopper_aicoding_2'])
})

test('statistics hide question and business tools per message without changing Session permissions', async () => {
  const rows = []
  const guard = await LoopperAccountingGuard({ directory: '/tmp', client: {
    tool: { ids: async () => ({ data: ['question', 'read', 'write', 'aicoding_story_continue'] }) },
    session: { messages: async () => ({ data: rows }) },
  } })
  const stats = { message: { id: 'msg_loopper_aicoding_begin', role: 'user', agent: 'build' }, parts: [] }
  await guard['chat.message']({ sessionID: 's' }, stats)
  assert.equal(stats.message.agent, 'loopper-accounting')
  assert.deepEqual(stats.message.tools, { question: false, read: false, write: false, list_mcp_resources: false, list_mcp_resource_templates: false, read_mcp_resource: false })
  await guard['tool.execute.before']({ sessionID: 's', tool: 'aicoding_story_continue', callID: 'stats' })
  await assert.rejects(() => guard['tool.execute.before']({ sessionID: 's', tool: 'question', callID: 'stats' }), /ACCOUNTING_TOOL_DENIED/)
  const business = { message: { id: 'business', role: 'user', agent: 'build' }, parts: [] }
  await guard['chat.message']({ sessionID: 's' }, business)
  assert.equal(business.message.agent, 'build')
  assert.deepEqual(business.message.tools, { aicoding_story_continue: false })
  await guard['tool.execute.before']({ sessionID: 's', tool: 'question', callID: 'business' })
  await assert.rejects(() => guard['tool.execute.before']({ sessionID: 's', tool: 'aicoding_story_continue', callID: 'business' }), /ACCOUNTING_TOOL_DENIED/)
  rows.push({ info: { id: 'late', parentId: stats.message.id }, parts: [{ type: 'tool', callID: 'late' }] })
  await assert.rejects(() => guard['tool.execute.before']({ sessionID: 's', tool: 'write', callID: 'late' }), /ACCOUNTING_TOOL_DENIED/)
})

test('tool discovery failure affects the statistics round only', async () => {
  const guard = await LoopperAccountingGuard({ client: { tool: { ids: async () => { throw Error('offline') } } } })
  await guard['chat.message']({ sessionID: 's' }, { message: { id: 'business' } })
  await assert.rejects(() => guard['chat.message']({ sessionID: 's' }, { message: { id: 'msg_loopper_aicoding_1' } }), /offline/)
})


test('ordinary OpenCode sessions can still run their manually selected aicoding plugin', async () => {
  const guard = await LoopperAccountingGuard({ client: { session: { get: async () => ({ data: { title: 'My manual session' } }) } } })
  const message = { message: { id: 'ordinary', agent: 'build' } }
  await guard['chat.message']({ sessionID: 'manual' }, message)
  assert.equal(message.message.tools, undefined)
  await guard['tool.execute.before']({ sessionID: 'manual', tool: 'aicoding_story_start', callID: 'manual-call' })
})

test('an unbound Loopper business Session cannot start statistics from its model', async () => {
  const guard = await LoopperAccountingGuard({ client: {
    session: { get: async () => ({ data: { title: 'OpenCode Loopper Requirement Designer' } }), messages: async () => ({ data: [] }) },
    tool: { ids: async () => ({ data: ['question', 'aicoding_story_start'] }) },
  } })
  const output = { message: { id: 'business', agent: 'build' } }
  await guard['chat.message']({ sessionID: 'unbound' }, output)
  assert.deepEqual(output.message.tools, { aicoding_story_start: false })
  await guard['tool.execute.before']({ sessionID: 'unbound', tool: 'question', callID: 'question' })
  await assert.rejects(() => guard['tool.execute.before']({ sessionID: 'unbound', tool: 'aicoding_story_start', callID: 'unexpected' }), /ACCOUNTING_TOOL_DENIED/)
})
