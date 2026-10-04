import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { workflowRuns } from '@/api/workflowRuns'
import { workflowApi } from '@/api/workflow'
import { FixedInputContent, AttemptFiles, AttemptKnowledge } from './Content'
import { RequirementChoice } from './Choice'
import { requirementFrame, requirementFixture, setupRequirementDom, pushPreview } from './test-support'
import { workflowPublication } from '@/api/workflowPublication'
import { workflowPush } from '@/api/workflowPush'
import { PublicationPanel } from './Publication'
import { createPublicationController } from './publicationController'
import { semanticName } from '@/foundation/semanticRegistry'
const all: ReturnType<typeof requirementFixture>[] = []
const fixture = () => { const value = requirementFixture(); all.push(value); return value }
const scope = { requirement: 'req', node: 'node', attempt: 'attempt', direction: 'inputs' as const, name: 'value' }
beforeEach(() => { setupRequirementDom(); vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('unmocked transport forbidden'))) })
afterEach(() => { cleanup(); all.splice(0).forEach(value => value.dispose()); vi.restoreAllMocks(); vi.unstubAllGlobals() })
describe('real on-demand React fixed content and picker', () => {
  it('StrictMode fixed reference defers download until explicit action, exact cursor concatenation and safe structured render', async () => {
    const f = fixture(), read = vi.spyOn(workflowRuns, 'inputContent').mockResolvedValueOnce({ name: 'value', kind: 'JSON', sha256: 'sha', text: '{"text":', offset: 0, totalLength: 29, nextOffset: 8 }).mockResolvedValue({ name: 'value', kind: 'JSON', sha256: 'sha', text: '"<img src=x>文字"}', offset: 8, totalLength: 29, nextOffset: null })
    const firstText = '{"text":', lastText = '"<img src=x>文字"}', total = firstText.length + lastText.length
    read.mockReset().mockResolvedValueOnce({ name: 'value', kind: 'JSON', sha256: 'sha', text: firstText, offset: 0, totalLength: total, nextOffset: firstText.length }).mockResolvedValue({ name: 'value', kind: 'JSON', sha256: 'sha', text: lastText, offset: firstText.length, totalLength: total, nextOffset: null })
    render(<StrictMode>{requirementFrame(<FixedInputContent page={f.props} scope={scope} input={{ name: 'value', source: 'REQUIREMENT', sourceId: 'value', outputName: null, attemptId: null, kind: 'JSON', sha256: 'sha', content: null, reference: { version: 1, contentSha256: 'sha', sizeBytes: total } }} />)}</StrictMode>)
    expect(read).not.toHaveBeenCalled(); fireEvent.click(screen.getByRole('button', { name: semanticName('workflow.inputContent') })); await screen.findByText(`正文尚未读完，已读取 ${firstText.length} / ${total} 字符。`)
    expect(document.querySelector('[data-code-renderer]')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: semanticName('ui.loadMore', '固定输入正文') })); await waitFor(() => expect(document.querySelector('[data-code-renderer="react-lezer"]')).toBeTruthy())
    expect(read.mock.calls.map(call => call[4])).toEqual([0, firstText.length]); expect(document.querySelector('img')).toBeNull(); expect(screen.getByRole('textbox').textContent).toContain('<img src=x>文字')
  })
  it('StrictMode picker reads actual project list and selecting a row invokes only its explicit callback', async () => {
    const f = fixture(), guard = vi.spyOn(f.props.navigation, 'registerGuard'), selected = vi.fn(), template = vi.spyOn(workflowApi, 'get'), read = vi.spyOn(workflowRuns, 'projects').mockResolvedValue({ items: [{ id: 'p', name: '明确项目', createdAt: '' }], nextCursor: undefined, facets: {} }), write = vi.spyOn(workflowRuns, 'create')
    render(<StrictMode>{requirementFrame(<RequirementChoice page={f.props} kind="project" onSelect={selected} />)}</StrictMode>); await screen.findByText('明确项目'); expect(guard).toHaveBeenCalledTimes(2); expect(f.guards.size).toBe(1); expect(read).toHaveBeenCalledWith('', ''); fireEvent.click(screen.getByRole('button', { name: semanticName('selection.select', '明确项目') })); expect(selected).toHaveBeenCalledWith({ id: 'p', name: '明确项目', createdAt: '' }); expect(write).not.toHaveBeenCalled(); expect(template).not.toHaveBeenCalled()
  })
  it('native fixed file links preserve complete owner tuple and excluded files have no download/preview', async () => {
    const f = fixture(); vi.spyOn(workflowRuns, 'files').mockResolvedValue({ items: [{ path: 'original.md', sizeBytes: 10, sha256: 'sha', mode: null }, { path: 'excluded.md', sizeBytes: 1, sha256: null, blobSha: null, exclusion: '未采集', mode: null }], nextCursor: null })
    render(requirementFrame(<AttemptFiles page={f.props} scope={scope} archive />)); expect(document.querySelector('a[download]')?.getAttribute('href')).toBe(workflowRuns.archiveUrl('req', 'node', 'attempt', 'inputs', 'value'))
    fireEvent.click(screen.getByRole('button', { name: semanticName('workflow.fixedFiles') })); await screen.findByText('original.md'); expect(screen.getByRole('link', { name: 'original.md' }).getAttribute('href')).toBe(workflowRuns.fileUrl('req', 'node', 'attempt', 'inputs', 'value', 'original.md')); expect(screen.queryByRole('link', { name: 'excluded.md' })).toBeNull(); expect(screen.queryByRole('button', { name: semanticName('workflow.previewDocument', 'excluded.md') })).toBeNull()
  })
})

