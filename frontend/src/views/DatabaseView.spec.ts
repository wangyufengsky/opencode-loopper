import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import DatabaseView from './DatabaseView.vue'
afterEach(() => vi.restoreAllMocks())
describe('database management', () => {
  it('distinguishes installed driver, connection check and pending vendor acceptance', async () => {
    vi.spyOn(api, 'getProjects').mockResolvedValue([])
    vi.spyOn(api, 'getDatabaseDrivers').mockResolvedValue([{ filename: 'vendor.jar', sizeBytes: 100, sha256: 'a'.repeat(64) }])
    vi.spyOn(api, 'getDatabaseConnections').mockResolvedValue({ items: [{ id: 'c', name: '只读业务库', config: { type: 'GAUSSDB', host: 'intranet', port: 5432, database: 'app', username: 'reader', driverFile: 'vendor.jar', driverClass: 'vendor.Driver', schemas: ['app'], parameters: {}, timeoutSeconds: 10, maxRows: 200 }, passwordConfigured: true, enabled: true, archived: false, projectIds: [], version: 0, createdAt: '' }], nextCursor: undefined, facets: {} })
    vi.spyOn(api, 'testDatabaseConnection').mockResolvedValue({ connected: true, sessionReadOnly: true, serverProduct: 'Vendor', serverVersion: 'test', driverVersion: '1', driverSha256: 'a'.repeat(64), compatibilityVerified: false, detail: '账号权限需要现场验收' })
    const wrapper = mount(DatabaseView, { global: { plugins: [ElementPlus], stubs: { PageHeader: true } } }); await flushPromises()
    expect(wrapper.text()).toContain('驱动已安装'); expect(wrapper.text()).toContain('待现场版本联调')
    const button = wrapper.findAll('button').find(b => b.text() === '测试连接'); await button!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('连接成功'); expect(wrapper.text()).toContain('账号权限需要现场验收'); expect(wrapper.text()).toContain('待现场版本联调'); wrapper.unmount()
  })
})
