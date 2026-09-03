/**
 * Loopper transport guard; does not register or implement aicoding.
 * Statistics identities are durable message IDs, independent of model text.
 */
export const LoopperAccountingGuard = async ({ client, directory }) => {
  const prefix = 'msg_loopper_aicoding_'
  const affected = new Set()
  const statisticsRound = new Map()
  const parent = info => info?.parentID ?? info?.parentId
  const isStatistics = message => message.info?.id?.startsWith(prefix)
    || parent(message.info)?.startsWith(prefix)
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
        if (accounting ? !isAccountingTool(id) : isAccountingTool(id)) output.message.tools[id] = false
      }
      if (accounting) output.message.agent = 'loopper-accounting'
      statisticsRound.set(input.sessionID, accounting)
      if (accounting) affected.add(input.sessionID)
    },
    'experimental.chat.messages.transform': async (_input, output) => {
      const lastUser = output.messages.findLast(message => message.info?.role === 'user')
      if (!lastUser) return
      const session = lastUser.info.sessionID
      const accounting = lastUser.info.id.startsWith(prefix)
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
        if (!isAccountingTool(input.tool) || !await managedSession(input.sessionID)) return
      }
      let accounting = statisticsRound.get(input.sessionID) === true
      try {
        const result = await client.session.messages({ path: { id: input.sessionID }, query: { directory } })
        const owner = result.data?.find(message => message.parts?.some(part =>
          part.type === 'tool' && part.callID === input.callID))
        if (owner) accounting = isStatistics(owner)
      } catch { /* Preserve ordinary tool execution if the advisory lookup fails. */ }
      if (accounting && !isAccountingTool(input.tool)) throw new Error('LOOPPER_ACCOUNTING_TOOL_DENIED: statistics cannot execute business tools')
      if (!accounting && isAccountingTool(input.tool)) throw new Error('LOOPPER_ACCOUNTING_TOOL_DENIED: accounting tools require an explicit statistics round')
    },
  }
}
