import { readFile, writeFile, access } from 'node:fs/promises'
import { join } from 'node:path'
const { endpoint, directory, projectRoot, receiver } = JSON.parse(await readFile(process.argv[2], 'utf8'))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
async function call(path, body, method = 'POST') {
  const response = await fetch(endpoint + path, { method: body === undefined ? 'GET' : method,
    headers: { 'Content-Type': 'application/json', 'X-Loopper-Local-UI': '1', Origin: endpoint }, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await response.text()
  if (!response.ok && response.status === 409 && path.includes('/questions/')) return null
  if (!response.ok) throw Error(`${path} ${response.status}: ${text.slice(0, 1500)}`)
  return text ? JSON.parse(text) : null
}
for (let i = 0; i < 45; i++) { try { await call('/actuator/health'); break } catch { if (i === 44) throw Error('Startup failed'); await sleep(1000) } }
const project = (await call('/api/projects')).find(p => p.rootPath === projectRoot) ?? await call('/api/projects', { name: '三个包的设计会话复用', rootPath: projectRoot })
const goal = '开发 Node 数学函数 sum、difference、product，分别返回两个数字之和、差、积，使用 Node 原生测试验证结果。分成三个独立业务包，每包包含实现和测试。'
const draft = await call('/api/loop-drafts', { spec: { schemaVersion: 'v2', projectId: project.id, goal, stages: [{ objective: '等待设计', allowedPaths: [], deliverables: [], verifiers: [] }], model: { providerId: 'aicoding-test', modelId: 'mock' } } })
const created = process.argv.includes('--resume') ? JSON.parse(await readFile(join(directory, 'multi-design.json'), 'utf8')) : await call('/api/designer-sessions', { projectId: project.id, draftId: draft.id, initialMessage: goal,
  autoModeEnabled: false, storyBinding: { enabled: true, systemCode: 'SYS-MULTI', storyCode: '000002' } })
let previous, confirmed = false, done = false
let rollingTask
const edited = new Set()
if (process.argv.includes('--resume')) for (let n=1; n<=3; n++) {
  try { await access(join(directory, `multi-WP-${n}-before.json`)); edited.add(`WP-${n}`) } catch { }
}
for (let i = 0; i < 300; i++) {
  const design = await call(`/api/designer-sessions/${created.id}`)
  await writeFile(join(directory, 'multi-design.json'), JSON.stringify(design, null, 2))
  const state = JSON.stringify({ id: created.id, state: design.state, phase: design.workflowPhase, packages: design.workPackages?.map(p => [p.id, p.state, p.designRevision]), conversations: design.designConversations?.map(c => [c.externalSessionId, c.state]) })
  if (state !== previous) { console.log(state); previous = state }
  if (design.taskProfile?.decisionState === 'NEEDS_CONFIRMATION') {
    await call(`/api/designer-sessions/${created.id}/task-profile`, { intent: 'SOFTWARE_CHANGE', primaryArtifactKind: 'SOURCE_CODE', largeTaskMode: true,
      componentKeys: design.taskProfile.componentKeys, expectedVersion: design.taskProfile.version }, 'PUT')
  }
  for (const q of design.pendingQuestions ?? []) await call(`/api/designer-sessions/${created.id}/questions/${q.id}/reply`, { answers: q.questions.map(() => ['确认']) })
  if (!confirmed && design.state === 'REVIEWING' && design.workflowPhase === 'DISCUSSING_REQUIREMENT' && design.taskProfile?.confirmationReady) {
    await call(`/api/designer-sessions/${created.id}/requirement/confirm`, { expectedDiscussionRevision: design.discussionRevision }); confirmed = true
  }
  const p = design.workPackages?.find(p => p.state === 'REVIEWING')
  if (p) {
    const payload = { expectedDiscussionRevision: design.discussionRevision, expectedDesignRevision: p.designRevision }
    if (!edited.has(p.id)) {
      await writeFile(join(directory, `multi-${p.id}-before.json`), JSON.stringify(design, null, 2))
      await call(`/api/designer-sessions/${created.id}/work-packages/${p.id}/messages`, { ...payload, content: '明确本包数学运算与测试的对应关系，保持范围不变。' }); edited.add(p.id)
    } else {
      await writeFile(join(directory, `multi-${p.id}-after.json`), JSON.stringify(design, null, 2))
      await call(`/api/designer-sessions/${created.id}/work-packages/${p.id}/approve`, payload)
    }
  }
  if (design.taskId) {
    rollingTask = await call(`/api/tasks/${design.taskId}`)
    const workbench = await call(`/api/tasks/${design.taskId}/packages`)
    await writeFile(join(directory, 'multi-task.json'), JSON.stringify({ task: rollingTask, workbench }, null, 2))
    const ready = workbench.packages.find(p => p.state === 'EXECUTION_READY')
    if (ready) await call(`/api/tasks/${design.taskId}/packages/${ready.id}/start`, {
      expectedTaskVersion: workbench.taskVersion, expectedPackageVersion: ready.version,
      expectedDiscussionRevision: ready.discussionRevision, expectedDesignRevision: ready.designRevision })
    if (rollingTask.status === 'AWAITING_DECISION') { done = true; break }
  } else if (design.workflowPhase === 'FINAL_REVIEW') { done = true; break }
  if (['WAITING_INPUT', 'SESSION_ERROR'].includes(design.state)) throw Error('Multi-package design stopped; inspect multi-design.json')
  await sleep(1000)
}
if (!done || edited.size !== 3) throw Error('Three-package design did not finish')
for (let i = 0; i < 160; i++) {
  const calls = (await call('/api/story-accounting')).filter(row => row.designerSessionId === created.id)
  if (calls.length === 8 && calls.every(row => row.state === 'SUCCEEDED')) {
    await writeFile(join(directory, 'multi-accounting.json'), JSON.stringify(calls, null, 2)); break
  }
  if (i === 159) throw Error('Expected exactly four accounting pairs')
  await sleep(250)
}
const design = await call(`/api/designer-sessions/${created.id}`)
if (design.designConversations.length !== 4 || new Set(design.designConversations.map(c => c.externalSessionId)).size !== 4) throw Error('Package Sessions did not remain independent')
if (rollingTask && (rollingTask.executionResult !== 'SUCCEEDED' || rollingTask.judges.length !== 2 || rollingTask.judges.some(j => j.verdict !== 'PASS'))) throw Error('Rolling execution or dual review failed')
const ledger = await (await fetch(receiver + '/requests')).json()
await writeFile(join(directory, 'multi-receiver.json'), JSON.stringify(ledger, null, 2))
const designRemotes = new Set(design.designConversations.map(c => c.externalSessionId))
if (ledger.requests.filter(r => designRemotes.has(r.sessionId)).map(r => r.operation).join(',') !== Array(4).fill('start,complete').join(',')) throw Error('Unexpected multi accounting order')
console.log('THREE_PACKAGES_FOUR_SESSIONS_FOUR_PAIRS_PASSED')

if (rollingTask) console.log('ROLLING_THREE_IMPLEMENTATIONS_AND_DUAL_REVIEW_PASSED')
