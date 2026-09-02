/** Test-only native OpenCode plugin. Never installed by the production application. */
export const MockAicodingPlugin = async () => ({
  config: async (config) => {
    config.command ??= {}
    config.command.aicoding = {
      description: 'AICoding workload accounting simulator',
      template: 'Execute AICoding accounting operation: $ARGUMENTS',
      subtask: false,
    }
  },
  'command.execute.before': async (input, output) => {
    if (input.command !== 'aicoding') return
    const [operation, systemCode, storyCode] = input.arguments.trim().split(/\s+/)
    const response = await fetch(`${process.env.AICODING_MOCK_URL}/accounting`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ operation, systemCode, storyCode, sessionId: input.sessionID }),
    })
    const receipt = await response.json()
    if (!response.ok) throw new Error(`AICODING_MOCK: ${receipt.error}`)
    output.parts.splice(0, output.parts.length, {
      type: 'text', text: `AICODING_RECEIPT ${JSON.stringify(receipt)}\nReturn this receipt only. Do not use tools.`,
    })
  },
})
