import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PptJob } from '@/types/domain'
import { pptDeck } from '@/components/ppt/pptTestFixtures'
import { PptSlideNavigatorView } from './PptSlideNavigatorView'
import { slidePreviews } from './previews'

afterEach(cleanup)

function job(revision: number, slideId: string, artifactId: string, kind: PptJob['kind'] = 'PREVIEW'): PptJob {
  return {
    id: `job-${artifactId}`, documentId: 'doc', kind, revision, state: 'COMPLETED',
    completed: 1, total: 1, detail: '', createdAt: '2026-10-03T00:00:00Z',
    artifacts: [{ id: artifactId, slideId, name: 'preview.png', mediaType: 'image/png', url: '' }],
  }
}

describe('React PPT slide navigator', () => {
  it('uses only PNG previews for the requested revision and retains the first matching artifact', () => {
    const jobs = [
      job(2, 'slide-1', 'old'), job(3, 'slide-1', 'current'), job(3, 'slide-1', 'duplicate'),
      job(4, 'slide-2', 'future'), job(3, 'slide-2', 'export', 'EXPORT'),
    ]
    const other = job(3, 'slide-2', 'unsupported')
    other.artifacts[0]!.mediaType = 'application/pdf'
    jobs.push(other)
    const previews = slidePreviews(jobs, 3, id => `/artifacts/${id}`)
    const deck = pptDeck()
    const { container } = render(<PptSlideNavigatorView deck={deck} selected="slide-1" previews={previews} onSelect={vi.fn()} onAdd={vi.fn()} />)
    expect(container.querySelector('[data-canvas-kind="ppt-navigator"]')?.getAttribute('data-canvas-runtime')).toBe('react')
    const first = screen.getByRole('button', { name: '第 1 页：核心成果' })
    expect(first.getAttribute('aria-current')).toBe('page')
    expect(first.querySelector('img')?.getAttribute('src')).toBe('/artifacts/current')
    expect(first.querySelector('img')?.getAttribute('loading')).toBe('lazy')
    const second = screen.getByRole('button', { name: '第 2 页：下一步计划' })
    expect(second.querySelector('img')).toBeNull()
    expect(second.textContent).toContain('下一步计划')
    expect(second.querySelector('[aria-label="已锁定"]')).toBeTruthy()
  })

  it('allows readonly slide selection while disabling page insertion and marks the latest selection', () => {
    const deck = pptDeck()
    const onSelect = vi.fn()
    const onAdd = vi.fn()
    const props = { deck, selected: 'slide-1', previews: new Map<string, string>(), manual: true, disabled: true, onSelect, onAdd }
    const { rerender } = render(<PptSlideNavigatorView {...props} />)
    fireEvent.click(screen.getByRole('button', { name: '第 2 页：下一步计划' }))
    expect(onSelect).toHaveBeenCalledExactlyOnceWith('slide-2')
    fireEvent.click(screen.getByRole('button', { name: '新增页面' }))
    expect(onAdd).not.toHaveBeenCalled()
    rerender(<PptSlideNavigatorView {...props} selected="slide-2" disabled={false} />)
    expect(screen.getByRole('button', { name: '第 2 页：下一步计划' }).getAttribute('aria-current')).toBe('page')
    expect(screen.getByRole('button', { name: '第 1 页：核心成果' }).getAttribute('aria-current')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '新增页面' }))
    expect(onAdd).toHaveBeenCalledOnce()
  })

  it('hides insertion outside manual mode and displays a fallback title for unnamed slides', () => {
    const deck = pptDeck()
    deck.slides[0]!.title = ''
    render(<PptSlideNavigatorView deck={deck} selected="slide-1" previews={new Map()} onSelect={vi.fn()} onAdd={vi.fn()} />)
    expect(screen.queryByRole('button', { name: '新增页面' })).toBeNull()
    expect(screen.getByText('未命名页面')).toBeTruthy()
  })
})
