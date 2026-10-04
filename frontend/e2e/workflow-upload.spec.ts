import { createHash } from 'node:crypto'
const narrowLayoutInScope: boolean = false
import { semanticName } from './w3/semantics'
import { expect, test, type Page } from '@playwright/test'
import { requirement, execution } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowStart, WorkflowUpload } from '../src/types/workflow'

async function fixture(page: Page, interrupted = false) {
  const req = requirement({ id: 'upload-demo', title: '审批需求原文核对', state: 'PENDING_START' }), snapshot = execution('PENDING_START')
  req.graph.inputs = [{ name: 'documents', title: '需求文档', kind: 'DOCUMENT', required: true }]
  req.graph.nodes[0]!.inputs = [{ name: 'source', source: 'REQUIREMENT', sourceId: 'documents', output: null, kind: 'DOCUMENT', required: true }]
  snapshot.execution.id = req.id; snapshot.control.id = req.id
  const upload: WorkflowUpload = { id: 'fixed-documents', createdAt: '2026-09-29T00:00:00Z', ready: true, reference: { version: 1, type: 'UPLOADED_DOCUMENTS', uploadId: 'fixed-documents', sha256: 'fixed-sha' }, parserVersion: 'ASSIST_DOCUMENT_V2', resume: null, originals: [{ filename: '审批需求.md', path: 'original/01/审批需求.md', sizeBytes: file.buffer.length, sha256: createHash('sha256').update(file.buffer).digest('hex'), representationSha256: 'parsed-sha', format: 'md', sections: 2, limitations: ['仅提供可提取内容，不还原版式或图片中的文字。'] }] }
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
    if (path === '/api/roles') return route.fulfill({ json: { items: [], nextCursor: null } })
    return route.fulfill({ json: [] })
  })
  return { posts, starts }
}
const file = { name: '审批需求.md', mimeType: 'text/markdown', buffer: Buffer.from('# 审批权限\n审批人必须拥有本部门的审批权限。') }
test('上传固定原文、预览并将其绑定到执行请求', async ({ page }) => {
  const data = await fixture(page); await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/upload-demo'); await page.getByRole('button', { name: semanticName('workflow.flowInputs'), exact: true }).click()
  await page.getByLabel('选择需求文档').setInputFiles(file); await page.getByRole('button', { name: semanticName('workflow.uploadAndSelect'), exact: true }).click()
  await expect(page.getByText('已选 1 份文档', { exact: true })).toBeVisible(); expect(data.starts).toHaveLength(0)
  await page.getByRole('button', { name: semanticName('ui.open', '解析内容'), exact: true }).click(); await page.locator('section[aria-label="需求文档"] li').filter({ hasText: '文档 1 · 第 1 节' }).getByRole('button', { name: semanticName('ui.open', '文档章节'), exact: true }).click()
  await expect(page.locator('section[aria-label="需求文档"] .w3-rich-document')).toContainText('审批人必须拥有本部门的审批权限')
  await page.screenshot({ path: 'test-results/workflow-upload-desktop.png', fullPage: true })
  if (narrowLayoutInScope) { // OUT_OF_SCOPE: current acceptance is desktop only; historical assertions retained.
    await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: 'test-results/workflow-upload-mobile.png', fullPage: true })
  }
  await page.getByRole('button', { name: semanticName('workflow.continuous'), exact: true }).click(); await expect(page.getByText('公共资料已固定，重试和继续使用原资料。新增采集节点可获取新版本。')).toBeVisible(); expect(data.starts).toHaveLength(1)
})
test('上传失败后刷新页面，选择原记录补传并保留幂等身份', async ({ page }) => {
  page.on('dialog', dialog => dialog.accept())
  const data = await fixture(page, true); await page.goto('/requirements/upload-demo'); await page.getByRole('button', { name: semanticName('workflow.flowInputs'), exact: true }).click(); await page.getByLabel('选择需求文档').setInputFiles(file)
  await page.getByRole('button', { name: semanticName('workflow.uploadAndSelect'), exact: true }).click(); await expect(page.locator('section[aria-label="需求文档"] [role=alert]')).toContainText('保存中断')
  await page.reload(); await page.getByRole('button', { name: semanticName('workflow.flowInputs'), exact: true }).click(); await page.getByRole('button', { name: semanticName('workflow.selectUploaded'), exact: true }).click(); await page.getByRole('button', { name: semanticName('workflow.resumeUpload'), exact: true }).click()
  await page.getByLabel('选择需求文档').setInputFiles(file); await page.getByRole('button', { name: semanticName('workflow.uploadAndSelect'), exact: true }).click(); await expect(page.getByText('已选 1 份文档', { exact: true })).toBeVisible()
  expect(data.posts.map(body => JSON.parse(body.match(/\{[^\r\n]*"requestKey"[^\r\n]*\}/)![0]))[1]).toEqual(JSON.parse(data.posts[0]!.match(/\{[^\r\n]*"requestKey"[^\r\n]*\}/)![0])); expect(data.starts).toHaveLength(0)
})


