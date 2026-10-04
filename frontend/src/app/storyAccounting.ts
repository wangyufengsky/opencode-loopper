import { api, subscribeStoryAccountingEvents } from '@/api/client'
import type { StoryAccountingCall } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import type { LeaveDecision } from '@/foundation/contracts/types'
import { captureDto } from '@/foundation/contracts/immutable'
export const accountingActive = (call: StoryAccountingCall) => call.state === 'PREPARED' || call.state === 'CANCELLING'
export interface AccountingSnapshot { calls: StoryAccountingCall[]; selectedId: string; error: string; cancelling: boolean; retrying: boolean; now: number; command: { phase: 'IDLE' | 'SENDING' | 'UNKNOWN'; action?: 'cancel' | 'retry' | 'dismiss'; id?: string; error?: string } }
export function createStoryAccountingOwner() {
  const state: AccountingSnapshot = { calls: [], selectedId: '', error: '', cancelling: false, retrying: false, now: Date.now(), command: {phase:'IDLE'} }
  const listeners = new Set<() => void>()
  let snapshot = captureDto(state), alive = true, started = false, selection = 0, refreshing = false, listRequested = false, detailRequested = false, recoveryFailures = 0
  let timer: ReturnType<typeof setTimeout> | undefined, clock: ReturnType<typeof setInterval> | undefined, stream: { close(): void } | undefined
  const current = () => state.calls.find(call => call.id === state.selectedId)
  const locked = () => state.command.phase !== 'IDLE'
  const begin = (action:'cancel'|'retry'|'dismiss',id:string) => { state.command={phase:'SENDING',action,id};publish() }
  const accepted = () => { state.command={phase:'IDLE'} }
  const unknown = (failure:unknown) => { state.command={...state.command,phase:'UNKNOWN',error:userFacingError(failure,'原操作回执未知，请读取原结果；不会自动重发')} }
  const running = () => { const call = current(); return !!call && accountingActive(call) }
  const hidden = () => document.visibilityState === 'hidden'
  function publish() {
    if (!alive) return
    if (running() && !clock) clock = setInterval(() => { state.now = Date.now(); publish() }, 1000)
    if (!running() && clock) { clearInterval(clock); clock = undefined }
    snapshot = captureDto(state); for (const listener of [...listeners]) listener()
  }
  function selectAvailableCall() {
    const previous = state.selectedId, candidate = state.calls.find(accountingActive), selected = current()
    if (!selected || !accountingActive(selected) && candidate) state.selectedId = candidate?.id ?? state.calls[0]?.id ?? ''
    return previous !== state.selectedId
  }
  function requestRefresh(list = false) {
    if (!alive) return
    listRequested ||= list; detailRequested = true
    if (timer) { clearTimeout(timer); timer = undefined }
    if (!refreshing && !hidden()) void refresh()
  }
  async function refresh() {
    if (!alive || refreshing || hidden()) return
    refreshing = true
    const loadList = listRequested, generation = selection
    let listLoaded = !loadList
    listRequested = false; detailRequested = false
    try {
      if (loadList) {
        const next = await api.getStoryAccountingCalls(); listLoaded = true
        if (!alive || generation !== selection) { listRequested = alive; return }
        recoveryFailures = 0; state.calls = next; selectAvailableCall(); publish()
      }
      const id = state.selectedId, selected = current()
      if (id && selected && accountingActive(selected) && !hidden()) {
        const detail = await api.getStoryAccountingCall(id)
        if (!alive || selection !== generation || state.selectedId !== id) return
        if (detail.id !== id) throw new Error('统计读取结果不属于原记录')
        state.calls = state.calls.map(call => call.id === id ? detail : call)
        if (selectAvailableCall() && running()) detailRequested = true
      }
      state.error = ''
    } catch (failure) {
      if (alive && generation === selection && current()) state.error = userFacingError(failure, '统计状态暂时无法刷新')
      if (alive && generation === selection && !listLoaded && ++recoveryFailures <= 3) listRequested = true
    } finally {
      refreshing = false
      if (alive && !hidden()) {
        state.now = Date.now(); publish()
        if (listRequested || detailRequested || running()) {
          const delay = recoveryFailures > 0 ? Math.min(recoveryFailures * 2000, 6000) : listRequested || detailRequested ? 0 : 1200
          timer = setTimeout(() => { timer = undefined; void refresh() }, delay)
        }
      }
    }
  }
  function visibilityChanged() { if (hidden()) { if (timer) clearTimeout(timer); timer = undefined } else { recoveryFailures = 0; requestRefresh(true) } }
  return {
    getSnapshot: () => snapshot,
    subscribe: (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener) } },
    canLeave():LeaveDecision {return locked()?{kind:'BLOCK',reason:'故事点统计原操作仍待核对，请保留原身份',recoveryAction:'读取原统计结果'}:{kind:'ALLOW'}},
    async recover(){
      const original=state.command;if(!alive||original.phase!=='UNKNOWN'||!original.id)return
      const generation=selection
      try{
        if(original.action==='cancel'){const value=await api.getStoryAccountingCall(original.id);if(!alive||selection!==generation||state.command!==original)return;if(value.id!==original.id)throw new Error('统计回执不属于原会话');state.calls=state.calls.map(row=>row.id===value.id?value:row);if(value.state==='CANCELLING'||value.state==='CANCELLED')accepted()}
        else{const values=await api.getStoryAccountingCalls();if(!alive||selection!==generation||state.command!==original)return;state.calls=values;selectAvailableCall();if(original.action==='dismiss'&&!values.some(row=>row.id===original.id))accepted();else state.command={...original,error:'已读取当前统计记录；现有接口不能证明原请求结果，原身份继续保留。'}}
      }catch(failure){if(alive&&selection===generation&&state.command===original)state.command={...original,error:userFacingError(failure,'原统计结果读取失败')}}finally{publish()}
    },
    start() { if (!alive || started) return; started = true; const reconcile = () => { recoveryFailures = 0; requestRefresh(true) }; stream = subscribeStoryAccountingEvents(reconcile, reconcile); document.addEventListener('visibilitychange', visibilityChanged) },
    select(id: string) { if (!alive || !state.calls.some(call => call.id === id)) return; state.selectedId = id; selection++; publish(); requestRefresh() },
    async cancel() {
      const call = current(); if (!alive || !call || state.cancelling || !running() || locked()) return
      const id = call.id; selection++; state.cancelling = true; begin('cancel',id)
      try { const result = await api.cancelStoryAccountingCall(id); if (alive) { if(result.id!==id)throw new Error('统计回执不属于原会话');state.calls = state.calls.map(item => item.id === id ? result : item);accepted() } }
      catch (failure) { if (alive) unknown(failure) }
      finally { if (alive) { state.cancelling = false; publish() } }
    },
    async close() {
      if (!alive || running() || state.retrying || locked()) return
      selection++
      const finished = state.calls.filter(call => !accountingActive(call))
      try { for(const call of finished){if(!alive)return;begin('dismiss',call.id);await api.dismissStoryAccountingCall(call.id);if(!alive)return;accepted();state.calls=state.calls.filter(row=>row.id!==call.id);selectAvailableCall();publish()} }
      catch (failure) { if (alive) unknown(failure) }
      finally { publish() }
    },
    async retry() {
      const call = current(); if (!alive || !call?.retryAvailable || state.retrying || locked()) return
      const id = call.id; selection++; state.retrying = true; state.error = ''; begin('retry',id)
      try {
        const result = await api.retryStoryAccountingCall(id)
        if (!alive) return
        state.calls = [...state.calls.filter(item => item.id !== result.id), result]; state.selectedId = result.id; accepted()
        if (timer) clearTimeout(timer)
        timer = setTimeout(() => { timer = undefined; requestRefresh() }, 1200)
      } catch (failure) { if (alive) unknown(failure) }
      finally { if (alive) { state.retrying = false; publish() } }
    },
    dispose() {
      if (!alive) return
      alive = false; selection++
      const ownedStream = stream; stream = undefined
      let failure: unknown
      for (const cleanup of [() => ownedStream?.close(), () => document.removeEventListener('visibilitychange', visibilityChanged), () => { if (timer) clearTimeout(timer); timer = undefined }, () => { if (clock) clearInterval(clock); clock = undefined }, () => listeners.clear()]) {
        try { cleanup() } catch (cause) { failure ??= cause }
      }
      if (failure) throw failure
    },
  }
}
export type StoryAccountingOwner = ReturnType<typeof createStoryAccountingOwner>
