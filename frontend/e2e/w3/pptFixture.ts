import type { Page, Route } from '@playwright/test'
import type { PptDocument, PptDeck, PptMessage, PptJob, PptSource } from '../../src/types/ppt'
import { pptCapabilities, pptDeck, pptPlan } from '../../src/components/ppt/pptTestFixtures'
import { productFixture } from '../w2/productFixture'

export const pptDocument: PptDocument = { id: 'w3-ppt', title: '季度演示 · 本地模拟', projectId: null, model: 'fixture/model', phase: 'REVIEW', revision: 3, version: 3, archived: false, createdAt: '2026-10-03T00:00:00Z', updatedAt: '2026-10-03T00:00:00Z' }
/** Transport fixture only: local Canvas PNGs reflect the same text/geometry DTO.
 * This is not a backend PPTX rendering or model-generation assertion. */
async function mockPreviews(page: Page, deck: PptDeck) {
  const images = await page.evaluate(async deck => {
    await document.fonts.ready
    return deck.slides.map(slide => {
      const canvas = document.createElement('canvas'); canvas.width = deck.width; canvas.height = deck.height
      const context = canvas.getContext('2d')!; context.fillStyle = '#FFFFFF'; context.fillRect(0, 0, canvas.width, canvas.height)
      for (const element of slide.elements) {
        if (element.type !== 'text') throw new Error('This local preview fixture supports only the actual text DTO')
        context.save(); context.translate(element.x, element.y); context.rotate((element.rotation ?? 0) * Math.PI / 180)
        context.font = `${element.bold ? 'bold ' : ''}${element.fontSize ?? 24}px "${element.fontFamily ?? 'sans-serif'}"`
        context.fillStyle = `#${element.color ?? '172554'}`; context.textBaseline = 'top'; context.textAlign = element.align === 'center' ? 'center' : element.align === 'right' ? 'right' : 'left'
        const offset = element.align === 'center' ? element.width / 2 : element.align === 'right' ? element.width : 0
        ;(element.text ?? '').split('\n').forEach((line, index) => context.fillText(line, offset, index * (element.fontSize ?? 24) * 1.2, element.width))
        context.restore()
      }
      return { id: `png-${slide.id}`, base64: canvas.toDataURL('image/png').split(',')[1]! }
    })
  }, deck)
  return new Map(images.map(image => [image.id, Buffer.from(image.base64, 'base64')]))
}
export async function pptProductFixture(page: Page, options: { phase?: PptDocument['phase']; base?: Awaited<ReturnType<typeof productFixture>>; write?: (route: Route, state: { document: PptDocument; deck: PptDeck; messages: PptMessage[] }) => Promise<void> } = {}) {
  const base = options.base ?? await productFixture(page), requests: { method: string; path: string; body: string | null }[] = [], unexpected: string[] = []
  const state = { document: { ...pptDocument, ...(options.phase ? { phase: options.phase } : {}) }, deck: pptDeck(), messages: [] as PptMessage[] }
  const previews = await mockPreviews(page, state.deck)
  const jobs: PptJob[] = [{ id: 'preview-3', documentId: pptDocument.id, kind: 'PREVIEW', revision: 3, slideId: null, state: 'COMPLETED', completed: 2, total: 2, detail: '', artifacts: state.deck.slides.map(slide => ({ id: `png-${slide.id}`, slideId: slide.id, name: `${slide.title}.png`, mediaType: 'image/png', url: 'https://invalid.external/ignored' })), createdAt: '2026-10-03T00:00:00Z' }]
  await page.route('**/api/ppt/**', async route => {
    const request = route.request(), url = new URL(request.url()), method = request.method(), path = url.pathname
    requests.push({ method, path, body: request.postData() })
    if (path === '/api/ppt/capabilities') return route.fulfill({ json: pptCapabilities() })
    const prefix = `/api/ppt/documents/${pptDocument.id}`
    if (!path.startsWith(prefix)) return route.fallback()
    if (method !== 'GET') { if (options.write) return options.write(route, state); unexpected.push(`${method} ${path}`); return route.fulfill({ status: 501, json: { message: '未授权的模拟写入' } }) }
    const suffix = path.slice(prefix.length), payloads: Record<string, unknown> = {
      '': state.document, '/deck': state.deck, '/plan': { plan: pptPlan(), revision: state.document.revision }, '/sources': { sources: [] as PptSource[], assets: [] }, '/jobs': jobs,
      '/revisions': { items: [{ revision: state.document.revision, reason: '当前模拟快照', createdAt: pptDocument.createdAt }], facets: {} }, '/messages': { items: state.messages, facets: {} },
      '/agent': { state: 'IDLE', detail: '', version: 0, questions: [] }, '/generation': null, '/checks': { issues: [] }, '/knowledge': { project: null, sources: [], detail: '' },
    }
    const preview = previews.get(suffix.replace(/^\/artifacts\//, ''))
    if (preview) return route.fulfill({ body: preview, contentType: 'image/png' })
    if (Object.hasOwn(payloads, suffix)) return route.fulfill({ json: payloads[suffix] })
    unexpected.push(`${method} ${path}`); return route.fulfill({ status: 500, json: { message: 'PPT 缺少准确运输夹具' } })
  })
  return { state, requests, unexpected, base, errors: base.errors }
}
