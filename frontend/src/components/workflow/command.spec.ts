import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import { createW4Owner } from '@/pages/w4/shared/core'
import { idleCommand } from '@/pages/w3/templates/runs/core'
import type { CommandState } from '@/pages/w4/shared/core'
const owners: ReturnType<typeof createW4Owner<{command:CommandState}>>[]=[]
function command(id='A') { const value=createW4Owner('workflow-test-command',id,{command:idleCommand});value.base.attachView();owners.push(value);return value }
function submit<R>(value:ReturnType<typeof command>,label:string,write:()=>Promise<R>,after:(value:Readonly<R>)=>Promise<void>) { return value.command({label,input:{endpoint:'/actual-test-command',method:'POST',requestKey:`original-${label}`,body:{requestKey:`original-${label}`}},capability:{kind:'IDEMPOTENT_KEY'},write,read:async(receipt,context)=>{if(context.isCurrent())await after(receipt)},changed:command=>value.patch({command}),isDefinitiveRejection:cause=>cause instanceof ApiError&&cause.status===409}) }
function deferred<T>() { let resolve!:(value:T)=>void,reject!:(reason:unknown)=>void;const promise=new Promise<T>((done,fail)=>{resolve=done;reject=fail});return {promise,resolve,reject} }
afterEach(()=>{for(const value of owners)value.base.retire(true);owners.length=0})
describe('workflow command receipts',()=>{
 it('retries an unknown response without replacing its command and locks new commands',async()=>{
  const value=command(),execute=vi.fn().mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({id:'first'}),after=vi.fn().mockResolvedValue(undefined),other=vi.fn(),operation=submit(value,'创建',execute,after);await expect(operation.execute()).rejects.toThrow('timeout');expect(value.base.canLeave().kind).toBe('BLOCK');expect(()=>submit(value,'另一次',other,after)).toThrow();expect(other).not.toHaveBeenCalled();await operation.recoverWrite();expect(execute).toHaveBeenCalledTimes(2);expect(after).toHaveBeenCalledWith({id:'first'});expect(value.base.canLeave().kind).toBe('ALLOW')
 })
 it('only rereads after an accepted command whose refresh failed',async()=>{
  const value=command(),execute=vi.fn().mockResolvedValue({id:'receipt'}),after=vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(undefined),operation=submit(value,'执行',execute,after);await expect(operation.execute()).rejects.toThrow('offline');expect(operation.getSnapshot().accepted).toBe(true);await operation.retryReadback();expect(execute).toHaveBeenCalledTimes(1);expect(after).toHaveBeenCalledTimes(2);expect(operation.getSnapshot().phase).toBe('SETTLED');expect(value.base.canLeave().kind).toBe('ALLOW')
 })
 it('unlocks on deterministic rejection and suppresses callbacks after leaving',async()=>{
  const value=command(),after=vi.fn(),rejected=submit(value,'执行',async()=>{throw new ApiError('计划已变化',409)},after);await expect(rejected.execute()).rejects.toThrow('计划已变化');expect(value.base.canLeave().kind).toBe('ALLOW');const work=deferred<string>(),operation=submit(value,'下一次',()=>work.promise,after),running=operation.execute();await Promise.resolve();value.base.retire(true);work.resolve('done');await running;expect(after).not.toHaveBeenCalled()
 })
 it('keeps the new command locked when an invalidated write finishes late',async()=>{
  const value=command(),first=deferred<string>(),second=deferred<string>(),firstAfter=vi.fn(),secondAfter=vi.fn(),oldOperation=submit(value,'旧需求',()=>first.promise,firstAfter),oldWork=oldOperation.execute();await Promise.resolve();value.base.retire(true);const current=command('B'),newOperation=submit(current,'新需求',()=>second.promise,secondAfter),newWork=newOperation.execute();await Promise.resolve();first.resolve('accepted');await oldWork;expect(oldOperation.getSnapshot().accepted).toBe(true);expect(firstAfter).not.toHaveBeenCalled();expect(current.base.getSnapshot().command.phase).toBe('SENDING');expect(newOperation.getSnapshot().busy).toBe(true);expect(current.base.canLeave().kind).toBe('BLOCK');second.resolve('new receipt');await newWork;expect(secondAfter).toHaveBeenCalledWith('new receipt');expect(current.base.canLeave().kind).toBe('ALLOW')
 })
 it('does not replace the new error or pending command with an old rejection',async()=>{
  const value=command(),first=deferred<string>(),oldOperation=submit(value,'旧需求',()=>first.promise,vi.fn()),oldWork=oldOperation.execute();await Promise.resolve();value.base.retire(true);const current=command('B'),newOperation=submit(current,'新需求',async()=>{throw new Error('unknown')},vi.fn());await expect(newOperation.execute()).rejects.toThrow('unknown');const newSnapshot=current.base.getSnapshot(),newError=newOperation.getSnapshot().error;first.reject(new ApiError('旧计划已变化',409));await expect(oldWork).rejects.toThrow('旧计划已变化');expect(current.base.getSnapshot()).toBe(newSnapshot);expect(newOperation.getSnapshot().error).toBe(newError);expect(current.base.canLeave().kind).toBe('BLOCK')
 })
 it('rejects the previous identity before its load invalidates the command',async()=>{
  const value=command('A'),first=deferred<string>(),after=vi.fn(),current=value.base.capture(),operation=submit(value,'确认 A',()=>first.promise,after),work=operation.execute();await Promise.resolve();value.base.retire(true);command('B');expect(current.isCurrent()).toBe(false);first.resolve('accepted A');await work;expect(after).not.toHaveBeenCalled();expect(operation.getSnapshot().retired).toBe(true)
 })
 it('invalidates an accepted read without replaying its write or clearing a new read',async()=>{
  const value=command(),first=deferred<void>(),second=deferred<void>(),write=vi.fn().mockResolvedValue('accepted'),after=vi.fn(()=>first.promise),oldOperation=submit(value,'旧读取',write,after),oldWork=oldOperation.execute();await vi.waitFor(()=>expect(after).toHaveBeenCalledOnce());expect(oldOperation.getSnapshot().accepted).toBe(true);value.base.retire(true);const current=command('B'),newOperation=submit(current,'新读取',async()=>'new',()=>second.promise),newWork=newOperation.execute();await Promise.resolve();first.reject(new Error('old read failed'));await expect(oldWork).rejects.toThrow('old read failed');expect(current.base.canLeave().kind).toBe('BLOCK');expect(newOperation.getSnapshot().busy).toBe(true);expect(current.base.getSnapshot().command.error).toBe('');second.resolve();await newWork;expect(write).toHaveBeenCalledOnce();expect(current.base.canLeave().kind).toBe('ALLOW')
 })
})
