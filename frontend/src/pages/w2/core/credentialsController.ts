import { api } from '@/api/client'
import type { GitCredentialInput, GitCredentialView } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import { createCoreOwner, initialCoreState, type CoreState } from './owner'

export interface CredentialsState extends CoreState {
  saved?: GitCredentialView
  mode: 'CUSTOM' | 'INHERIT'
  serverUrl: string
  username: string
  secret: string
  repositoryUrl: string
  kind: 'TOKEN' | 'PASSWORD'
  loading: boolean
  testing: boolean
  probe?: { success: boolean; message: string }
}
export function createCredentialsController(projectId: string | undefined, demo: boolean | (() => boolean), client = api) {
  const isDemo = () => typeof demo === 'function' ? demo() : demo
  const owner = createCoreOwner<CredentialsState>(`git-credentials:${projectId ?? 'global'}`, { ...initialCoreState(), mode: projectId ? 'INHERIT' : 'CUSTOM',
    serverUrl: '', username: '', secret: '', repositoryUrl: '', kind: 'TOKEN', loading: false, testing: false },
  { extraLeave: state => state.loading || state.testing ? { kind: 'BLOCK', reason: 'Git 账号操作正在处理，请先等待。', recoveryAction: '等待处理完成' } : undefined })
  let initializing: Promise<void> | undefined, loaded = false, generation = 0
  function accept(saved: Readonly<GitCredentialView>) {
    owner.set({ saved: saved as GitCredentialView, mode: projectId && saved.mode === 'INHERIT' ? 'INHERIT' : 'CUSTOM',
      serverUrl: saved.serverUrl, username: saved.username, kind: saved.kind, secret: '', dirty: false, probe: undefined })
  }
  async function load() {
    if (isDemo() || owner.locked()) return
    const token = owner.capture(), current = ++generation
    owner.set({ loading: true, error: '', message: '' })
    try { const value = await client.gitCredentials(projectId); if (token.isCurrent() && current === generation) { accept(value); loaded = true } }
    catch (error) { if (token.isCurrent() && current === generation) owner.set({ error: userFacingError(error, 'Git 账号读取失败，请重新加载') }) }
    finally { if (token.isCurrent() && current === generation) owner.set({ loading: false }) }
  }
  function initialize() {
    if (loaded) return Promise.resolve()
    return initializing ??= load().finally(() => { initializing = undefined })
  }
  function change<K extends 'mode' | 'serverUrl' | 'username' | 'secret' | 'kind' | 'repositoryUrl'>(field: K, value: CredentialsState[K]) {
    if (owner.locked()) return
    generation++
    owner.edit({ [field]: value, probe: undefined, message: '' } as Partial<CredentialsState>)
  }
  function input(disable: boolean): GitCredentialInput {
    const state = owner.getSnapshot()
    return { mode: disable ? 'DISABLED' : state.mode, serverUrl: state.serverUrl.trim(), username: state.username.trim(), kind: state.kind,
      secret: disable ? undefined : state.secret || undefined, version: state.saved?.version ?? 0, repositoryUrl: state.repositoryUrl.trim() || undefined }
  }
  async function save(disable = false) {
    if (isDemo() || owner.locked() || owner.getSnapshot().loading || owner.getSnapshot().testing) return false
    const body = input(disable)
    return owner.mutate({ endpoint: `/git-credentials${projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''}`, method: 'PUT', body,
      versions: { version: body.version! } }, { label: '保存 Git 账号', write: original => client.saveGitCredentials(projectId, original as GitCredentialInput),
      // The read DTO never contains a secret. A changed secret cannot be verified by metadata/version alone.
      lookup: body.secret ? undefined : async original => {
        const found = await client.gitCredentials(projectId)
        const matches = found.version > (original.version ?? 0) && found.mode === original.mode
          && (original.mode !== 'CUSTOM' || (found.serverUrl === original.serverUrl && found.username === original.username && found.kind === original.kind))
        return matches ? { kind: 'ACCEPTED', receipt: found } : { kind: 'UNCONFIRMED' }
      },
      read: (value, apply) => { apply(() => { accept(value); owner.set({ error: '', message: disable ? '全局 Git 账号已停用' : 'Git 账号已保存，将用于后续 Git 远程操作' }) }) },
    })
  }
  async function test() {
    if (isDemo() || owner.locked() || owner.getSnapshot().loading || owner.getSnapshot().testing) return
    const token = owner.capture(), current = generation, body = input(false)
    owner.set({ testing: true, error: '', probe: undefined })
    try { const probe = await client.testGitCredentials(projectId, body); if (token.isCurrent() && current === generation) owner.set({ probe }) }
    catch (error) { if (token.isCurrent() && current === generation) owner.set({ error: userFacingError(error, 'Git 连接验证失败，请检查账号和仓库地址') }) }
    finally { if (token.isCurrent()) owner.set({ testing: false }) }
  }
  return { ...owner, initialize, load, change, save, test, projectId, get demo() { return isDemo() },
    retire(forced = false) {
      if (!forced && owner.canLeave().kind !== 'ALLOW') return owner.canLeave()
      owner.set({ secret: '', probe: undefined }); generation++
      return owner.retire(forced)
    },
  }
}
