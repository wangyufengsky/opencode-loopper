import { expect, test, type Page } from '@playwright/test'
import { requirement, execution, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import { preset, template, summary, verificationPreset } from '../src/components/workflow/workflowTestFixtures'
import type { WorkflowDelivery, WorkflowGraph, WorkflowLayout, WorkflowStart } from '../src/types/workflow'

// Browser interaction proof with explicit HTTP fixtures; no provider or SQLite claims.
async function fixture(page: Page, checkpoint = false) {
  const req = requirement(), snapshot = execution(), node = req.graph.nodes[0]!, run = attempt(); let delivery: WorkflowDelivery | null = null, starts: WorkflowStart[] = [], confirms = 0
  if (checkpoint) { req.state = 'PAUSED'; snapshot.execution.state = 'PAUSED'; snapshot.control = { ...snapshot.control, configured: true, mode: 'CONTINUOUS', state: 'WAITING', reasonCode: 'WORKFLOW_CHECKPOINT', checkpoints: [{ requirementId: req.id, nodeKey: node.id, attemptId: run.id, createdAt: '', acknowledgedAt: null }] } }
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname, method = route.request().method()
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/template-tasks/projects') return route.fulfill({ json: { items: [{ id: 'project', name: '示例项目' }], nextCursor: null } })
    if (path === '/api/workflows/templates') return route.fulfill({ json: { items: [summary()], nextCursor: null } })
    if (path === '/api/workflows/templates/builtin.workflow.development') return route.fulfill({ json: template({ id: 'builtin.workflow.development', title: '默认开发流程' }) })
    if (path === '/api/workflows/templates/example') return route.fulfill({ json: template() })
    if (path === '/api/workflows/node-presets') return route.fulfill({ json: { items: [preset()], nextCursor: null } })
    if (path === '/api/workflows/node-presets/analysis.read/versions/1') return route.fulfill({ json: preset() })
    if (path.endsWith('/plan') && method === 'PUT') {
      req.graph = (route.request().postDataJSON() as { graph: WorkflowGraph }).graph; req.revision++; req.headRevision = req.revision; req.version++
      snapshot.execution.revision = req.revision; snapshot.execution.version = req.version; snapshot.control.revision = req.revision; snapshot.control.version = req.version
      return route.fulfill({ json: { id: req.id, state: req.state, revision: req.revision, version: req.version, layoutVersion: req.layoutVersion } })
    }
    if (path === '/api/workflows/requirements' && method === 'POST') { expect(route.request().headers()['x-loopper-local-ui']).toBe('1'); expect(route.request().postDataJSON()).toMatchObject({ templateId: 'example', templateRevision: 2 }); return route.fulfill({ json: { id: req.id, revision: req.revision, version: req.version, state: 'PLANNING', layoutVersion: req.layoutVersion } }) }
    if (path === `/api/workflows/requirements/${req.id}`) return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: snapshot.execution.state, version: snapshot.execution.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/confirm')) { confirms++; req.state = 'PENDING_START'; snapshot.execution.state = req.state; req.version++; snapshot.execution.version = req.version; snapshot.control.version = req.version; return route.fulfill({ json: { id: req.id, state: req.state, revision: req.revision, version: req.version, layoutVersion: req.layoutVersion } }) }
    if (path.endsWith('/control/start')) {
      const command = route.request().postDataJSON() as WorkflowStart; starts.push(command); expect(route.request().headers()['x-loopper-local-ui']).toBe('1')
      if (checkpoint) { expect(command.checkpointAttempts).toEqual([run.id]); snapshot.control.checkpoints = []; snapshot.control.state = 'DONE'; snapshot.control.reasonCode = null; req.state = 'COMPLETED'; snapshot.execution.state = req.state }
      else { expect(command.targetKey).toBe(node.id); snapshot.execution.nodes = [{ id: 'node', nodeKey: node.id, state: 'WAITING_INPUT', attemptCount: 1, latestAttemptId: run.id, version: 1, outcome: null }]; snapshot.control = { ...snapshot.control, mode: command.mode, configured: true, state: 'WAITING', targetKey: node.id, reasonCode: 'WORKFLOW_HUMAN_INPUT' }; req.state = 'PAUSED'; snapshot.execution.state = req.state }
      return route.fulfill({ json: snapshot.control })
    }
    if (path.endsWith('/human/complete')) { delivery = route.request().postDataJSON().delivery; run.state = 'SUCCEEDED'; run.deliveryAccepted = true; snapshot.execution.nodes[0]!.state = 'SUCCEEDED'; snapshot.control.state = 'DONE'; snapshot.control.reasonCode = null; req.state = 'COMPLETED'; snapshot.execution.state = req.state; return route.fulfill({ json: { state: 'SUCCEEDED' } }) }
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith(`/attempts/${run.id}`)) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/result')) return route.fulfill({ json: { attemptId: run.id, state: 'SUCCEEDED', sha256: 'hash', delivery } })
    if (path.endsWith('/layout')) { req.layout = (route.request().postDataJSON() as { layout: WorkflowLayout }).layout; req.layoutVersion++; return route.fulfill({ json: { id: req.id, state: req.state, revision: req.revision, version: req.version, layoutVersion: req.layoutVersion } }) }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  return { starts, confirms: () => confirms, delivery: () => delivery }
}

