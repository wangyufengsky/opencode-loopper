import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { workflowRuns } from '@/api/workflowRuns'
import { workflowApi } from '@/api/workflow'
import { FixedInputContent, AttemptFiles } from './Content'
import { RequirementChoice } from './Choice'
import { requirementFrame, requirementFixture, setupRequirementDom } from './test-support'
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
