import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from '@/api/client'
import { FoundationProvider } from '@/foundation/provider'
import { semanticName } from '@/foundation/semanticRegistry'
import { skins } from '@/themes/registry'
import { RecoveryStudioPage } from './RecoveryStudioPage'
import { deferred, pageProps, recovery, task } from '../publication/test-support'

const live: ReturnType<typeof pageProps>[] = []
beforeEach(() => { vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn(), addListener: vi.fn(), removeListener: vi.fn() })) })
afterEach(() => { cleanup(); for (const p of live) p.retire(); live.length = 0; vi.restoreAllMocks(); vi.unstubAllGlobals() })
function fixture(status: ReturnType<typeof task>['status'] = 'FAILED') { vi.spyOn(api, 'getTask').mockResolvedValue(task({ status, stages: [{ id: 'failed', ordinal: 2, objective: '修复构建', status: 'FAILED', attempts: [] }], errors: [{ id: 'err', layer: 'TASK', code: 'VERIFIER_FAILED', message: '构建未通过', retryable: true, occurredAt: '2026-10-03' }] })); const list = vi.spyOn(api, 'getTaskRecoveries').mockResolvedValue([]), create = vi.spyOn(api, 'createTaskRecovery').mockImplementation(async (_id, mode) => { const row = recovery({ mode, writableSession: mode !== 'VERIFY_ONLY' }); list.mockResolvedValue([row]); return row }); return { list, create } }
function page(strict = false) { const p = pageProps('/tasks/task/recovery'); live.push(p); const child = <FoundationProvider skin={p.props.skin} reducedMotion><RecoveryStudioPage {...p.props} /></FoundationProvider>; return { ...p, view: render(strict ? <StrictMode>{child}</StrictMode> : child) } }
describe('W4 real React recovery page', () => {
  it('renders the actual failed context and three explicit modes under root StrictMode without writes', async () => {
    const f = fixture(); page(true); await screen.findByRole('heading', { name: '恢复工作台 · 完成真实改动' }); expect(screen.getByText('阶段 2 · 修复构建')).toBeTruthy(); expect(screen.getAllByRole('radio')).toHaveLength(3); expect(screen.getByRole('link', { name: '返回父任务' }).getAttribute('href')).toBe('/tasks/task'); expect(f.create).not.toHaveBeenCalled(); expect(document.querySelectorAll('[data-react-page]')).toHaveLength(1)
  })
  it('uses the real radio/create controls and opens the derived task without starting it', async () => {
    const f = fixture(), start = vi.spyOn(api, 'startTask'), p = page(); await screen.findByRole('radio', { name: '只读验证' }); fireEvent.click(screen.getByRole('radio', { name: '只读验证' })); expect(p.leave()[0]?.kind).toBe('CONFIRM_DISCARD'); fireEvent.click(screen.getByRole('button', { name: semanticName('task.createRecovery') })); await screen.findByRole('heading', { name: '恢复草稿已创建' }); expect(f.create).toHaveBeenCalledExactlyOnceWith('task', 'VERIFY_ONLY'); expect(screen.getByText('执行权限：只读验证')).toBeTruthy(); fireEvent.click(screen.getByRole('button', { name: semanticName('task.open', '派生任务 1') })); expect(p.props.navigation.go).toHaveBeenCalledWith('/tasks/child'); expect(start).not.toHaveBeenCalled()
  })
  it('locks all three real mode controls while sending and keeps unknown status/body through all skins', async () => {
    const f = fixture(), late = deferred<ReturnType<typeof recovery>>(); f.create.mockReturnValueOnce(late.promise); const p = page(); await screen.findByRole('radio', { name: '复制全部阶段' }); fireEvent.click(screen.getByRole('radio', { name: '复制全部阶段' })); fireEvent.click(screen.getByRole('button', { name: semanticName('task.createRecovery') })); expect(screen.getByRole('group', { name: '恢复模式' }).hasAttribute('disabled')).toBe(true); await act(async () => { late.reject(new Error('lost')) }); const root = document.querySelector('[data-react-page]'); expect(document.querySelector('[data-operation-phase="UNKNOWN"]')).toBeTruthy(); for (const skin of skins) p.view.rerender(<FoundationProvider skin={skin} reducedMotion><RecoveryStudioPage {...p.props} skin={skin} /></FoundationProvider>); expect(document.querySelector('[data-react-page]')).toBe(root); expect((screen.getByRole('radio', { name: '复制全部阶段' }) as HTMLInputElement).checked).toBe(true); expect(screen.getByRole('group', { name: '恢复模式' }).hasAttribute('disabled')).toBe(true); expect(f.create).toHaveBeenCalledOnce(); expect(p.leave()[0]?.kind).toBe('BLOCK'); fireEvent.click(screen.getByRole('button', { name: semanticName('receipt.readOriginal') })); await waitFor(() => expect(f.list.mock.calls.length).toBeGreaterThan(1)); expect(f.create).toHaveBeenCalledOnce()
  })

  it('preserves the original direct workspace 409 explanation without claiming a child exists or showing the raw error code', async () => {
    const f = fixture('CANCELLED'); f.create.mockRejectedValueOnce(new ApiError('工作区指纹已经变化', 409, { code: 'RECOVERY_WORKSPACE_FINGERPRINT_MISMATCH' })); page(); await screen.findByRole('button', { name: semanticName('task.createRecovery') }); fireEvent.click(screen.getByRole('button', { name: semanticName('task.createRecovery') })); await screen.findByText('恢复未创建'); expect(screen.getByText(/工作区指纹已经变化/)).toBeTruthy(); expect(screen.queryByText('RECOVERY_WORKSPACE_FINGERPRINT_MISMATCH')).toBeNull(); expect(screen.queryByRole('heading', { name: '恢复草稿已创建' })).toBeNull(); expect(f.create).toHaveBeenCalledOnce()
  })
  it('shows lineage without exposing raw fingerprint or making the UI writable for a succeeded parent', async () => {
    const f = fixture('SUCCEEDED'); f.list.mockResolvedValue([recovery()]); page(); await screen.findByText('当前任务不可恢复'); expect(screen.queryByRole('radio')).toBeNull(); expect(screen.getByRole('button', { name: semanticName('task.open', '派生任务 1') })).toBeTruthy(); expect(screen.queryByText('frozenhash')).toBeNull(); expect(f.create).not.toHaveBeenCalled()
  })
})
