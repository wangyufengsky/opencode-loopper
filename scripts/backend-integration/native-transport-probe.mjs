/** Bounded real OpenCode / local mock transport. No inherited auth or qualification scripts. */
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { createWriteStream } from 'node:fs'
import { mkdir, mkdtemp, readFile, readdir, realpath, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { dirname, isAbsolute, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { StringDecoder } from 'node:string_decoder'
import { createMockReceiver } from '../aicoding/mock-receiver.mjs'

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const [binary, jdk, classpathFile, evidenceRoot, expectedRevision] = process.argv.slice(2)
assert.equal(process.argv.length, 7)
for (const p of [binary, jdk, classpathFile, evidenceRoot]) {
  assert.ok(isAbsolute(p)); assert.equal(await realpath(p), p)
}
assert.match(expectedRevision, /^[a-f0-9]{40}$/)
const root = await mkdtemp(join(evidenceRoot, 'native-transport-'))
const directories = Object.fromEntries(['home', 'config', 'data', 'state', 'cache', 'tmp', 'managed', 'work', 'classes', 'npm-cache']
  .map(name => [name, join(root, name)]))
await Promise.all(Object.values(directories).map(p => mkdir(p, { mode: 0o700 })))
for (const file of ['npm-user', 'npm-global']) await writeFile(join(root, file), '', { mode: 0o600 })
const env = { HOME: directories.home, XDG_CONFIG_HOME: directories.config,
  XDG_DATA_HOME: directories.data, XDG_STATE_HOME: directories.state, XDG_CACHE_HOME: directories.cache,
  TMPDIR: directories.tmp, PATH: `${jdk}/bin:${dirname(process.execPath)}:/usr/bin:/bin`, LANG: 'C.UTF-8', TZ: 'UTC',
  GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: '/dev/null', GIT_TERMINAL_PROMPT: '0',
  npm_config_offline: 'true', npm_config_cache: directories['npm-cache'],
  npm_config_userconfig: join(root, 'npm-user'), npm_config_globalconfig: join(root, 'npm-global'),
  OPENCODE_TEST_MANAGED_CONFIG_DIR: directories.managed }
for (const flag of ['DISABLE_PROJECT_CONFIG', 'DISABLE_DEFAULT_PLUGINS', 'PURE', 'DISABLE_EXTERNAL_SKILLS',
  'DISABLE_CLAUDE_CODE', 'DISABLE_MODELS_FETCH', 'DISABLE_AUTOUPDATE', 'DISABLE_AUTOCOMPACT', 'DISABLE_LSP_DOWNLOAD'])
  env[`OPENCODE_${flag}`] = 'true'
const owned = []
const password = randomUUID() // memory only; never put in argv, files, reports or console.
const basicValue = Buffer.from(`opencode:${password}`).toString('base64')
const redact = value => String(value).replaceAll(password, '<redacted>').replaceAll(basicValue, '<redacted>')
function privateLog(stream, output) {
  const decoder = new StringDecoder('utf8'); let buffered = ''
  function drain(end = false) {
    buffered = redact(buffered)
    let newline
    while ((newline = buffered.indexOf('\n')) !== -1) { output.write(buffered.slice(0, newline + 1)); buffered = buffered.slice(newline + 1) }
    // Retain more than both secret lengths so split chunks cannot expose a complete value.
    if (buffered.length > 65536) { output.write(buffered.slice(0, -256)); buffered = buffered.slice(-256) }
    if (end) { output.write(redact(buffered)); buffered = '' }
  }
  stream.on('data', chunk => { buffered += decoder.write(chunk); drain() })
  stream.on('end', () => { buffered += decoder.end(); drain(true) })
}
const cancellation = new AbortController()
const onInterrupt = signal => cancellation.abort(new Error(`transport interrupted: ${signal}`))
const onTerm = () => onInterrupt('SIGTERM')
const onInt = () => onInterrupt('SIGINT')
process.on('SIGTERM', onTerm); process.on('SIGINT', onInt)
function launch(argv, cwd, extraEnv = {}) {
  cancellation.signal.throwIfAborted()
  const label = `child-${owned.length}`
  const log = createWriteStream(join(root, `${label}.log`), { flags: 'wx', mode: 0o600 })
  const child = spawn(argv[0], argv.slice(1), { cwd, env: { ...env, ...extraEnv }, detached: true, stdio: ['ignore', 'pipe', 'pipe'] })
  privateLog(child.stdout, log); privateLog(child.stderr, log)
  const exited = new Promise(resolveExit => child.once('close', (code, signal) => log.end(() => resolveExit({ code, signal }))))
  const error = new Promise((_, reject) => child.once('error', reject))
  const record = { child, exited: Promise.race([exited, error]), log, argv, label }
  owned.push(record); return record
}
const pause = ms => new Promise(resolvePause => setTimeout(resolvePause, ms))
async function finish(record, timeout, cancellable = true) {
  let timer, onCancel
  try {
    if (cancellable) cancellation.signal.throwIfAborted()
    const waits = [record.exited, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(`${record.label} deadline`)), timeout) })]
    if (cancellable) waits.push(new Promise((_, reject) => {
      onCancel = () => reject(cancellation.signal.reason)
      cancellation.signal.addEventListener('abort', onCancel, { once: true })
    }))
    return await Promise.race(waits)
  } finally { clearTimeout(timer); if (onCancel) cancellation.signal.removeEventListener('abort', onCancel) }
}
async function terminate(record) {
  // Never signal a historical process group after its parent exit was observed.
  if (record.child.exitCode !== null || record.child.signalCode !== null) return { pid: record.child.pid, ...await finish(record, 5000, false) }
  try { process.kill(-record.child.pid, 'SIGTERM') } catch (e) { if (e.code !== 'ESRCH') throw e }
  let observed
  try { observed = await finish(record, 5000, false) }
  catch {
    if (record.child.exitCode === null && record.child.signalCode === null) {
      try { process.kill(-record.child.pid, 'SIGKILL') } catch (e) { if (e.code !== 'ESRCH') throw e }
    }
    observed = await finish(record, 5000, false)
  }
  return { pid: record.child.pid, ...observed }
}
let receiver, server, port, failure
const report = { revision: expectedRevision, root, layers: 'real Java client -> real OpenCode -> deterministic local HTTP mock',
  realModelCalls: 0, productionSpringBusinessCoverage: false, start: new Date().toISOString() }
