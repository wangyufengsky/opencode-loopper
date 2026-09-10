import assert from 'node:assert/strict'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { join } from 'node:path'
import { execFileSync } from 'node:child_process'

const env = JSON.parse(await readFile(process.argv[2], 'utf8'))
const { endpoint, directory, receiver } = env
const selected = process.argv.find(value => value.startsWith('--cases='))?.slice(8).split(',')
const runName = process.argv.find(value => value.startsWith('--run='))?.slice(6) ?? 'diagnosis'
const output = join(directory, `matrix-${runName}`)
await mkdir(output, { recursive: true })
const delay = 20_000
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const active = row => ['PREPARED', 'CANCELLING'].includes(row.state)
const results = []
async function http(base, path, body, method = body === undefined ? 'GET' : 'POST') {
  const response = await fetch(base + path, { method, headers: {
    'Content-Type': 'application/json', 'X-Loopper-Local-UI': '1', Origin: endpoint,
  }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(90_000) })
  const text = await response.text()
  if (!response.ok) throw Object.assign(new Error(`${path} HTTP ${response.status}: ${text.slice(0, 1_000)}`), { status: response.status })
  return text ? JSON.parse(text) : undefined
}
const call = (path, body) => http(endpoint, path, body)
const control = body => http(receiver, '/control', body)
const ledger = () => http(receiver, '/requests')
async function until(label, fn, timeout = 180_000) {
  const deadline = Date.now() + timeout
  let last
  while (Date.now() < deadline) {
    last = await fn()
    if (last) return last
    await pause(350)
  }
  throw new Error(`Timed out: ${label}; last=${JSON.stringify(last)}`)
}
const scenarios = [
  { id: 'normal' },
  { id: 'fallback', fallback: true },
  { id: 'cancel-designer-start', cancelStats: ['REQUIREMENT_DESIGNER', 'start'] },
  { id: 'cancel-designer-continue', fallback: true, cancelStats: ['REQUIREMENT_DESIGNER', 'continue'] },
  { id: 'cancel-designer-complete', cancelStats: ['REQUIREMENT_DESIGNER', 'complete'] },
  { id: 'cancel-implementation-start', cancelStats: ['IMPLEMENTATION', 'start'] },
  { id: 'cancel-implementation-continue', fallback: true, cancelStats: ['IMPLEMENTATION', 'continue'] },
  { id: 'cancel-implementation-complete', cancelStats: ['IMPLEMENTATION', 'complete'] },
  { id: 'stop-designer-during-start', stopDesigner: true },
  { id: 'cancel-task-during-start', cancelTask: 'start' },
  { id: 'cancel-task-during-complete', cancelTask: 'complete' },
  { id: 'retry-complete', failComplete: true, retryStats: 'complete' },
  { id: 'retry-begin', fallback: true, failContinue: true, retryStats: 'continue' },
  { id: 'verification-retry', wrongWrites: 1 },
  { id: 'judge-retry', judgeRetry: true },
  { id: 'rework', rework: true },
  { id: 'pause-resume', pauseTask: true },
  { id: 'browser-cancel-and-dismiss', cancelStats: ['REQUIREMENT_DESIGNER', 'start'], browser: true },
  { id: 'retry-cancelled-complete', cancelStats: ['IMPLEMENTATION', 'complete'], retryStats: 'complete' },
  { id: 'cancel-task-during-handoff', cancelHandoff: true },
  { id: 'restart-during-start', restart: true },
  { id: 'scope-confirm', scope: 'confirm' },
  { id: 'scope-cancel-task', scope: 'cancel' },
]
for (const [index, scenario] of scenarios.entries()) {
  if (selected && !selected.includes(scenario.id)) continue
  const caseId = `${runName}-${scenario.id}`
  const evidence = { caseId, scenario, startedAt: new Date().toISOString(), checkpoints: [] }
  const mark = (name, detail = {}) => {
    evidence.checkpoints.push({ name, at: new Date().toISOString(), ...detail })
    console.log(JSON.stringify({ caseId, checkpoint: name, ...detail }))
  }
  let designId, taskId, projectId, browser, page
  const rows = async () => (await call('/api/story-accounting')).filter(row => row.storyCode === evidence.storyCode)
  const task = async id => call(`/api/tasks/${id ?? taskId}`)
  async function snapshot() {
    if (designId) evidence.design = await call(`/api/designer-sessions/${designId}`)
    taskId ??= evidence.design?.taskId ?? evidence.design?.autoMode?.taskId
    if (taskId) evidence.task = await task()
    evidence.accounting = await rows()
    await writeFile(join(output, `${scenario.id}.json`), JSON.stringify(evidence, null, 2))
    return evidence
  }
  async function pending(role, operation) {
    return until(`${role} ${operation} at native receiver`, async () => {
      await snapshot()
      const row = evidence.accounting.find(row => row.role === role && row.operation === operation && active(row))
      if (!row) return false
      taskId ??= row.taskId
      const requests = (await ledger()).requests.filter(item => item.caseId === caseId && item.operation === operation
        && Date.parse(item.at) >= Date.parse(row.startedAt) - 100)
      if (!requests.length) return false
      return row
    })
  }
  async function settled() {
    let quietSince
    await until('all accounting and independent receiver receipts settled', async () => {
      const current = await rows()
      const requests = (await ledger()).requests.filter(item => item.caseId === caseId)
      const quiet = current.length > 0 && current.every(row => !active(row)) && requests.every(row => row.receiptAt)
      if (!quiet) quietSince = undefined
      else quietSince ??= Date.now()
      return quiet && Date.now() - quietSince >= 2_500
    }, 100_000)
    await snapshot()
    assert.ok(evidence.accounting.every(row => !active(row)), 'Late collector call remained pending')
  }
  async function accepted() {
    const result = await until('business acceptance with both Judges', async () => {
      await snapshot()
      const t = evidence.task
      if (!t) {
        if (['SESSION_ERROR', 'CANCELLED'].includes(evidence.design.state) || evidence.design.autoMode?.state === 'BLOCKED')
          throw new Error(`Design blocked: ${evidence.design.state}/${evidence.design.autoMode?.reason ?? evidence.design.autoMode?.state}`)
        return false
      }
      if (['AWAITING_DECISION', 'COMPLETED', 'SUCCEEDED'].includes(t.status) && t.executionResult === 'SUCCEEDED') return t
      if (['FAILED', 'CANCELLED'].includes(t.status) || t.status === 'AWAITING_DECISION'
        || (t.status === 'WAITING_INPUT' && !scenario.judgeRetry)) throw new Error(`Task blocked: ${t.status}; ${JSON.stringify(t.errors)}`)
      return false
    }, 240_000)
    assert.ok(['REQUIREMENT', 'RISK'].every(role => result.judges.some(j => j.role === role && j.status === 'COMPLETED' && j.verdict === 'PASS')))
    assert.equal(result.attemptCount, scenario.wrongWrites || scenario.pauseTask ? 2 : 1)
    const diff = JSON.parse(result.artifacts.find(item => item.kind === 'GIT_DIFF').content)
    assert.deepEqual(diff.changedPaths, scenario.scope ? ['config.properties', 'notes.properties'] : ['config.properties'],
      'Ignored plugin state leaked into the persisted Judge diff')
    if (!scenario.scope) assert.deepEqual(diff.approvalRequiredPaths, [])
    evidence.acceptedDiff = diff
    const preview = await call(`/api/tasks/${taskId}/diff-preview?path=config.properties`)
    assert.match(preview.patch, /^\+feature\.enabled=true$/m)
    assert.match(preview.patch, /^-feature\.enabled=false$/m)
    evidence.acceptedPatch = preview.patch
    mark('business-accepted', { taskId, attemptCount: result.attemptCount })
    return result
  }
  try {
    const root = join(directory, 'cases', caseId)
    await mkdir(root, { recursive: true })
    await writeFile(join(root, 'config.properties'), 'feature.enabled=false\nkeep.value=unchanged\n')
    await writeFile(join(root, '.gitignore'), '.aicoding/\n')
    if (scenario.scope) await writeFile(join(root, 'notes.properties'), 'note=original\n')
    const git = args => execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: 'pipe' })
    git(['init', '--initial-branch=main']); git(['add', '.'])
    git(['-c', 'user.name=Loopper Test', '-c', 'user.email=test@example.invalid', 'commit', '-m', 'fixture'])
    evidence.root = root
    evidence.storyCode = `${Date.now()}${index}`
    await control({ reset: true, workspace: root, caseId, delayMs: delay, strictActiveRun: true,
      operations: {
        start: { fail: !!scenario.fallback, seedExistingStory: !!scenario.fallback },
        continue: { fail: !!scenario.failContinue }, complete: { fail: !!scenario.failComplete },
      }, model: { wrongWritesRemaining: scenario.wrongWrites ?? 0, judgeVerdict: scenario.judgeRetry ? 'REVISE' : 'PASS',
        judgeDelayMs: scenario.cancelTask === 'complete' ? 10_000 : 0, implementationDelayMs: 0, outsideWrite: !!scenario.scope } })
    if (scenario.browser) {
      for (const old of await call('/api/story-accounting')) {
        if (active(old)) await call(`/api/story-accounting/${old.id}/cancel`, {})
        await call(`/api/story-accounting/${old.id}/dismiss`, {})
      }
      const { chromium } = await import('../../frontend/node_modules/playwright/index.mjs')
      browser = await chromium.launch({ executablePath: process.env.CHROME_EXECUTABLE ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true })
      page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
      await page.goto(endpoint)
    }
    const project = await call('/api/projects', { name: caseId, rootPath: root }); projectId = project.id
    const capability = await call(`/api/projects/${projectId}/story-binding-capability`)
    assert.equal(capability.available, true)
    const goal = '本地配置维护：修改 `config.properties`，将 feature.enabled 从 false 改为 true，保留其他配置内容。'
    const draft = await call('/api/loop-drafts', { spec: { schemaVersion: 'v2', projectId, goal,
      stages: [{ objective: '等待确认设计', allowedPaths: [], deliverables: [], verifiers: [] }], model: { providerId: 'aicoding-test', modelId: 'mock' } } })
    const creation = await call('/api/designer-sessions', { projectId, draftId: draft.id, initialMessage: goal,
      autoModeEnabled: true, storyBinding: { enabled: true, systemCode: 'SYS-001', storyCode: evidence.storyCode } })
    designId = creation.id
    mark('created', { designId })
    if (scenario.cancelStats) {
      const row = await pending(...scenario.cancelStats)
      await pause(2_000)
      if (page) {
        await page.getByRole('button', { name: '取消本次统计，继续任务', exact: true }).click()
        await until('browser statistics cancellation', async () => (await call(`/api/story-accounting/${row.id}`)).state === 'CANCELLED')
        await page.screenshot({ path: join(output, `${scenario.id}-cancelled.png`) })
      }
      const cancelled = page ? await call(`/api/story-accounting/${row.id}`) : await call(`/api/story-accounting/${row.id}/cancel`, {})
      assert.equal(cancelled.state, 'CANCELLED')
      evidence.cancelledCall = row.id
      mark('statistics-cancelled', { role: row.role, operation: row.operation, callId: row.id })
    }
    if (scenario.scope) {
      const approval = await until('out-of-scope existing file confirmation', async () => {
        await snapshot()
        return taskId ? call(`/api/tasks/${taskId}/git-diff-scope-approval`) : false
      })
      assert.deepEqual(approval.files.map(file => file.path), ['notes.properties'])
      evidence.scopeApproval = approval
      mark('scope-decision-requested')
      if (scenario.scope === 'cancel') {
        await call(`/api/tasks/${taskId}/cancel`, {})
        await until('scope cancellation finalized', async () => (await task()).status === 'CANCELLED')
        await settled(); mark('scope-task-cancelled')
      } else {
        await call(`/api/tasks/${taskId}/git-diff-scope-approval/${approval.requestId}/resolve`, {
          expectedTaskVersion: approval.taskVersion,
          decisions: approval.files.map(file => ({ path: file.path, patchSha256: file.patchSha256, action: 'ALLOW' })),
        })
        mark('scope-confirmed'); await accepted(); await settled()
      }
    } else if (scenario.restart) {
      const row = await pending('REQUIREMENT_DESIGNER', 'start'); await pause(2_000)
      await control({ restartLoopper: true })
      await until('restart marks pending call unknown', async () => (await call(`/api/story-accounting/${row.id}`)).state === 'UNKNOWN')
      await pause(delay)
      evidence.restartedCall = await call(`/api/story-accounting/${row.id}`)
      const starts = (await ledger()).requests.filter(item => item.caseId === caseId && ['start', 'continue'].includes(item.operation))
      assert.equal(starts.length, 1, 'Restart blindly redispatched BEGIN')
      mark('restart-preserved-unknown', { callId: row.id }); await snapshot()
    } else if (scenario.cancelHandoff) {
      await pending('REQUIREMENT_DESIGNER', 'complete')
      const t = await until('Task waiting for designer complete', async () => (await call('/api/tasks')).find(t => t.projectId === projectId))
      taskId = t.id; assert.equal(t.status, 'PENDING_START')
      assert.equal((await call(`/api/tasks/${taskId}/cancel`, {})).status, 'CANCELLED')
      await settled()
      assert.equal((await task()).status, 'CANCELLED')
      assert.equal((await rows()).filter(row => row.role === 'IMPLEMENTATION').length, 0)
      mark('handoff-cancelled-without-executor')
    } else if (scenario.stopDesigner) {
      await pending('REQUIREMENT_DESIGNER', 'start'); await pause(2_000)
      evidence.stopRequestedAt = new Date().toISOString()
      evidence.stop = await call(`/api/designer-sessions/${designId}/stop`, {})
      await until('designer cancellation', async () => (await call(`/api/designer-sessions/${designId}`)).state === 'CANCELLED')
      await settled(); assert.ok(!evidence.design.taskId && !evidence.design.autoMode?.taskId, 'Cancelled designer created a Task')
      mark('designer-cancelled')
    } else if (scenario.cancelTask) {
      await pending('IMPLEMENTATION', scenario.cancelTask); await pause(2_000)
      const cancelled = await call(`/api/tasks/${taskId}/cancel`, {})
      assert.ok(['STOPPING', 'CANCELLED'].includes(cancelled.status))
      await until('task cancellation finalized', async () => (await task()).status === 'CANCELLED')
      await settled(); assert.equal((await task()).status, 'CANCELLED')
      mark('task-cancelled', { taskId })
    } else {
      if (scenario.pauseTask) {
        await pending('IMPLEMENTATION', 'start'); await pause(2_000)
        const paused = await call(`/api/tasks/${taskId}/pause`, {}); assert.equal(paused.status, 'PAUSED')
        mark('task-paused')
        await call(`/api/tasks/${taskId}/resume`, {}); mark('task-resumed')
      }
      if (scenario.judgeRetry) {
        await until('Judge rejection', async () => { await snapshot(); return evidence.task?.status === 'WAITING_INPUT' })
        mark('judge-rejected', { reason: evidence.task.waitingReasonCode })
        await control({ model: { judgeVerdict: 'PASS' } })
        await call(`/api/tasks/${taskId}/judges/retry`, {}); mark('judge-retried')
      }
      if (scenario.wrongWrites) {
        await until('deterministic no-change rejection', async () => {
          await snapshot()
          return evidence.task?.status === 'AWAITING_DECISION' || evidence.task?.attemptCount > 1
        })
        if (evidence.task.status === 'AWAITING_DECISION') {
          assert.equal(evidence.task.executionResult, 'FAILED')
          await call(`/api/tasks/${taskId}/loop/retry`, {}); mark('business-retried')
        }
      }
      await accepted(); await settled()
      if (scenario.retryStats) {
        const latest = evidence.accounting.filter(row => row.operation === scenario.retryStats && ['FAILED', 'CANCELLED'].includes(row.state) && row.retryAvailable)
        assert.ok(latest.length > 0, 'No retired statistics call available for retry')
        await control({ operations: {}, delayMs: delay })
        const old = latest.at(-1)
        const responses = await Promise.allSettled([call(`/api/story-accounting/${old.id}/retry`, {}), call(`/api/story-accounting/${old.id}/retry`, {})])
        assert.equal(responses.filter(r => r.status === 'fulfilled').length, 1, 'Duplicate retry was accepted')
        const retried = responses.find(r => r.status === 'fulfilled').value
        await until('manual statistics retry success', async () => (await call(`/api/story-accounting/${retried.id}`)).state === 'SUCCEEDED', 60_000)
        assert.equal((await task()).attemptCount, 1)
        mark('statistics-retried', { oldId: old.id, newId: retried.id })
        await settled()
      }
      if (scenario.rework) {
        evidence.parentTask = evidence.task
        const child = await call(`/api/tasks/${taskId}/recoveries`, { mode: 'REWORK_ALL_STAGES' })
        taskId = child.taskId
        const started = await call(`/api/tasks/${taskId}/start`, {}); assert.equal(started.status, 'RUNNING')
        mark('rework-started', { taskId })
        await accepted(); await settled()
      }
    }
    const received = await ledger()
    evidence.requests = received.requests.filter(row => row.caseId === caseId)
    evidence.modelRequests = received.modelRequests.filter(row => row.caseId === caseId)
    if (scenario.stopDesigner) assert.equal(evidence.modelRequests.filter(row => row.at > evidence.stopRequestedAt
      && (row.content.includes('Requirement Designer') || row.content.includes('需求讨论设计师'))).length, 0,
    'Stopped designer dispatched a late business prompt')
    assert.ok(evidence.requests.length > 0)
    for (const row of evidence.requests) {
      assert.ok(row.receiptAt, `Missing independent receipt: ${row.operation}`)
      assert.ok(Date.parse(row.receiptAt) - Date.parse(row.at) >= delay - 250, `Delay was not 20 seconds: ${row.operation}`)
    }
    assert.ok(evidence.accounting.every(row => ['REQUIREMENT_DESIGNER', 'PACKAGE_DESIGNER', 'IMPLEMENTATION'].includes(row.role)), 'Unexpected Judge accounting')
    if (evidence.cancelledCall) assert.equal((await call(`/api/story-accounting/${evidence.cancelledCall}`)).state, 'CANCELLED', 'Late reply overwrote cancellation')
    if (page) {
      for (const row of evidence.accounting) await call(`/api/story-accounting/${row.id}/dismiss`, {})
      await page.reload(); await pause(2_000)
      assert.equal((await rows()).length, 0)
      await page.screenshot({ path: join(output, `${scenario.id}-dismissed.png`) })
      mark('dismissal-survived-reload')
    }
    if (['normal', 'fallback'].includes(scenario.id)) {
      assert.ok(evidence.accounting.every(row => row.state === 'SUCCEEDED' || (scenario.fallback && row.operation === 'start' && row.state === 'FAILED')))
      assert.equal(evidence.requests.map(row => row.operation).join(','), scenario.fallback
        ? 'start,continue,complete,start,continue,complete' : 'start,complete,start,complete')
      assert.ok(!evidence.requests.some(row => row.error === 'ACTIVE_RUN_EXISTS'))
    }
    evidence.result = 'PASS'; mark('passed')
  } catch (error) {
    evidence.result = 'FAIL'; evidence.error = error.stack ?? String(error)
    mark('failed', { error: error.message })
    try { await snapshot() } catch (captureError) { evidence.captureError = String(captureError) }
    try {
      const received = await ledger()
      evidence.requests = received.requests.filter(row => row.caseId === caseId)
      evidence.modelRequests = received.modelRequests.filter(row => row.caseId === caseId)
    } catch {}
  } finally {
    await browser?.close()
    // Retire only the fixtures made by this case. Keep all directories and evidence.
    if (taskId) {
      try { if (!['CANCELLED', 'AWAITING_DECISION', 'COMPLETED'].includes((await task()).status)) await call(`/api/tasks/${taskId}/cancel`, {}) }
      catch (error) { evidence.cleanupTaskError = String(error) }
    } else if (designId) {
      try { await call(`/api/designer-sessions/${designId}/stop`, {}) } catch (error) { evidence.cleanupDesignError = String(error) }
    }
    if (evidence.result === 'FAIL' || scenario.restart) {
      evidence.cleanupCancelledStatistics = []
      for (const row of await rows()) if (active(row)) {
        try { await call(`/api/story-accounting/${row.id}/cancel`, {}); evidence.cleanupCancelledStatistics.push(row.id) } catch {}
      }
    }
    try {
      await until('fixture cleanup receipts', async () => {
        const current = await rows()
        return current.every(row => !active(row))
          && (await ledger()).requests.filter(row => row.caseId === caseId).every(row => row.receiptAt)
      }, 65_000)
      await pause(1_500)
    } catch (error) { evidence.cleanupAccountingError = String(error) }
    evidence.finishedAt = new Date().toISOString()
    await writeFile(join(output, `${scenario.id}.json`), JSON.stringify(evidence, null, 2))
    results.push({ id: scenario.id, result: evidence.result, error: evidence.error?.split('\n')[0], startedAt: evidence.startedAt, finishedAt: evidence.finishedAt })
    await writeFile(join(output, 'results.json'), JSON.stringify({ endpoint, jar: env.jar, results }, null, 2))
  }
}
console.log(JSON.stringify({ output, results }, null, 2))
process.exitCode = results.some(row => row.result !== 'PASS') ? 1 : 0
