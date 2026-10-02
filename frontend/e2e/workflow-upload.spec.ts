import { expect, test, type Page } from '@playwright/test'
import { requirement, execution } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowStart, WorkflowUpload } from '../src/types/workflow'

async function fixture(page: Page, interrupted = false) {
  const req = requirement({ id: 'upload-demo', title: '审批需求原文核对', state: 'PENDING_START' }), snapshot = execution('PENDING_START')
  req.graph.inputs = [{ name: 'documents', title: '需求文档', kind: 'DOCUMENT', required: true }]
  req.graph.nodes[0]!.inputs = [{ name: 'source', source: 'REQUIREMENT', sourceId: 'documents', output: null, kind: 'DOCUMENT', required: true }]
  snapshot.execution.id = req.id; snapshot.control.id = req.id
  const upload: WorkflowUpload = { id: 'fixed-documents', createdAt: '2026-09-29T00:00:00Z', ready: true, reference: { version: 1, type: 'UPLOADED_DOCUMENTS', uploadId: 'fixed-documents', sha256: 'fixed-sha' }, parserVersion: 'ASSIST_DOCUMENT_V2', resume: null, originals: [{ filename: '审批需求.md', path: 'original/01/审批需求.md', sizeBytes: 73, sha256: 'raw-sha', representationSha256: 'parsed-sha', format: 'md', sections: 2, limitations: ['仅提供可提取内容，不还原版式或图片中的文字。'] }] }
  const posts: string[] = [], starts: WorkflowStart[] = []; let accepted = false
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname, method = route.request().method()
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/upload-demo') return route.fulfill({ json: req })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/candidates')) return route.fulfill({ json: { items: [], nextCursor: null } })
    if (path.endsWith('/documents') && method === 'POST') {
      expect(route.request().headers()['x-loopper-local-ui']).toBe('1'); const body = route.request().postData()!; posts.push(body)
      const metadata = JSON.parse(body.match(/\{[^\r\n]*"requestKey"[^\r\n]*\}/)![0])
      if (interrupted && posts.length === 1) { upload.ready = false; upload.resume = metadata; return route.fulfill({ status: 500, json: { detail: '原文件保存中断，请补传原文件。' } }) }
      upload.ready = true; upload.resume = null; accepted = true; return route.fulfill({ json: upload })
    }
    if (path.endsWith('/documents')) return route.fulfill({ json: { items: posts.length ? [upload] : [], nextCursor: null } })
    if (path.endsWith('/documents/fixed-documents')) return route.fulfill({ json: upload })
    if (path.endsWith('/documents/fixed-documents/files')) return route.fulfill({ json: { items: [{ path: 'parsed/01/0001.md', sizeBytes: 82, sha256: 'section-sha', mode: null }], nextCursor: null } })
    if (path.endsWith('/documents/fixed-documents/text')) return route.fulfill({ json: { text: '# 审批权限\n\n审批人必须拥有本部门的审批权限。\n\n超出额度时转交上一级。', nextOffset: null } })
    if (path.endsWith('/control/start')) { expect(accepted).toBe(true); const body = route.request().postDataJSON() as WorkflowStart; starts.push(body); expect(body.inputs?.documents?.content).toEqual(upload.reference); snapshot.execution.state = 'PAUSED'; snapshot.control.configured = true; snapshot.control.reasonCode = 'WORKFLOW_HUMAN_INPUT'; return route.fulfill({ json: snapshot.control }) }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  return { posts, starts }
}
const file = { name: '审批需求.md', mimeType: 'text/markdown', buffer: Buffer.from('# 审批权限\n审批人必须拥有本部门的审批权限。') }
test('上传固定原文、预览并将其绑定到执行请求', async ({ page }) => {
  const data = await fixture(page); await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/upload-demo'); await page.getByRole('button', { name: '需求与资料', exact: true }).click()
  await page.getByLabel('选择需求文档').setInputFiles(file); await page.getByRole('button', { name: '上传并选用', exact: true }).click()
  await expect(page.getByText('已选 1 份文档', { exact: true })).toBeVisible(); expect(data.starts).toHaveLength(0)
  await page.getByRole('button', { name: '查看解析内容', exact: true }).click(); await page.getByRole('button', { name: '文档 1 · 第 1 节', exact: true }).click()
  await expect(page.locator('.workflow-document-preview')).toContainText('审批人必须拥有本部门的审批权限')
  await page.screenshot({ path: 'test-results/workflow-upload-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: 'test-results/workflow-upload-mobile.png', fullPage: true })
  await page.getByRole('button', { name: '连续执行', exact: true }).click(); await expect(page.getByText('本次资料已固定，可在节点详情查看实际输入。')).toBeVisible(); expect(data.starts).toHaveLength(1)
})
test('上传失败后刷新页面，选择原记录补传并保留幂等身份', async ({ page }) => {
  page.on('dialog', dialog => dialog.accept())
  const data = await fixture(page, true); await page.goto('/requirements/upload-demo'); await page.getByRole('button', { name: '需求与资料', exact: true }).click(); await page.getByLabel('选择需求文档').setInputFiles(file)
  await page.getByRole('button', { name: '上传并选用', exact: true }).click(); await expect(page.locator('.workflow-document-input [role=alert]')).toContainText('保存中断')
  await page.reload(); await page.getByRole('button', { name: '需求与资料', exact: true }).click(); await page.getByRole('button', { name: '选择已上传资料', exact: true }).click(); await page.getByRole('button', { name: '补传原文件', exact: true }).click()
  await page.getByLabel('选择需求文档').setInputFiles(file); await page.getByRole('button', { name: '上传并选用', exact: true }).click(); await expect(page.getByText('已选 1 份文档', { exact: true })).toBeVisible()
  expect(data.posts.map(body => JSON.parse(body.match(/\{[^\r\n]*"requestKey"[^\r\n]*\}/)![0]))[1]).toEqual(JSON.parse(data.posts[0]!.match(/\{[^\r\n]*"requestKey"[^\r\n]*\}/)![0])); expect(data.starts).toHaveLength(0)
})


test('上传回执未知时拒绝关闭、Escape与离页，并按原身份重试', async ({ page }) => {
  const data = await fixture(page, true)
  page.on('dialog', dialog => dialog.dismiss())
  await page.goto('/requirements/upload-demo')
  await page.getByRole('button', { name: '需求与资料', exact: true }).click()
  await page.getByLabel('选择需求文档').setInputFiles(file)
  await page.getByRole('button', { name: '上传并选用', exact: true }).click()
  await expect(page.locator('.workflow-document-input [role=alert]')).toContainText('保存中断')
  const close = page.getByRole('button', { name: '关闭需求与资料', exact: true })
  if (await close.isEnabled()) await close.click()
  await page.locator('.workflow-context-panel').focus(); await page.keyboard.press('Escape')
  await expect(page.getByRole('button', { name: '更多工具', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '连续执行', exact: true })).toBeDisabled()
  await page.getByRole('link', { name: '返回需求任务', exact: true }).click()
  await expect(page).toHaveURL('/requirements/upload-demo')
  await expect(page.getByText('待上传：审批需求.md', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '上传并选用', exact: true })).toBeEnabled()
  expect(data.starts).toHaveLength(0)
  await page.getByRole('button', { name: '上传并选用', exact: true }).click()
  await expect(page.getByText('已选 1 份文档', { exact: true })).toBeVisible()
  const metadata = data.posts.map(body => JSON.parse(body.match(/\{[^\r\n]*"requestKey"[^\r\n]*\}/)![0]))
  expect(metadata).toHaveLength(2); expect(metadata[1]).toEqual(metadata[0])
  await page.getByRole('button', { name: '关闭需求与资料', exact: true }).click()
  await expect(page.locator('.workflow-context-panel')).toHaveCount(0)
})
