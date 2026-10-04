import {StrictMode} from 'react'
import {act,render,cleanup,fireEvent} from '@testing-library/react'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {api} from '@/api/client'
import {createTaskApplicationOwner} from '@/stores/taskStore'
import {createSessionLifecycleController} from '@/pages/w4/task/sessionLifecycleController'
import {SessionLifecyclePanel} from '@/pages/w4/task/SessionLifecyclePanel'
import {FoundationProvider} from '@/foundation/provider'
import {panelFixture,taskFixture,flushPromises} from './task'
import {deferred} from '@/pages/w4/task/test-support'
import type {Project,RuntimeInfo} from '@/types/domain'
import {setupCoreDom} from '@/pages/w2/core/coreTestHelpers'
const clean:(()=>void)[]=[]
beforeEach(setupCoreDom)
afterEach(()=>{cleanup();clean.splice(0).forEach(fn=>fn());vi.restoreAllMocks();vi.unstubAllGlobals()})
it('one App owner constructs without reads or SSE and its last snapshot remains immutable after disposal',async()=>{
 const get=vi.spyOn(api,'getProjects'),runtime=vi.spyOn(api,'getRuntime'),source=vi.fn();vi.stubGlobal('EventSource',source)
 const owner=createTaskApplicationOwner(),listener=vi.fn();owner.usingDemo=false;owner.subscribe(listener)
 expect(get).not.toHaveBeenCalled();expect(runtime).not.toHaveBeenCalled();expect(source).not.toHaveBeenCalled()
 const pending=deferred<Project[]>();get.mockReturnValue(pending.promise);const read=owner.loadProjects(),before=owner.getSnapshot();owner.dispose();pending.resolve([]);await read
 expect(owner.getSnapshot()).toBe(before);expect(Object.isFrozen(before.projects)).toBe(true);const calls=listener.mock.calls.length
 await owner.startRuntime();owner.projects=[];expect(listener).toHaveBeenCalledTimes(calls);expect(runtime).not.toHaveBeenCalled();expect(source).not.toHaveBeenCalled()
})
it('App runtime read order and demo scope prevent stale REST from replacing the current projection',async()=>{
 const first=deferred<RuntimeInfo>(),second=deferred<RuntimeInfo>(),get=vi.spyOn(api,'getRuntime').mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise),owner=createTaskApplicationOwner();clean.push(owner.dispose);owner.usingDemo=false
 const a=owner.refreshRuntime(),b=owner.refreshRuntime();const current:RuntimeInfo={status:'ONLINE',managed:true,checkedAt:'latest'};second.resolve(current);await b;first.resolve({...current,status:'OFFLINE',checkedAt:'old'});await a;expect(owner.runtime).toEqual(current)
 const late=deferred<RuntimeInfo>();get.mockReturnValueOnce(late.promise);const read=owner.refreshRuntime();owner.activateDemo();const snapshot=owner.getSnapshot();late.reject(new Error('旧真实 Runtime 失败'));await read;expect(owner.getSnapshot()).toBe(snapshot);expect(owner.usingDemo).toBe(true);expect(owner.error).toBeUndefined()
})
it('actual root StrictMode Session leases retire every replay and ignore a detached late read without new requests',async()=>{
 const pending=deferred<Awaited<ReturnType<typeof api.getTaskSessionTodos>>>(),todos=vi.spyOn(api,'getTaskSessionTodos').mockReturnValue(pending.promise),checks=vi.spyOn(api,'getTaskSessionCheckpoints').mockResolvedValue([]),task=taskFixture('task',{status:'PAUSED'}),f=panelFixture(task),owner=createSessionLifecycleController(task,'local');clean.push(()=>owner.retire(true))
 const leases:{released:boolean}[]=[],nativeAttach=owner.attachView;const attach=vi.spyOn(owner,'attachView').mockImplementation(()=>{const release=nativeAttach();const state={released:false};leases.push(state);return()=>{state.released=true;release()}}),view=render(<StrictMode><FoundationProvider skin={f.props.page.skin} reducedMotion><SessionLifecyclePanel {...f.props} sessionId="local" controller={owner}/></FoundationProvider></StrictMode>);await flushPromises();expect(attach.mock.calls.length).toBeGreaterThanOrEqual(2);expect(leases).toHaveLength(2);expect(leases[0]!.released).toBe(true);expect(leases[1]!.released).toBe(false);expect(todos).toHaveBeenCalledOnce();expect(checks).toHaveBeenCalledOnce()
 view.unmount();f.dispose();const before=owner.getSnapshot();pending.resolve([]);await flushPromises();expect(owner.getSnapshot()).toBe(before);expect(leases.every(row=>row.released)).toBe(true);expect(todos).toHaveBeenCalledOnce();expect(checks).toHaveBeenCalledOnce()
})
it('keyless Session unknown recovery reads the same identity and never rewrites or discards the original input',async()=>{
 vi.spyOn(api,'getTaskSessionTodos').mockResolvedValue([]);vi.spyOn(api,'getTaskSessionCheckpoints').mockResolvedValue([]);const post=vi.spyOn(api,'createTaskSessionCheckpoint').mockRejectedValue(new Error('发送后连接中断')),task=taskFixture('task',{status:'PAUSED'}),owner=createSessionLifecycleController(task,'local'),lease=owner.attachView();clean.push(()=>{lease();owner.retire(true)})
 await flushPromises();owner.change('messageId','original-message');owner.request('checkpoint');await flushPromises();expect(owner.getSnapshot().command.phase).toBe('UNKNOWN');expect(owner.canLeave().kind).toBe('BLOCK');owner.change('messageId','replacement');owner.request('checkpoint');await owner.recover();expect(post).toHaveBeenCalledOnce();expect(post).toHaveBeenCalledWith('task','local','original-message');expect(owner.getSnapshot().messageId).toBe('original-message');expect(owner.canLeave().kind).toBe('BLOCK');expect(owner.retire().kind).toBe('BLOCK')
})
it('Session rollback confirmation rechecks actual Task version and DIRECT permission before any write',async()=>{
 vi.spyOn(api,'getTaskSessionTodos').mockResolvedValue([]);vi.spyOn(api,'getTaskSessionCheckpoints').mockResolvedValue([]);const write=vi.spyOn(api,'revertTaskSession'),task=taskFixture('task',{status:'PAUSED',branch:'loopper/task'}),owner=createSessionLifecycleController(task,'local'),lease=owner.attachView();clean.push(()=>{lease();owner.retire(true)});await flushPromises();owner.change('messageId','message');owner.change('partId','part');owner.request('revert');expect(owner.confirmationCurrent()).toBe(true);owner.updateTask({...task,branch:'DIRECT',version:4});expect(owner.confirmationCurrent()).toBe(false);owner.confirm();expect(write).not.toHaveBeenCalled();expect(owner.canLeave().kind).toBe('CONFIRM_DISCARD')
})
it('actual Session UI invokes explicit real sync once and closing the root does not issue stop or cancel',async()=>{
 vi.spyOn(api,'getTaskSessionTodos').mockResolvedValue([]);vi.spyOn(api,'getTaskSessionCheckpoints').mockResolvedValue([]);const sync=vi.spyOn(api,'refreshTaskSessionTodos').mockResolvedValue([]),cancel=vi.spyOn(api,'cancelTask'),task=taskFixture('task',{status:'PAUSED'}),f=panelFixture(task),owner=createSessionLifecycleController(task,'local');clean.push(()=>owner.retire(true));const view=render(<FoundationProvider skin={f.props.page.skin} reducedMotion><SessionLifecyclePanel {...f.props} sessionId="local" controller={owner}/></FoundationProvider>);await flushPromises();expect(sync).not.toHaveBeenCalled();act(()=>fireEvent.click(view.container.querySelector('[data-semantic="session.syncTodos"]')!));await flushPromises();expect(sync).toHaveBeenCalledWith('task','local');expect(sync).toHaveBeenCalledOnce();view.unmount();f.dispose();expect(cancel).not.toHaveBeenCalled()
})
