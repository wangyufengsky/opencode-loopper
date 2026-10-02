import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, type EffectScope } from 'vue'
import { api } from '@/api/client'
import type { AppSettings } from '@/types/domain'
import { useWorkflowModel } from './modelChoice'

vi.mock('@/api/client', () => ({ api: { getSettings: vi.fn(), getSettingsModels: vi.fn() } }))
const settings = (name = 'default') => ({ openCode: { provider: 'configured', model: name } } as AppSettings)
let scope: EffectScope
const create = () => { scope = effectScope(); return scope.run(useWorkflowModel)! }
beforeEach(() => { vi.resetAllMocks(); vi.mocked(api.getSettings).mockResolvedValue(settings()) })
afterEach(() => scope?.stop())

describe('requirement execution model selection', () => {
  it('initializes once without mounting a panel or enumerating model choices', async () => {
    const choice = create(); await Promise.all([choice.initialize(), choice.initialize()]); await choice.initialize()
    expect(choice.model.value).toEqual({ providerId: 'configured', modelId: 'default', thinking: null })
    expect(api.getSettings).toHaveBeenCalledTimes(1); expect(api.getSettingsModels).not.toHaveBeenCalled()
  })
  it('prefers a persisted control model and preserves deliberate user choices', async () => {
    const choice = create(), persisted = { providerId: 'saved', modelId: 'version', thinking: true }
    choice.adoptControl(persisted); await choice.initialize(); expect(api.getSettings).not.toHaveBeenCalled(); expect(choice.model.value).toEqual(persisted)
    choice.choose({ ...persisted, modelId: 'manual' }); choice.adoptControl(persisted); expect(choice.model.value?.modelId).toBe('manual')
  })
  it('does not overwrite manual or persisted choices with late default responses', async () => {
    let resolve!: (value: AppSettings) => void
    vi.mocked(api.getSettings).mockImplementation(() => new Promise(done => { resolve = done }))
    const choice = create(), first = choice.initialize(); choice.choose({ providerId: 'user', modelId: 'chosen', thinking: null })
    resolve(settings()); await first; expect(choice.model.value?.modelId).toBe('chosen'); expect(choice.loading.value).toBe(false)
    choice.reset(); const second = choice.initialize(); choice.adoptControl({ providerId: 'control', modelId: 'frozen', thinking: null })
    resolve(settings()); await second; expect(choice.model.value?.modelId).toBe('frozen')
  })
  it('ignores old requirement responses and disposal without disturbing the new request', async () => {
    const pending: Array<(value: AppSettings) => void> = []
    vi.mocked(api.getSettings).mockImplementation(() => new Promise(done => pending.push(done)))
    const choice = create(), old = choice.initialize(); choice.reset(); const current = choice.initialize()
    pending[0]!(settings('old')); await old; expect(choice.model.value).toBeNull(); expect(choice.loading.value).toBe(true)
    pending[1]!(settings('current')); await current; expect(choice.model.value?.modelId).toBe('current')
    choice.reset(); const disposed = choice.initialize(); scope.stop(); pending[2]!(settings('disposed')); await disposed; expect(choice.model.value).toBeNull()
  })
  it('keeps a recoverable error for absent defaults or failed settings and retries only explicitly', async () => {
    vi.mocked(api.getSettings).mockResolvedValueOnce(settings('')).mockRejectedValueOnce(new Error('offline')).mockResolvedValue(settings('recovered'))
    const choice = create(); await choice.initialize(); expect(choice.error.value).toContain('尚未配置'); await choice.initialize(); expect(api.getSettings).toHaveBeenCalledTimes(1)
    await choice.loadDefault(); expect(choice.error.value).toContain('暂时无法读取'); await choice.loadDefault()
    expect(choice.model.value?.modelId).toBe('recovered'); expect(choice.error.value).toBe(''); expect(api.getSettingsModels).not.toHaveBeenCalled()
  })
})
