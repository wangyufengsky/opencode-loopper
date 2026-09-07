import test from 'node:test'
import assert from 'node:assert/strict'
import { LoopperAccountingGuard } from '../../src/main/resources/opencode/loopper-accounting-guard.mjs'

test('semantic preparation narrows all submission and question tools and rejects execution', async () => {
  const tools = ['read', 'glob', 'grep', 'question', 'private_submit_package_design', 'private_submit_package_design_v2', 'private_submit_candidate']
  let parentID = 'msg_loopper_design_s_012345'
  const client = {
    tool: { ids: async () => ({ data: tools }) },
    session: {
      get: async () => ({ data: { title: 'OpenCode Loopper package Designer' } }),
      messages: async () => ({ data: [{ info: { parentID }, parts: [{ type: 'tool', callID: 'call' }] }] }),
    },
  }
  const guard = await LoopperAccountingGuard({ client, directory: '/tmp/isolated' })
  const output = { message: { id: parentID } }
  await guard['chat.message']({ sessionID: 'session' }, output)
  for (const id of tools.slice(3)) {
    assert.equal(output.message.tools[id], false)
    await assert.rejects(guard['tool.execute.before']({ sessionID: 'session', callID: 'call', tool: id }), /LOOPPER_DESIGN_PHASE_TOOL_DENIED/)
  }
  assert.notEqual(output.message.tools.read, false)
  parentID = 'msg_loopper_design_p_012345'
  await guard['tool.execute.before']({ sessionID: 'session', callID: 'call', tool: 'private_submit_package_design_v2' })
  await assert.rejects(guard['tool.execute.before']({ sessionID: 'session', callID: 'call', tool: 'question' }), /LOOPPER_DESIGN_PHASE_TOOL_DENIED/)
})
