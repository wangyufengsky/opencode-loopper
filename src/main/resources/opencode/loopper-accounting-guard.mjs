/**
 * Loopper transport guard; does not register or implement aicoding.
 * Statistics identities are durable message IDs, independent of model text.
 */
export const LoopperAccountingGuard = async ({ client, directory }) => {
  const prefix = 'msg_loopper_aicoding_'
  const affected = new Set()
  const statisticsRound = new Map()
  const isStatistics = message => message.info?.id?.startsWith(prefix)
    || message.info?.parentID?.startsWith(prefix)
  return {
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
        ? message.info.id === lastUser.info.id || message.info.parentID === lastUser.info.id
        : !isStatistics(message))
      output.messages.splice(0, output.messages.length, ...selected)
    },
    'tool.execute.before': async (input) => {
      if (!affected.has(input.sessionID) || /^aicoding(?:_|$)/.test(input.tool)) return
      let accounting = statisticsRound.get(input.sessionID) === true
      try {
        const result = await client.session.messages({ path: { id: input.sessionID }, query: { directory } })
        const owner = result.data?.find(message => message.parts?.some(part =>
          part.type === 'tool' && part.callID === input.callID))
        if (owner) accounting = isStatistics(owner)
      } catch { /* Preserve ordinary tool execution if the advisory lookup fails. */ }
      if (accounting) throw new Error('LOOPPER_ACCOUNTING_TOOL_DENIED: statistics cannot execute business tools')
    },
  }
}
