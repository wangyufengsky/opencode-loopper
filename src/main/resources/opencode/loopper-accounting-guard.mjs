/**
 * Loopper transport guard; does not register or implement aicoding.
 * Statistics identities are durable message IDs, independent of model text.
 */
import { createHash } from 'node:crypto'
import { readFile, readdir } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const configuredMessage = /^msg_loopper_aicoding_[0-9a-f]{32}_role$/
const sha256 = value => createHash('sha256').update(value).digest('hex')
const defaultDispatchDirectory = join(dirname(fileURLToPath(import.meta.url)), 'accounting-role-dispatch')

async function roleDescriptor(directory, sessionID, messageID) {
  if (!configuredMessage.test(messageID) || !sessionID) throw new Error('LOOPPER_ACCOUNTING_ROLE_IDENTITY_INVALID')
  let names
  try { names = (await readdir(directory)).filter(name => name.startsWith(`${messageID}.`) && name.endsWith('.json')) }
  catch { throw new Error('LOOPPER_ACCOUNTING_ROLE_DESCRIPTOR_MISSING') }
  if (names.length === 0) throw new Error('LOOPPER_ACCOUNTING_ROLE_DESCRIPTOR_MISSING')
  if (names.length !== 1 || !new RegExp(`^${messageID}\\.[0-9a-f]{64}\\.json$`).test(names[0]))
    throw new Error('LOOPPER_ACCOUNTING_ROLE_DESCRIPTOR_INVALID')
  let bytes
  try { bytes = await readFile(join(directory, names[0])) }
  catch { throw new Error('LOOPPER_ACCOUNTING_ROLE_DESCRIPTOR_MISSING') }
  if (bytes.length > 300_000 || sha256(bytes) !== names[0].slice(messageID.length + 1, -5))
    throw new Error('LOOPPER_ACCOUNTING_ROLE_DESCRIPTOR_INVALID')
  let descriptor
  try { descriptor = JSON.parse(bytes.toString('utf8')) }
  catch { throw new Error('LOOPPER_ACCOUNTING_ROLE_DESCRIPTOR_INVALID') }
  if (descriptor.schemaVersion !== 1 || descriptor.sessionID !== sessionID || descriptor.messageID !== messageID
      || typeof descriptor.roleID !== 'string' || !descriptor.roleID
      || typeof descriptor.revisionID !== 'string' || !descriptor.revisionID
      || !/^[0-9a-f]{64}$/.test(descriptor.roleSha256)
      || typeof descriptor.prompt !== 'string' || !descriptor.prompt.trim()
      || Buffer.byteLength(descriptor.prompt, 'utf8') > 256_000
      || descriptor.promptSha256 !== sha256(Buffer.from(descriptor.prompt, 'utf8')))
    throw new Error('LOOPPER_ACCOUNTING_ROLE_DESCRIPTOR_INVALID')
  return descriptor
}

