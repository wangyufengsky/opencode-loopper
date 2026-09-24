import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtemp, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { LoopperAccountingGuard } from '../../src/main/resources/opencode/loopper-accounting-guard.mjs'

test('configured statistics inject only the frozen static fragment for the exact message', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'loopper-accounting-role-'))
  try {
    const sessionID = 'session-1'
    const messageID = `msg_loopper_aicoding_${'a'.repeat(32)}_role`
    const prompt = '本轮只执行已冻结的统计指令。'
    const sha = value => createHash('sha256').update(value).digest('hex')
    const descriptor = JSON.stringify({ schemaVersion: 1, sessionID, messageID, roleID: 'accounting',
      revisionID: 'revision-1', roleSha256: 'b'.repeat(64), promptSha256: sha(prompt), prompt })
    await writeFile(join(directory, `${messageID}.${sha(descriptor)}.json`), descriptor)
    const rows = [{ info: { id: messageID, sessionID, role: 'user' }, parts: [] }]
    const guard = await LoopperAccountingGuard({ directory: '/tmp', client: {
      tool: { ids: async () => ({ data: ['aicoding_story_start', 'read'] }) },
      session: { messages: async () => ({ data: rows }) },
    } }, directory)
    await guard['chat.message']({ sessionID }, { message: { id: messageID, role: 'user' }, parts: [] })
    await assert.rejects(() => guard['tool.execute.before']({ sessionID, tool: 'aicoding_story_start', callID: 'call-1' }), /ROLE_PROMPT_MISSING/)
    const system = { system: ['base'] }
    await guard['experimental.chat.system.transform']({ sessionID }, system)
    assert.deepEqual(system.system, ['base', prompt])
    rows.push({ info: { id: 'assistant-1', role: 'assistant', sessionID, parentID: messageID },
      parts: [{ type: 'tool', callID: 'call-1' }] })
    await guard['tool.execute.before']({ sessionID, tool: 'aicoding_story_start', callID: 'call-1' })
    rows.push({ info: { id: 'business', sessionID, role: 'user' }, parts: [] })
    await guard['chat.message']({ sessionID }, { message: { id: 'business', role: 'user' }, parts: [] })
    const business = { system: ['base'] }
    await guard['experimental.chat.system.transform']({ sessionID }, business)
    assert.deepEqual(business.system, ['base'])
  } finally { await rm(directory, { recursive: true, force: true }) }
})

test('configured statistics reject missing, changed or mismatched role descriptors', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'loopper-accounting-role-'))
  try {
    const sessionID = 'session-1'
    const messageID = `msg_loopper_aicoding_${'c'.repeat(32)}_role`
    const rows = [{ info: { id: messageID, sessionID, role: 'user' }, parts: [] }]
    const guard = await LoopperAccountingGuard({ directory: '/tmp', client: {
      tool: { ids: async () => ({ data: ['aicoding_story_start'] }) },
      session: { messages: async () => ({ data: rows }) },
    } }, directory)
    await assert.rejects(() => guard['chat.message']({ sessionID }, { message: { id: messageID } }), /DESCRIPTOR_MISSING/)
    const sha = value => createHash('sha256').update(value).digest('hex')
    const descriptor = JSON.stringify({ schemaVersion: 1, sessionID: 'another-session', messageID,
      roleID: 'accounting', revisionID: 'revision-1', roleSha256: 'b'.repeat(64),
      promptSha256: sha('static'), prompt: 'static' })
    const path = join(directory, `${messageID}.${sha(descriptor)}.json`)
    await writeFile(path, descriptor)
    await assert.rejects(() => guard['chat.message']({ sessionID }, { message: { id: messageID } }), /DESCRIPTOR_INVALID/)
    await writeFile(path, descriptor + 'changed')
    await assert.rejects(() => guard['chat.message']({ sessionID }, { message: { id: messageID } }), /DESCRIPTOR_INVALID/)
  } finally { await rm(directory, { recursive: true, force: true }) }
})

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

test('reused designer narrows question and submission tools by the exact business message', async () => {
  const rows = []
  const submissionTools = [
    'private_submit_decomposition_plan', 'private_submit_acceptance_choice',
    'private_submit_package_design', 'private_submit_rolling_package_plan',
    'private_submit_reviewer_report', 'private_submit_project_convention',
    'private_submit_judge_decision', 'private_submit_candidate',
  ]
  const guard = await LoopperAccountingGuard({ client: {
    session: { get: async () => ({ data: { title: 'OpenCode Loopper Requirement Designer' } }), messages: async () => ({ data: rows }) },
    tool: { ids: async () => ({ data: ['read', 'question', ...submissionTools, 'aicoding_story_start'] }) },
  } })
  const requirement = { message: { id: 'msg_loopper_design_r_1' } }
  await guard['chat.message']({ sessionID: 's' }, requirement)
  submissionTools.forEach(name => assert.equal(requirement.message.tools[name], false))
  assert.equal(requirement.message.tools.question, undefined)
  const design = { message: { id: 'msg_loopper_design_p_2' } }
  await guard['chat.message']({ sessionID: 's' }, design)
  assert.equal(design.message.tools.question, false)
  submissionTools.forEach(name => assert.equal(design.message.tools[name], undefined))
  rows.push({ info: { id: 'old', parentID: requirement.message.id }, parts: [{ type: 'tool', callID: 'old-call' }] })
  rows.push({ info: { id: 'new', parentID: design.message.id }, parts: [{ type: 'tool', callID: 'new-call' }] })
  for (const name of submissionTools) {
    await assert.rejects(() => guard['tool.execute.before']({ sessionID: 's', tool: name, callID: 'old-call' }), /DESIGN_PHASE_TOOL_DENIED/)
  }
  await assert.rejects(() => guard['tool.execute.before']({ sessionID: 's', tool: 'question', callID: 'new-call' }), /DESIGN_PHASE_TOOL_DENIED/)
  for (const name of submissionTools) {
    await guard['tool.execute.before']({ sessionID: 's', tool: name, callID: 'new-call' })
  }
})
