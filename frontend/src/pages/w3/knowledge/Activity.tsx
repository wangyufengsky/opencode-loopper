import { useId, useState } from 'react'
import { SemanticIcon } from '@/foundation/semanticRegistry'
import { knowledgeStateLabel, knowledgeToolLabel } from '@/utils/displayLabels'
import { RichDocument } from '@/pages/w3/shared/RichDocument'
import type { SkinDefinition } from '@/themes/types'
import type { KnowledgeCall } from '@/types/domain'

export function KnowledgeActivity({message,thinking,answer,skin,toolLabel=knowledgeToolLabel}:{message:{state:string;calls?:KnowledgeCall[];questions?:{state:string}[]};thinking:string;answer:string;skin:SkinDefinition;toolLabel?:(tool:string)=>string}) {
  const id=useId(),[thought,setThought]=useState(false),[tools,setTools]=useState(false)
  const active=['PREPARED','CREATING','SENDING','RUNNING'].includes(message.state) && !message.questions?.some(q => ['PENDING','PREPARED','SENDING','UNKNOWN'].includes(q.state))
  const calls=(message.calls ?? []).slice(-30),latest=calls.at(-1)
  const waiting=active && !thinking.trim() && !answer.trim() && latest?.state !== 'RUNNING' ? message.state === 'RUNNING' ? '正在思考' : message.state === 'SENDING' ? '正在发送问题' : '正在准备回答' : ''
  return <>{waiting && <div className="knowledge-waiting" role="status" aria-live="polite"><SemanticIcon semanticKey="section.activity" />{waiting}<span className="knowledge-waiting-dots" aria-hidden="true"><i/><i/><i/></span></div>}
    {thinking.trim() && <section className="knowledge-thinking" aria-label="思考"><button type="button" aria-expanded={thought} aria-controls={`${id}-thinking`} onClick={() => setThought(!thought)}><SemanticIcon semanticKey="section.activity" /><strong>思考</strong><span>{thinking.trim().split(/\n\s*\n/).at(-1)?.replace(/\s+/g,' ').slice(-160)}</span></button><div id={`${id}-thinking`} hidden={!thought}><RichDocument content={thinking} skin={skin} allowImages={false}/></div></section>}
    {latest && <section className="knowledge-thinking" aria-label="工具调用"><button type="button" aria-expanded={tools} aria-controls={`${id}-tools`} onClick={() => setTools(!tools)}>{active && latest.state === 'RUNNING' ? <span className="knowledge-spinner" aria-label="正在调用" /> : <SemanticIcon semanticKey="nav.tools"/>}<strong>工具调用</strong><span>{toolLabel(latest.tool)}{latest.detail && ` · ${latest.detail}`}</span><small>{knowledgeStateLabel(latest.state)}</small></button><div id={`${id}-tools`} hidden={!tools}><ol>{calls.map(call => <li key={call.id}>{toolLabel(call.tool)} {call.detail} <small>{knowledgeStateLabel(call.state)}</small></li>)}</ol>{(message.calls?.length ?? 0)>=30 && <small>最近 30 项调用</small>}</div></section>}
  </>
}