it('StrictMode knowledge disclosure launches one current-view read and pagination remains exactly one explicit next read', async () => {
  const f = fixture(), entry = { id: 'entry', toolName: 'read_knowledge_source', createdAt: '2026-09-29' }
  const read = vi.spyOn(workflowRuns, 'knowledgeEvidence').mockResolvedValueOnce({ items: [entry], nextCursor: 'next' }).mockResolvedValue({ items: [{ ...entry, id: 'next' }], nextCursor: null })
  const view = render(<StrictMode>{requirementFrame(<AttemptKnowledge page={f.props} scope={scope} />)}</StrictMode>)
  await waitFor(() => expect(view.container.querySelectorAll('li')).toHaveLength(1))
  expect(read).toHaveBeenCalledTimes(1)
  expect(read.mock.calls[0]).toEqual(['req', 'node', 'attempt', ''])
  fireEvent.click(screen.getByRole('button', { name: semanticName('ui.loadMore', '检索证据') }))
  await waitFor(() => expect(view.container.querySelectorAll('li')).toHaveLength(2))
  expect(read).toHaveBeenCalledTimes(2); expect(read.mock.calls[1]).toEqual(['req', 'node', 'attempt', 'next'])
  view.unmount()
  await Promise.resolve(); expect(read).toHaveBeenCalledTimes(2)
})
it('verified committed publication with a successful empty push read explicitly says it has not been pushed without writing', async () => {
  const f = fixture(), owner = createPublicationController('req', 2)
  const read = vi.spyOn(workflowPublication, 'status').mockResolvedValue({ requirementId: 'req', state: 'COMMITTED', version: 3, nodeTitle: '交付节点', outputTitle: '代码', attemptState: 'SUCCEEDED', branch: 'results', message: '明确提交', commit: 'commit', createdAt: '', reasonCode: null })
  const push = vi.spyOn(workflowPush, 'status').mockResolvedValue(null), write = vi.spyOn(workflowPush, 'confirm')
  render(requirementFrame(<PublicationPanel page={f.props} controller={owner} revision={2} visible onClose={() => {}} />))
  await owner.readStatus('commit'); await owner.readStatus('push')
  expect(await screen.findByText('尚未推送到远端')).toBeTruthy()
  expect(read).toHaveBeenCalledWith('req', expect.any(AbortSignal)); expect(push).toHaveBeenCalledWith('req', expect.any(AbortSignal)); expect(write).not.toHaveBeenCalled()
})
it('an opened push draft requires explicit Stay and an UNKNOWN push cannot present the prior empty GET as not pushed', async () => {
  const f = fixture(), owner = createPublicationController('req', 2), close = vi.fn()
  vi.spyOn(workflowPublication, 'status').mockResolvedValue({ requirementId: 'req', state: 'COMMITTED', version: 3, nodeTitle: '交付节点', outputTitle: '代码', attemptState: 'SUCCEEDED', branch: 'results', message: '明确提交', commit: 'commit', createdAt: '', reasonCode: null })
  vi.spyOn(workflowPush, 'status').mockResolvedValue(null)
  vi.spyOn(workflowPush, 'remotes').mockResolvedValue(['origin'])
  vi.spyOn(workflowPush, 'preview').mockResolvedValue(pushPreview)
  const write = vi.spyOn(workflowPush, 'confirm').mockRejectedValue(new Error('原推送回执未知'))
  render(requirementFrame(<PublicationPanel page={f.props} controller={owner} revision={2} visible onClose={close} />))
  await owner.readStatus('commit'); await owner.readStatus('push')
  expect(await screen.findByText('尚未推送到远端')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: semanticName('workflow.push') }))
  await screen.findByRole('option', { name: 'origin' })
  expect(owner.canLeave().kind).toBe('CONFIRM_DISCARD')
  fireEvent.click(screen.getByRole('button', { name: semanticName('ui.close', '需求代码成果') }))
  await screen.findByRole('dialog', { name: '放弃当前修改？' })
  fireEvent.click(screen.getByRole('button', { name: semanticName('ui.stay') }))
  expect(close).not.toHaveBeenCalled(); expect(owner.getSnapshot().pushOpen).toBe(true)
  expect(write).not.toHaveBeenCalled()
  fireEvent.change(screen.getByRole('combobox', { name: '推送远端' }), { target: { value: 'origin' } })
  fireEvent.click(screen.getByRole('button', { name: semanticName('workflow.inspectPush') }))
  fireEvent.click(await screen.findByRole('button', { name: semanticName('workflow.pushConfirm') }))
  await waitFor(() => expect(owner.getSnapshot().command.phase).toBe('UNKNOWN'))
  expect(owner.canLeave().kind).toBe('BLOCK'); expect(write).toHaveBeenCalledTimes(1)
  expect(screen.queryByText('尚未推送到远端')).toBeNull()
  expect(screen.getByRole('button', { name: semanticName('ui.close', '需求代码成果') }).matches(':disabled')).toBe(true)
})
