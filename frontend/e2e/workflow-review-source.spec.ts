import { expandWorkflowNodeSettings, selectWorkflowNode } from './fixtures/workflowNavigation'
import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { requirement, execution, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowNode } from '../src/types/workflow'

const preset = (JSON.parse(readFileSync('../src/main/resources/workflows/node-presets.json', 'utf8')) as { presets: { id: string; node: WorkflowNode }[] }).presets.find(value => value.id === 'review.snapshot')!.node
test('开始前显式选择分支，启动请求保持完整来源身份', async ({ page }) => {
  const node: WorkflowNode = { ...preset, id: 'source', inputs: ['branch', 'startDate', 'endDate'].map(name => ({ name, source: 'REQUIREMENT', sourceId: name, output: null, kind: 'TEXT', required: true })) }
  const req = requirement({ id: 'choose-branch', state: 'PENDING_START', title: '固定分支分析', graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [{ name: 'branch', title: '代码分支', kind: 'TEXT', required: true }, { name: 'startDate', title: '开始日期', kind: 'TEXT', required: true }, { name: 'endDate', title: '结束日期', kind: 'TEXT', required: true }] } })
  const snapshot = execution('PENDING_START'); snapshot.execution.id = req.id; snapshot.control.id = req.id
  const main = { id: 'local:refs/heads/main', label: 'main', ref: 'refs/heads/main', remote: null }, review = { id: 'remote:origin:refs/heads/review', label: 'review', ref: 'refs/heads/review', remote: 'origin' }
  let branchReads = 0, input: unknown = null
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/choose-branch') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/branches')) { branchReads++; return route.fulfill({ json: { page: { items: url.searchParams.has('cursor') ? [review] : [main], nextCursor: url.searchParams.has('cursor') ? null : 'next' }, defaultBranch: main, defaultBranchId: main.id, remoteAvailable: true } }) }
    if (path.endsWith('/start')) { input = route.request().postDataJSON(); return route.fulfill({ json: snapshot.control }) }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/choose-branch')
  await page.getByRole('button', { name: '需求与资料', exact: true }).click()
  const inspector = page.getByRole('complementary', { name: '需求与资料' }); await expect(inspector).toContainText('尚未选择分支'); expect(branchReads).toBe(0)
  await page.getByRole('button', { name: '选择代码分支', exact: true }).click(); await expect(page.getByRole('combobox', { name: '代码分支', exact: true })).toHaveValue('')
  await page.getByRole('button', { name: '更多分支', exact: true }).click(); await expect(page.getByRole('combobox', { name: '代码分支', exact: true }).locator('option')).toHaveCount(3)
  await page.getByRole('combobox', { name: '代码分支', exact: true }).selectOption(review.id); await expect(inspector).toContainText('远程 origin · review'); expect(input).toBeNull()
  await page.getByLabel('开始日期', { exact: true }).fill('2026-09-01'); await page.getByLabel('结束日期', { exact: true }).fill('2026-09-11')
  await page.screenshot({ path: 'test-results/workflow-review-source-selection-desktop.png', fullPage: true })
  await page.getByRole('button', { name: '连续执行', exact: true }).click(); await expect.poll(() => input).toMatchObject({ inputs: { branch: { kind: 'TEXT', content: review.id }, startDate: { kind: 'TEXT', content: '2026-09-01' }, endDate: { kind: 'TEXT', content: '2026-09-11' } } })
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: 'test-results/workflow-review-source-selection-mobile.png', fullPage: true })
})
for (const failed of [false, true]) test(`分支采集${failed ? '未完成可恢复' : '固定文件可读取'}且窄屏可读`, async ({ page }) => {
  const node: WorkflowNode = { ...preset, id: 'source', inputs: ['branch', 'startDate', 'endDate'].map(name => ({ name, source: 'REQUIREMENT', sourceId: name, output: null, kind: 'TEXT', required: true })) }
  const req = requirement({ id: 'source-fixture', title: '项目源码分析', state: failed ? 'STALLED' : 'COMPLETED', graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [{ name: 'branch', title: '代码分支', kind: 'TEXT', required: true }, { name: 'startDate', title: '开始日期', kind: 'TEXT', required: true }, { name: 'endDate', title: '结束日期', kind: 'TEXT', required: true }] } })
  const snapshot = execution(req.state); snapshot.execution.id = req.id
  snapshot.execution.nodes = [{ id: 'source-run', nodeKey: node.id, state: failed ? 'FAILED' : 'SUCCEEDED', attemptCount: 1, latestAttemptId: 'source-attempt', version: 2, outcome: null }]
  snapshot.control = { ...snapshot.control, id: req.id, configured: true, state: failed ? 'STALLED' : 'DONE', reasonCode: failed ? 'WORKFLOW_RETRY_EXHAUSTED' : null }
  const run = attempt({ id: 'source-attempt', state: failed ? 'FAILED' : 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true, commandState: failed ? 'FAILED' : 'SUCCEEDED', commandVersion: 2 })
  let fileReads = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/source-fixture') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/source-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/files')) { fileReads++; return route.fulfill({ json: { items: [
      { path: 'units/000001.json', sizeBytes: 2300, sha256: 'a'.repeat(64), mode: null, target: null, exclusion: null },
    ], nextCursor: null } }) }
    if (path.endsWith('/command/evidence')) return route.fulfill({ json: { attemptId: run.id, request: { id: run.id, directory: '/private/capture', argv: ['java', '-jar', '/private/helper.jar'], timeoutSeconds: 180 }, requestSha256: 'private-request', resultSha256: 'private-result', registration: null, result: { requestSha256: 'private-request', worker: { pid: 123, startedAt: '2026-09-29T00:00:00Z' }, exitCode: failed ? 1 : 0, launched: true, timedOut: false, cancelled: false, stopConfirmed: true, outputTruncated: false, output: '', error: '', children: [] } } })
    if (path.endsWith('/result')) return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'fixed-delivery', delivery: { summary: failed ? '目标源码未能完整读取。' : '源码已冻结。', outcome: null, outputs: {
      ...(failed ? {} : { source: { kind: 'DOCUMENT', content: { version: 1, type: 'REVIEW_SOURCE', snapshotId: 'private-source-id', sha256: 'private-hash' } } }),
      report: { kind: 'JSON', content: { version: 1, type: 'REVIEW_SOURCE', complete: !failed, branchId: 'remote:origin:refs/heads/main', mode: 'DATE_INCREMENTAL', sourceSha: 'a'.repeat(40), baselineSha: 'b'.repeat(40), targetSha: 'c'.repeat(40), projectPrefix: 'server', unitCount: 3, excludedCount: 1, noChanges: false, nonMonotonic: false, capturedAt: '2026-09-29T00:00:00Z', startDate: '2026-09-01', endDate: '2026-09-11', timezone: 'Asia/Shanghai', code: failed ? 'WORKFLOW_REVIEW_CAPTURE_FAILED' : null,
        exclusions: [{ path: failed ? 'src/Legacy.java' : '.env', reason: failed ? '无法解析为 UTF-8 文本' : '受保护文件不提供读取' }] } },
    } } } })
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/source-fixture')
  await selectWorkflowNode(page, '固定日期增量代码'); await page.getByRole('button', { name: '交付物', exact: true }).click()
  const report = page.locator('.workflow-review-source-report'); await expect(report).toContainText(failed ? '采集未完成' : '审查资料已固定')
  expect(fileReads).toBe(0); await expect(page.getByRole('complementary', { name: '节点执行详情' })).not.toContainText('private-source-id')
  if (failed) { await expect(report).toContainText('已定位的提交和审查范围保持不变'); await expect(report).not.toContainText('WORKFLOW_REVIEW_CAPTURE_FAILED') }
  else { await page.getByRole('button', { name: '查看固定版本文件', exact: true }).click(); await expect(page.locator('.workflow-file-list a')).toHaveCount(1); await expect(page.locator('.workflow-file-list')).toContainText('units/000001.json'); expect(fileReads).toBe(1) }
  await expect(report).toContainText('远程 origin · main'); if (!failed) await expect(report).toContainText('尚未生成审查结论')
  await page.screenshot({ path: `test-results/workflow-review-source-${failed ? 'incomplete' : 'ready'}-desktop.png`, fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); await report.scrollIntoViewIfNeeded()
  await expect(report).toContainText('2026-09-01 至 2026-09-11'); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-review-source-${failed ? 'incomplete' : 'ready'}-report-mobile.png`, fullPage: false })
  await page.getByRole('button', { name: '执行记录', exact: true }).click(); await expect(page.locator('.workflow-command-evidence')).toContainText('版本审查采集'); await expect(page.locator('.workflow-command-evidence .cm-editor')).toHaveCount(0)
  await page.getByText('查看采集命令', { exact: true }).click(); await expect(page.locator('.workflow-command-evidence .cm-editor')).toContainText('/private/helper.jar')
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-review-source-${failed ? 'incomplete' : 'ready'}-mobile.png`, fullPage: true })
})

