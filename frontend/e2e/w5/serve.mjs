/** W5 test-only same-origin production SPA + native Designer SSE. No Java/model. */
import http from 'node:http'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve, extname } from 'node:path'
const root = resolve('dist'), evidence = process.env.CANVAS_W5_EVIDENCE_DIR ?? '/tmp/w5-browser'
await mkdir(evidence, { recursive: true })
const rows = [], counts = new Map(), streams = new Set()
let writes = Promise.resolve()
function save() { const data = JSON.stringify(rows, null, 2); writes = writes.then(() => writeFile(resolve(evidence, 'native-sse.json'), data)) }
save()
const mime = { '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml', '.html': 'text/html', '.woff2': 'font/woff2' }
const server = http.createServer(async (request, response) => {
  const path = new URL(request.url, 'http://127.0.0.1').pathname
  if (/^\/api\/designer-sessions\/[^/]+\/events$/.test(path)) {
    const key = `${path}|${request.headers['x-w5-case'] ?? ''}`, ordinal = (counts.get(key) ?? 0) + 1; counts.set(key, ordinal)
    const row = { path, ordinal, cursor: request.headers['last-event-id'] ?? null, opened: Date.now(), closed: null }; rows.push(row); save()
    response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' }); response.flushHeaders()
    response.write(`retry: 100\nid: ${ordinal === 1 ? '17' : '18'}\ndata: ${JSON.stringify({ type: 'STATUS', sessionId: path.split('/')[3], state: 'REVIEWING', workflowPhase: 'DISCUSSING_REQUIREMENT', activeActor: 'SYSTEM', runtimeConnected: true, at: '2026-10-03', notice: '模拟 SSE 通知；REST 状态保持权威' })}\n\n`)
    if (ordinal === 1) response.end()
    else { const heartbeat = setInterval(() => response.write(': heartbeat\n\n'), 1000); streams.add(response); response.on('close', () => { clearInterval(heartbeat); streams.delete(response) }) }
    response.on('close', () => { row.closed = Date.now(); save() }); return
  }
  if (path.startsWith('/api/')) { response.writeHead(501, { 'Content-Type': 'application/json' }); response.end('{"message":"测试缺少明确运输夹具"}'); return }
  let file = resolve(root, `.${decodeURIComponent(path)}`)
  if (!file.startsWith(root + '/') || !extname(path)) file = resolve(root, 'index.html')
  try { const bytes = await readFile(file); response.writeHead(200, { 'Content-Type': mime[extname(file)] ?? 'application/octet-stream' }); response.end(bytes) }
  catch { response.writeHead(404); response.end('Missing production asset') }
})
server.listen(41775, '127.0.0.1')
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => { for (const response of streams) response.end(); server.close(() => { writes.finally(() => process.exit(0)) }) })
