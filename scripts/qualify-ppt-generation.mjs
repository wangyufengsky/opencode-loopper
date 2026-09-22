#!/usr/bin/env node
// Explicit real-Provider qualification; never called by the default build.
import assert from 'node:assert/strict'
import { randomUUID, createHash } from 'node:crypto'
import { mkdir, writeFile, readFile } from 'node:fs/promises'
import path from 'node:path'

const base = new URL(process.env.PPT_BASE_URL || 'http://127.0.0.1:8080')
assert(['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname), 'Only local qualification services are supported')
assert(process.env.PPT_ALLOW_PROVIDER === '1', 'Set PPT_ALLOW_PROVIDER=1 to explicitly enable model calls')
const output = path.resolve(process.env.PPT_OUTPUT_DIR || `data/ppt-generation-${new Date().toISOString().replaceAll(':', '-')}`)
await mkdir(output, { recursive: true })
const statePath = path.join(output, 'evidence.json')
let evidence
try { evidence = JSON.parse(await readFile(statePath, 'utf8')) } catch (error) {
  if (error.code !== 'ENOENT') throw error
  evidence = { base: base.href, documentId: randomUUID(), generationKey: randomUUID(), providerInvoked: true }
}
assert.equal(evidence.base, base.href, 'An existing evidence directory cannot be reused for another service')
async function save() { await writeFile(statePath, JSON.stringify(evidence, null, 2) + '\n') }
async function api(route, body) {
  const response = await fetch(new URL(route, base), {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : { 'X-Loopper-Local-UI': '1', 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000),
  })
  const result = await response.json()
  assert(response.ok, `${response.status} ${route}: ${JSON.stringify(result)}`)
  return result
}
const route = `/api/ppt/documents/${evidence.documentId}`
const prompt = process.env.PPT_PROMPT || '制作一份6页中文季度项目汇报，面向部门领导，5分钟讲完，简洁商务风。主题是“服务体验改进”。这是验收用的虚构案例，所有数据明确标注为示例：一月至三月满意度分别为72、80、88分，工单量分别为120、100、85件。页面包括封面、一页结论、满意度趋势折线图、工单量柱状图、改进行动表格、下一步安排。每页有简短讲稿。无需外部图片，使用可编辑文字图形及原生图表。其他内容和设计由你决定，直接生成完整PPT，不需要我再选方案。'
if (!evidence.started) {
  await save()
  evidence.createRequest ||= { id: evidence.documentId, title: 'AI 需求沟通与生成验收', ...(process.env.PPT_PROJECT_ID ? { projectId: process.env.PPT_PROJECT_ID } : {}) }
  await save()
  await api('/api/ppt/documents', evidence.createRequest)
  evidence.request = evidence.request || { idempotencyKey: evidence.generationKey, expectedRevision: 0, prompt }
  await save()
  evidence.initialGeneration = await api(`${route}/generate`, evidence.request)
  evidence.started = true
  await save()
}
async function waitForGeneration(generationId) {
  const deadline = Date.now() + Number(process.env.PPT_WAIT_MS || 1200000)
  let last = ''
  while (Date.now() < deadline) {
    const value = await api(`${route}/generation`)
    assert.equal(value.id, generationId, 'A different request became the latest generation')
    evidence.currentGeneration = value
    await save()
    const progress = `${value.state} ${value.step} ${value.detail}`
    if (progress !== last) { console.log(progress); last = progress }
    if (value.state === 'COMPLETED') return value
    if (value.state === 'WAITING_INPUT' && process.env.PPT_DIALOGUE_ACCEPTANCE === '1') {
      // Explicit qualification fixture acts as the user; normal usage still waits for the real user's decision.
      const agent = await api(`${route}/agent`)
      const question = agent.questions.find(item => item.state === 'PENDING')
      if (agent.state !== 'WAITING_INPUT' || !question) { await new Promise(resolve => setTimeout(resolve, 1000)); continue }
      const document = await api(route)
      evidence.questionReplies ||= []
      let reply = evidence.questionReplies.find(item => item.questionId === question.id)
      if (!reply) {
        assert(evidence.questionReplies.length < 8, 'Dialogue fixture has reached its observation limit; inspect pending question manually')
        const confirm = question.kind === 'REQUIREMENTS_CONFIRMATION'
        if (!evidence.questionReplies.some(item => item.body.confirmed)) {
          assert.equal(document.revision, 0, 'No plan may be saved before explicit requirements confirmation')
          assert.equal((await api(`${route}/deck`)).slides.length, 0)
        }
        reply = { questionId: question.id, kind: question.kind, prompt: question.prompt, body: {
          idempotencyKey: randomUUID(), expectedRevision: document.revision, version: question.version,
          answer: confirm ? '确认以上需求，请开始设计。' : '给部门领导汇报，重点突出成果、风险与下一步；控制6页、5分钟，简洁商务风，图表和表格都可编辑。优先使用已选项目资料，缺少事实不要编造。验收案例中的数据按示例标注。',
          ...(confirm ? { confirmed: true } : {}),
        } }
        evidence.questionReplies.push(reply); await save()
      }
      await api(`${route}/questions/${question.id}/reply`, reply.body)
      console.log(`ANSWERED ${question.kind}`)
      continue
    }
    if (['FAILED', 'STOPPED', 'WAITING_INPUT'].includes(value.state)) {
      evidence.agent = await api(`${route}/agent`)
      await save()
      throw new Error(`Generation requires attention: ${value.state}. Open ${new URL(`/ppt/${evidence.documentId}`, base)}`)
    }
    await new Promise(resolve => setTimeout(resolve, 2000))
  }
  throw new Error('Observation deadline reached; generation remains saved and is not automatically cancelled')
}
async function artifacts(label) {
  const document = await api(route), deck = await api(`${route}/deck`), jobs = await api(`${route}/jobs`)
  const current = jobs.filter(job => job.revision === document.revision && job.state === 'COMPLETED')
  const preview = current.find(job => job.kind === 'PREVIEW' && !job.slideId)
  const exported = current.find(job => job.kind === 'EXPORT')
  assert(preview && exported, 'Current version requires complete preview and editable export')
  assert.equal(preview.artifacts.length, deck.slides.length)
  const files = []
  for (const artifact of [...preview.artifacts, ...exported.artifacts]) {
    const response = await fetch(new URL(artifact.url, base), { signal: AbortSignal.timeout(30000) })
    assert(response.ok)
    const bytes = Buffer.from(await response.arrayBuffer())
    const name = artifact.slideId ? `${label}-${artifact.slideId}.png` : `${label}.pptx`
    await writeFile(path.join(output, name), bytes)
    files.push({ name, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'), url: artifact.url })
  }
  return { revision: document.revision, deck, files }
}
if (!evidence.original) {
  evidence.initialCompleted = await waitForGeneration(evidence.initialGeneration.id)
  evidence.original = await artifacts('original')
  assert(evidence.original.deck.slides.length >= 2, 'Whole presentation was not generated')
  await save()
}
if (!evidence.editRequest) {
  const last = evidence.original.deck.slides.at(-1)
  evidence.editRequest = {
    idempotencyKey: randomUUID(), expectedRevision: evidence.original.revision,
    text: '请仅把这一页最上方的主标题文字改为“下一步：持续改善体验”，不要改动任何其他对象、位置、讲稿或页面。完成后检查并保存。',
    scope: { kind: 'SLIDE', slideId: last.id },
  }
  await save()
}
if (!evidence.editMessage) {
  evidence.editMessage = await api(`${route}/messages`, evidence.editRequest)
  evidence.revisionGeneration = await api(`${route}/generation`)
  assert.notEqual(evidence.revisionGeneration.id, evidence.initialGeneration.id)
  await save()
}
evidence.revisionCompleted = await waitForGeneration(evidence.revisionGeneration.id)
evidence.revised = await artifacts('revised')
assert(evidence.revised.revision > evidence.original.revision)
assert.deepEqual(evidence.revised.deck.slides.slice(0, -1), evidence.original.deck.slides.slice(0, -1))
const originalLast = evidence.original.deck.slides.at(-1)
const revisedLast = evidence.revised.deck.slides.at(-1)
const revisedTitle = revisedLast.elements.find(element => element.text === '下一步：持续改善体验')
assert(revisedTitle, 'Requested title was not changed')
const expectedLast = structuredClone(originalLast)
const expectedTitle = expectedLast.elements.find(element => element.id === revisedTitle.id)
assert(expectedTitle, 'Title identity must remain stable')
expectedTitle.text = revisedTitle.text
assert.deepEqual(revisedLast, expectedLast, 'Other objects, properties, notes and page metadata must remain unchanged')
for (const original of evidence.original.files.filter(file => file.name.endsWith('.pptx'))) {
  const response = await fetch(new URL(original.url, base))
  assert(response.ok)
  assert.equal(createHash('sha256').update(Buffer.from(await response.arrayBuffer())).digest('hex'), original.sha256)
}
evidence.result = 'PASS'
evidence.url = new URL(`/ppt/${evidence.documentId}`, base).href
await save()
console.log(JSON.stringify({ result: evidence.result, url: evidence.url, output, originalRevision: evidence.original.revision, revisedRevision: evidence.revised.revision }, null, 2))
