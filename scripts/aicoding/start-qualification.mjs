import { spawn } from 'node:child_process'
import { mkdtemp, mkdir, readFile, realpath, writeFile } from 'node:fs/promises'
import { createWriteStream } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { createServer } from 'node:net'
import { createMockReceiver } from './mock-receiver.mjs'
import { maintenanceModel } from './mock-model.mjs'

const repo = resolve(new URL('../..', import.meta.url).pathname)
const directory = await realpath(await mkdtemp(join(tmpdir(), 'loopper-story-qualification-')))
const workspaces = [join(directory, 'project')]
await mkdir(workspaces[0])
await writeFile(join(workspaces[0], 'config.properties'), 'feature.enabled=false\nkeep.value=unchanged\n')
const receiver = await createMockReceiver({ modelReply: maintenanceModel(workspaces) })
const socket = createServer()
await new Promise(resolve => socket.listen(0, '127.0.0.1', resolve))
const port = socket.address().port
await new Promise(resolve => socket.close(resolve))
const config = { model: 'aicoding-test/mock', plugin: process.argv.includes('--without-plugin') ? [] : [new URL('./mock-plugin.mjs', import.meta.url).href],
  provider: { 'aicoding-test': { npm: '@ai-sdk/openai-compatible', name: 'Local accounting qualification',
    options: { baseURL: `${receiver.url}/v1`, apiKey: 'local-test' }, models: { mock: { name: 'mock' } } } } }
const env = { ...process.env, LOOPPER_DATA_DIR: join(directory, 'data'), LOOPPER_ALLOWED_ROOT: directory,
  LOOPPER_OPEN_BROWSER: 'false', LOOPPER_OPENCODE_MODE: 'managed', OPENCODE_MODEL: 'aicoding-test/mock',
  OPENCODE_EXECUTABLE: process.env.OPENCODE_EXECUTABLE ?? 'opencode',
  OPENCODE_CONFIG_CONTENT: JSON.stringify(config), AICODING_MOCK_URL: receiver.url,
  XDG_CONFIG_HOME: join(directory, 'config'), XDG_DATA_HOME: join(directory, 'opencode-data'),
  XDG_STATE_HOME: join(directory, 'state'), XDG_CACHE_HOME: join(directory, 'cache') }
for (const path of [env.XDG_CONFIG_HOME, env.XDG_DATA_HOME, env.XDG_STATE_HOME, env.XDG_CACHE_HOME]) await mkdir(path)
const java = process.env.JAVA_EXECUTABLE ?? (process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'java') : 'java')
const jar = process.argv.slice(2).find(value => !value.startsWith('--'))
const args = jar ? ['-jar', resolve(jar)] : ['-cp', `${repo}/target/classes:${(await readFile('/tmp/loopper-story-classpath.txt', 'utf8')).trim()}`, 'io.opencode.loopper.LoopperApplication']
const log = createWriteStream(join(directory, 'loopper.log'))
const child = spawn(java, [...args, `--server.port=${port}`, '--spring.main.banner-mode=off'], { cwd: repo, env, stdio: ['ignore', 'pipe', 'pipe'] })
child.stdout.pipe(log); child.stderr.pipe(log)
const descriptor = { directory, projectRoot: workspaces[0], endpoint: `http://127.0.0.1:${port}`, receiver: receiver.url, pid: child.pid, jar: jar ?? null }
await writeFile(join(directory, 'environment.json'), JSON.stringify(descriptor, null, 2))
console.log(JSON.stringify(descriptor))
let closing = false
async function close() {
  if (closing) return
  closing = true
  let managedPid
  try {
    const runtime = await (await fetch(`${descriptor.endpoint}/api/runtime/opencode`, { signal: AbortSignal.timeout(1500) })).json()
    if (runtime.managed && Number.isInteger(runtime.pid)) managedPid = runtime.pid
    await writeFile(join(directory, 'runtime-at-stop.json'), JSON.stringify({
      loopperVersion: runtime.loopperVersion, version: runtime.version, pid: managedPid,
      endpoint: runtime.endpoint, generation: runtime.generation,
    }, null, 2))
  } catch { /* Startup may have failed before the runtime endpoint became available. */ }
  await writeFile(join(directory, 'receiver-ledger.json'), JSON.stringify({ requests: receiver.requests, modelRequests: receiver.modelRequests }, null, 2))
  child.kill('SIGTERM')
  const timer = setTimeout(() => child.kill('SIGKILL'), 8000)
  if (child.exitCode === null) await new Promise(resolve => child.once('exit', resolve))
  // A timed-out JVM shutdown must not leave its isolated OpenCode process behind.
  if (managedPid) { try { process.kill(managedPid, 'SIGTERM') } catch { /* Already stopped. */ } }
  clearTimeout(timer); await receiver.close(); log.end()
  process.exit(0)
}
process.on('SIGTERM', close); process.on('SIGINT', close)
child.once('exit', () => { if (!closing) { console.error('Loopper process exited; inspect loopper.log'); void close() } })
setInterval(async () => {
  await writeFile(join(directory, 'receiver-ledger.json'), JSON.stringify({ requests: receiver.requests, modelRequests: receiver.modelRequests }, null, 2))
}, 2000).unref()
