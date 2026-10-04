import type { JudgeRun } from '@/types/domain'
import type { SkinDefinition } from '@/themes/types'
import { RichDocument } from '@/pages/w3/shared/RichDocument'
import { judgeRoleLabel, statusLabel } from '@/utils/displayLabels'
import { judgeReasonMarkdown } from '@/utils/judgeReasonMarkdown'
export function JudgeReviewCard({judge,skin}:{judge:JudgeRun;skin:SkinDefinition}) {
 return <article className="w4-judge"><header className="judge-role"><strong>{judgeRoleLabel(judge.role)}</strong><span className="judge-verdict">{statusLabel(judge.verdict??judge.status)} · 第 {judge.ordinal} 次</span></header><RichDocument content={judgeReasonMarkdown(judge.reason??(judge.status==='TIMED_OUT'?'本次独立评审已超时，不再处于运行状态；保留历史证据。':['FAILED','CANCELLED'].includes(judge.status)?'本次独立评审已结束，尚无可用评审正文。':'等待独立审阅结果…'))} skin={skin}/></article>
}
