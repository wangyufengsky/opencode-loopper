import { createServer } from 'node:http'

/** Independent request ledger and deterministic model for native transport tests. */
export async function createMockReceiver({ modelReply } = {}) {
  const requests = [], modelRequests = [], bindings = new Map(), runs = new Map(), activeRuns = new Map()
  let behavior = { delayMs: 0, fail: false, loseResponse: false }
  const server = createServer(async (request, response) => {
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    let body
    try { body = chunks.length ? JSON.parse(Buffer.concat(chunks)) : {} }
    catch { response.writeHead(400).end(); return }
    const json = (status, value) => {
      response.writeHead(status, { 'content-type': 'application/json' })
      response.end(JSON.stringify(value))
    }
    if (request.url === '/control') { behavior = { ...behavior, ...body }; json(200, behavior); return }
    if (request.url === '/requests') { json(200, { requests, modelRequests }); return }
    if (request.url === '/accounting') {
      const record = { ...body, at: new Date().toISOString(), ordinal: requests.length + 1 }
      requests.push(record)
      const selected = { ...behavior }
      if (selected.onlyOperation && selected.onlyOperation !== body.operation) Object.assign(selected, { delayMs: 0, fail: false, loseResponse: false })
      if (behavior.remaining !== undefined) {
        if (behavior.remaining <= 0) Object.assign(selected, { delayMs: 0, fail: false, loseResponse: false })
        else behavior.remaining -= 1
      }
      if (selected.delayMs) await new Promise(resolve => setTimeout(resolve, selected.delayMs))
      if (selected.fail) { json(503, { error: '模拟统计服务暂不可用' }); return }
      const { operation, systemCode, storyCode, sessionId } = body
      if (operation === 'start' || operation === 'continue') {
        if (!systemCode || !storyCode) { json(400, { error: '系统编号和故事编号必填' }); return }
        const key = `${systemCode}/${storyCode}`
        let runId = runs.get(key)
        if (operation === 'start') {
          if (selected.strictActiveRun && activeRuns.has(key)) {
            record.error = 'ACTIVE_RUN_EXISTS'; json(409, { ok: false, errorCode: record.error }); return
          }
          runId = `run-${record.ordinal}`; runs.set(key, runId); activeRuns.set(key, runId)
        }
        if (!runId) { json(409, { error: '故事尚未开始' }); return }
        bindings.set(sessionId, { runId, systemCode, storyCode, completed: false })
      } else if (!['complete', 'status', 'sync'].includes(operation)) {
        json(400, { error: '未知统计操作' }); return
      }
      const binding = bindings.get(sessionId)
      if (!binding) { json(409, { error: '当前会话未绑定故事' }); return }
      if (operation === 'complete') {
        binding.completed = true
        const key = `${binding.systemCode}/${binding.storyCode}`
        if (activeRuns.get(key) === binding.runId) activeRuns.delete(key)
      }
      record.receiptAt = new Date().toISOString()
      record.receipt = { ok: true, operation, sessionId, ...binding }
      if (selected.loseResponse) { response.destroy(); return }
      json(200, record.receipt)
      return
    }
    if (request.url === '/v1/chat/completions') {
      const last = body.messages?.findLast(message => message.role === 'user')
      const content = typeof last?.content === 'string' ? last.content
        : (last?.content ?? []).filter(part => part.type === 'text').map(part => part.text).join('\n')
      modelRequests.push({ at: new Date().toISOString(), content, body })
      const answer = content.includes('AICODING_RECEIPT') ? { text: content.split('\n')[0] }
        : modelReply ? await modelReply(body, content)
        : { text: content.match(/BUSINESS_RESULT_[A-Z0-9_]+/)?.[0] ?? 'BUSINESS_RESULT_OK' }
      let modelDelay = content.includes('AICODING_RECEIPT') ? (behavior.accountingModelDelayMs ?? 0) : 0
      if (behavior.modelOperation && !content.includes(`"operation":"${behavior.modelOperation}"`)) modelDelay = 0
      if (behavior.modelRemaining !== undefined) {
        if (behavior.modelRemaining <= 0) modelDelay = 0
        else if (modelDelay) behavior.modelRemaining -= 1
      }
      const text = answer.text ?? ''
      const toolCalls = answer.toolCalls
      const finishReason = toolCalls ? 'tool_calls' : 'stop'
      if (!body.stream) {
        json(200, { id: `chat-${modelRequests.length}`, object: 'chat.completion', model: 'mock',
          choices: [{ index: 0, message: { role: 'assistant', content: text, ...(toolCalls ? { tool_calls: toolCalls } : {}) }, finish_reason: finishReason }],
          usage: { prompt_tokens: 5, completion_tokens: 5, total_tokens: 10 } })
        return
      }
      response.writeHead(200, { 'content-type': 'text/event-stream' })
      const base = { id: `chat-${modelRequests.length}`, object: 'chat.completion.chunk', created: 1, model: 'mock' }
      response.write(`data: ${JSON.stringify({ ...base, choices: [{ index: 0, delta: { role: 'assistant', content: text, ...(toolCalls ? { tool_calls: toolCalls.map((call, index) => ({ ...call, index })) } : {}) }, finish_reason: null }] })}\n\n`)
      if (modelDelay) await new Promise(resolve => setTimeout(resolve, modelDelay))
      response.write(`data: ${JSON.stringify({ ...base, choices: [{ index: 0, delta: {}, finish_reason: finishReason }], usage: { prompt_tokens: 5, completion_tokens: 5, total_tokens: 10 } })}\n\n`)
      response.end('data: [DONE]\n\n')
      return
    }
    json(404, { error: 'not found' })
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  return { url: `http://127.0.0.1:${server.address().port}`, requests, modelRequests,
    setBehavior: value => { behavior = { ...behavior, ...value } },
    close: () => new Promise(resolve => { server.closeAllConnections(); server.close(resolve) }) }
}
