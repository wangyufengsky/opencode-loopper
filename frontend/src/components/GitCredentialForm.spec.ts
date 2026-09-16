import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import GitCredentialForm from './GitCredentialForm.vue'
import type { GitCredentialView } from '@/types/domain'

const global: GitCredentialView = { mode: 'CUSTOM', serverUrl: 'https://gitlab.example', username: 'shared', kind: 'TOKEN', configured: true, source: 'GLOBAL', version: 2, updatedAt: null }
const render = (projectId?: string) => mount(GitCredentialForm, { props: { projectId } })
const button = (wrapper: ReturnType<typeof render>, label: string) => wrapper.findAll('button').find(b => b.text() === label)!
afterEach(() => vi.restoreAllMocks())
describe('Git credentials', () => {
  it('loads the global account without a password and preserves it when left empty', async () => {
    vi.spyOn(api, 'gitCredentials').mockResolvedValue(global)
    const save = vi.spyOn(api, 'saveGitCredentials').mockResolvedValue({ ...global, version: 3 })
    const wrapper = render(); await flushPromises()
    expect(wrapper.get('input[aria-label="Git 密码或令牌"]').element).toHaveProperty('value', '')
    await button(wrapper, '保存 Git 账号').trigger('click'); await flushPromises()
    expect(save).toHaveBeenCalledWith(undefined, expect.objectContaining({ mode: 'CUSTOM', secret: undefined, version: 2 }))
    expect(wrapper.text()).toContain('Git 账号已保存')
    wrapper.unmount()
  })
  it('inherits the global account by default and allows an independent project account', async () => {
    vi.spyOn(api, 'gitCredentials').mockResolvedValue({ ...global, mode: 'INHERIT', version: 0 })
    const save = vi.spyOn(api, 'saveGitCredentials').mockResolvedValue({ ...global, username: 'own', source: 'PROJECT', version: 1 })
    const wrapper = render('project-1'); await flushPromises()
    expect(wrapper.text()).toContain('全局账号：shared')
    expect(wrapper.find('input[aria-label="Git 密码或令牌"]').exists()).toBe(false)
    await wrapper.get('input[value="CUSTOM"]').setValue(true)
    await wrapper.get('input[aria-label="Git 用户名"]').setValue('own')
    await wrapper.get('input[aria-label="Git 密码或令牌"]').setValue('synthetic-test-token')
    await button(wrapper, '保存 Git 账号').trigger('click'); await flushPromises()
    expect(save).toHaveBeenCalledWith('project-1', expect.objectContaining({ mode: 'CUSTOM', username: 'own', secret: 'synthetic-test-token' }))
    expect(wrapper.get('input[aria-label="Git 密码或令牌"]').element).toHaveProperty('value', '')
    wrapper.unmount()
  })
  it('tests an unsaved draft without saving and clears stale verification when input changes', async () => {
    vi.spyOn(api, 'gitCredentials').mockResolvedValue({ ...global, configured: false, version: 0 })
    const save = vi.spyOn(api, 'saveGitCredentials')
    const test = vi.spyOn(api, 'testGitCredentials').mockResolvedValue({ success: true, message: '连接成功' })
    const wrapper = render(); await flushPromises()
    await wrapper.get('input[aria-label="Git 密码或令牌"]').setValue('draft-fixture')
    await wrapper.get('input[aria-label="Git 验证仓库地址"]').setValue('https://gitlab.example/group/a.git')
    await button(wrapper, '验证连接').trigger('click'); await flushPromises()
    expect(test).toHaveBeenCalledWith(undefined, expect.objectContaining({ secret: 'draft-fixture', repositoryUrl: 'https://gitlab.example/group/a.git' }))
    expect(save).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('连接成功')
    await wrapper.get('input[aria-label="Git 用户名"]').setValue('changed')
    expect(wrapper.text()).not.toContain('连接成功')
    wrapper.unmount()
  })
  it('shows a failed read-only connection result without claiming the account was saved', async () => {
    vi.spyOn(api, 'gitCredentials').mockResolvedValue(global)
    vi.spyOn(api, 'testGitCredentials').mockResolvedValue({ success: false, message: 'Git 认证失败，请检查账号' })
    const wrapper = render(); await flushPromises()
    await button(wrapper, '验证连接').trigger('click'); await flushPromises()
    expect(wrapper.find('el-alert').exists()).toBe(false)
    expect(wrapper.get('.el-alert__title').text()).toContain('Git 认证失败'); expect(wrapper.text()).not.toContain('Git 账号已保存')
    wrapper.unmount()
  })
})
