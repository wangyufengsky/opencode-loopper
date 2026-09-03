/** Test-only JS plugin: requests are sent by native custom tools, never by the command hook. */
const { tool } = await import(process.env.AICODING_MOCK_PLUGIN_API ?? '@opencode-ai/plugin')
export const MockAicodingTools = async () => {
  const tools = {}
  for (const operation of ['start', 'continue', 'complete', 'status', 'sync']) {
    tools[`aicoding_story_${operation}`] = tool({
      description: `Execute the explicit aicoding ${operation} operation and return its receipt.`,
      args: ['start', 'continue'].includes(operation)
        ? { ipmpSystemCode: tool.schema.string(), storyCode: tool.schema.string() } : {},
      async execute(args, context) {
        const response = await fetch(`${process.env.AICODING_MOCK_URL}/accounting`, {
          method: 'POST', headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ operation, systemCode: args.ipmpSystemCode, storyCode: args.storyCode,
            sessionId: context.sessionID }),
        })
        const receipt = await response.json()
        if (!response.ok) throw new Error(`AICODING_MOCK: ${receipt.error}`)
        return JSON.stringify(receipt)
      },
    })
  }
  return {
    tool: tools,
    config: async config => {
      config.command ??= {}
      config.command.aicoding = { description: 'AICoding native custom-tool simulator',
        template: 'Execute AICoding accounting operation: $ARGUMENTS', subtask: false }
    },
    'command.execute.before': async (input, output) => {
      if (input.command !== 'aicoding') return
      const [operation, ipmpSystemCode, storyCode] = input.arguments.trim().split(/\s+/)
      if (!tools[`aicoding_story_${operation}`]) throw Error('Unknown accounting operation')
      const args = ['start', 'continue'].includes(operation) ? { ipmpSystemCode, storyCode } : {}
      output.parts.splice(0, output.parts.length, { type: 'text',
        text: `AICODING_NATIVE_TOOL ${operation} ${JSON.stringify(args)}\nUse exactly aicoding_story_${operation}. Return the tool result verbatim. Do not call business tools.` })
    },
  }
}
