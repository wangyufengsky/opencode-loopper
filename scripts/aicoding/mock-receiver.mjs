import { createServer } from 'node:http'

/** Independent request ledger and deterministic model for native transport tests. */
export async function createMockReceiver({ modelReply, onControl } = {}) {
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
    if (request.url === '/control') {
      try {
        await onControl?.(body)
        behavior = { ...(body.reset ? { delayMs: 0, fail: false, loseResponse: false } : behavior), ...body }
        json(200, behavior)
      } catch (error) { json(400, { error: error.message }) }
      return
    }
    if (request.url === '/requests') { json(200, { requests, modelRequests }); return }
    if (request.url === '/accounting') {
      const record = { ...body, caseId: behavior.caseId, at: new Date().toISOString(), ordinal: requests.length + 1 }
      requests.push(record)
      const selected = { ...behavior, ...(behavior.operations?.[body.operation] ?? {}) }
      record.delayMs = selected.delayMs ?? 0
      response.once('close', () => { if (!response.writableEnded) record.clientClosedAt = new Date().toISOString() })
      const finish = (status, value) => {
        record.receiptAt = new Date().toISOString(); record.status = status; record.receipt = value
        if (status >= 400) record.error = value.errorCode ?? value.error
        json(status, value)
      }
      if (selected.onlyOperation && selected.onlyOperation !== body.operation) Object.assign(selected, { delayMs: 0, fail: false, loseResponse: false })
      if (behavior.remaining !== undefined && (!selected.onlyOperation || selected.onlyOperation === body.operation)) {
        if (behavior.remaining <= 0) Object.assign(selected, { delayMs: 0, fail: false, loseResponse: false })
        else behavior.remaining -= 1
      }
      if (selected.delayMs) await new Promise(resolve => setTimeout(resolve, selected.delayMs))
      if (selected.fail) {
        if (selected.seedExistingStory && body.operation === 'start') {
          const key = `${body.systemCode}/${body.storyCode}`
          if (!runs.has(key)) runs.set(key, `seeded-${record.ordinal}`)
          record.seededExistingStory = true
        }
        finish(503, { error: '模拟统计服务暂不可用' }); return
      }
      const { operation, systemCode, storyCode, sessionId } = body
      if (operation === 'start' || operation === 'continue') {
        if (!systemCode || !storyCode) { finish(400, { error: '系统编号和故事编号必填' }); return }
        const key = `${systemCode}/${storyCode}`
        let runId = runs.get(key)
        if (operation === 'start') {
          if (selected.strictActiveRun && activeRuns.has(key)) {
            record.error = 'ACTIVE_RUN_EXISTS'; finish(409, { ok: false, errorCode: record.error }); return
          }
          runId = `run-${record.ordinal}`; runs.set(key, runId); activeRuns.set(key, runId)
        }
        if (!runId) { finish(409, { error: '故事尚未开始' }); return }
        activeRuns.set(key, runId)
        bindings.set(sessionId, { runId, systemCode, storyCode, completed: false })
      } else if (!['complete', 'status', 'sync'].includes(operation)) {
        finish(400, { error: '未知统计操作' }); return
      }
      const binding = bindings.get(sessionId)
      if (!binding) { finish(409, { error: '当前会话未绑定故事' }); return }
      if (operation === 'complete') {
        binding.completed = true
        const key = `${binding.systemCode}/${binding.storyCode}`
        if (activeRuns.get(key) === binding.runId) activeRuns.delete(key)
      }
      record.receiptAt = new Date().toISOString()
      record.receipt = { ok: true, operation, sessionId, ...binding }
      if (selected.loseResponse) { response.destroy(); return }
      finish(200, record.receipt)
      return
    }
    if (request.url === '/v1/chat/completions') {
      const last = body.messages?.findLast(message => message.role === 'user')
      const content = typeof last?.content === 'string' ? last.content
        : (last?.content ?? []).filter(part => part.type === 'text').map(part => part.text).join('\n')
      const modelRecord = { at: new Date().toISOString(), caseId: behavior.caseId, content, body }; modelRequests.push(modelRecord)
      const answer = content.includes('AICODING_RECEIPT') ? { text: content.split('\n')[0] }
        : modelReply ? await modelReply(body, content)
        : { text: content.match(/BUSINESS_RESULT_[A-Z0-9_]+/)?.[0] ?? 'BUSINESS_RESULT_OK' }
      let modelDelay = content.includes('AICODING_RECEIPT') ? (behavior.accountingModelDelayMs ?? 0) : 0
      if (behavior.modelOperation && !content.includes(`"operation":"${behavior.modelOperation}"`)) modelDelay = 0
      if (behavior.modelRemaining !== undefined) {
        if (behavior.modelRemaining <= 0) modelDelay = 0
        else if (modelDelay) behavior.modelRemaining -= 1
      }
      modelRecord.replyAt = new Date().toISOString()
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
