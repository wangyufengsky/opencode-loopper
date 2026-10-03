import { Alert } from 'antd'
import { UiActionButton } from '@/foundation/components'
import { useOwnerSnapshot, type W2PageProps } from '@/pages/w2/shared'
import type { createTemplateCreationController } from './creation'

/** Each unresolved owner stays reachable even when its template no longer exists in the catalogue. */
export function CreationRecovery({owner,props,label}:{owner:ReturnType<typeof createTemplateCreationController>;props:W2PageProps;label:string}) {
  const s=useOwnerSnapshot(owner)
  if (['IDLE','SETTLED'].includes(s.phase) && !s.error)return null
  async function open() {const target=owner.getSnapshot().destination;if(!target || owner.getSnapshot().startUnknown || owner.getSnapshot().busy)return;const permit=owner.prepareHandoff();if(await props.navigation.go(target,false,permit))owner.completeHandoff()}
  return <Alert role="alert" type="warning" title={`${label}：${s.hashing ? '正在计算原文档哈希' : s.busy ? '正在处理' : s.startUnknown || s.phase==='UNKNOWN' ? '原操作结果尚未确认' : '写入已接受，请核对读取与交接结果'}`} description={<>
    {s.error && <p>{s.error}</p>}{s.canRead && <UiActionButton actionKey="receipt.readOriginal" target={label} busy={s.busy} onAction={() => {void owner.recover()}}/>}{s.canRetry && !s.needsFiles && <UiActionButton actionKey="receipt.retryOriginal" target={label} busy={s.busy} onAction={() => {void owner.retryOriginal().then(id => {if(id)void open()})}}/>}
    {s.needsFiles && <label>恢复原文档<p>刷新后原 File 实例不可恢复；请按原顺序重新选择字节相同的文件。</p><input type="file" aria-label={`恢复原文档 ${label}`} multiple accept=".docx,.md,.markdown,.pdf" disabled={s.busy} onChange={event => {const files=Array.from(event.target.files ?? []);event.target.value='';if(files.length)void owner.resumeWithFiles(files).then(id => {if(id)void open()})}}/></label>}
    {s.destination && !s.startUnknown && !s.busy && <UiActionButton actionKey="ui.open" target={`原运行记录 ${label}`} onAction={() => {void open()}}/>}
    {s.startAllowed && <UiActionButton actionKey="template.start" target={label} onAction={() => {void owner.startOriginal().then(() => open())}}/>}
    <p>关闭详情不会取消服务端操作。未确认身份不能离开；已核对结果只允许交接到该原记录。</p>
  </>} />
}
