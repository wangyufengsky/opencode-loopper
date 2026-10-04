/** Four approved fault scenarios. Run only after review/freeze under task-owned Tini. */
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { createWriteStream } from 'node:fs'
import { mkdir, mkdtemp, readFile, readdir, realpath, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { dirname, isAbsolute, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { StringDecoder } from 'node:string_decoder'
import { createFaultReceiver } from './native-transport-fault-receiver.mjs'

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const [binary, jdk, classpathFile, evidenceRoot, expectedRevision] = process.argv.slice(2)
assert.equal(process.argv.length, 7)
for (const p of [binary, jdk, classpathFile, evidenceRoot]) {
  assert.ok(isAbsolute(p)); assert.equal(await realpath(p), p)
}
assert.match(expectedRevision, /^[a-f0-9]{40}$/)
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
assert.equal(hash(await readFile(binary)), 'de0724a36eaf3166e7f1ff38d0f4478b95ccc47725e9597b3fe66d3d3e18baa2')
const ancestorExe = await realpath(`/proc/${process.ppid}/exe`)
assert.equal(hash(await readFile(ancestorExe)), '8cc70470bb4b21c25ddca7108c5820fa14d5019b5f1ec7e94bfca65de2479990', 'task-owned Tini direct ancestor required')
const ancestorArgs = (await readFile(`/proc/${process.ppid}/cmdline`, 'utf8')).split('\0').filter(Boolean)
assert.deepEqual(ancestorArgs.slice(1, 4), ['-s', '-vv', '--'], 'explicit task-local subreaper flags required')
const root = await mkdtemp(join(evidenceRoot, 'native-transport-fault-'))
const directories = Object.fromEntries(['home', 'config', 'data', 'state', 'cache', 'tmp', 'managed', 'work', 'classes', 'npm-cache']
  .map(name => [name, join(root, name)]))
await Promise.all(Object.values(directories).map(p => mkdir(p, { mode: 0o700 })))
for (const file of ['npm-user', 'npm-global']) await writeFile(join(root, file), '', { mode: 0o600 })
const env = { HOME: directories.home, XDG_CONFIG_HOME: directories.config, XDG_DATA_HOME: directories.data,
  XDG_STATE_HOME: directories.state, XDG_CACHE_HOME: directories.cache, TMPDIR: directories.tmp,
  PATH: `${jdk}/bin:${dirname(process.execPath)}:/usr/bin:/bin`, LANG: 'C.UTF-8', TZ: 'UTC',
  GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: '/dev/null', GIT_TERMINAL_PROMPT: '0',
  npm_config_offline: 'true', npm_config_cache: directories['npm-cache'],
  npm_config_userconfig: join(root, 'npm-user'), npm_config_globalconfig: join(root, 'npm-global'),
  OPENCODE_TEST_MANAGED_CONFIG_DIR: directories.managed }
for (const flag of ['DISABLE_PROJECT_CONFIG', 'DISABLE_DEFAULT_PLUGINS', 'PURE', 'DISABLE_EXTERNAL_SKILLS',
  'DISABLE_CLAUDE_CODE', 'DISABLE_MODELS_FETCH', 'DISABLE_AUTOUPDATE', 'DISABLE_AUTOCOMPACT', 'DISABLE_LSP_DOWNLOAD']) env[`OPENCODE_${flag}`] = 'true'
const password = randomUUID(), basicValue = Buffer.from(`opencode:${password}`).toString('base64')
const authorization = `Basic ${basicValue}`
const redact = value => String(value).replaceAll(password, '<redacted>').replaceAll(basicValue, '<redacted>')
function logStream(stream, output) {
  const decoder = new StringDecoder('utf8'); let pending = ''
  function drain(end = false) {
    pending = redact(pending)
    let newline
    while ((newline = pending.indexOf('\n')) !== -1) { output.write(pending.slice(0, newline + 1)); pending = pending.slice(newline + 1) }
    if (pending.length > 65536) { output.write(pending.slice(0, -256)); pending = pending.slice(-256) }
    if (end) { output.write(redact(pending)); pending = '' }
  }
  stream.on('data', chunk => { pending += decoder.write(chunk); drain() })
  stream.on('end', () => { pending += decoder.end(); drain(true) })
}
const stop = new AbortController()
const interrupt = signal => stop.abort(new Error(`fault transport interrupted: ${signal}`))
const onInt = () => interrupt('SIGINT'), onTerm = () => interrupt('SIGTERM')
process.on('SIGINT', onInt); process.on('SIGTERM', onTerm)
const totalTimer = setTimeout(() => stop.abort(new Error('180s total transport deadline')), 180000)
const owned = []
function launch(argv, cwd, extra = {}) {
  stop.signal.throwIfAborted()
  const label = `child-${owned.length}`, output = createWriteStream(join(root, `${label}.log`), { flags: 'wx', mode: 0o600 })
  const child = spawn(argv[0], argv.slice(1), { cwd, env: { ...env, ...extra }, detached: true, stdio: ['ignore', 'pipe', 'pipe'] })
  logStream(child.stdout, output); logStream(child.stderr, output)
  const exited = new Promise((resolveExit, reject) => {
    child.once('error', reject)
    child.once('close', (code, signal) => output.end(() => resolveExit({ code, signal })))
  })
  // Retain rejection for bounded finish without creating an unhandled rejection.
  exited.catch(() => {})
  const record = { child, exited, label }; owned.push(record); return record
}
async function finish(record, millis, cancellable = true) {
  let timer, cancel
  try {
    if (cancellable) stop.signal.throwIfAborted()
    const waits = [record.exited, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(`${record.label} deadline`)), millis) })]
    if (cancellable) waits.push(new Promise((_, reject) => {
      cancel = () => reject(stop.signal.reason)
      stop.signal.addEventListener('abort', cancel, { once: true })
    }))
    return await Promise.race(waits)
  } finally { clearTimeout(timer); if (cancel) stop.signal.removeEventListener('abort', cancel) }
}
async function terminate(record) {
  if (record.child.exitCode !== null || record.child.signalCode !== null) return { pid: record.child.pid, ...await finish(record, 3000, false) }
  if (!record.child.pid) return { spawnFailed: true }
  try { process.kill(-record.child.pid, 'SIGTERM') } catch (error) { if (error.code !== 'ESRCH') throw error }
  let exit
  try { exit = await finish(record, 5000, false) }
  catch {
    if (record.child.exitCode === null && record.child.signalCode === null) {
      try { process.kill(-record.child.pid, 'SIGKILL') } catch (error) { if (error.code !== 'ESRCH') throw error }
    }
    exit = await finish(record, 5000, false)
  }
  return { pid: record.child.pid, ...exit }
}
async function classes(directory, prefix = '') {
  const list = []
  for (const row of await readdir(directory, { withFileTypes: true })) {
    assert.ok(!row.isSymbolicLink(), 'runtime class symlinks forbidden')
    const name = prefix + row.name
    if (row.isDirectory()) list.push(...await classes(join(directory, row.name), `${name}/`))
    else if (row.isFile() && name.endsWith('.class')) list.push({ path: name, sha256: hash(await readFile(join(directory, row.name))) })
  }
  return list.sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0)
}
let receiver, failure
const report = { revision: expectedRevision, start: new Date().toISOString(), root,
  layers: 'real Java client -> private forwarding receiver -> real OpenCode 1.18.23 -> deterministic loopback provider',
  realModelCalls: 0, springBusinessCoverage: false, maximumOriginalPrompts: 4, scenarios: [],
  deadlinesMs: { startup: 20000, compile: 20000, scenarioProcess: 20000, total: 180000,
    businessObservation: 12000, javaTransportRead: 500, injectedGetDelay: 1000, cancelStreamSafetyBound: 12000 },
  binarySha256: hash(await readFile(binary)), tiniAncestor: { pid: process.ppid, executableSha256: hash(await readFile(ancestorExe)) },
  childEnvironmentKeys: Object.keys(env).sort() }
