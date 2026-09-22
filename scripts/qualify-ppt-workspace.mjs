#!/usr/bin/env node
// Local, synthetic end-to-end fixture. No Provider invocation; uses the public PPT REST contract.
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { randomUUID, createHash } from 'node:crypto'
import assert from 'node:assert/strict'

const base = new URL(process.env.PPT_BASE_URL || 'http://127.0.0.1:8080')
assert(['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname), 'This fixture only runs against a local service')
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const output = path.resolve(process.env.PPT_OUTPUT_DIR || path.join(root, 'data/ppt-qualification', new Date().toISOString().replaceAll(':', '-')))
await mkdir(output, { recursive: true })
const digest = data => createHash('sha256').update(data).digest('hex')
async function api(route, body) {
  const response = await fetch(new URL(route, base), { method: body === undefined ? 'GET' : 'POST', headers: body === undefined ? {} : { 'X-Loopper-Local-UI': '1', ...(body instanceof FormData ? {} : { 'Content-Type': 'application/json' }) }, body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body), signal: AbortSignal.timeout(60000) })
  const result = await response.json()
  assert(response.ok, `${route}: ${response.status} ${JSON.stringify(result)}`)
  return result
}
const sample = JSON.parse(await readFile(path.join(root, 'src/test/resources/ppt/sample-12.json'), 'utf8'))
const document = await api('/api/ppt/documents', { id: randomUUID(), title: sample.title })
const route = `/api/ppt/documents/${document.id}`
let revision = document.revision
async function operation(operations) {
  const result = await api(`${route}/operations`, { idempotencyKey: randomUUID(), expectedRevision: revision, operations })
  revision = result.revision
  return result.deck
}
async function action(name, extra = {}) { return api(`${route}/actions/${name}`, { idempotencyKey: randomUUID(), expectedRevision: revision, ...extra }) }
async function upload(name, bytes, kind, type) {
  const data = new FormData(); data.set('file', new Blob([bytes], { type }), name); data.set('idempotencyKey', randomUUID())
  const result = await api(`${route}/${kind}`, data); assert.equal(result.state, 'READY'); return result
}
const asset = await upload('功能验收示意图.png', await readFile(path.join(root, 'src/test/resources/ppt/sample-illustration.png')), 'assets', 'image/png')
const source = await upload('示例数据说明.md', '# 功能验收示例\n所有数值均为虚构演示数据，不代表实际经营表现。\n', 'sources', 'text/markdown')
for (const slide of sample.slides) for (const element of slide.elements) if (element.assetId === 'sample-illustration') element.assetId = asset.id
const plan = {
  brief: { purpose: '验证独立 PPT 工作室能力', audience: '开发与验收人员', duration: '12 分钟', pageCount: 12, requirements: '仅使用演示数据；保留原生可编辑对象' },
  directions: [{ id: 'capabilities', title: '逐项展示制作能力', description: '资料、内容、制作、修改及导出依次展示' }], selectedDirectionId: 'capabilities', theme: sample.theme,
  slides: sample.slides.map(slide => ({ id: slide.id, title: slide.title, section: slide.section, message: slide.title, content: slide.elements.map(e => e.text || '').join('\n'), layout: 'title_content', sourceIds: [source.id], notes: slide.notes })),
  delivery: { fileName: 'PPT工作室综合验收.pptx', targetSoftware: 'WPS', includeNotes: true },
}
revision = (await api(`${route}/plan`, { idempotencyKey: randomUUID(), expectedRevision: revision, plan })).revision
await action('finish-planning'); await action('confirm-direction'); await action('start-production', { useAgent: false })
for (const slide of sample.slides) await operation([{ op: 'create_slide', slide }])
async function check() { const result = await api(`${route}/checks?revision=${revision}`); assert.equal(result.issues.filter(i => i.severity === 'ERROR').length, 0, JSON.stringify(result)); return result }
await check(); await action('finish-production')
async function job(kind, slideId) {
  let value = await api(`${route}/jobs`, { kind, revision, ...(slideId ? { slideId } : {}), idempotencyKey: randomUUID() })
  const deadline = Date.now() + 180000
  while (['PREPARED', 'RUNNING'].includes(value.state) && Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 500)); value = await api(`${route}/jobs/${value.id}`)
  }
  assert.equal(value.state, 'COMPLETED', JSON.stringify(value)); return value
}
async function download(artifact, name) {
  const response = await fetch(new URL(artifact.url, base)); assert(response.ok)
  const bytes = Buffer.from(await response.arrayBuffer()); await writeFile(path.join(output, name), bytes)
  return { path: path.join(output, name), bytes: bytes.length, sha256: digest(bytes), artifactId: artifact.id }
}
const originalPreview = await job('PREVIEW')
const previews = []
for (const artifact of originalPreview.artifacts) previews.push(await download(artifact, `${artifact.slideId}.png`))
const originalExport = await job('EXPORT')
const original = await download(originalExport.artifacts[0], 'sample-original.pptx')
// Lock the cover. A scoped text change, layout change, and theme change are independent revisions.
await operation([{ op: 'update_slide', slideId: 'demo-01', patch: { locked: true } }])
let before = await api(`${route}/deck`)
await operation([{ op: 'update_element', slideId: 'demo-12', elementId: 'demo-12-title', patch: { text: '验收修订：内容与交付分别验证' } }])
assert.deepEqual((await api(`${route}/deck`)).slides.slice(0, 11), before.slides.slice(0, 11))
before = await api(`${route}/deck`)
await operation([
  { op: 'update_element', slideId: 'demo-10', elementId: 'demo-10-left', patch: { x: 60, y: 140, width: 410, height: 300 } },
  { op: 'update_element', slideId: 'demo-10', elementId: 'demo-10-right', patch: { x: 490, y: 140, width: 410, height: 300 } },
])
assert.deepEqual((await api(`${route}/deck`)).slides.filter(s => s.id !== 'demo-10'), before.slides.filter(s => s.id !== 'demo-10'))
await operation([{ op: 'apply_theme', theme: 'minimal' }]); const checks = await check()
const revisedPreview = await job('PREVIEW'), revisedExport = await job('EXPORT')
const revised = await download(revisedExport.artifacts[0], 'sample-revised.pptx')
for (const artifact of revisedPreview.artifacts) await download(artifact, `revised-${artifact.slideId}.png`)
const oldCover = previews.find(p => p.path.endsWith('/demo-01.png'))
const newCover = await readFile(path.join(output, 'revised-demo-01.png'))
assert.equal(digest(newCover), oldCover.sha256, 'Locked cover must keep identical rendered pixels across a global theme change')
const stillOriginal = Buffer.from(await (await fetch(new URL(originalExport.artifacts[0].url, base))).arrayBuffer())
assert.equal(digest(stillOriginal), original.sha256, 'Historical export must remain immutable')
const evidence = { documentId: document.id, url: new URL(`/ppt/${document.id}`, base).href, revision, original, revised, previews, checks, lockedCoverUnchanged: true, unrelatedPagesUnchanged: true, historicalExportUnchanged: true, providerInvoked: false }
await writeFile(path.join(output, 'evidence.json'), JSON.stringify(evidence, null, 2) + '\n')
console.log(JSON.stringify({ documentId: document.id, revision, output, original, revised }, null, 2))
