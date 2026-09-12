import { expect, test } from '@playwright/test'
import { readdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

for (const type of ['代码审查', '项目贡献周报']) {
  for (const width of [1440, 390]) {
    test(`${type}主子报告跳转、命名下载与${width}px布局`, async ({ page }) => {
      const directory = `${type}_模板示例项目(虚构)_20260905-20260911_001`
      const root = resolve('..', 'docs/examples/template-reports-v3', directory)
      const paths = readdirSync(root, { recursive: true, encoding: 'utf8' }).filter(path => path.endsWith('.md'))
      const reports = paths.map((name, index) => ({ id: `report-${index}`, kind: 'TEMPLATE_REPORT', name, contentType: 'text/markdown', attemptId: 'attempt', createdAt: '2026-09-12T00:00:00Z',
        metadataSummary: { repairRound: 0, bundleId: 'attempt', directoryName: directory, mainPath: `${directory}.md`, reportRole: name === `${directory}.md` ? 'SUMMARY' : 'DETAIL', displayName: readFileSync(resolve(root, name), 'utf8').split('\n')[0]!.replace(/^# /, '') },
      }))
      let reads = 0
      await page.route('http://127.0.0.1:41773/api/**', async route => {
        const path = new URL(route.request().url()).pathname
        if (path.endsWith('/overview')) return route.fulfill({ json: { id: 'fixture', projectId: 'p', projectName: '模板示例项目', title: type, goal: '模板报告验收', status: 'COMPLETED', loopRetryAvailable: false, cancellationAvailable: false, hasDesignHistory: false, archived: false, executionMode: 'TEMPLATE_REPORT', workspacePolicy: 'ISOLATED_REPORT', templateProgress: { dualReviewRequired: false, reportCount: reports.length }, attemptCount: 2, maxAttempts: 10, stages: [], createdAt: '2026-09-12T00:00:00Z', updatedAt: '2026-09-12T00:00:00Z' } })
        if (path.endsWith('/audit')) return route.fulfill({ json: { attempts: [], errors: [], judges: [], artifacts: reports } })
        if (path.endsWith('/content')) {
          const report = reports.find(report => path.includes(`/${report.id}/`))!
          reads++
          return route.fulfill({ json: { id: report.id, kind: report.kind, content: readFileSync(resolve(root, report.name), 'utf8'), metadata: {} } })
        }
        if (path.endsWith('/download')) return route.fulfill({ contentType: 'application/zip', body: Buffer.from('fixture-download') })
        if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: '' })
        return route.fulfill({ json: { items: [], attempts: [], errors: [], judges: [], artifacts: [] } })
      })
      await page.setViewportSize({ width, height: 1000 })
      await page.goto('/tasks/fixture')
      const panel = page.getByRole('region', { name: '模板任务报告' })
      await expect(panel.getByRole('button', { name: '查看最新总结' })).toBeVisible()
      expect(reads).toBe(0)
      await expect(page.getByText('评审通过', { exact: true })).toHaveCount(0)
      await expect(page.getByRole('button', { name: '启动双评审' })).toHaveCount(0)
      await panel.getByRole('button', { name: '查看最新总结' }).click()
      await expect(panel.getByRole('heading', { name: /总结报告/, exact: false })).toBeVisible()
      await panel.getByRole('link', { name: '完整问题清单', exact: true }).click()
      await expect(panel.getByText('F001', { exact: true })).toBeVisible()
      await panel.getByRole('link', { name: '返回总结报告' }).click()
      await expect(panel.getByRole('heading', { name: /总结报告/, exact: false })).toBeVisible()
      expect(reads).toBe(2)
      await expect(panel.getByText('已校验并保存', { exact: true })).toBeVisible()
      const download = page.waitForEvent('download')
      await panel.getByRole('button', { name: '下载整套报告' }).click()
      expect((await download).suggestedFilename()).toBe(`${directory}.zip`)
      expect(await page.locator('body').evaluate(node => node.scrollWidth <= window.innerWidth)).toBeTruthy()
    })
  }
}
