import { workflowTool } from './fixtures/workflowNavigation'
import { expect, test } from '@playwright/test'
import { candidate, execution, requirement } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowGraph, WorkflowLayout } from '../src/types/workflow'

// Explicit HTTP fixtures test browser behavior; model and SQLite correctness have separate integration tests.
test('候选先查看、编辑后确认、历史预览和手动后续调整', async ({ page }) => {
  const req = requirement({ state: 'PAUSED' }), draft = candidate(), snapshot = execution('PAUSED')
  snapshot.control = { ...snapshot.control, configured: true, mode: 'CONTINUOUS', reasonCode: 'WORKFLOW_PLAN_REVIEW_REQUIRED' }
  snapshot.execution.nodes = [{ id: 'source', nodeKey: 'review', state: 'SUCCEEDED', attemptCount: 1, latestAttemptId: draft.attemptId, version: 2, outcome: null }]
  const applies: WorkflowGraph[] = [], starts: unknown[] = []; let manualApplies = 0
  const receipt = () => ({ id: req.id, state: req.state, revision: req.revision, version: req.version, layoutVersion: req.layoutVersion })
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname, method = route.request().method()
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/req') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: snapshot.execution.state, version: snapshot.execution.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/candidates')) return route.fulfill({ json: { items: new URL(route.request().url()).searchParams.has('state') && draft.state !== 'PENDING' ? [] : [{ ...draft, sourceState: 'SUCCEEDED', createdAt: '' }], nextCursor: null } })
    if (path.endsWith('/candidates/candidate')) return route.fulfill({ json: draft })
    if (path.endsWith('/apply')) {
      expect(route.request().headers()['x-loopper-local-ui']).toBe('1'); const body = route.request().postDataJSON(); expect(body.expectedRevision).toBe(req.revision); expect(body.expectedVersion).toBe(snapshot.execution.version)
      req.graph = body.graph; req.revision++; req.version++; snapshot.execution.revision = req.revision; snapshot.execution.version = req.version; snapshot.control.revision = req.revision; snapshot.control.version = req.version; snapshot.control.reasonCode = 'WORKFLOW_PLAN_CHANGED'
      if (path.endsWith('/candidates/candidate/apply')) { applies.push(body.graph); draft.state = 'APPLIED'; draft.appliedRevision = req.revision; draft.version++ } else manualApplies++
      return route.fulfill({ json: receipt() })
    }
    if (path.endsWith('/control/start')) { starts.push(route.request().postDataJSON()); return route.fulfill({ json: snapshot.control }) }
    if (path.endsWith('/layout')) { req.layout = (route.request().postDataJSON() as { layout: WorkflowLayout }).layout; req.layoutVersion++; return route.fulfill({ json: receipt() }) }
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [], nextCursor: null } })
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/req')
  await expect(page.getByRole('button', { name: '连续执行', exact: true })).toBeDisabled(); await workflowTool(page, '候选计划')
  await page.locator('.workflow-candidate-list button').first().click(); await expect(page.getByText('设计节点 提出的计划', { exact: true })).toBeVisible(); await page.screenshot({ path: 'test-results/workflow-candidate-list.png', fullPage: true })
  await page.getByRole('button', { name: '在画布中查看', exact: true }).click(); await expect(page.locator('.workflow-proposal-banner')).toContainText('尚未生效'); expect(applies).toHaveLength(0)
  await page.getByRole('button', { name: '适应画布', exact: true }).click(); await page.locator('.workflow-node').filter({ hasText: '候选后续检查' }).click(); await page.getByLabel('任务说明', { exact: true }).fill('检查新增阶段的结果和交付物')
  await page.screenshot({ path: 'test-results/workflow-candidate-preview.png', fullPage: true }); await page.getByRole('button', { name: '确认并应用候选计划', exact: true }).click()
  await expect(page.getByText('计划已应用，请选择执行方式继续。', { exact: true })).toBeVisible(); expect(applies).toHaveLength(1); expect(applies[0]!.nodes[1]!.task).toBe('检查新增阶段的结果和交付物'); expect(starts).toHaveLength(0)
  await workflowTool(page, '候选计划'); await page.getByLabel('候选状态', { exact: true }).selectOption(''); await page.locator('.workflow-candidate-list button').first().click(); await page.getByRole('button', { name: '在画布中查看', exact: true }).click()
  await expect(page.locator('.workflow-proposal-banner')).toContainText('只读预览'); await expect(page.getByRole('button', { name: '确认并应用候选计划', exact: true })).toHaveCount(0); await page.getByRole('button', { name: '退出候选预览', exact: true }).click()
  await workflowTool(page, '调整后续计划'); await page.locator('.workflow-node').filter({ hasText: '候选后续检查' }).click(); await page.getByLabel('任务说明', { exact: true }).fill('进一步核对成果')
  await page.getByRole('button', { name: '应用计划调整', exact: true }).click(); await expect(page.getByText('计划版本 4', { exact: true })).toBeVisible(); expect(manualApplies).toBe(1); expect(starts).toHaveLength(0)
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true); await page.screenshot({ path: 'test-results/workflow-plan-review-mobile.png', fullPage: true })
})
