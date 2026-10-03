import { api } from '@/api/client'
import type { AppSettings, AvailableModel } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import { createCoreOwner, initialCoreState, sameDto, type CoreState } from './owner'

export type SettingsSection = 'canvas' | 'runtime' | 'models' | 'limits' | 'retry' | 'git-credentials' | 'publication' | 'demo'
export function defaultSettings(): AppSettings {
  return {
    runtime: { serverPort: 8080, openBrowser: true, allowedRoot: '', monitorDelaySeconds: 2, designerMonitorDelayMillis: 750, abortCleanupAttempts: 3 },
    openCode: { cliPath: 'opencode', mode: 'managed', baseUrl: 'http://127.0.0.1:4096', provider: '', model: '', connectTimeoutSeconds: 5, requestTimeoutSeconds: 30, startupTimeoutSeconds: 15 },
    limits: { templateAnalysisConcurrency: 4, timeoutEnabled: false, maxStageAttempts: 3, maxTaskAttempts: 12, sessionErrorLimit: 3, maxDurationMinutes: 120, attemptTimeoutMinutes: 30, verifierTimeoutMinutes: 10, designerTimeoutMinutes: 30 },
    retryWait: { rateLimitBaseSeconds: 60, rateLimitMaxSeconds: 300, sessionBaseSeconds: 10, sessionMaxSeconds: 60, verificationBaseSeconds: 5, verificationMaxSeconds: 30 },
    publication: { httpWebHosts: ['gitlab.spdb.com'], gitlabHost: 'gitlab.spdb.com', gitlabApiBaseUrl: 'http://gitlab.spdb.com/api/v4', connectTimeoutSeconds: 3, requestTimeoutSeconds: 10 },
    appliedLiveFields: [], restartRequiredFields: [],
  }
}
export function settingsWritable(value: Readonly<AppSettings>) {
  return { runtime: value.runtime, openCode: value.openCode, limits: { ...value.limits, timeoutEnabled: value.limits.timeoutEnabled ?? false,
    templateAnalysisConcurrency: value.limits.templateAnalysisConcurrency ?? 4 }, retryWait: value.retryWait, publication: value.publication }
}
export interface SettingsState extends CoreState {
  settings: AppSettings
  hosts: string
  models: AvailableModel[]
  loading: boolean
  refreshingModels: boolean
  switchingDemo: boolean
  section: SettingsSection
  fieldError: string
  modelError: string
}
export function createSettingsController(port: { refreshRuntime(): Promise<unknown>; activateDemo(): void; deactivateDemo(): Promise<unknown>; getSnapshot(): { usingDemo: boolean } }, client = api) {
  const owner = createCoreOwner<SettingsState>('settings', { ...initialCoreState(), settings: defaultSettings(), hosts: 'gitlab.spdb.com',
    models: [], loading: true, refreshingModels: false, switchingDemo: false, section: 'runtime', fieldError: '', modelError: '' },
  { extraLeave: state => state.switchingDemo ? { kind: 'BLOCK', reason: '正在切换数据来源，请先等待。', recoveryAction: '等待切换完成' } : undefined })
  let loaded = false, initializing: Promise<void> | undefined, modelGeneration = 0
  async function refreshModels(fallback = true) {
    if (owner.locked()) return
    const current = ++modelGeneration, token = owner.capture(), cliPath = owner.getSnapshot().settings.openCode.cliPath.trim()
    owner.set({ refreshingModels: true, modelError: '' })
    try {
      const models = await client.getSettingsModels(cliPath)
      if (!token.isCurrent() || owner.locked() || current !== modelGeneration || cliPath !== owner.getSnapshot().settings.openCode.cliPath.trim()) return
      const state = owner.getSnapshot(), settings = structuredClone(state.settings)
      const providers = [...new Set(models.map(item => item.provider))].sort()
      if (fallback && !providers.includes(settings.openCode.provider)) settings.openCode.provider = providers[0] ?? ''
      const providerModels = models.filter(item => item.provider === settings.openCode.provider)
      if (fallback && !providerModels.some(item => item.model === settings.openCode.model)) settings.openCode.model = providerModels[0]?.model ?? ''
      const changed = !sameDto(settingsWritable(settings), settingsWritable(state.settings))
      owner.set({ models, settings, dirty: state.dirty || changed, draftRevision: state.draftRevision + (changed ? 1 : 0) })
    } catch (error) { if (token.isCurrent() && current === modelGeneration) owner.set({ models: [], modelError: userFacingError(error, '模型读取失败，请检查配置') }) }
    finally { if (token.isCurrent() && current === modelGeneration) owner.set({ refreshingModels: false }) }
  }
  function initialize() {
    if (initializing) return initializing
    if (loaded) return Promise.resolve()
    const token = owner.capture()
    initializing = (async () => {
      try {
        const settings = await client.getSettings()
        if (!token.isCurrent()) return
        owner.set({ settings, hosts: settings.publication.httpWebHosts.join(', '), dirty: false })
        await refreshModels(); loaded = true
      } catch (error) { if (token.isCurrent()) owner.set({ error: userFacingError(error, '设置读取失败') }) }
      finally { if (token.isCurrent()) owner.set({ loading: false }); initializing = undefined }
    })()
    return initializing
  }
  function change<G extends 'runtime' | 'openCode' | 'limits' | 'retryWait' | 'publication', K extends keyof AppSettings[G]>(group: G, key: K, value: AppSettings[G][K]) {
    if (owner.locked()) return
    const settings = structuredClone(owner.getSnapshot().settings)
    settings[group][key] = value
    if (group === 'openCode' && key === 'provider') {
      const models = owner.getSnapshot().models.filter(item => item.provider === value)
      if (models.length && !models.some(item => item.model === settings.openCode.model)) settings.openCode.model = models[0]!.model
    }
    if (group === 'openCode' && key === 'cliPath') { modelGeneration++; owner.set({ refreshingModels: false, models: [] }) }
    owner.edit({ settings })
  }
  async function save() {
    if (owner.locked() || owner.getSnapshot().loading || owner.getSnapshot().refreshingModels) return false
    const state = owner.getSnapshot(), body = structuredClone(state.settings)
    owner.set({ fieldError: '', modelError: '' })
    if (!body.openCode.cliPath.trim()) { owner.set({ modelError: 'OpenCode CLI 路径不能为空。', section: 'models' }); return false }
    if (body.runtime.allowedRoot.trim() && !/^(\/|[A-Za-z]:[\\/]|\\\\[^\\]+\\[^\\]+)/.test(body.runtime.allowedRoot.trim())) {
      owner.set({ fieldError: '允许项目根必须是绝对路径。', section: 'runtime' }); return false
    }
    if (!state.models.some(item => item.provider === body.openCode.provider && item.model === body.openCode.model)) {
      owner.set({ modelError: '请先刷新并选择一个可用模型。', section: 'models' }); return false
    }
    body.publication.httpWebHosts = state.hosts.split(',').map(value => value.trim()).filter(Boolean)
    return owner.mutate({ endpoint: '/settings', method: 'PUT', body }, { label: '保存设置',
      write: original => client.updateSettings(original as AppSettings),
      lookup: async original => {
        const found = await client.getSettings()
        return sameDto(settingsWritable(original), settingsWritable(found)) ? { kind: 'ACCEPTED', receipt: found } : { kind: 'UNCONFIRMED' }
      },
      read: async (settings, apply) => {
        await port.refreshRuntime()
        apply(() => owner.set({ settings: settings as AppSettings, hosts: settings.publication.httpWebHosts.join(', '), dirty: false,
          error: '', message: '设置已保存；运行项立即生效，启动项将在下次启动生效。' }))
      },
    })
  }
  async function toggleDemo() {
    if (owner.locked() || owner.getSnapshot().switchingDemo) return
    const token = owner.capture(); owner.set({ switchingDemo: true, error: '' })
    try { if (port.getSnapshot().usingDemo) await port.deactivateDemo(); else port.activateDemo() }
    catch (error) { if (token.isCurrent()) owner.set({ error: userFacingError(error, '演示数据切换失败') }) }
    finally { if (token.isCurrent()) owner.set({ switchingDemo: false }) }
  }
  return { ...owner, initialize, refreshModels, change, save, toggleDemo,
    setSection: (section: SettingsSection) => owner.set({ section }), setHosts: (hosts: string) => owner.edit({ hosts }) }
}