test('创建需求、确认计划、单步人工执行、读取交付物与保存布局', async ({ page }) => {
  const data = await fixture(page); await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/new')
  await page.getByLabel('需求名称', { exact: true }).fill('交付需求'); await page.getByLabel('需求说明', { exact: true }).fill('核对并交付目标成果')
  await page.getByRole('button', { name: '选择项目', exact: true }).click(); await page.getByRole('button', { name: '示例项目', exact: true }).click()
  await page.getByRole('button', { name: '选择流程', exact: true }).click(); await page.getByRole('button', { name: /交付流程.*版本 2/ }).click()
  await page.getByRole('button', { name: '进入规划画布', exact: true }).click(); await expect(page).toHaveURL('/requirements/req'); await expect(page.locator('.workflow-node')).toHaveCount(1)
  await page.getByRole('button', { name: '确认计划', exact: true }).click(); await expect(page.getByRole('button', { name: '连续执行', exact: true })).toBeEnabled(); expect(data.confirms()).toBe(1); expect(data.starts).toHaveLength(0)
  await page.locator('.workflow-node').click(); await page.getByRole('button', { name: '执行所选节点', exact: true }).click(); await expect(page.getByRole('heading', { name: '填写人工结果', exact: true })).toBeVisible(); expect(data.starts[0]!.mode).toBe('SINGLE')
  await page.getByLabel('结果说明', { exact: true }).fill('已确认所有交付内容'); await page.getByLabel('检查结果', { exact: true }).fill('人工核验通过，结果已记录。')
  await page.getByRole('button', { name: '提交结果并完成节点', exact: true }).click(); await expect(page.locator('.workflow-node')).toContainText('已完成')
  await page.getByRole('button', { name: '交付物', exact: true }).click(); await expect(page.getByText('人工核验通过，结果已记录。', { exact: true })).toBeVisible(); expect(data.delivery()?.outputs.result?.content).toBe('人工核验通过，结果已记录。')
  await page.locator('.workflow-node').focus(); await page.keyboard.press('ArrowRight'); await page.getByRole('button', { name: '保存布局', exact: true }).click(); await expect(page.getByText('计划已保存。', { exact: true })).toBeVisible()
  for (const skin of ['spdb', 'tech-blue', 'github-white']) { await page.evaluate(value => { document.documentElement.dataset.skin = value }, skin); await page.screenshot({ path: `test-results/workflow-requirement-${skin}.png`, fullPage: true }) }
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true); await page.screenshot({ path: 'test-results/workflow-requirement-mobile.png', fullPage: true })
})

