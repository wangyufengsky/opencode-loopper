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
