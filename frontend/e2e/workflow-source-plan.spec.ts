const narrowLayoutInScope: boolean = false
import { semanticName } from './w3/semantics'
import { selectWorkflowNode, workflowTool } from './fixtures/workflowNavigation'
import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { candidate, execution, requirement, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowGraph, WorkflowNode, WorkflowInput } from '../src/types/workflow'

// These are explicit browser fixtures. The complete same-template execution is also covered in SQLite integration.
const presets = (JSON.parse(readFileSync('../src/main/resources/workflows/node-presets.json', 'utf8')) as { presets: { id: string; node: WorkflowNode }[] }).presets
const flow = (JSON.parse(readFileSync('../src/main/resources/workflows/templates.json', 'utf8')) as { flows: { id: string; nodes: { id: string; presetId: string; inputs: WorkflowInput[] }[]; edges: WorkflowGraph['edges']; inputs: WorkflowGraph['inputs'] }[] }).flows.find(f => f.id === 'builtin.workflow.source-design')!
const base: WorkflowGraph = { schemaVersion: 1, nodes: flow.nodes.map(n => ({ ...presets.find(p => p.id === n.presetId)!.node, id: n.id, inputs: n.inputs })), edges: flow.edges, inputs: flow.inputs }
const source = base.nodes[1]!.inputs[0]!
function proposed(): WorkflowGraph {
  const author = base.nodes[2]!, review = base.nodes[3]!, document = base.nodes[4]!
  const authors = ['author', 'author2'].map((id, i) => ({ ...author, id, title: `${author.title} · 第 ${i + 1} 批`, parameters: { ...author.parameters, targetPaths: JSON.stringify([i ? 'src/other/Part.java' : 'src/Main.java']) } }))
  const reviews = ['reviewer', 'reviewer2'].map((id, i) => ({ ...review, id, title: `${review.title} · 第 ${i + 1} 批`, parameters: authors[i]!.parameters, inputs: [source, ...authors.map((a, j): WorkflowInput => ({ name: i === j ? 'draft' : `related_${j + 1}`, source: 'NODE', sourceId: a.id, output: 'design', kind: 'JSON', required: true }))] }))
  const inputs: WorkflowInput[] = [source, ...authors.map((a, i): WorkflowInput => ({ name: `draft_${i + 1}`, source: 'NODE', sourceId: a.id, output: 'design', kind: 'JSON', required: true })), ...reviews.map((r, i): WorkflowInput => ({ name: `review_${i + 1}`, source: 'NODE', sourceId: r.id, output: 'review', kind: 'DECISION', required: true }))]
  const pairs = [...authors.map(a => ['batches', a.id]), ...authors.flatMap(a => reviews.map(r => [a.id, r.id])), ...reviews.map(r => [r.id, 'document'])]
  return { ...base, nodes: [...base.nodes.slice(0, 2), ...authors, ...reviews, { ...document, inputs }], edges: [base.edges[0]!, ...pairs.map(([from, to], i) => ({ id: `edge_${i}`, from: from!, to: to!, outcome: null }))] }
}
for (const failed of [false, true]) test(`源码分批${failed ? '超限保留原计划' : '先查看批次再确认且不自动启动'}`, async ({ page }) => {
  const req = requirement({ id: 'source-plan', title: '源码详细设计', state: failed ? 'STALLED' : 'PAUSED', graph: base, revision: 1, headRevision: 1 })
  const snapshot = execution(req.state); snapshot.execution.id = req.id; snapshot.execution.revision = 1
  snapshot.execution.nodes = base.nodes.map(n => ({ id: `run-${n.id}`, nodeKey: n.id, state: n.id === 'source' ? 'SUCCEEDED' : n.id === 'batches' ? failed ? 'FAILED' : 'SUCCEEDED' : 'PENDING', attemptCount: ['source', 'batches'].includes(n.id) ? 1 : 0, latestAttemptId: n.id === 'batches' ? 'source-plan-attempt' : null, version: 1, outcome: null }))
  snapshot.control = { ...snapshot.control, id: req.id, revision: 1, configured: true, mode: 'CONTINUOUS', state: failed ? 'STALLED' : 'PAUSED', reasonCode: failed ? 'WORKFLOW_RETRY_EXHAUSTED' : 'WORKFLOW_PLAN_REVIEW_REQUIRED' }
  const draft = candidate({ nodeKey: 'batches', sourceTitle: '源码设计分批规划', baseRevision: 1, graph: proposed(), originalGraph: base,
    changes: { added: ['author2', 'reviewer2'], changed: ['author', 'reviewer', 'document'], removed: [], affected: ['author', 'author2', 'reviewer', 'reviewer2', 'document'], protectedNodes: ['source', 'batches'] } })
  const run = attempt({ id: 'source-plan-attempt', state: failed ? 'FAILED' : 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true, modelState: null, roleName: null })
  let applies = 0, starts = 0, resultReads = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/source-plan') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/candidates')) return route.fulfill({ json: { items: failed || draft.state !== 'PENDING' ? [] : [{ ...draft, sourceState: 'SUCCEEDED', createdAt: '' }], nextCursor: null } })
    if (path.endsWith('/candidates/candidate')) return route.fulfill({ json: draft })
    if (path.endsWith('/candidates/candidate/apply')) {
      expect(route.request().headers()['x-loopper-local-ui']).toBe('1'); const body = route.request().postDataJSON(); applies++; req.graph = body.graph; req.revision = 2; req.headRevision = 2; req.version++
      draft.state = 'APPLIED'; draft.appliedRevision = 2; draft.version++; snapshot.execution.revision = 2; snapshot.execution.version = req.version; snapshot.control.revision = req.revision; snapshot.control.version = req.version; snapshot.control.reasonCode = 'WORKFLOW_PLAN_CHANGED'
      return route.fulfill({ json: { id: req.id, state: req.state, revision: 2, version: req.version, layoutVersion: req.layoutVersion } })
    }
    if (path.endsWith('/layout')) { const body = route.request().postDataJSON(); expect(body).toMatchObject({ expectedRevision: req.revision, expectedLayoutVersion: req.layoutVersion }); req.layout = body.layout; req.layoutVersion++; return route.fulfill({ json: { id: req.id, state: req.state, revision: req.revision, version: req.version, layoutVersion: req.layoutVersion } }) }
    if (path.endsWith('/control/start')) { starts++; return route.fulfill({ json: snapshot.control }) }
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/source-plan-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: base.nodes[1] })
    if (path.endsWith('/result')) { resultReads++; return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'private-delivery', delivery: { summary: failed ? '分批计划未生成' : '完整候选已生成', outcome: null, outputs: {
      ...(failed ? {} : { plan: { kind: 'PLAN', content: { version: 1, baseRevision: 1, graph: draft.graph } } }),
      report: { kind: 'JSON', content: { version: 1, type: 'SOURCE_DESIGN_PLAN', complete: !failed, code: failed ? 'WORKFLOW_SOURCE_PLAN_LIMIT' : null, sourceCount: 2, batchCount: 2, batches: [{ ordinal: 0, title: 'src', paths: ['src/Main.java'] }, { ordinal: 1, title: 'src/other', paths: ['src/other/Part.java'] }] } },
    } } } }) }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    if (path === '/api/roles') return route.fulfill({ json: { items: [], nextCursor: null } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/source-plan')
  await selectWorkflowNode(page, '源码设计分批规划'); expect(resultReads).toBe(0)
  await page.getByRole('button', { name: semanticName('workflow.deliverables'), exact: true }).click()
  const report = page.locator('.workflow-professional-report'); await expect(report).toContainText(failed ? '分批计划未生成' : '2 个源码文件，分为 2 批')
  if (failed) await expect(report).toContainText('原源码和配置已保留')
  else {
    await report.locator('summary').first().click(); await expect(report.getByText('src/Main.java', { exact: true })).toBeVisible()
    await expect(page.getByRole('region', { name: '节点执行详情' })).not.toContainText('system.source.design-plan')
    await expect(page.getByRole('button', { name: semanticName('workflow.continuous'), exact: true })).toBeDisabled()
  }
  await page.screenshot({ path: `test-results/workflow-source-plan-${failed ? 'limit' : 'report'}-desktop.png`, fullPage: true })
  if (narrowLayoutInScope) { // OUT_OF_SCOPE: current acceptance is desktop only; historical assertions retained.
    await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-source-plan-${failed ? 'limit' : 'report'}-mobile.png`, fullPage: true })
  }
  if (failed) { expect(applies).toBe(0); expect(starts).toBe(0); return }
  await page.setViewportSize({ width: 1600, height: 1000 }); await workflowTool(page, '候选计划'); await page.getByRole('complementary', { name: '候选计划' }).locator('.w5-list [data-semantic="selection.select"]').first().click()
  await expect(page.getByText('源码设计分批规划 提出的计划', { exact: true })).toBeVisible(); await page.getByRole('button', { name: semanticName('workflow.reviewCandidate'), exact: true }).click()
  await expect(page.locator('.workflow-proposal-banner')).toContainText('尚未生效'); await expect(page.locator('.workflow-node')).toHaveCount(7); expect(applies).toBe(0); expect(starts).toBe(0)
  await page.getByRole('button', { name: '适应画布', exact: true }).click(); await page.screenshot({ path: 'test-results/workflow-source-plan-preview.png', fullPage: true })
  await page.getByRole('button', { name: semanticName('workflow.applyCandidate'), exact: true }).click(); await expect(page.getByText('计划已应用，请选择执行方式继续。', { exact: true })).toBeVisible()
  expect(applies).toBe(1); expect(starts).toBe(0)
})
