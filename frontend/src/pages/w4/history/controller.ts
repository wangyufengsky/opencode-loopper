import { api } from '@/api/client'
import type { TaskDesignHistory } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import { createW4Owner } from '../shared/core'

export interface HistoryState { record?: TaskDesignHistory; loading: boolean; error: string; attachmentPreviews: Record<string,string>; previewBusy: string }
/** Every cache belongs to this frozen Task, never to a global attachment ID. */
export function createHistoryController(taskId:string) {
  const owner=createW4Owner<HistoryState>('task-design-history',taskId,{loading:true,error:'',attachmentPreviews:{},previewBusy:''})
  async function load() {
    const ticket=owner.ticket('record'); owner.patch({loading:true,error:''})
    try { const record=await api.getTaskDesignHistory(taskId); if(record.taskId!==taskId) throw new Error('历史记录与当前任务不一致，请重新读取。'); if(ticket.current()) owner.patch({record}) }
    catch(cause){if(ticket.current())owner.patch({error:userFacingError(cause,'历史设计加载失败')})}
    finally{if(ticket.current())owner.patch({loading:false})}
  }
  async function preview(id:string){
    const state=owner.base.getSnapshot(); if(!owner.active()||state.attachmentPreviews[id]!==undefined||!state.record?.frozenAttachments?.some(file=>file.id===id))return
    const ticket=owner.ticket(`attachment:${id}`); owner.patch({previewBusy:id})
    try { const value=await api.getTaskDesignAttachmentPreview(taskId,id); if(ticket.current())owner.patch({attachmentPreviews:{...owner.base.getSnapshot().attachmentPreviews,[id]:value.text||'该文件使用经过验证的原始内容预览。'}}) }
    catch(cause){if(ticket.current())owner.patch({error:userFacingError(cause,'附件预览暂不可用')})}
    finally{if(ticket.current())owner.patch({previewBusy:''})}
  }
  owner.setStart(()=>void load())
  return {...owner.base,load,preview}
}