try {
  report.binarySha256 = createHash('sha256').update(await readFile(binary)).digest('hex')
  assert.equal(report.binarySha256, 'de0724a36eaf3166e7f1ff38d0f4478b95ccc47725e9597b3fe66d3d3e18baa2')
  report.runnerSha256 = createHash('sha256').update(await readFile(fileURLToPath(import.meta.url))).digest('hex')
  const revision = launch(['git', 'rev-parse', 'HEAD'], repo)
  assert.equal((await finish(revision, 5000)).code, 0)
  assert.equal((await readFile(join(root, `${revision.label}.log`), 'utf8')).trim(), expectedRevision)
  const git = launch(['git', '-c', 'init.defaultBranch=main', '-c', 'core.hooksPath=/dev/null', 'init', '-q'], directories.work)
  assert.equal((await finish(git, 5000)).code, 0)
  const jars = (await readFile(classpathFile, 'utf8')).trim().split(':')
  for (const p of jars) { assert.ok(p.startsWith('/workspace/backend-integration-tools/m2/')); assert.equal(await realpath(p), p) }
  const cp = [join(repo, 'target/classes'), ...jars].join(':')
  async function classHashes(directory, prefix = '') {
    const files = []
    for (const row of await readdir(directory, { withFileTypes: true })) {
      const name = `${prefix}${row.name}`
      if (row.isDirectory()) files.push(...await classHashes(join(directory, row.name), `${name}/`))
      else if (row.isFile() && name.endsWith('.class')) files.push({ path: name, sha256: createHash('sha256').update(await readFile(join(directory, row.name))).digest('hex') })
    }
    return files
  }
  const classes = (await classHashes(join(repo, 'target/classes'))).sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0)
  assert.ok(classes.length > 0)
  report.runtimeClasses = { count: classes.length, sha256: createHash('sha256').update(JSON.stringify(classes)).digest('hex') }
  report.runtimeJars = []
  for (const path of jars) report.runtimeJars.push({ path, sha256: createHash('sha256').update(await readFile(path)).digest('hex') })
  const source = join(repo, 'scripts/backend-integration/NativeTransportProbe.java')
  report.javaProbeSha256 = createHash('sha256').update(await readFile(source)).digest('hex')
  const compile = launch([`${jdk}/bin/javac`, '--release', '21', '-cp', cp, '-d', directories.classes, source], root)
  assert.equal((await finish(compile, 20000)).code, 0)
  receiver = await createMockReceiver()
  const reservation = createServer()
  await new Promise(r => reservation.listen(0, '127.0.0.1', r)); port = reservation.address().port
  await new Promise(r => reservation.close(r))
  const config = { model: 'aicoding-test/mock', small_model: 'aicoding-test/mock', enabled_providers: ['aicoding-test'],
    permission: 'deny', plugin: [], mcp: {}, share: 'disabled', snapshot: false, lsp: false, formatter: false,
    agent: { 'loopper-router': { mode: 'primary', steps: 2, permission: 'deny' } },
    provider: { 'aicoding-test': { npm: '@ai-sdk/openai-compatible', name: 'Isolated transport mock',
      options: { baseURL: `${receiver.url}/v1`, apiKey: 'local-test' }, models: { mock: { name: 'mock' } } } } }
  server = launch([binary, 'serve', '--hostname', '127.0.0.1', '--port', String(port), '--log-level', 'ERROR'], directories.work,
    { OPENCODE_CONFIG_CONTENT: JSON.stringify(config), OPENCODE_SERVER_PASSWORD: password })
  const endpoint = `http://127.0.0.1:${port}`
  const authorization = `Basic ${basicValue}`
  const headers = { authorization }
  let health
  const deadline = Date.now() + 20000
  while (Date.now() < deadline) {
    cancellation.signal.throwIfAborted()
    if (server.child.exitCode !== null) throw new Error('native server exited before health')
    try { const res = await fetch(`${endpoint}/global/health`, { headers, signal: AbortSignal.any([cancellation.signal, AbortSignal.timeout(1000)]) }); if (res.ok) { health = await res.json(); break } } catch { }
    await pause(150)
  }
  assert.equal(health?.healthy, true); assert.equal(health.version, '1.18.23'); report.health = health
  const java = launch([`${jdk}/bin/java`, `-Duser.home=${directories.home}`, `-Djava.io.tmpdir=${directories.tmp}`,
    '-cp', `${directories.classes}:${cp}`, 'NativeTransportProbe', endpoint, directories.work, join(root, 'java-result.json')], root,
    { PROBE_SERVER_PASSWORD: password })
  const javaExit = await finish(java, 45000); report.javaExit = javaExit; assert.equal(javaExit.code, 0)
  const result = JSON.parse(await readFile(join(root, 'java-result.json'), 'utf8')); report.javaResult = result
  const sessionRead = await fetch(`${endpoint}/session/${result.sessionId}?directory=${encodeURIComponent(directories.work)}`, { headers, signal: AbortSignal.any([cancellation.signal, AbortSignal.timeout(2000)]) })
  assert.equal(sessionRead.status, 200); const session = await sessionRead.json()
  assert.equal(session.directory, directories.work)
  assert.ok(session.permission.some(r => r.permission === '*' && r.pattern === '*' && r.action === 'deny'))
  assert.ok(session.permission.every(r => r.action === 'deny')); report.permissions = session.permission
  assert.equal(receiver.requests.length, 0); assert.equal(receiver.modelRequests.length, 1)
  assert.ok(receiver.modelRequests[0].content.includes('BUSINESS_RESULT_OK'))
  assert.equal((receiver.modelRequests[0].body.tools ?? []).length, 0)
  report.localMockModelRequests = receiver.modelRequests.length; report.advertisedTools = 0; report.accountingRequests = 0
  report.status = 'PASS'
} catch (e) { failure = e; report.status = 'FAIL'; report.error = redact(e.message) }
finally {
  report.cleanup = []
  for (const record of [...owned].reverse()) {
    try { report.cleanup.push(await terminate(record)) } catch (e) { report.cleanup.push({ pid: record.child.pid, error: redact(e.message) }); report.status = 'FAIL'; failure ??= e }
  }
  if (receiver) { try { await receiver.close() } catch (e) { report.status = 'FAIL'; failure ??= e } }
  report.remainingOwnedGroupMembers = []
  for (const pid of await readdir('/proc')) {
    if (!/^\d+$/.test(pid)) continue
    try { const stat = await readFile(`/proc/${pid}/stat`, 'utf8'); const fields = stat.slice(stat.lastIndexOf(')') + 2).split(' ')
      if (owned.some(r => String(r.child.pid) === fields[2])) report.remainingOwnedGroupMembers.push({ pid, state: fields[0] }) } catch { }
  }
  if (report.remainingOwnedGroupMembers.length) { report.status = 'FAIL'; failure ??= new Error('owned group members remain') }
  report.finish = new Date().toISOString()
  report.interrupted = cancellation.signal.aborted
  if (report.interrupted) { report.status = 'FAIL'; report.error ??= redact(cancellation.signal.reason?.message ?? 'interrupted'); failure ??= cancellation.signal.reason }
  process.off('SIGTERM', onTerm); process.off('SIGINT', onInt)
  await writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify({ root, status: report.status, realModelCalls: 0, localMockModelRequests: report.localMockModelRequests ?? 0,
    ownedGroupMembers: report.remainingOwnedGroupMembers, error: report.error ?? null }))
}
if (failure) process.exitCode = 1
