import { workflowTool } from './fixtures/workflowNavigation'
import { expect, test } from '@playwright/test'
import { requirement, execution } from '../src/components/workflow/workflowRunTestFixtures'
import { newNode } from '../src/components/workflow/graph'
import type { WorkflowPublicationSource, WorkflowPublicationPreview, WorkflowWritebackView, WorkflowWritebackPreview } from '../src/types/domain'
test('普通目录成果先检查冲突，明确确认后支持未知回执、阻断恢复和刷新完成', async ({ page }) => {
  const node = { ...newNode('free.write'), id: 'work', title: '开发与修正' }
  const req = requirement({ id: 'publication-preview', title: '订单流程开发', state: 'COMPLETED', revision: 2, graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } })
  const snapshot = execution(req.state); snapshot.execution.id = req.id; snapshot.execution.revision = 2; snapshot.execution.nodes = [{ id: 'private-node', nodeKey: 'work', state: 'FAILED', attemptCount: 2, latestAttemptId: 'private-attempt', version: 4, outcome: null }]; snapshot.control = { ...snapshot.control, id: req.id, configured: true, state: 'DONE' }
  const source: WorkflowPublicationSource = { nodeKey: 'work', nodeTitle: '开发与修正', attemptId: 'private-attempt', ordinal: 2, attemptState: 'FAILED', outputName: 'code', outputTitle: '代码成果', createdAt: '2026-09-29T08:00:00Z', changedFiles: 3, totalFiles: 24 }
  const preview: WorkflowPublicationPreview = { requirementId: req.id, requirementVersion: req.version, planRevision: 2, requirementState: 'COMPLETED', source, workspaceKind: 'DIRECT', sourceBranch: null, reference: { version: 1, snapshotId: 'private-snapshot', sha256: 'a'.repeat(64) }, deliverySha256: 'b'.repeat(64), baseTree: 'private-base', resultTree: 'private-result', added: 1, modified: 1, deleted: 1, totalBytes: 15360, sha256: 'c'.repeat(64) }
  let lists = 0, previews = 0, changes = 0, writes = 0, checks = 0, confirms = 0, retries = 0
  let saved: WorkflowWritebackView | null = null, original: unknown
  const directoryPreview: WorkflowWritebackPreview = { requirementId: req.id, requirementVersion: req.version, revision: 2, sourceSha256: preview.sha256, directory: '/项目/订单处理应用', currentSha256: 'current', targetSha256: 'target', sha256: 'check-2', added: 1, modified: 1, deleted: 1, preservedChanges: 2, conflictCount: 0, conflicts: [] }
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (route.request().method() !== 'GET') writes++
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === `/api/workflows/requirements/${req.id}`) return route.fulfill({ json: req })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/publication/writeback/preview')) {
      checks++; expect(route.request().method()).toBe('POST'); expect(route.request().headers()['x-loopper-local-ui']).toBe('1')
      expect(route.request().postDataJSON()).toEqual({ revision: 2, node: 'work', attempt: source.attemptId, output: 'code', sourceSha256: preview.sha256 })
      return route.fulfill({ json: { requirementId: req.id, requirementVersion: req.version, revision: 2, sourceSha256: preview.sha256, directory: '/项目/订单处理应用', currentSha256: 'current', targetSha256: checks === 1 ? null : 'target', sha256: 'check-' + checks, added: 1, modified: 1, deleted: 1, preservedChanges: 2, conflictCount: checks === 1 ? 1 : 0, conflicts: checks === 1 ? ['src/订单处理/OrderService.java'] : [] } })
    }
    if (path.endsWith('/publication/writeback/confirm')) {
      confirms++; expect(route.request().headers()['x-loopper-local-ui']).toBe('1')
      const body = route.request().postDataJSON(); expect(body).toMatchObject({ expectedVersion: req.version, selection: { revision: 2, node: 'work', attempt: source.attemptId, output: 'code', sourceSha256: preview.sha256 }, previewSha256: directoryPreview.sha256 })
      if (confirms === 1) { original = body; return route.abort('failed') }
      expect(body).toEqual(original); saved = { requirementId: req.id, state: 'BLOCKED', version: 2, queueState: 'ADMITTED', queuePosition: 0, preview: directoryPreview, nodeTitle: source.nodeTitle, outputTitle: source.outputTitle, attemptState: 'FAILED', createdAt: 'now', appliedAt: null, blocker: 'WORKFLOW_WRITEBACK_PREPARATION_CHANGED' }
      return route.fulfill({ json: saved })
    }
    if (path.endsWith('/publication/writeback/retry')) {
      retries++; expect(route.request().headers()['x-loopper-local-ui']).toBe('1'); expect(route.request().postDataJSON()).toEqual({ expectedVersion: 2 })
      saved = { ...saved!, state: 'APPLYING', version: 3, blocker: null }; return route.fulfill({ json: saved })
    }
    if (path.endsWith('/publication/writeback')) return route.fulfill({ json: saved })
    if (path.endsWith('/publication')) return route.fulfill({ json: null })
    if (path.endsWith('/publication/sources')) { lists++; expect(url.searchParams.get('revision')).toBe('2'); return route.fulfill({ json: { items: [source], nextCursor: null } }) }
    if (path.endsWith('/publication/preview')) { previews++; expect(url.searchParams.get('attempt')).toBe(source.attemptId); return route.fulfill({ json: preview }) }
    if (path.endsWith('/changes')) { changes++; expect(path).toContain('/nodes/work/attempts/private-attempt/outputs/code/'); return route.fulfill({ json: { items: [{ path: 'src/订单处理/OrderService.java', kind: 'MODIFY', beforeBlob: 'before', afterBlob: 'after' }, { path: 'src/test/OrderServiceTest.java', kind: 'ADD', beforeBlob: null, afterBlob: 'after' }, { path: 'docs/旧版订单处理说明.md', kind: 'DELETE', beforeBlob: 'before', afterBlob: null }], nextCursor: null } }) }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto(`/requirements/${req.id}`)
  const panel = page.getByRole('region', { name: '需求代码成果' }); expect(lists).toBe(0)
  await workflowTool(page, '查看代码成果'); await expect(panel).toContainText('开发与修正 · 代码成果'); expect(previews).toBe(0)
  await panel.getByRole('button', { name: /开发与修正 · 代码成果/ }).click(); await expect(panel).toContainText('该节点执行失败'); expect(changes).toBe(0)
  await panel.getByRole('button', { name: '查看改动文件' }).click(); await expect(panel).toContainText('继承的上游代码'); await expect(panel).toContainText('旧版订单处理说明.md')
  await expect(panel.getByRole('link', { name: 'docs/旧版订单处理说明.md' })).toHaveCount(0); await expect(panel).not.toContainText('private-')
  expect([lists, previews, changes, writes, checks]).toEqual([1, 1, 1, 0, 0])
  const directory = panel.getByRole('region', { name: '普通目录回填检查' })
  await directory.getByRole('button', { name: '检查原目录', exact: true }).click(); await expect(directory).toContainText('1 个路径'); await expect(directory).toContainText('src/订单处理/OrderService.java'); await expect(directory).not.toContainText('预计新增')
  await directory.screenshot({ path: 'test-results/workflow-writeback-conflict.png' })
  await directory.getByRole('button', { name: '重新检查原目录', exact: true }).click(); await expect(directory).toContainText('预计新增 1 · 修改 1 · 删除 1'); await expect(directory).toContainText('保留 2 处用户后续修改'); await expect(directory).toContainText('没有写入文件')
  expect([writes, checks]).toEqual([2, 2]); await panel.screenshot({ path: 'test-results/workflow-writeback-desktop.png' })
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await panel.screenshot({ path: 'test-results/workflow-writeback-mobile.png' })
  await page.reload(); await workflowTool(page, '查看代码成果'); await panel.getByRole('button', { name: /开发与修正 · 代码成果/ }).click(); await expect(directory.getByRole('button', { name: '检查原目录', exact: true })).toBeVisible(); expect(checks).toBe(2)
  await directory.getByRole('button', { name: '检查原目录', exact: true }).click(); await expect(directory).toContainText('预计新增');
  // The newest explicit check must remain the consent reference.
  directoryPreview.sha256 = 'check-3'
  await page.setViewportSize({ width: 1600, height: 1000 })
  const writeback = panel.getByRole('region', { name: '普通目录成果回填', exact: true })
  await writeback.getByRole('button', { name: '回填所选成果', exact: true }).click(); await expect(writeback).toContainText('删除 1'); expect(confirms).toBe(0)
  await expect(panel.getByRole('button', { name: '收起代码成果', exact: true })).toBeDisabled(); await writeback.scrollIntoViewIfNeeded(); await writeback.screenshot({ path: 'test-results/workflow-writeback-confirmation.png' })
  await writeback.getByRole('button', { name: '确认回填这些改动' }).click(); await expect(writeback.getByRole('button', { name: '重试原回填操作' })).toBeVisible()
  await writeback.getByRole('button', { name: '重试原回填操作' }).click(); await expect(writeback).toContainText('回填需处理'); await expect(writeback).toContainText('未开始回填')
  await writeback.getByRole('button', { name: '恢复原回填' }).click(); await expect(writeback).toContainText('正在回填原目录'); expect(retries).toBe(1)
  saved = { ...saved!, state: 'APPLIED', version: 4, queueState: 'FINISHED', appliedAt: 'now' }
  await writeback.getByRole('button', { name: '刷新回填状态' }).click(); await expect(writeback).toContainText('已回填原目录'); await expect(writeback).toContainText('原执行结果为失败'); expect(confirms).toBe(2)
  await panel.screenshot({ path: 'test-results/workflow-writeback-applied.png' }); await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await writeback.screenshot({ path: 'test-results/workflow-writeback-applied-mobile.png' })
  await page.reload(); await workflowTool(page, '查看代码成果'); await expect(writeback).toContainText('已回填原目录'); expect(confirms).toBe(2); expect(checks).toBe(3)

})