test('切换版本审查范围同步公共日期并保存配置', async ({ page }) => {
  const node: WorkflowNode = { ...preset, id: 'source', title: '版本审查资料', parameters: { ...preset.parameters, reviewMode: 'FULL' },
    inputs: [{ name: 'branch', source: 'REQUIREMENT', sourceId: 'branch', output: null, kind: 'TEXT', required: true }] }
  let saved = { id: 'edit-source', title: '版本审查流程', description: '', builtin: false, archived: false, revision: 1, headRevision: 1, version: 0, layoutVersion: 0,
    graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [{ name: 'branch', title: '代码分支', kind: 'TEXT', required: true }] },
    layout: { positions: { source: { x: 50, y: 50 } }, x: 0, y: 0, zoom: 1 }, diagnostics: [], sourceTemplateId: null, sourceRevision: null }
  const bodies: Array<{ graph: typeof saved.graph }> = []
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/templates/validate') return route.fulfill({ json: [] })
    if (path === '/api/workflows/templates/edit-source') {
      if (route.request().method() === 'PUT') {
        const body = route.request().postDataJSON(); bodies.push(body); saved = { ...saved, ...body, revision: saved.revision + 1, headRevision: saved.headRevision + 1, version: saved.version + 1 }
        return route.fulfill({ json: { id: saved.id, revision: saved.revision, version: saved.version, layoutVersion: saved.layoutVersion, state: 'ACTIVE' } })
      }
      return route.fulfill({ json: saved })
    }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/workflows/edit-source')
  await page.locator('.workflow-node').filter({ hasText: '版本审查资料' }).click(); await expandWorkflowNodeSettings(page)
  await page.getByRole('combobox', { name: '版本审查范围', exact: true }).selectOption('DATE_INCREMENTAL')
  await page.getByRole('button', { name: '保存流程', exact: true }).click()
  await expect.poll(() => bodies.length).toBe(1); expect(bodies[0]!.graph.inputs.map(i => i.name)).toEqual(['branch', 'startDate', 'endDate'])
  await expect(page.getByRole('button', { name: '保存流程', exact: true })).toBeEnabled()
  await page.getByRole('combobox', { name: '版本审查范围', exact: true }).selectOption('FULL')
  await page.getByRole('button', { name: '保存流程', exact: true }).click()
  await expect.poll(() => bodies.length).toBe(2); expect(bodies[1]!.graph.inputs.map(i => i.name)).toEqual(['branch'])
  expect(bodies[1]!.graph.nodes[0]!.parameters.reviewMode).toBe('FULL')
  await page.setViewportSize({ width: 390, height: 844 }); await page.getByRole('combobox', { name: '版本审查范围', exact: true }).scrollIntoViewIfNeeded()
  expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: 'test-results/workflow-review-source-mode-mobile.png', fullPage: false })
})
