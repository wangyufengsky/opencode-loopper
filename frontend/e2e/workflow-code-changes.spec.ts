import { selectWorkflowNode } from './fixtures/workflowNavigation'
import { expect, test } from '@playwright/test'
import { execution, requirement, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import { newNode } from '../src/components/workflow/graph'
import type { WorkflowGraph } from '../src/types/workflow'
for (const width of [1600, 390]) test(`固定代码的累计改动和删除文件 ${width}`, async ({ page }) => {
  const node = { ...newNode('free.write'), id: 'write', title: '实现需求', task: '完成用户要求的改动。', outputs: [{ name: 'code', title: '代码成果', kind: 'CODE' as const, required: true }] }
  const graph: WorkflowGraph = { schemaVersion: 1, nodes: [node], edges: [], inputs: [] }
  const req = requirement({ id: 'code-changes', title: '查看需求代码成果', state: 'COMPLETED', graph, revision: 1, headRevision: 1 })
  const snapshot = execution(req.state); snapshot.execution.id = req.id; snapshot.execution.revision = 1
  snapshot.execution.nodes = [{ id: 'run-write', nodeKey: 'write', state: 'SUCCEEDED', attemptCount: 1, latestAttemptId: 'write-attempt', version: 1, outcome: null }]
  snapshot.control = { ...snapshot.control, id: req.id, revision: 1, configured: true, mode: 'CONTINUOUS', state: 'COMPLETED', reasonCode: null }
  const run = attempt({ id: 'write-attempt', state: 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true, modelState: 'SUCCEEDED', roleName: '开发工程师' })
  let changes = 0, files = 0, retried = false
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/code-changes') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/candidates')) return route.fulfill({ json: { items: [], nextCursor: null } })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/write-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/outputs/code/changes')) {
      changes++
      if (url.searchParams.get('cursor') === 'next') {
        if (!retried) { retried = true; return route.fulfill({ status: 503, json: { detail: '读取暂时失败，请重试。' } }) }
        return route.fulfill({ json: { items: [{ path: 'scripts/run.sh', kind: 'MODIFY', beforeBlob: 'private-before', afterBlob: 'private-before' }], nextCursor: null } })
      }
      return route.fulfill({ json: { items: [
        { path: 'src/main/java/example/requirements/workflow/流程代码.java', kind: 'ADD', beforeBlob: null, afterBlob: 'private-after' },
        { path: 'src/legacy/obsolete.txt', kind: 'DELETE', beforeBlob: 'private-before', afterBlob: null },
      ], nextCursor: 'next' } })
    }
    if (path.endsWith('/outputs/code/files')) { files++; return route.fulfill({ json: { items: [{ path: 'scripts/run.sh', sizeBytes: 120, sha256: 'fixed-hash', mode: '100755' }], nextCursor: null } }) }
    if (path.endsWith('/result')) return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'private-delivery', delivery: { summary: '完成流程功能，代码成果已保存。', outcome: null, outputs: { code: { kind: 'CODE', content: { version: 1, snapshotId: 'private-code', sha256: 'private-hash' } } } } } })
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width, height: width === 390 ? 844 : 1000 }); await page.goto('/requirements/code-changes')
  await selectWorkflowNode(page, node.title)
  await page.getByRole('button', { name: '交付物', exact: true }).click(); expect(changes).toBe(0); expect(files).toBe(0)
  await page.getByRole('button', { name: '查看改动文件', exact: true }).click()
  const list = page.getByRole('region', { name: '代码改动文件' })
  await expect(list).toContainText('相对原始基线的累计改动'); await expect(list).toContainText('继承的上游代码')
  await expect(list.locator('li').filter({ hasText: 'obsolete.txt' })).toContainText('删除')
  await expect(list.locator('li').filter({ hasText: 'obsolete.txt' }).locator('a')).toHaveCount(0)
  await expect(list.getByRole('link')).toHaveAttribute('href', /outputs\/code\/file\?path=/)
  await page.getByRole('button', { name: '更多改动文件', exact: true }).click()
  await list.getByRole('button', { name: '重试读取改动', exact: true }).click()
  await expect(list.locator('li')).toHaveCount(3); await expect(list).toContainText('修改'); await expect(list).not.toContainText('private-')
  expect(changes).toBe(3); expect(files).toBe(0)
  await page.getByRole('button', { name: '查看固定版本文件', exact: true }).click(); await expect(page.locator('.workflow-file-list')).toContainText('120 字节')
  expect(files).toBe(1); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-code-changes-${width}.png`, fullPage: true })
})
