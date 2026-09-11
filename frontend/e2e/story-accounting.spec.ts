import { expect, test } from '@playwright/test'
import { createServer, type ServerResponse } from 'node:http'

test('统计事件打开弹窗，空闲和结束后不轮询，重连恢复未关闭结果', async ({ page }) => {
  const streams = new Set<ServerResponse>()
  const server = createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'Access-Control-Allow-Origin': '*' })
    streams.add(response)
    response.on('close', () => streams.delete(response))
    response.write('event: ready\ndata: ready\n\n')
  })
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  const address = server.address()
  if (!address || typeof address === 'string') throw new Error('SSE fixture failed to bind')
  let listReads = 0, detailReads = 0
  let state: 'PREPARED' | 'SUCCEEDED' = 'PREPARED'
  let exists = false
  const call = () => ({ id: 'accounting-one', operation: 'start', state, role: 'IMPLEMENTATION', systemCode: 'SYS', storyCode: '000123',
    startedAt: new Date().toISOString(), finishedAt: state === 'SUCCEEDED' ? new Date().toISOString() : undefined,
    parts: [{ id: 'output', type: 'OUTPUT', content: '统计输出验收', label: '输出' }] })
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/story-accounting/events') return route.continue({ url: `http://127.0.0.1:${address.port}/events` })
    if (path === '/api/story-accounting') { listReads++; return route.fulfill({ json: exists ? [call()] : [] }) }
    if (path === '/api/story-accounting/accounting-one') { detailReads++; return route.fulfill({ json: call() }) }
    if (path.endsWith('/dismiss')) { exists = false; return route.fulfill({ status: 204 }) }
    return route.fulfill({ json: [] })
  })
  try {
    await page.clock.install()
    await page.goto('/')
    await expect.poll(() => listReads).toBe(1)
    await page.clock.runFor(60_000)
    expect(listReads).toBe(1); expect(detailReads).toBe(0)
    for (const response of streams) response.write(': keepalive\n\n')
    await page.clock.runFor(5_000)
    expect(listReads).toBe(1)
    exists = true
    for (const response of streams) response.write('data: accounting-one\n\n')
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByText('统计输出验收')).toBeVisible()
    await expect.poll(() => detailReads).toBe(1)
    await page.clock.runFor(1_300)
    await expect.poll(() => detailReads).toBe(2)
    expect(listReads).toBe(2)
    state = 'SUCCEEDED'
    for (const response of streams) response.write('data: accounting-one\n\n')
    await expect(page.getByRole('status')).toContainText('统计已完成')
    await page.screenshot({ path: '/tmp/loopper-story-accounting-completed.png', fullPage: true })
    const finishedReads = detailReads
    await page.clock.runFor(60_000)
    expect(detailReads).toBe(finishedReads); expect(listReads).toBe(3)
    for (const response of streams) response.end()
    await expect.poll(() => listReads, { timeout: 10_000 }).toBe(4)
    await expect(page.getByRole('dialog')).toBeVisible()
    await page.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(page.getByRole('dialog')).toBeHidden()
    await page.clock.runFor(60_000)
    expect(listReads).toBe(4)
  } finally {
    for (const response of streams) response.end()
    server.closeAllConnections()
    await new Promise<void>(resolve => server.close(() => resolve()))
  }
})
