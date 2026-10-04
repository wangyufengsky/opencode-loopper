/** Private loopback provider/gateway. Only this test instance injects faults. */
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { createServer } from 'node:http'

const hash = bytes => createHash('sha256').update(bytes).digest('hex')
const modes = ['normal', 'provider-error', 'transport-deadline', 'cancel']
export async function createFaultReceiver({ authorization, signal, work }) {
  let nativeEndpoint, current, closed = false
  const models = [], transport = [], violations = [], timers = new Set(), forwards = new Set()
  const sockets = new Set()
  const json = (res, status, body) => {
    if (!res.destroyed) { res.writeHead(status, { 'content-type': 'application/json' }); res.end(JSON.stringify(body)) }
  }
  async function bytes(req) {
    const chunks = []; let size = 0
    for await (const chunk of req) {
      size += chunk.length; assert.ok(size <= 65536, 'request body bound')
      chunks.push(chunk)
    }
    return Buffer.concat(chunks)
  }
  function delay(ms, res) {
    return new Promise(resolve => {
      const item = { timer: null, finish: null }
      const finish = () => { clearTimeout(item.timer); timers.delete(item); res.off('close', finish); resolve() }
      item.finish = finish; item.timer = setTimeout(finish, ms); timers.add(item)
      res.once('close', finish)
    })
  }
  const server = createServer(async (req, res) => {
    try {
      assert.ok(!closed && current, 'active scenario required')
      const url = new URL(req.url, 'http://127.0.0.1')
      const raw = await bytes(req)
      if (url.pathname === '/v1/chat/completions') {
        assert.equal(req.method, 'POST')
        assert.equal(current.modelRequests, 0, 'no provider retry permitted')
        assert.ok(models.length < 4, 'four total local model requests maximum')
        const body = JSON.parse(raw.toString('utf8'))
        assert.equal((body.tools ?? []).length, 0, 'advertised tools must be empty')
        const user = body.messages?.findLast(row => row.role === 'user')
        const content = typeof user?.content === 'string' ? user.content
          : (user?.content ?? []).filter(row => row.type === 'text').map(row => row.text).join('\n')
        assert.ok(content.includes(current.marker), 'original synthetic prompt marker')
        const row = { caseId: current.mode, marker: current.marker, bodySha256: hash(raw),
          tools: 0, startedAt: new Date().toISOString(), stream: body.stream === true,
          closed: false, ended: false }
        models.push(row); current.modelRequests++
        res.once('close', () => { row.closed = true; row.closedAt = new Date().toISOString(); row.ended = res.writableEnded })
        if (current.mode === 'provider-error') {
          row.httpStatus = 400
          json(res, 400, { error: { message: 'SYNTHETIC_PROVIDER_ERROR', type: 'invalid_request_error', code: 'synthetic_fault' } })
          return
        }
        assert.equal(body.stream, true, 'native streaming provider path required')
        row.httpStatus = 200
        res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' })
        const base = { id: `fault-${models.length}`, object: 'chat.completion.chunk', created: 1, model: 'mock' }
        const chunk = (delta, finish_reason) => res.write(`data: ${JSON.stringify({ ...base,
          choices: [{ index: 0, delta, finish_reason }] })}\n\n`)
        if (current.mode === 'cancel') {
          chunk({ role: 'assistant', content: '' }, null)
          row.activeStream = true
          await delay(12000, res)
          row.activeStream = false
          if (res.destroyed) return
          // Reaching this instead of explicit abort is a failure, never success.
          violations.push('cancel stream survived its 12s bound')
        }
        if (!res.destroyed) {
          chunk({ role: 'assistant', content: current.marker }, null)
          chunk({}, 'stop'); res.end('data: [DONE]\n\n')
        }
        return
      }
      assert.ok(req.headers.authorization === authorization, 'private gateway authentication')
      if (url.pathname === '/probe/evidence') {
        assert.equal(req.method, 'GET')
        json(res, 200, snapshot()); return
      }
      if (url.pathname === '/probe/control') {
        assert.equal(req.method, 'POST')
        assert.deepEqual(JSON.parse(raw.toString('utf8')), { action: 'armReadDeadline' })
        assert.equal(current.mode, 'transport-deadline')
        assert.equal(current.prompts, 1)
        assert.equal(current.deadlineArmed, false)
        current.deadlineArmed = true; json(res, 200, { armed: true }); return
      }
      assert.ok(nativeEndpoint, 'native target must be configured')
      assert.equal(url.searchParams.get('directory'), work, 'only this private execution directory')
      assert.ok(transport.length < 240, 'total forwarded request bound')
      assert.ok(['GET', 'POST'].includes(req.method))
      const create = req.method === 'POST' && url.pathname === '/session'
      const prompt = req.method === 'POST' && /^\/session\/[^/]+\/prompt_async$/.test(url.pathname)
      const abort = req.method === 'POST' && /^\/session\/[^/]+\/abort$/.test(url.pathname)
      assert.ok(req.method === 'GET' ? /^\/(global\/health|session(?:\/.*)?)$/.test(url.pathname) : create || prompt || abort,
        'allow only probe-owned session protocol')
      if (create) { assert.equal(current.creates, 0); current.creates++ }
      const sessionPath = url.pathname.match(/^\/session\/([^/]+)(?:\/|$)/)
      if (sessionPath && sessionPath[1] !== 'status') assert.equal(sessionPath[1], current.sessionId, 'only the original scenario session')
      if (prompt) {
        assert.equal(current.prompts, 0, 'accepted/unknown prompt cannot be repeated')
        current.prompts++
      }
      if (abort) { assert.equal(current.mode, 'cancel'); assert.equal(current.aborts, 0); current.aborts++ }
      const row = { caseId: current.mode, method: req.method, path: url.pathname,
        query: url.search, bodySha256: hash(raw), at: new Date().toISOString(),
        delayMs: current.deadlineArmed && req.method === 'GET' && url.pathname === '/session/status' ? 1000 : 0 }
      transport.push(row)
      if (prompt) {
        const body = JSON.parse(raw.toString('utf8'))
        row.messageId = body.messageID
        assert.ok(typeof row.messageId === 'string' && row.messageId.startsWith('msg_'))
      }
      if (row.delayMs) current.deadlineArmed = false
      const controller = new AbortController(); forwards.add(controller)
      try {
        const upstream = await fetch(nativeEndpoint + url.pathname + url.search, {
          method: req.method, headers: { authorization, ...(raw.length ? { 'content-type': 'application/json' } : {}) },
          body: req.method === 'GET' ? undefined : raw,
          redirect: 'error', signal: AbortSignal.any([signal, controller.signal, AbortSignal.timeout(4000)]) })
        const value = Buffer.from(await upstream.arrayBuffer())
        assert.ok(value.length <= 1024 * 1024, 'upstream response bound')
        row.upstreamStatus = upstream.status; row.responseSha256 = hash(value)
        row.upstreamReceivedAt = new Date().toISOString()
        if (create && upstream.status === 200) {
          current.sessionId = JSON.parse(value.toString('utf8')).id
          assert.ok(typeof current.sessionId === 'string' && current.sessionId.length > 0)
        }
        if (row.delayMs) await delay(row.delayMs, res)
        row.clientClosedBeforeReply = res.destroyed
        if (!res.destroyed) {
          // Forward the original status/body/content-type without a synthesized receipt.
          res.writeHead(upstream.status, { 'content-type': upstream.headers.get('content-type') ?? 'application/octet-stream' })
          res.end(value)
        }
      } finally { forwards.delete(controller) }
    } catch (error) {
      // AssertionError messages may embed actual/expected input values. Never retain them.
      violations.push({ category: error.name, contract: 'private receiver boundary failed' })
      json(res, 400, { error: 'probe receiver contract failed' })
    }
  })
  server.on('connection', socket => { sockets.add(socket); socket.once('close', () => sockets.delete(socket)) })
  function snapshot() {
    return { caseId: current?.mode, creates: current?.creates ?? 0, prompts: current?.prompts ?? 0,
      aborts: current?.aborts ?? 0, modelRequests: current?.modelRequests ?? 0,
      modelStarted: models.some(row => row.caseId === current?.mode),
      activeStream: models.some(row => row.caseId === current?.mode && row.activeStream && !row.closed),
      modelClosed: models.some(row => row.caseId === current?.mode && row.closed),
      delayedGets: transport.filter(row => row.caseId === current?.mode && row.delayMs).length,
      violations: [...violations] }
  }
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
  return {
    url: `http://127.0.0.1:${server.address().port}`, models, transport, violations,
    setNative: value => { const url = new URL(value); assert.equal(url.hostname, '127.0.0.1'); assert.equal(url.protocol, 'http:'); nativeEndpoint = url.origin },
    begin: (mode, marker) => {
      assert.ok(modes.includes(mode)); assert.ok(!models.some(row => row.activeStream && !row.closed))
      assert.ok(!transport.some(row => row.caseId === mode)); assert.match(marker, /^BUSINESS_RESULT_[A-Z_]+$/)
      current = { mode, marker, creates: 0, prompts: 0, aborts: 0, modelRequests: 0, deadlineArmed: false }
    },
    snapshot,
    close: async () => {
      closed = true
      for (const controller of forwards) controller.abort(new Error('owned receiver cleanup'))
      for (const timer of [...timers]) timer.finish()
      for (const socket of sockets) socket.destroy()
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('receiver close deadline')), 3000)
        server.close(error => { clearTimeout(timer); if (error) reject(error); else resolve() })
      })
      assert.equal(timers.size, 0); assert.equal(sockets.size, 0); assert.equal(forwards.size, 0)
      return { timers: timers.size, sockets: sockets.size, forwards: forwards.size }
    },
  }
}
