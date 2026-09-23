import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { api } from '@/api/client'
import { useSourceTemplateStore } from './sourceTemplateStore'
const input = { templateId: 'UNIT_TEST_DEVELOPMENT', templateVersion: '1', projectId: 'p1', sourcePath: 'src/main' }
beforeEach(() => { sessionStorage.clear(); setActivePinia(createPinia()) })
afterEach(() => vi.restoreAllMocks())
it('reuses the same creation identity after a lost response and reload', async () => {
  const create = vi.spyOn(api, 'createSourceTemplate').mockRejectedValueOnce(new Error('断开')).mockResolvedValue({ id: 'same-run' } as never)
  await expect(useSourceTemplateStore().create(input)).rejects.toThrow('断开')
  const request = create.mock.calls[0]![0]
  setActivePinia(createPinia())
  expect(await useSourceTemplateStore().create(input)).toBe('same-run')
  expect(create.mock.calls[1]![0]).toEqual(request)
})
it('prevents duplicate requests while creation is pending', async () => {
  let finish!: (value: never) => void
  const create = vi.spyOn(api, 'createSourceTemplate').mockImplementation(() => new Promise(done => { finish = done }))
  const store = useSourceTemplateStore(), first = store.create(input)
  expect(await store.create(input)).toBeUndefined()
  expect(create).toHaveBeenCalledTimes(1)
  finish({ id: 'only-run' } as never)
  expect(await first).toBe('only-run')
})
