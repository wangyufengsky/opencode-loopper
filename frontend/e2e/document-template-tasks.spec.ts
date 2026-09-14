import { expect, test } from '@playwright/test'

for (const width of [1440, 390]) {
  test(`文档评审上传重试、刷新与按需报告 ${width}px`, async ({ page }) => {
    const posts: string[] = []; let bodies = 0
    const overview = { id: 'document', projectId: 'p', templateId: 'REQUIREMENT_CODE_REVIEW', templateVersion: '1', title: '需求代码评审 · 示例项目',
      state: 'COMPLETED', waitingReasonCode: null, waitingMessage: null, designerId: null, taskId: null, requirementRevision: 1, version: 10,
      canCancel: false, canResume: false, archived: false, uploadReady: true, snapshotSha: 'frozen-sha', createdAt: 'now', updatedAt: 'now',
      files: [{ id: 'file', filename: '付款需求.md', sectionCount: 1, sha256: 'file-sha', limitations: ['图片中的流程未提取'] }],
      progress: { requirements: 1, attempts: 4, validated: 4, active: 0, stopped: 0, reports: 1, revision: 10 } }
    await page.route('http://127.0.0.1:41773/api/**', async route => {
      const request = route.request(); const path = new URL(request.url()).pathname
      let body: unknown = { items: [], facets: {} }
      if (path === '/api/template-tasks/catalog') body = { templates: [{ id: 'REQUIREMENT_CODE_REVIEW', version: '1', title: '需求代码评审', description: '按文档检查冻结代码', inputs: { documents: true, branch: true, dates: false } }], dimensions: [] }
      else if (path === '/api/template-tasks/projects') body = { items: [{ id: 'p', name: '示例项目', createdAt: 'now' }], nextCursor: null }
      else if (path === '/api/template-tasks/projects/p') body = { id: 'p', name: '示例项目', createdAt: 'now' }
      else if (path.endsWith('/branches')) {
        const branch = { id: 'local:refs/heads/main', label: 'main', ref: 'refs/heads/main', remote: null }
        body = { page: { items: [branch], nextCursor: null }, defaultBranch: branch, defaultBranchId: branch.id, remoteAvailable: true }
      } else if (path === '/api/template-tasks/document-runs' && request.method() === 'POST') {
        expect(request.headers()['x-loopper-local-ui']).toBe('1')
        const data = request.postDataBuffer()!.toString('utf8'); posts.push(data.match(/"requestKey":"([^"]+)"/)![1]!)
        expect(data).toContain('"branchId":"local:refs/heads/main"'); expect(data).not.toContain('startDate')
        if (posts.length === 1) return route.abort('connectionfailed')
        body = overview
      } else if (path.endsWith('/document') || path.includes('/by-request/')) body = overview
      else if (path.endsWith('/requirements')) body = { items: [{ requirementKey: 'RQ-1', title: '付款权限', ordinal: 0, groupName: '付款', kind: 'PERMISSION', issueCount: 0, conclusion: 'INCORRECT' }], nextOffset: null, revision: 1 }
      else if (path.endsWith('/requirements/RQ-1')) body = { requirement: { title: '付款权限', statement: '付款入口必须检查权限', sources: [], acceptance: ['无权限拒绝'], issues: [] }, assessment: { rationale: '付款入口直接扣款，缺少权限检查', testSourceCoverage: '未发现权限测试', evidence: [], checkedPaths: ['PaymentService.java'], limitations: [] } }
      else if (path.endsWith('/reports')) body = { items: [{ id: 'summary', name: 'summary.md', kind: 'SUMMARY', sha256: 'report', bytes: 90, createdAt: 'now' }], nextCursor: null }
      else if (path.endsWith('/reports/content')) { bodies++; body = { id: 'summary', name: 'summary.md', content: '# 静态评审报告\n\n评审已完成。付款权限实现不符；本次未执行测试。' } }
      else if (path.endsWith('/reports/download')) return route.fulfill({ contentType: 'application/zip', body: Buffer.from('browser-download-fixture') })
      else if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: '' })
      await route.fulfill({ json: body })
    })
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/template-tasks?projectId=p')
    await expect(page.getByRole('combobox', { name: '分支', exact: true })).toBeVisible()
    await expect(page.locator('.el-date-editor')).toHaveCount(0)
    await page.locator('#requirement-files').setInputFiles({ name: '付款需求.md', mimeType: 'text/markdown', buffer: Buffer.from('付款入口必须检查权限') })
    await page.getByRole('button', { name: '开始评审', exact: true }).click()
    await expect(page.getByRole('alert').filter({ hasText: /失败|未确认|重试|异常/ })).toBeVisible()
    await page.getByRole('button', { name: '开始评审', exact: true }).click()
    await expect(page).toHaveURL(/\/document-runs\/document$/)
    expect(posts).toHaveLength(2); expect(posts[0]).toBe(posts[1])
    await expect(page.getByText('本次未执行构建或测试。', { exact: false })).toBeVisible()
    expect(bodies).toBe(0)
    await page.getByRole('button', { name: '总体报告', exact: true }).click()
    await expect(page.getByRole('heading', { name: '静态评审报告', exact: true })).toBeVisible(); expect(bodies).toBe(1)
    const download = page.waitForEvent('download'); await page.getByRole('button', { name: '下载整包', exact: true }).click()
    expect((await download).suggestedFilename()).toBe('需求报告.zip')
    await page.reload(); await expect(page.getByRole('heading', { name: /需求代码评审 · 示例项目/ })).toBeVisible()
    expect(posts).toHaveLength(2); expect(bodies).toBe(1)
    expect(await page.locator('body').evaluate(node => node.scrollWidth <= window.innerWidth)).toBeTruthy()
    await page.screenshot({ path: `/private/tmp/loopper-document-template-${width}.png`, fullPage: true })
  })
}
