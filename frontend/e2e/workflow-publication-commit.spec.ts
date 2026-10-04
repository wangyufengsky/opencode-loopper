const narrowLayoutInScope: boolean = false
import { semanticName } from './w3/semantics'
import { workflowTool } from './fixtures/workflowNavigation'
import { expect, test } from '@playwright/test'
import { requirement, execution } from '../src/components/workflow/workflowRunTestFixtures'
import { newNode } from '../src/components/workflow/graph'
import type { WorkflowPublicationSource, WorkflowPublicationPreview, WorkflowPublicationCommit } from '../src/types/domain'
test('确认固定成果后恢复同一本地提交，并在重新打开画布后保留记录', async ({ page }) => {
  const node = { ...newNode('free.write'), id: 'work', title: '开发与修正' }
  const req = requirement({ id: 'publication-preview', title: '订单流程开发', state: 'COMPLETED', revision: 2, graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } })
  const snapshot = execution(req.state); snapshot.execution.id = req.id; snapshot.execution.revision = 2; snapshot.execution.nodes = [{ id: 'private-node', nodeKey: 'work', state: 'FAILED', attemptCount: 2, latestAttemptId: 'private-attempt', version: 4, outcome: null }]; snapshot.control = { ...snapshot.control, id: req.id, configured: true, state: 'DONE' }
  const source: WorkflowPublicationSource = { nodeKey: 'work', nodeTitle: '开发与修正', attemptId: 'private-attempt', ordinal: 2, attemptState: 'FAILED', outputName: 'code', outputTitle: '代码成果', createdAt: '2026-09-29T08:00:00Z', changedFiles: 3, totalFiles: 24 }
  const preview: WorkflowPublicationPreview = { requirementId: req.id, requirementVersion: req.version, planRevision: 2, requirementState: 'COMPLETED', source, workspaceKind: 'GIT', sourceBranch: 'feature/order-flow', reference: { version: 1, snapshotId: 'private-snapshot', sha256: 'a'.repeat(64) }, deliverySha256: 'b'.repeat(64), baseTree: 'private-base', resultTree: 'private-result', added: 1, modified: 1, deleted: 1, totalBytes: 15360, sha256: 'c'.repeat(64) }
  let lists = 0, previews = 0, changes = 0, writes = 0
  let publication: WorkflowPublicationCommit | null = null
  const saved = { requirementId: req.id, version: 1, nodeTitle: source.nodeTitle, outputTitle: source.outputTitle, attemptState: source.attemptState, branch: 'loopper/results/89b76d4b-73d0-4c90-99b5-31e13007bb28', message: '完成订单处理与测试', commit: null, createdAt: '2026-09-29T08:00:00Z' }
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (route.request().method() !== 'GET') writes++
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === `/api/workflows/requirements/${req.id}`) return route.fulfill({ json: req })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/publication') && route.request().method() === 'POST') {
      expect(route.request().headers()['x-loopper-local-ui']).toBe('1')
      expect(route.request().postDataJSON()).toMatchObject({ expectedVersion: preview.requirementVersion, revision: 2, node: 'work', attempt: source.attemptId, output: 'code', previewSha256: preview.sha256, message: saved.message })
      publication = { ...saved, state: 'BLOCKED' }; return route.fulfill({ json: { ...publication, state: 'CONFIRMED' } })
    }
    if (path.endsWith('/publication/retry')) {
      expect(route.request().postDataJSON()).toEqual({ expectedVersion: 1 }); publication = { ...saved, state: 'COMMITTED', version: 3, commit: 'abcd1234'.repeat(5) }; return route.fulfill({ json: publication })
    }
    if (path.endsWith('/publication')) return route.fulfill({ json: publication })
    if (path.endsWith('/publication/push')) return route.fulfill({ json: null })
    if (path.endsWith('/publication/sources')) { lists++; expect(url.searchParams.get('revision')).toBe('2'); return route.fulfill({ json: { items: [source], nextCursor: null } }) }
    if (path.endsWith('/publication/preview')) { previews++; expect(url.searchParams.get('attempt')).toBe(source.attemptId); return route.fulfill({ json: preview }) }
    if (path.endsWith('/changes')) { changes++; expect(path).toContain('/nodes/work/attempts/private-attempt/outputs/code/'); return route.fulfill({ json: { items: [{ path: 'src/订单处理/OrderService.java', kind: 'MODIFY', beforeBlob: 'before', afterBlob: 'after' }, { path: 'src/test/OrderServiceTest.java', kind: 'ADD', beforeBlob: null, afterBlob: 'after' }, { path: 'docs/旧版订单处理说明.md', kind: 'DELETE', beforeBlob: 'before', afterBlob: null }], nextCursor: null } }) }
    if (path.endsWith('/publication/writeback')) return route.fulfill({ json: null })
    if (path.endsWith('/publication/push')) return route.fulfill({ json: null })
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    if (path === '/api/roles') return route.fulfill({ json: { items: [], nextCursor: null } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto(`/requirements/${req.id}`)
  const panel = page.getByRole('complementary', { name: '需求代码成果' }); expect(lists).toBe(0)
  await workflowTool(page, '查看代码成果'); await expect(panel).toContainText('开发与修正 · 代码成果'); expect(previews).toBe(0)
  await panel.getByRole('button', { name: /开发与修正 · 代码成果/ }).click(); await expect(panel).toContainText('该节点执行失败'); expect(changes).toBe(0)
  await panel.getByRole('button', { name: semanticName('ui.open', '代码改动文件') }).click(); await expect(panel).toContainText('继承的上游代码'); await expect(panel).toContainText('旧版订单处理说明.md')
  await expect(panel.getByRole('link', { name: 'docs/旧版订单处理说明.md' })).toHaveCount(0); await expect(panel).not.toContainText('private-')
  expect([lists, previews, changes, writes]).toEqual([1, 1, 1, 0])
  await panel.getByRole('button', { name: semanticName('workflow.commit'), exact: true }).click()
  await panel.getByLabel('提交说明').fill(saved.message); expect(writes).toBe(0)
  await panel.getByRole('button', { name: semanticName('workflow.commitConfirm'), exact: true }).click(); await expect(panel).toContainText('提交需处理'); expect(writes).toBe(1)
  await panel.getByRole('button', { name: semanticName('receipt.readOriginal', '原提交'), exact: true }).click(); await expect(panel).toContainText('已保存本地提交'); expect(writes).toBe(2)
  await expect(panel).toContainText('尚未推送到远端'); await expect(panel).toContainText('原执行结果为失败')
  await page.reload(); await workflowTool(page, '查看代码成果'); await expect(panel).toContainText('已保存本地提交'); expect(writes).toBe(2)
  await panel.screenshot({ path: 'test-results/workflow-publication-commit-desktop.png' })
  if (narrowLayoutInScope) { // OUT_OF_SCOPE: current acceptance is desktop only; historical assertions retained.
    await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await panel.screenshot({ path: 'test-results/workflow-publication-commit-mobile.png' })
  }
})