test('重新打开检查点仍需明确确认交付物后才可继续', async ({ page }) => {
  const data = await fixture(page, true); await page.goto('/requirements/req'); await expect(page.getByRole('button', { name: '连续执行', exact: true })).toBeDisabled(); await page.reload(); await expect(page.getByRole('checkbox')).not.toBeChecked()
  await page.getByRole('checkbox').check(); await page.getByRole('button', { name: '连续执行', exact: true }).click(); await expect(page.locator('.workflow-editor-header')).toContainText('已完成'); expect(data.starts).toHaveLength(1)
})

test('需求规划从预设添加任务、保存并重开，不自动确认或执行', async ({ page }) => {
  const data = await fixture(page); await page.goto('/requirements/req')
  await page.getByRole('button', { name: '预设工作模块', exact: true }).click(); await page.locator('.workflow-preset-list button').filter({ hasText: '资料分析' }).click()
  await page.getByLabel('参考资料来源', { exact: true }).selectOption({ label: '人工验收 · 检查结果' }); await page.getByRole('button', { name: '添加到画布', exact: true }).click()
  await expect(page.locator('.workflow-node')).toHaveCount(2); await expect(page.locator('.workflow-wire')).toHaveCount(1)
  await page.getByRole('button', { name: '保存计划', exact: true }).click(); await expect(page.getByText('计划已保存。', { exact: true })).toBeVisible()
  await page.reload(); await expect(page.locator('.workflow-node')).toHaveCount(2); expect(data.starts).toHaveLength(0); expect(data.confirms()).toBe(0)
})

test('失败的程序检查保留报告和重试入口，不显示仍在收尾或模型操作', async ({ page }) => {
  const data = await fixture(page), req = requirement({ state: 'STALLED' }), state = execution('STALLED')
  const node = { ...verificationPreset().node, id: 'review' }, run = attempt({ state: 'FAILED', deliveryAccepted: true, stopConfirmed: true })
  req.graph.nodes = [node]; state.execution.nodes = [{ id: 'node', nodeKey: 'review', state: 'FAILED', attemptCount: 1, latestAttemptId: run.id, version: 1, outcome: 'FAIL' }]
  state.control = { ...state.control, configured: true, mode: 'CONTINUOUS', state: 'STALLED', reasonCode: 'WORKFLOW_RETRY_EXHAUSTED' }
  await page.route(/\/api\/workflows\/requirements\/req(?:\/|$)/, async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/req')) return route.fulfill({ json: req })
    if (path.endsWith('/execution')) return route.fulfill({ json: state })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/run')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/result')) return route.fulfill({ json: { attemptId: run.id, state: 'FAILED', sha256: 'hash', delivery: {
      summary: '交付物检查存在未通过项。', outcome: 'FAIL', outputs: { report: { kind: 'JSON', content: { version: 1, checks: [
        { title: '版本内容正确', path: 'version.txt', state: 'FAIL' }, { title: '旧配置已移除', path: 'old.json', state: 'PASS' },
      ] } } },
    } } })
    return route.fallback()
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/req')
  await page.locator('.workflow-node').click(); await page.getByRole('button', { name: '交付物', exact: true }).click()
  const panel = page.getByRole('complementary', { name: '节点执行详情' })
  await expect(panel.getByText('版本内容正确', { exact: true })).toBeVisible(); await expect(panel.getByText('未通过', { exact: true })).toBeVisible()
  await expect(panel.getByText('通过', { exact: true })).toBeVisible(); await expect(panel).not.toContainText('正在等待执行与资源收尾')
  await expect(panel.getByRole('button', { name: '模型日志', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '重试所选节点', exact: true })).toBeEnabled(); expect(data.starts).toHaveLength(0)
  await page.screenshot({ path: 'test-results/workflow-file-report-desktop.png', fullPage: true })
})
