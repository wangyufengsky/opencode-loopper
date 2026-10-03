import { useState } from 'react'
import { UiActionButton } from '@/foundation/components'
export function KnowledgeUsage({usage}:{usage?:{inputTokens:number|null;outputTokens:number|null}|null}) {
  const [open,setOpen]=useState(false), number=(v:number|null|undefined) => v == null ? '暂未提供' : v.toLocaleString('zh-CN')
  const total=usage?.inputTokens != null && usage.outputTokens != null ? usage.inputTokens+usage.outputTokens : null
  return <div className="knowledge-usage" onKeyDown={event => {if (event.key === 'Escape') setOpen(false)}}><button type="button" aria-label="Token 用量" aria-expanded={open} onClick={() => setOpen(!open)}>Token 用量</button>{open && <div role="status"><strong>会话 Token 用量</strong><dl><dt>输入</dt><dd>{number(usage?.inputTokens)}</dd><dt>输出</dt><dd>{number(usage?.outputTokens)}</dd><dt>合计</dt><dd>{number(total)}</dd></dl><UiActionButton actionKey="ui.close" target="用量" onAction={() => setOpen(false)}/></div>}</div>
}
