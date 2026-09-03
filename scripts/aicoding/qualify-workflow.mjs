import { readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
const environment = JSON.parse(await readFile(process.argv[2], 'utf8'))
const fault = process.argv.includes('--fault')
const longWait = process.argv.includes('--long-wait')
const manualCancel = process.argv.includes('--manual-cancel')
const modelWait = process.argv.includes('--model-wait')
const completeWait = process.argv.includes('--complete-wait')
const handoffWait = process.argv.includes('--handoff-wait')
const { endpoint, projectRoot, directory, receiver } = environment
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
async function call(path, body) {
  const response = await fetch(endpoint + path, { method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Loopper-Local-UI': '1', Origin: endpoint },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(120000) })
  const text = await response.text()
  if (!response.ok) throw new Error(`${path} HTTP ${response.status}: ${text.slice(0, 1000)}`)
  return text ? JSON.parse(text) : undefined
}
await fetch(receiver + '/control', { method: 'POST', body: JSON.stringify({ fail: fault, delayMs: handoffWait ? 40000 : longWait && !modelWait ? (manualCancel ? 120000 : 40000) : 0, remaining: longWait ? 1 : 1000, onlyOperation: handoffWait ? 'complete' : null, strictActiveRun: handoffWait, accountingModelDelayMs: modelWait ? 40000 : 0, modelRemaining: 1, modelOperation: completeWait ? 'complete' : null }) })
const existing = await call('/api/projects')
const project = existing.find(item => item.rootPath === projectRoot) ?? await call('/api/projects', { name: '故事统计真实链路', rootPath: projectRoot })
const capability = await call(`/api/projects/${project.id}/story-binding-capability`)
if (!capability.available) throw new Error(JSON.stringify(capability))
const goal = '本地配置维护：修改 `config.properties`，将 feature.enabled 从 false 改为 true，保留其他配置内容。'
const draft = await call('/api/loop-drafts', { spec: { schemaVersion: 'v2', projectId: project.id, goal,
  stages: [{ objective: '等待确认设计', allowedPaths: [], deliverables: [], verifiers: [] }],
  model: { providerId: 'aicoding-test', modelId: 'mock' } } })
const creation = call('/api/designer-sessions', { projectId: project.id, draftId: draft.id,
  initialMessage: goal, autoModeEnabled: true, storyBinding: { enabled: true, systemCode: 'SYS-001', storyCode: fault ? '000124' : '000123' } })
if (longWait || modelWait) {
  let pending
  for (let i = 0; i < 200; i++) {
    pending = (await call('/api/story-accounting')).find(row => row.state === 'PREPARED' && row.operation === (completeWait ? 'complete' : 'start'))
    if (pending) break
    await pause(200)
  }
  if (!pending) throw new Error('No live accounting call')
  console.log(JSON.stringify({ awaitingStatistics: pending.id, manualCancel }))
  await writeFile(join(directory, 'pending-call.json'), JSON.stringify(pending, null, 2))
  if (!manualCancel) {
    await pause(32000)
    const waiting = await call(`/api/story-accounting/${pending.id}`)
    if (waiting.state !== 'PREPARED') throw new Error('Statistics must still be running beyond 30 seconds')
    await writeFile(join(directory, 'past-30-seconds.json'), JSON.stringify(waiting, null, 2))
    await call(`/api/story-accounting/${pending.id}/cancel`, {})
  }
}
const created = await creation
console.log(JSON.stringify({ designerId: created.id, draftId: draft.id, capability }))
const history = []
let previous = ''
let finalTask
const artifact = handoffWait ? 'handoff-workflow.json' : fault ? 'fault-workflow.json' : (longWait || modelWait) ? 'cancel-workflow.json' : 'normal-workflow.json'
for (let index = 0; index < 240; index++) {
  const design = await call(`/api/designer-sessions/${created.id}`)
  const taskId = design.taskId ?? design.autoMode?.taskId
  const task = taskId ? await call(`/api/tasks/${taskId}`) : null
  finalTask = task
  const state = JSON.stringify({ designerState: design.state, phase: design.workflowPhase,
    profile: design.taskProfile?.decisionState, autoMode: design.autoMode?.state,
    taskState: task?.state ?? task?.status, taskId })
  if (state !== previous) { history.push({ at: new Date().toISOString(), state }); console.log(state); previous = state }
  await writeFile(join(directory, artifact),
    JSON.stringify({ capability, history, design, task }, null, 2))
  if (task && ['AWAITING_DECISION', 'COMPLETED', 'FAILED', 'CANCELLED', 'WAITING_INPUT'].includes(task.state ?? task.status)) break
  if (['SESSION_ERROR', 'WAITING_INPUT'].includes(design.state) || design.autoMode?.state === 'BLOCKED') {
    console.log('Workflow needs inspection; persisted snapshot contains the exact reason.'); break
  }
  await pause(1000)
}
// Allow the collector to finish retired Sessions before checking the request ledger.
for (let i = 0; i < (handoffWait ? 300 : 50); i++) {
  const rows = (await call('/api/story-accounting')).filter(row => row.designerSessionId === created.id || row.taskId === finalTask?.id)
  if (rows.length === 4 && rows.every(row => !['PREPARED', 'CANCELLING'].includes(row.state))) break
  await pause(200)
}
const accounting = (await call('/api/story-accounting')).filter(row => row.designerSessionId === created.id || row.taskId === finalTask?.id)
await writeFile(join(directory, 'accounting-roles.json'), JSON.stringify(accounting, null, 2))
if (accounting.length !== 4 || accounting.some(row => !['REQUIREMENT_DESIGNER', 'IMPLEMENTATION'].includes(row.role))
    || accounting.some(row => row.operation === 'continue')) throw new Error('Unexpected accounting role or operation')
const ledger = await (await fetch(receiver + '/requests')).json()
await writeFile(join(directory, 'receiver-ledger.json'), JSON.stringify(ledger, null, 2))
if (ledger.requests.length !== 4 || ledger.requests.map(row => row.operation).join(',') !== 'start,complete,start,complete') {
  throw new Error('A statistics command returned without the expected native plugin requests')
}
if (fault && accounting.some(row => row.state !== 'FAILED')) throw new Error('Injected statistics failures were not recorded')
if (handoffWait) {
  if (accounting.some(row => row.state !== 'SUCCEEDED')) throw new Error('A slow complete was interrupted')
  if (Date.parse(ledger.requests[2].at) < Date.parse(ledger.requests[1].receiptAt)) throw new Error('Next start overtook complete')
  if (ledger.requests.some(row => row.error === 'ACTIVE_RUN_EXISTS')) throw new Error('Story run overlapped')
}
console.log(JSON.stringify({ requests: ledger.requests.map(row => ({ operation: row.operation, sessionId: row.sessionId, receipt: row.receipt })), modelTurns: ledger.modelRequests.length }))
if (!finalTask || !['AWAITING_DECISION', 'COMPLETED'].includes(finalTask.state ?? finalTask.status)) {
  throw new Error(`Workflow did not reach result review; inspect ${join(directory, artifact)}`)
}
if (finalTask.executionResult !== 'SUCCEEDED' || finalTask.attemptCount !== 1 || finalTask.judges.length !== 2
    || !['REQUIREMENT', 'RISK'].every(role => finalTask.judges.some(judge => judge.role === role
      && judge.status === 'COMPLETED' && judge.verdict === 'PASS'))) {
  throw new Error(`Accounting affected business acceptance or retry budget; inspect ${join(directory, artifact)}`)
}
