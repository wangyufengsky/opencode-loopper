import { spawn } from 'node:child_process'
import { mkdtemp, mkdir, realpath, writeFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import { createWriteStream } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { createServer } from 'node:net'
import { createMockReceiver } from './mock-receiver.mjs'

const directory = await realpath(await mkdtemp(join(tmpdir(), 'loopper-aicoding-native-')))
const receiver = await createMockReceiver()
const socket = createServer()
await new Promise(resolve => socket.listen(0, '127.0.0.1', resolve))
const port = socket.address().port
await new Promise(resolve => socket.close(resolve))
const password = randomUUID()
const config = { model: 'aicoding-test/mock', plugin: [new URL('./mock-plugin.mjs', import.meta.url).href,
  new URL('../../src/main/resources/opencode/loopper-accounting-guard.mjs', import.meta.url).href],
  agent: { 'loopper-accounting': { mode: 'primary', steps: 2, permission: { '*': 'deny', 'aicoding*': 'allow' } } },
  provider: { 'aicoding-test': { npm: '@ai-sdk/openai-compatible', name: 'Accounting transport test',
    options: { baseURL: `${receiver.url}/v1`, apiKey: 'local-test' }, models: { mock: { name: 'mock' } } } } }
const log = createWriteStream(join(directory, 'opencode.log'))
const isolation = { XDG_CONFIG_HOME: join(directory, 'config'), XDG_DATA_HOME: join(directory, 'data'),
  XDG_STATE_HOME: join(directory, 'state'), XDG_CACHE_HOME: join(directory, 'cache') }
for (const path of Object.values(isolation)) await mkdir(path)
const child = spawn(process.env.OPENCODE_EXECUTABLE ?? 'opencode', ['serve', '--hostname', '127.0.0.1', '--port', String(port)], {
  cwd: directory, env: { ...process.env, ...isolation, OPENCODE_CONFIG_CONTENT: JSON.stringify(config),
    OPENCODE_SERVER_PASSWORD: password, AICODING_MOCK_URL: receiver.url }, stdio: ['ignore', 'pipe', 'pipe'],
})
child.stdout.pipe(log); child.stderr.pipe(log)
const endpoint = `http://127.0.0.1:${port}`
async function call(path, body, timeout = 15000) {
  const response = await fetch(`${endpoint}${path}`, { method: body === undefined ? 'GET' : 'POST',
    headers: { authorization: `Basic ${Buffer.from(`opencode:${password}`).toString('base64')}`, 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(timeout) })
  const text = await response.text()
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 500)}`)
  return text ? JSON.parse(text) : null
}
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
try {
  for (let i = 0; ; i++) {
    try { await call('/global/health', undefined, 1000); break }
    catch (error) { if (i === 50) throw error; await pause(200) }
  }
  const commands = await call('/command')
  if (!commands.some(command => command.name === 'aicoding')) throw new Error('Native command missing')
  const newSession = () => call('/session', { title: 'Native accounting transport probe', permission: [{ permission: '*', pattern: '*', action: 'deny' }] })
  let session = await newSession()
  const commandFor = (target, operation, timeout) => call(`/session/${target.id}/command`, {
    command: 'aicoding', arguments: operation, messageID: `msg_loopper_aicoding_${randomUUID().replaceAll('-', '')}`,
    model: 'aicoding-test/mock', agent: 'loopper-accounting',
  }, timeout)
  const command = (operation, timeout) => commandFor(session, operation, timeout)
  await command('start ZH-0737 001327')
  await call(`/session/${session.id}/message`, { parts: [{ type: 'text', text: 'Reply exactly BUSINESS_RESULT_FIRST' }], model: { providerID: 'aicoding-test', modelID: 'mock' } })
  await command('complete')
  session = await newSession()
  await command('continue ZH-0737 001327')
  await command('complete')
  assert.deepEqual(receiver.requests.map(row => row.operation), ['start', 'complete', 'continue', 'complete'])
  const parallel = await Promise.all([newSession(), newSession()])
  await Promise.all(parallel.map(target => commandFor(target, 'continue ZH-0737 001327')))
  await Promise.all(parallel.map(target => commandFor(target, 'complete')))
  assert.ok(parallel.every(target => receiver.requests.filter(row => row.sessionId === target.id).length === 2))
  const failures = []
  for (const [label, argumentsText, behavior] of [
    ['parameter-rejected', 'start', {}], ['service-error', 'status', { fail: true }],
    ['received-response-lost', 'status', { fail: false, loseResponse: true }],
    ['command-error', 'invalid-operation', { loseResponse: false }],
  ]) {
    receiver.setBehavior(behavior)
    try { await command(argumentsText); throw new Error('expected injected failure') }
    catch (error) { assert.notEqual(error.message, 'expected injected failure'); failures.push({ label, error: error.message }) }
  }
  receiver.setBehavior({ delayMs: 1800 })
  let timeoutObserved = false
  try { await command('continue ZH-0737 001327', 300) } catch { timeoutObserved = true }
  receiver.setBehavior({ delayMs: 0 })
  await call(`/session/${session.id}/message`, { parts: [{ type: 'text', text: 'Reply exactly BUSINESS_RESULT_AFTER_TIMEOUT' }], model: { providerID: 'aicoding-test', modelID: 'mock' } })
  await pause(2500)
  const messages = await call(`/session/${session.id}/message`)
  assert.equal(timeoutObserved, true)
  const report = { endpoint, version: await call('/global/health'), sessionId: session.id, timeoutObserved, failures, parallel: parallel.map(row => row.id), requests: receiver.requests,
    modelRequests: receiver.modelRequests, messages }
  await writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2))
  console.log(JSON.stringify({ directory, timeoutObserved, operations: receiver.requests.map(r => r.operation),
    modelTurns: receiver.modelRequests.map(r => r.content.slice(0, 100)),
    messages: messages.map(m => ({ id: m.info.id, parentID: m.info.parentID, role: m.info.role, text: m.parts.filter(p => p.type === 'text').map(p => p.text).join(' ').slice(0, 140) })) }, null, 2))
} finally {
  child.kill('SIGTERM')
  await Promise.race([new Promise(resolve => child.once('exit', resolve)), pause(3000)])
  if (child.exitCode === null) child.kill('SIGKILL')
  await receiver.close()
  log.end()
}
