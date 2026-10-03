/** Shared test contract: the four original W0 assertions now exercise the actual React page. */
import { act, fireEvent, render, screen } from '@testing-library/react'
import { useLayoutEffect } from 'react'
import { expect, vi } from 'vitest'
import { api, type CursorPage } from '@/api/client'
import type { DesignerHistoryItem } from '@/types/domain'
import { semanticName } from '@/foundation/semanticRegistry'
import { createDesignerHistoryController } from './designerHistoryController'
import { DesignerHistoryPage } from './DesignerHistoryPage'
import { deferred, flush, pageFrame, pageProps } from './page.test-support'

export type HistoryW0Case = 'query' | 'cursor' | 'retired-success' | 'retired-error'
export async function designerHistoryW0Contract(mode: HistoryW0Case, options: {
  read: typeof api.listDesignerHistoryPage
  page: (id: string) => CursorPage<DesignerHistoryItem>
  proof?: (name: string, evidence: unknown) => void
}) {
  const pending = deferred<CursorPage<DesignerHistoryItem>>(), read = vi.mocked(options.read)
  read.mockReset()
  if (mode === 'cursor') read.mockResolvedValueOnce(options.page('A')).mockReturnValueOnce(pending.promise).mockResolvedValue(options.page('B'))
  else if (mode === 'query') read.mockReturnValueOnce(pending.promise).mockResolvedValue(options.page('B'))
  else read.mockReturnValue(pending.promise)
  const owner = createDesignerHistoryController({ api: { ...api, listDesignerHistoryPage: read, getProjects: async () => [] } })
  const props = pageProps('/designs'), retained = new Map<object, () => void>()
  props.lifecycle.retain = (key, dispose) => { if (!retained.has(key)) retained.set(key, dispose) }
  // The route bridge owns this lifetime, separately from replayable React view leases.
  // Retire at the actual root removal, BEFORE the first snapshot and any late resolution.
  function RouteLifetime() {
    useLayoutEffect(() => () => { for (const dispose of retained.values()) dispose(); retained.clear() }, [])
    return <DesignerHistoryPage {...props} controller={owner} />
  }
  const root = render(pageFrame(<RouteLifetime />))
  await act(flush)
  try {
    if (mode === 'cursor') {
      fireEvent.click(screen.getByRole('button', { name: '加载更多：历史设计' })); await act(flush)
      expect(read.mock.calls[1]?.[0]?.cursor).toBe('A-cursor')
    }
    fireEvent.change(screen.getByLabelText('搜索历史设计'), { target: { value: 'B' } }); await act(flush)
    if (mode.startsWith('retired')) {
      root.unmount()
      expect(owner.capture().isCurrent()).toBe(false)
      const before = { designs: owner.getSnapshot().rows, error: owner.getSnapshot().error, loading: owner.getSnapshot().loading }
      await act(async () => { if (mode === 'retired-success') pending.resolve(options.page('A')); else pending.reject(new Error('A retired read failed')); await flush(); await vi.advanceTimersByTimeAsync(200) })
      const after = { designs: owner.getSnapshot().rows, error: owner.getSnapshot().error, loading: owner.getSnapshot().loading }
      options.proof?.(`B8.1/${mode}`, { before, after, readCalls: read.mock.calls.length })
      expect.soft(after.designs).toEqual(before.designs); expect.soft(after.error).toBe(before.error); expect.soft(after.loading).toBe(before.loading); expect(read).toHaveBeenCalledTimes(1)
    } else {
      await act(async () => { await vi.advanceTimersByTimeAsync(180); await flush() })
      const second = read.mock.calls[mode === 'cursor' ? 2 : 1]?.[0]
      expect(second?.q).toBe('B'); expect(owner.getSnapshot().rows[0]?.id).toBe('B')
      await act(async () => { pending.resolve(options.page(mode === 'cursor' ? 'A-next' : 'A')); await flush() })
      const s = owner.getSnapshot()
      options.proof?.(`B8.1/${mode}`, { requests: read.mock.calls, list: s.rows.map(row => row.id), facets: s.facets, cursor: s.cursor })
      expect.soft(s.rows.map(row => row.id)).toEqual(['B']); expect.soft(s.facets.ARCHIVED_TOTAL).toBe(7); expect.soft(s.cursor).toBe('B-cursor')
      expect(screen.getByRole('button', { name: semanticName('selection.select', options.page('B').items[0]!.goal || '未命名设计') })).toBeTruthy()
      expect(screen.queryByRole('button', { name: semanticName('selection.select', options.page('A').items[0]!.goal || '未命名设计') })).toBeNull()
    }
  } finally { root.unmount(); owner.retire(true) }
}
