import { selectWorkflowNode } from './fixtures/workflowNavigation'
import { expect, test } from '@playwright/test'
import { requirement, execution, attempt } from '../src/components/workflow/workflowRunTestFixtures'

test('固定正文按需分页、断线重读、历史内联输入与窄屏显示', async ({ page }) => {
  const req = requirement({ state: 'COMPLETED' }), snapshot = execution('COMPLETED'), node = req.graph.nodes[0]!
  node.inputs = [{ name: 'design', source: 'NODE', sourceId: 'prior-design', output: 'result', kind: 'TEXT', required: true }]
  const run = attempt({ state: 'SUCCEEDED', stopConfirmed: true }), old = attempt({ id: 'old', ordinal: 1, state: 'FAILED', stopConfirmed: true })
  run.ordinal = 2
  snapshot.execution.nodes = [{ id: 'consumer', nodeKey: node.id, state: 'SUCCEEDED', attemptCount: 2, latestAttemptId: run.id, version: 4, outcome: null }]
  snapshot.control = { ...snapshot.control, configured: true, state: 'DONE', reasonCode: null }
  const body = '固定设计正文。'.repeat(1800) + '\n\n完整正文结尾。', offsets: number[] = []
  let failMore = true, metadataReads = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === `/api/workflows/requirements/${req.id}`) return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run, old], nextCursor: null } })
    if (path.endsWith(`/attempts/${run.id}`)) return route.fulfill({ json: run })
    if (path.endsWith('/attempts/old')) return route.fulfill({ json: old })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/inputs')) {
      metadataReads++; const historical = path.includes('/attempts/old/')
      return route.fulfill({ json: { version: historical ? 1 : 2, requirementId: req.id, planRevision: 1, nodeId: node.id, objective: req.objective,
        values: [{ name: 'design', kind: 'TEXT', source: 'NODE', sourceId: 'private-producer', outputName: 'result', attemptId: 'private-parent', sha256: 'private-hash',
          content: historical ? '历史尝试原样保留的内联设计。' : null, ...(historical ? {} : { reference: { version: 1, contentSha256: 'private-body-hash', sizeBytes: body.length * 3 } }) }] } })
    }
    if (path.endsWith('/inputs/design/content')) {
      const offset = Number(url.searchParams.get('offset')); offsets.push(offset); expect(url.searchParams.get('limit')).toBe('12000')
      if (offset > 0 && failMore) { failMore = false; return route.fulfill({ status: 503, json: { detail: '正文暂时无法读取，请重试。' } }) }
      const end = Math.min(offset + 12000, body.length)
      return route.fulfill({ json: { name: 'design', kind: 'TEXT', sha256: 'private-hash', offset, text: body.slice(offset, end), nextOffset: end < body.length ? end : null, totalLength: body.length } })
    }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto(`/requirements/${req.id}`)
  await selectWorkflowNode(page, node.title)
  expect(metadataReads).toBe(0); expect(offsets).toEqual([])
  await page.getByRole('button', { name: '固定输入', exact: true }).click(); await expect(page.getByRole('button', { name: '查看固定版本正文', exact: true })).toBeVisible()
  expect(offsets).toEqual([]); await page.getByRole('button', { name: '查看固定版本正文', exact: true }).click()
  const panel = page.locator('.workflow-input-content'); await expect(panel).toContainText('正文尚未读完'); expect(offsets).toEqual([0])
  await expect(panel.locator('pre')).toContainText('固定设计正文。')
  await page.screenshot({ path: 'test-results/workflow-input-reference-partial.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: 'test-results/workflow-input-reference-mobile.png', fullPage: true })
  await page.getByRole('button', { name: '继续读取正文', exact: true }).click(); await expect(panel.getByRole('alert')).toContainText('正文暂时无法读取')
  await page.getByRole('button', { name: '重试读取', exact: true }).click(); await expect(panel).toContainText('完整正文结尾。'); expect(offsets).toEqual([0, 12000, 12000])
  await expect(panel.getByText('正文尚未读完', { exact: false })).toHaveCount(0)
  await page.getByLabel('执行尝试', { exact: true }).selectOption('old'); await page.getByRole('button', { name: '固定输入', exact: true }).click()
  await expect(page.getByRole('complementary', { name: '节点执行详情' })).toContainText('历史尝试原样保留的内联设计。')
  await expect(page.getByRole('button', { name: '查看固定版本正文', exact: true })).toHaveCount(0); expect(offsets).toHaveLength(3)
  await expect(page.getByRole('complementary', { name: '节点执行详情' })).not.toContainText('private-')
})