export const LoopperAccountingGuard = async ({ client, directory }, dispatchDirectory = defaultDispatchDirectory) => {
  const prefix = 'msg_loopper_aicoding_'
  const affected = new Set()
  const statisticsRound = new Map()
  const configuredRound = new Map()
  const roleInjected = new Set()
  const designSessions = new Set()
  const parent = info => info?.parentID ?? info?.parentId
  const isStatistics = message => message.info?.id?.startsWith(prefix)
    || parent(message.info)?.startsWith(prefix)
  const designPhase = id => /^msg_loopper_design_([rqps])_/.exec(id ?? '')?.[1]
  const submissionTools = [
    'submit_decomposition_plan', 'submit_acceptance_choice', 'submit_package_design', 'submit_package_design_v2',
    'submit_rolling_package_plan', 'submit_reviewer_report', 'submit_project_convention',
    'submit_judge_decision', 'submit_candidate',
  ]
  const isSubmissionTool = id => submissionTools.some(name => id?.endsWith(`_${name}`))
  const deniedInPhase = (id, phase) => (phase === 'r' || phase === 's') && isSubmissionTool(id) || (phase === 'p' || phase === 's') && id === 'question'
  const isAccountingTool = name => /^aicoding(?:_|$)/.test(name)
  const managedSession = async sessionID => {
    if (affected.has(sessionID)) return true
    const result = await client.session.get({ path: { id: sessionID }, query: { directory } })
    if (!result.data?.title?.startsWith('OpenCode Loopper ')) return false
    affected.add(sessionID)
    return true
  }
  return {
    'chat.message': async (input, output) => {
      const accounting = output.message.id.startsWith(prefix)
      if (configuredMessage.test(output.message.id)) {
        await roleDescriptor(dispatchDirectory, input.sessionID, output.message.id)
        configuredRound.set(input.sessionID, output.message.id)
        roleInjected.delete(`${input.sessionID}:${output.message.id}`)
      } else configuredRound.delete(input.sessionID)
      if (designPhase(output.message.id)) designSessions.add(input.sessionID)
      if (!accounting) {
        try { if (!await managedSession(input.sessionID)) return }
        catch { return } // Unavailable metadata must not break an ordinary prompt.
      }
      // Message.tools only narrows this round; it must not replace Session.permission.
      // In particular, an interactive Designer's question tool is not a statistics tool.
      let ids
      try { ids = (await client.tool.ids({ query: { directory } })).data }
      catch (error) { if (accounting) throw error; return }
      if (!Array.isArray(ids)) {
        if (accounting) throw new Error('LOOPPER_ACCOUNTING_TOOLS_UNAVAILABLE: cannot isolate statistics tools')
        return
      }
      output.message.tools ??= {}
      for (const id of [...ids, 'list_mcp_resources', 'list_mcp_resource_templates', 'read_mcp_resource']) {
        if (accounting ? !isAccountingTool(id) : isAccountingTool(id) || deniedInPhase(id, designPhase(output.message.id))) output.message.tools[id] = false
      }
      if (accounting) output.message.agent = 'loopper-accounting'
      statisticsRound.set(input.sessionID, accounting)
      if (accounting) affected.add(input.sessionID)
    },
    'experimental.chat.system.transform': async (input, output) => {
      if (!input.sessionID) return
      let messages
      try { messages = (await client.session.messages({ path: { id: input.sessionID }, query: { directory } })).data }
      catch {
        if (configuredRound.has(input.sessionID)) throw new Error('LOOPPER_ACCOUNTING_ROLE_MESSAGE_UNVERIFIED')
        return
      }
      const lastUser = Array.isArray(messages) ? messages.findLast(message => message.info?.role === 'user') : null
      const messageID = lastUser?.info?.id
      if (!configuredMessage.test(messageID ?? '')) {
        if (configuredRound.has(input.sessionID)) throw new Error('LOOPPER_ACCOUNTING_ROLE_MESSAGE_UNVERIFIED')
        return
      }
      if (lastUser.info.sessionID !== input.sessionID || configuredRound.get(input.sessionID) !== messageID)
        throw new Error('LOOPPER_ACCOUNTING_ROLE_MESSAGE_UNVERIFIED')
      const descriptor = await roleDescriptor(dispatchDirectory, input.sessionID, messageID)
      output.system.push(descriptor.prompt)
      roleInjected.add(`${input.sessionID}:${messageID}`)
    },
    'experimental.chat.messages.transform': async (_input, output) => {
      const lastUser = output.messages.findLast(message => message.info?.role === 'user')
      if (!lastUser) return
      const session = lastUser.info.sessionID
      const accounting = lastUser.info.id.startsWith(prefix)
      if (designPhase(lastUser.info.id)) designSessions.add(session)
      statisticsRound.set(session, accounting)
      if (output.messages.some(isStatistics)) affected.add(session)
      if (!affected.has(session)) return
      // Accounting sees its own operation only; business cannot consume receipts.
      const selected = output.messages.filter(message => accounting
        ? message.info.id === lastUser.info.id || parent(message.info) === lastUser.info.id
        : !isStatistics(message))
      output.messages.splice(0, output.messages.length, ...selected)
    },
    'tool.execute.before': async (input) => {
      if (!affected.has(input.sessionID)) {
        if (!isAccountingTool(input.tool) && !isSubmissionTool(input.tool) && input.tool !== 'question' || !await managedSession(input.sessionID)) return
      }
      let phase
      let accounting = statisticsRound.get(input.sessionID) === true
      try {
        const result = await client.session.messages({ path: { id: input.sessionID }, query: { directory } })
        const owner = result.data?.find(message => message.parts?.some(part =>
          part.type === 'tool' && part.callID === input.callID))
        if (owner) {
          accounting = isStatistics(owner)
          phase = designPhase(parent(owner.info))
          const messageID = parent(owner.info)
          if (configuredMessage.test(messageID ?? '') && !roleInjected.has(`${input.sessionID}:${messageID}`))
            throw new Error('LOOPPER_ACCOUNTING_ROLE_PROMPT_MISSING')
        }
      } catch (error) {
        if (configuredRound.has(input.sessionID) || String(error?.message).startsWith('LOOPPER_ACCOUNTING_ROLE_')) throw error
        /* Preserve ordinary tool execution if the advisory lookup fails. */
      }
      if (configuredRound.has(input.sessionID) && !roleInjected.has(`${input.sessionID}:${configuredRound.get(input.sessionID)}`))
        throw new Error('LOOPPER_ACCOUNTING_ROLE_PROMPT_MISSING')
      if (!accounting && !phase && designSessions.has(input.sessionID) && (isSubmissionTool(input.tool) || input.tool === 'question')) throw new Error('LOOPPER_DESIGN_PHASE_UNKNOWN: cannot verify tool message identity')
      if (deniedInPhase(input.tool, phase)) throw new Error('LOOPPER_DESIGN_PHASE_TOOL_DENIED: tool is unavailable in the current design phase')
      if (accounting && !isAccountingTool(input.tool)) throw new Error('LOOPPER_ACCOUNTING_TOOL_DENIED: statistics cannot execute business tools')
      if (!accounting && isAccountingTool(input.tool)) throw new Error('LOOPPER_ACCOUNTING_TOOL_DENIED: accounting tools require an explicit statistics round')
    },
  }
}