try {
  const revision = launch(['git', 'rev-parse', 'HEAD'], repo)
  assert.equal((await finish(revision, 5000)).code, 0)
  assert.equal((await readFile(join(root, `${revision.label}.log`), 'utf8')).trim(), expectedRevision)
  const init = launch(['git', '-c', 'init.defaultBranch=main', '-c', 'core.hooksPath=/dev/null', 'init', '-q'], directories.work)
  assert.equal((await finish(init, 5000)).code, 0)
  const jars = (await readFile(classpathFile, 'utf8')).trim().split(':')
  for (const p of jars) { assert.ok(p.startsWith('/workspace/backend-integration-tools/m2/')); assert.equal(await realpath(p), p) }
  const runtime = join(repo, 'target/backend-dev/classes')
  assert.equal(await realpath(runtime), runtime)
  const cp = [runtime, ...jars].join(':')
  const beforeClasses = await classes(runtime); assert.ok(beforeClasses.length > 0)
  report.runtimeClasses = { path: runtime, count: beforeClasses.length, sha256: hash(JSON.stringify(beforeClasses)) }
  report.runtimeJars = []
  for (const p of jars) report.runtimeJars.push({ path: p, sha256: hash(await readFile(p)) })
  report.sourceHashes = {}
  for (const name of ['native-transport-fault-probe.mjs', 'native-transport-fault-receiver.mjs', 'NativeTransportFaultProbe.java'])
    report.sourceHashes[name] = hash(await readFile(join(repo, 'scripts/backend-integration', name)))
  const compile = launch([`${jdk}/bin/javac`, '--release', '21', '-cp', cp, '-d', directories.classes,
    join(repo, 'scripts/backend-integration/NativeTransportFaultProbe.java')], root)
  assert.equal((await finish(compile, 20000)).code, 0)
  receiver = await createFaultReceiver({ authorization, signal: stop.signal, work: directories.work })
  receiver.begin('normal', 'BUSINESS_RESULT_NORMAL')
  const reservation = createServer()
  await new Promise((resolveListen, reject) => { reservation.once('error', reject); reservation.listen(0, '127.0.0.1', resolveListen) })
  const port = reservation.address().port
  await new Promise(resolveClose => reservation.close(resolveClose))
  const endpoint = `http://127.0.0.1:${port}`
  receiver.setNative(endpoint)
  const config = { model: 'aicoding-test/mock', small_model: 'aicoding-test/mock', enabled_providers: ['aicoding-test'],
    permission: 'deny', plugin: [], mcp: {}, share: 'disabled', snapshot: false, lsp: false, formatter: false,
    agent: { 'loopper-router': { mode: 'primary', steps: 2, permission: 'deny' } },
    provider: { 'aicoding-test': { npm: '@ai-sdk/openai-compatible', name: 'Isolated fault mock',
      options: { baseURL: `${receiver.url}/v1`, apiKey: 'local-test' }, models: { mock: { name: 'mock' } } } } }
  const native = launch([binary, 'serve', '--hostname', '127.0.0.1', '--port', String(port), '--log-level', 'ERROR'], directories.work,
    { OPENCODE_CONFIG_CONTENT: JSON.stringify(config), OPENCODE_SERVER_PASSWORD: password })
  const startDeadline = Date.now() + 20000
  let health
  while (Date.now() < startDeadline) {
    stop.signal.throwIfAborted()
    assert.equal(native.child.exitCode, null, 'native process alive before health')
    try {
      const res = await fetch(`${endpoint}/global/health`, { headers: { authorization }, redirect: 'error',
        signal: AbortSignal.any([stop.signal, AbortSignal.timeout(1000)]) })
      if (res.ok) { health = await res.json(); break }
    } catch { stop.signal.throwIfAborted() }
    await new Promise(resolvePause => setTimeout(resolvePause, 150))
  }
  assert.equal(health?.healthy, true); assert.equal(health.version, '1.18.23'); report.health = health
  const cases = [['normal', 'BUSINESS_RESULT_NORMAL'], ['provider-error', 'BUSINESS_RESULT_ERROR'],
    ['transport-deadline', 'BUSINESS_RESULT_RECOVERED'], ['cancel', 'BUSINESS_RESULT_CANCELLED']]
  for (const [mode, marker] of cases) {
    if (mode !== 'normal') receiver.begin(mode, marker)
    const resultFile = join(root, `${mode}.json`)
    const java = launch([`${jdk}/bin/java`, `-Duser.home=${directories.home}`, `-Djava.io.tmpdir=${directories.tmp}`,
      '-cp', `${directories.classes}:${cp}`, 'NativeTransportFaultProbe', receiver.url, directories.work, resultFile, mode], root,
      { PROBE_SERVER_PASSWORD: password })
    const exit = await finish(java, 20000)
    const result = JSON.parse(await readFile(resultFile, 'utf8'))
    report.scenarios.push({ ...result, javaExit: exit, resultSha256: hash(await readFile(resultFile)) })
    assert.equal(exit.code, 0); assert.equal(result.status, 'PASS'); assert.equal(result.caseId, mode)
    const sessionRead = await fetch(`${endpoint}/session/${result.sessionId}?directory=${encodeURIComponent(directories.work)}`,
      { headers: { authorization }, redirect: 'error', signal: AbortSignal.any([stop.signal, AbortSignal.timeout(2000)]) })
    assert.equal(sessionRead.status, 200)
    const session = await sessionRead.json()
    assert.equal(session.id, result.sessionId); assert.equal(session.directory, directories.work)
    assert.ok(session.permission.some(r => r.permission === '*' && r.pattern === '*' && r.action === 'deny'))
    assert.ok(session.permission.every(r => r.action === 'deny'))
    report.scenarios.at(-1).permissions = session.permission
    const original = receiver.transport.filter(r => r.caseId === mode && r.method === 'POST' && r.path.endsWith('/prompt_async'))
    assert.equal(original.length, 1); assert.equal(original[0].messageId, result.messageId)
    assert.equal(receiver.models.filter(r => r.caseId === mode).length, 1)
    assert.equal(receiver.violations.length, 0)
    if (mode === 'transport-deadline') {
      const delayed = receiver.transport.filter(r => r.caseId === mode && r.delayMs)
      assert.equal(delayed.length, 1); assert.equal(delayed[0].upstreamStatus, 200)
      assert.equal(delayed[0].clientClosedBeforeReply, true)
      assert.ok(Date.parse(delayed[0].upstreamReceivedAt) < Date.parse(result.transportFailureObservedAt),
        'real native GET succeeded before the client deadline; only delivery was delayed')
      assert.ok(result.transportElapsedMs >= 400 && result.transportElapsedMs < 3000)
    }
    if (mode === 'cancel') {
      const held = receiver.models.find(r => r.caseId === mode)
      assert.equal(held.closed, true); assert.equal(held.ended, false)
      assert.equal(result.abortAcknowledgement, 'ACKNOWLEDGED')
    }
  }
  assert.equal(receiver.models.length, 4)
  assert.equal(receiver.transport.filter(r => r.method === 'POST' && r.path === '/session').length, 4)
  assert.equal(receiver.transport.filter(r => r.method === 'POST' && r.path.endsWith('/abort')).length, 1)
  assert.equal(report.runtimeClasses.sha256, hash(JSON.stringify(await classes(runtime))), 'runtime classes unchanged')
  report.status = 'PASS'
} catch (error) { failure = error; report.status = 'FAIL'; report.error = redact(error.message) }
finally {
  clearTimeout(totalTimer)
  report.cleanup = []
  for (const child of [...owned].reverse()) {
    try { report.cleanup.push(await terminate(child)) }
    catch (error) { failure ??= error; report.status = 'FAIL'; report.cleanup.push({ pid: child.child.pid, error: redact(error.message) }) }
  }
  if (receiver) {
    report.modelRequests = receiver.models; report.transportRequests = receiver.transport; report.violations = receiver.violations
    try { report.receiverCleanup = await receiver.close() }
    catch (error) { failure ??= error; report.status = 'FAIL'; report.receiverCleanup = { error: redact(error.message) } }
  }
  report.remainingOwnedGroupMembers = []
  for (const pid of await readdir('/proc')) {
    if (!/^\d+$/.test(pid)) continue
    try {
      const value = await readFile(`/proc/${pid}/stat`, 'utf8'), fields = value.slice(value.lastIndexOf(')') + 2).trim().split(/\s+/)
      if (owned.some(r => String(r.child.pid) === fields[2]))
        report.remainingOwnedGroupMembers.push({ pid: Number(pid), state: fields[0], ppid: Number(fields[1]), pgid: Number(fields[2]), startTicks: Number(fields[19]) })
    } catch (error) { if (error.code !== 'ENOENT' && error.code !== 'ESRCH') { failure ??= error; report.status = 'FAIL' } }
  }
  if (report.remainingOwnedGroupMembers.length) { failure ??= new Error('owned process group members remain'); report.status = 'FAIL' }
  report.interrupted = stop.signal.aborted
  if (report.interrupted) { failure ??= stop.signal.reason; report.status = 'FAIL'; report.error ??= redact(stop.signal.reason?.message) }
  report.finish = new Date().toISOString()
  process.off('SIGINT', onInt); process.off('SIGTERM', onTerm)
  await writeFile(join(root, 'report.json'), JSON.stringify(report, (_key, value) => typeof value === 'string' ? redact(value) : value, 2) + '\n', { mode: 0o600 })
  console.log(JSON.stringify({ root, status: report.status, scenarios: report.scenarios.length,
    localMockModelRequests: receiver?.models.length ?? 0, realModelCalls: 0, remainingOwnedGroupMembers: report.remainingOwnedGroupMembers }))
}
if (failure) process.exitCode = 1
