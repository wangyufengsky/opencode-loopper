import { readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
const env = JSON.parse(await readFile(process.argv[2], 'utf8'))
const { endpoint, directory, projectRoot, receiver } = env
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
async function call(path, body) {
  const response = await fetch(endpoint + path, { method: body === undefined ? 'GET' : 'POST', headers: {
    'Content-Type': 'application/json', 'X-Loopper-Local-UI': '1', Origin: endpoint }, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await response.text()
  if (!response.ok) throw Error(`${path} ${response.status}: ${text.slice(0, 1500)}`)
  return text ? JSON.parse(text) : null
}
for (let n = 0; n < 45; n++) { try { await call('/actuator/health'); break } catch { if (n === 44) throw Error('Loopper startup failed'); await sleep(1000) } }
const project = (await call('/api/projects')).find(row => row.rootPath === projectRoot) ?? await call('/api/projects', { name: '设计师会话复用验收', rootPath: projectRoot })
const capability = await call(`/api/projects/${project.id}/story-binding-capability`)
if (!capability.available) throw Error(JSON.stringify(capability))
const goal = '开发 Node 加法函数 sum(a,b)，返回两个数字之和，使用 Node 原生测试验证 sum(2,3) 返回 5。'
const draft = await call('/api/loop-drafts', { spec: { schemaVersion: 'v2', projectId: project.id, goal, stages: [{ objective: '等待设计', allowedPaths: [], deliverables: [], verifiers: [] }], model: { providerId: 'aicoding-test', modelId: 'mock' } } })
const created = (process.argv.includes('--resume') || process.argv.includes('--resume-execution')) ? JSON.parse(await readFile(join(directory, 'reuse-design.json'), 'utf8')) : await call('/api/designer-sessions', { projectId: project.id, draftId: draft.id, initialMessage: goal, autoModeEnabled: false, storyBinding: { enabled: true, systemCode: 'SYS-REUSE', storyCode: '000123' } })
let previous, revisions = process.argv.includes('--resume-execution') ? 2 : 0, requirementConfirmed = false
for (let i = 0; !process.argv.includes('--resume-execution') && i < 240; i++) {
  const design = await call(`/api/designer-sessions/${created.id}`)
  await writeFile(join(directory, 'reuse-design.json'), JSON.stringify(design, null, 2))
  const state = JSON.stringify({ id: design.id, state: design.state, phase: design.workflowPhase, settings: design.taskProfile?.decisionState, package: design.workPackages?.map(p => ({ id:p.id,state:p.state,revision:p.designRevision })), revisions })
  if (state !== previous) { console.log(state); previous = state }
  if (design.taskProfile?.decisionState === 'NEEDS_CONFIRMATION') await call(`/api/designer-sessions/${created.id}/task-profile/confirm`, { expectedVersion: design.taskProfile.version })
  for (const q of design.pendingQuestions ?? []) await call(`/api/designer-sessions/${created.id}/questions/${q.id}/reply`, { answers: q.questions.map(() => ['确认']) })
  if (design.workflowPhase === 'DISCUSSING_REQUIREMENT' && design.state === 'REVIEWING' && !requirementConfirmed && design.taskProfile?.confirmationReady) {
    await call(`/api/designer-sessions/${created.id}/requirement/confirm`, { expectedDiscussionRevision: design.discussionRevision }); requirementConfirmed = true
  }
  const workPackage = design.workPackages?.find(p => ['REVIEWING', 'APPROVED'].includes(p.state))
  if (workPackage) {
    const remotes = design.designConversations?.filter(c => c.state === 'OPEN') ?? []
    if (remotes.length !== 1) throw Error('Single package lost its conversation')
    const accounting = (await call('/api/story-accounting')).filter(row => row.designerSessionId === created.id)
    if (accounting.length !== 1 || accounting[0].operation !== 'start' || accounting[0].state !== 'SUCCEEDED') throw Error('Statistics repeated before handoff')
    await writeFile(join(directory, `reuse-revision-${revisions}.json`), JSON.stringify({ design, accounting }, null, 2))
    if (revisions < 2) {
      if (workPackage.state === 'APPROVED') await call(`/api/designer-sessions/${created.id}/work-packages/${workPackage.id}/reopen`, {
        expectedDiscussionRevision: design.discussionRevision, expectedDesignRevision: workPackage.designRevision })
      const editing = await call(`/api/designer-sessions/${created.id}`)
      await call(`/api/designer-sessions/${created.id}/work-packages/${workPackage.id}/messages`, {
        content: `第 ${revisions + 1} 次修改：进一步说明 sum 的数字加法验收，不改变实现范围。`,
        expectedDiscussionRevision: editing.discussionRevision, expectedDesignRevision: workPackage.designRevision })
      revisions++
    } else { console.log('REUSE_TWO_REVISIONS_PASSED'); break }
  }
  if (['WAITING_INPUT', 'SESSION_ERROR'].includes(design.state)) throw Error(`Stopped: inspect ${directory}/reuse-design.json`)
  await sleep(1000)
}
await writeFile(join(directory, 'reuse-receiver.json'), JSON.stringify(await (await fetch(receiver + '/requests')).json(), null, 2))

if (process.argv.includes('--execute')) {
  const design = await call(`/api/designer-sessions/${created.id}`)
  if (design.workflowPhase !== 'FINAL_REVIEW' || revisions !== 2) throw Error('Design did not finish its two revisions')
  await fetch(receiver + '/control', { method: 'POST', body: JSON.stringify({ strictActiveRun: true, onlyOperation: 'complete', delayMs: process.argv.includes('--slow') ? 35000 : 0, remaining: 1 }) })
  const confirmation = await call(`/api/loop-drafts/${design.draft.id}/confirm`, {})
  const task = { id: confirmation.taskId }
  await call(`/api/tasks/${task.id}/start`, {})
  let result
  for (let n = 0; n < 240; n++) {
    result = await call(`/api/tasks/${task.id}`)
    await writeFile(join(directory, 'reuse-task.json'), JSON.stringify(result, null, 2))
    if (result.status === 'AWAITING_DECISION') break
    if (['WAITING_INPUT', 'FAILED', 'CANCELLED'].includes(result.status)) throw Error('Implementation stopped')
    await sleep(1000)
  }
  if (result.executionResult !== 'SUCCEEDED' || result.judges.length !== 2 || result.judges.some(j => j.verdict !== 'PASS')) throw Error('Implementation or dual review failed')
  for (let n = 0; n < 200; n++) {
    const calls = (await call('/api/story-accounting')).filter(row => row.designerSessionId === created.id || row.taskId === task.id)
    if (calls.length === 4 && calls.every(row => row.state === 'SUCCEEDED')) {
      await writeFile(join(directory, 'reuse-accounting.json'), JSON.stringify(calls, null, 2)); break
    }
    if (n === 199) throw Error('Accounting did not complete exactly two pairs')
    await sleep(300)
  }
  const ledger = await (await fetch(receiver + '/requests')).json()
  await writeFile(join(directory, 'reuse-receiver.json'), JSON.stringify(ledger, null, 2))
  if (ledger.requests.map(row => row.operation).join(',') !== 'start,complete,start,complete') throw Error('Unexpected accounting sequence')
  if (Date.parse(ledger.requests[2].at) < Date.parse(ledger.requests[1].receiptAt)) throw Error('Next start overtook complete receipt')
  console.log('REUSE_IMPLEMENTATION_DUAL_REVIEW_HANDOFF_PASSED')
}