test('上传回执未知时拒绝关闭、Escape与离页，并按原身份重试', async ({ page }) => {
  const data = await fixture(page, true)
  page.on('dialog', dialog => dialog.dismiss())
  await page.goto('/requirements/upload-demo')
  await page.getByRole('button', { name: semanticName('workflow.flowInputs'), exact: true }).click()
  await page.getByLabel('选择需求文档').setInputFiles(file)
  await page.getByRole('button', { name: semanticName('workflow.uploadAndSelect'), exact: true }).click()
  await expect(page.locator('section[aria-label="需求文档"] [role=alert]')).toContainText('保存中断')
  const canvas = page.locator('[data-canvas-kind="workflow"]')
  await expect(canvas).toHaveAttribute('data-canvas-runtime', 'react')
  await canvas.evaluate(node => node.setAttribute('data-original-instance', 'yes'))
  await page.evaluate(() => { const key = 'loopper.canvas.runtime.v1'; localStorage.setItem(key, JSON.stringify({ workflow: 'vue' })); window.dispatchEvent(new StorageEvent('storage', { key })) })
  await expect(canvas).toHaveAttribute('data-original-instance', 'yes')
  await expect(canvas).toHaveAttribute('data-canvas-runtime', 'react')
  const close = page.getByRole('button', { name: semanticName('ui.close', '需求与资料'), exact: true })
  if (await close.isEnabled()) await close.click()
  await page.locator('[data-foundation-component="context"]:not([hidden])').focus(); await page.keyboard.press('Escape')
  await expect(page.getByRole('button', { name: semanticName('workflow.moreTools'), exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: semanticName('workflow.continuous'), exact: true })).toBeDisabled()
  await page.getByRole('button', { name: semanticName('ui.expand', semanticName('app.navigation')), exact: true }).click(); await page.getByRole('dialog', { name: '应用导航' }).getByRole('link', { name: semanticName('nav.requirements'), exact: true }).click()
  await expect(page).toHaveURL('/requirements/upload-demo')
  await expect(page.getByText('待上传：审批需求.md', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: semanticName('ui.collapse', semanticName('app.navigation')), exact: true }).click()
  const retry = page.locator('section[aria-label="需求文档"]').getByRole('button', { name: semanticName('receipt.retryOriginal'), exact: true })
  await expect(retry).toBeEnabled()
  await expect(page.getByRole('button', { name: semanticName('workflow.uploadAndSelect'), exact: true })).toBeDisabled()
  expect(data.starts).toHaveLength(0)
  await retry.click()
  await expect(page.getByText('已选 1 份文档', { exact: true })).toBeVisible()
  const metadata = data.posts.map(body => JSON.parse(body.match(/\{[^\r\n]*"requestKey"[^\r\n]*\}/)![0]))
  expect(metadata).toHaveLength(2); expect(metadata[1]).toEqual(metadata[0])
  await page.getByRole('button', { name: semanticName('ui.close', '需求与资料'), exact: true }).click()
  await expect(page.locator('[data-foundation-component="context"]:not([hidden])')).toHaveCount(0)
})
