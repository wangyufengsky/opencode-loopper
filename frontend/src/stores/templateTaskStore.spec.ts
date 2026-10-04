import { beforeEach,afterEach,describe,it,expect,vi } from 'vitest'
import { api } from '@/api/client'
import { createReportCreationController } from '@/pages/w3/templates/catalog/creation'
import { task } from '@/pages/w3/templates/catalog/fixtures'
const input={templateId:'CODE_REVIEW' as const,templateVersion:'1',projectId:'p',branchId:'local:refs/heads/main',startDate:'2026-09-01',endDate:'2026-09-07'}
const owners:ReturnType<typeof createReportCreationController>[]=[]
function owner(){const value=createReportCreationController();owners.push(value);return value}
beforeEach(()=>{sessionStorage.clear();vi.spyOn(api,'getTask').mockResolvedValue(task())})
afterEach(()=>{owners.forEach(value=>value.retire(true));owners.length=0;vi.restoreAllMocks()})
describe('template task creation',()=>{
 it('retries the same confirmed task when Start acknowledgement is lost',async()=>{const create=vi.spyOn(api,'createTemplateTask').mockResolvedValue({id:'task',state:'PENDING_START'});const start=vi.spyOn(api,'startTemplateTask').mockRejectedValueOnce(new Error('network')).mockResolvedValue({id:'task',state:'RUNNING'});const value=owner();expect(await value.start(input)).toBeUndefined();expect(value.getSnapshot().startUnknown).toBe(true);expect(await value.startOriginal()).toBe('task');expect(create).toHaveBeenCalledTimes(1);expect(start).toHaveBeenCalledTimes(2);expect(create.mock.calls[0]![0]).not.toHaveProperty('story')})
 it('reuses the idempotency key after lost confirmation and creates a new key for another run',async()=>{const create=vi.spyOn(api,'createTemplateTask').mockRejectedValueOnce(new Error('network')).mockResolvedValue({id:'task',state:'PENDING_START'});vi.spyOn(api,'startTemplateTask').mockResolvedValue({id:'task',state:'RUNNING'});const value=owner();expect(await value.start(input)).toBeUndefined();expect(value.getSnapshot().phase).toBe('UNKNOWN');expect(await value.start(input)).toBe('task');value.completeHandoff();value.retire(true);await owner().start(input);expect(create.mock.calls[0]![0].requestKey).toBe(create.mock.calls[1]![0].requestKey);expect(create.mock.calls[2]![0].requestKey).not.toBe(create.mock.calls[1]![0].requestKey)})
})
