import { beforeEach,afterEach,expect,it,vi } from 'vitest'
import { api } from '@/api/client'
import { createSourceCreationController } from '@/pages/w3/templates/catalog/creation'
import { sourceRun } from '@/pages/w3/templates/catalog/fixtures'
const input={templateId:'UNIT_TEST_DEVELOPMENT',templateVersion:'1',projectId:'p1',sourcePath:'src/main'}
const owners:ReturnType<typeof createSourceCreationController>[]=[]
function owner(){const value=createSourceCreationController();owners.push(value);return value}
beforeEach(()=>{sessionStorage.clear();vi.spyOn(api,'sourceTemplate').mockImplementation(async id=>sourceRun(id))})
afterEach(()=>{owners.forEach(value=>value.retire(true));owners.length=0;vi.restoreAllMocks()})
it('reuses the same creation identity after a lost response and reload',async()=>{const create=vi.spyOn(api,'createSourceTemplate').mockRejectedValueOnce(new Error('断开')).mockResolvedValue(sourceRun('same-run'));const first=owner();expect(await first.start(input)).toBeUndefined();expect(first.getSnapshot().phase).toBe('UNKNOWN');const request=create.mock.calls[0]![0];first.retire(true);expect(await owner().start(input)).toBe('same-run');expect(create.mock.calls[1]![0]).toEqual(request)})
it('prevents duplicate requests while creation is pending',async()=>{let finish!:(value:ReturnType<typeof sourceRun>)=>void;const create=vi.spyOn(api,'createSourceTemplate').mockImplementation(()=>new Promise(done=>{finish=done}));const value=owner(),first=value.start(input);expect(await value.start(input)).toBeUndefined();expect(create).toHaveBeenCalledTimes(1);finish(sourceRun('only-run'));expect(await first).toBe('only-run')})
