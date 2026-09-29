import { expect, test } from '@playwright/test'
import { requirement, execution } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowSaveTemplate, WorkflowTemplateSelection } from '../src/types/workflow'

// Real browser interaction with explicit HTTP fixtures; backend persistence is covered separately.
for (const width of [1600, 390]) test(`另存流程先预览、保留当前步骤及未知回执 ${width}`, async ({ page }) => {
  const req = requirement(), snapshot = execution(); const saves: WorkflowSaveTemplate[] = [], selections: WorkflowTemplateSelection[] = []
  let fail = true
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/req') return route.fulfill({ json: req })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/templates/preview')) {
      expect(route.request().headers()['x-loopper-local-ui']).toBe('1')
      const body = route.request().postDataJSON() as WorkflowTemplateSelection; selections.push(body)
      return route.fulfill({ json: { mode: body.mode, sourceRevision: body.mode === 'CURRENT' ? 2 : 1, initialAvailable: true, graph: body.mode === 'CURRENT' ? body.graph : req.graph, layout: body.layout, fixedPlanningNodes: [], sha256: body.mode.toLowerCase(), diagnostics: [] } })
    }
    if (path.endsWith('/templates')) {
      expect(route.request().headers()['x-loopper-local-ui']).toBe('1'); saves.push(route.request().postDataJSON())
      if (fail) { fail = false; return route.abort('failed') }
      return route.fulfill({ json: { id: 'saved-flow', revision: 1, version: 0, layoutVersion: 0, state: 'ACTIVE' } })
    }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width, height: 1000 }); await page.goto('/requirements/req')
  await page.getByRole('button', { name: '人工检查', exact: true }).click()
  await page.getByRole('button', { name: '另存为流程模板', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '另存为流程模板' })
  await expect(dialog.getByLabel('保存结构')).toHaveValue('CURRENT'); await expect(dialog.locator('.workflow-node')).toHaveCount(2)
  await dialog.getByRole('button', { name: '适应画布', exact: true }).click(); expect(saves).toHaveLength(0)
  await dialog.getByLabel('保存结构').selectOption('INITIAL'); await expect(dialog.locator('.workflow-node')).toHaveCount(1)
  await dialog.getByRole('button', { name: '适应画布', exact: true }).click()
  await expect(dialog).toContainText('之后新增或修改的节点不纳入本次模板')
  await dialog.getByLabel('保存结构').selectOption('CURRENT'); await expect(dialog.locator('.workflow-node')).toHaveCount(2)
  await dialog.getByRole('button', { name: '适应画布', exact: true }).click()
  await dialog.locator('.workflow-node').first().click(); await expect(dialog.getByLabel('节点设置')).toBeVisible()
  await expect(dialog.getByLabel('节点设置').getByLabel('名称', { exact: true })).toBeDisabled()
  expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-save-template-${width}.png`, fullPage: true })
  await dialog.getByRole('button', { name: '确认保存为新流程', exact: true }).click()
  await expect(dialog.getByRole('button', { name: '返回任务画布', exact: true })).toBeDisabled()
  await dialog.getByRole('button', { name: '重试原保存操作', exact: true }).click()
  await expect(dialog.getByRole('link', { name: '打开新流程' })).toHaveAttribute('href', '/workflows/saved-flow')
  expect(saves).toHaveLength(2); expect(saves[0]).toEqual(saves[1]); expect(selections.map(value => value.mode)).toEqual(['CURRENT', 'INITIAL', 'CURRENT'])
  await dialog.getByRole('button', { name: '返回任务画布', exact: true }).click()
  await expect(page.locator('.workflow-save-state')).toContainText('有未保存修改'); await expect(page.locator('.workflow-node')).toHaveCount(2)
})
