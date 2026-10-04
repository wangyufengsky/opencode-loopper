import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { workflowRuns } from '@/api/workflowRuns'
import type { AppSettings } from '@/types/domain'
import { createRequirementController } from '@/pages/w5/requirements/controller'
import { execution, requirement } from './workflowRunTestFixtures'
import { newNode } from './graph'
vi.mock('@/api/client', () => ({ ApiError: class extends Error {}, api: { getSettings: vi.fn(), getSettingsModels: vi.fn() } }))
vi.mock('@/api/workflowRuns', () => ({ workflowRuns: { execution: vi.fn() } }))
const settings = (name = 'default') => ({ openCode: { provider: 'configured', model: name } } as AppSettings)
const owners: ReturnType<typeof createRequirementController>[] = []
function create() { const owner = createRequirementController(`model-${owners.length}`), value=requirement({id:owner.id}), snapshot=execution('PENDING_START'); snapshot.execution.id=owner.id; snapshot.control.id=owner.id; value.graph.nodes=[newNode('free.readonly')]; owner.patch({base:value,graph:value.graph,layout:value.layout,execution:snapshot,readable:true}); owner.setStart(()=>{}); owner.attachView(); owners.push(owner); return owner }
beforeEach(()=>{vi.resetAllMocks();vi.mocked(api.getSettings).mockResolvedValue(settings())})
afterEach(()=>{for(const owner of owners) owner.retire(true);owners.length=0})
describe('requirement execution model selection',()=>{
 it('initializes once without mounting a panel or enumerating model choices',async()=>{
  const choice=create();await Promise.all([choice.initializeModel(),choice.initializeModel()]);await choice.initializeModel();expect(choice.getSnapshot().model).toEqual({providerId:'configured',modelId:'default',thinking:null});expect(api.getSettings).toHaveBeenCalledTimes(1);expect(api.getSettingsModels).not.toHaveBeenCalled()
 })
 it('prefers a persisted control model and preserves deliberate user choices',async()=>{
  const choice=create(),persisted={providerId:'saved',modelId:'version',thinking:true},state=execution('PENDING_START');state.execution.id=choice.id;state.control.id=choice.id;state.control.model=persisted;vi.mocked(workflowRuns.execution).mockResolvedValue(state);await choice.refresh();await choice.initializeModel();expect(api.getSettings).not.toHaveBeenCalled();expect(choice.getSnapshot().model).toEqual(persisted);choice.chooseModel({...persisted,modelId:'manual'});await choice.refresh();expect(choice.getSnapshot().model?.modelId).toBe('manual')
 })
 it('does not overwrite manual or persisted choices with late default responses',async()=>{
  let resolve!:(value:AppSettings)=>void;vi.mocked(api.getSettings).mockImplementation(()=>new Promise(done=>{resolve=done}));const choice=create(),first=choice.initializeModel();choice.chooseModel({providerId:'user',modelId:'chosen',thinking:null});resolve(settings());await first;expect(choice.getSnapshot().model?.modelId).toBe('chosen');expect(choice.getSnapshot().modelLoading).toBe(false)
  choice.retire(true);const current=create(),second=current.initializeModel(),state=execution('PENDING_START');state.execution.id=current.id;state.control.id=current.id;state.control.model={providerId:'control',modelId:'frozen',thinking:null};vi.mocked(workflowRuns.execution).mockResolvedValue(state);await current.refresh();resolve(settings());await second;expect(current.getSnapshot().model?.modelId).toBe('frozen')
 })
 it('ignores old requirement responses and disposal without disturbing the new request',async()=>{
  const pending:Array<(value:AppSettings)=>void>=[];vi.mocked(api.getSettings).mockImplementation(()=>new Promise(done=>pending.push(done)));const oldOwner=create(),old=oldOwner.initializeModel();oldOwner.retire(true);const currentOwner=create(),current=currentOwner.initializeModel();pending[0]!(settings('old'));await old;expect(currentOwner.getSnapshot().model).toBeNull();expect(currentOwner.getSnapshot().modelLoading).toBe(true);pending[1]!(settings('current'));await current;expect(currentOwner.getSnapshot().model?.modelId).toBe('current');currentOwner.retire(true);const disposedOwner=create(),disposed=disposedOwner.initializeModel();disposedOwner.retire(true);pending[2]!(settings('disposed'));await disposed;expect(disposedOwner.getSnapshot().model).toBeNull()
 })
 it('keeps a recoverable error for absent defaults or failed settings and retries only explicitly',async()=>{
  vi.mocked(api.getSettings).mockResolvedValueOnce(settings('')).mockRejectedValueOnce(new Error('offline')).mockResolvedValue(settings('recovered'));const choice=create();await choice.initializeModel();expect(choice.getSnapshot().modelError).toContain('默认执行模型暂时无法读取');await choice.initializeModel();expect(api.getSettings).toHaveBeenCalledTimes(1);await choice.initializeModel(true);expect(choice.getSnapshot().modelError).toContain('暂时无法读取');await choice.initializeModel(true);expect(choice.getSnapshot().model?.modelId).toBe('recovered');expect(choice.getSnapshot().modelError).toBe('');expect(api.getSettingsModels).not.toHaveBeenCalled()
 })
})
