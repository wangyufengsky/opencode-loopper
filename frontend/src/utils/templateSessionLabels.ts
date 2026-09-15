import type { TaskSessionSummary } from '@/types/domain'
export function templateSessionTitle(session: TaskSessionSummary): string | undefined {
  const b = session.templateBatch
  if (!b) return undefined
  const stage = `阶段 ${session.stageOrdinal ?? 2}`
  if (b.cleanup) return `${stage} · 会话清理`
  if (b.ordinal == null) return `${stage} · 批次信息缺失`
  if (b.overallOrdinal != null && b.overallTotal != null) return `${stage} · 分析批次 ${b.overallOrdinal}/${b.overallTotal}`
  return `${stage} · ${templateBatchPurpose(b.purpose)} · 第 ${b.ordinal} 批`
}
export function templateSessionDetail(session: TaskSessionSummary): string | undefined {
  const b = session.templateBatch
  if (!b || b.cleanup || b.ordinal == null) return undefined
  return `${templateBatchPurpose(b.purpose)} · 第 ${b.ordinal}${b.total == null ? '' : `/${b.total}`} 批${b.repairRound ? ` · 第 ${b.repairRound} 轮返修` : ''}`
}

export function templateBatchPurpose(purpose: string | null): string {
  const labels: Record<string, string> = { SNAPSHOT_PLAN: '功能规划', SNAPSHOT_LINKS: '衔接规划', SNAPSHOT_ANALYSIS: '功能分析', SNAPSHOT_SUPPLEMENT: '补充分析', SNAPSHOT_REVIEW: '独立复核', SNAPSHOT_RELATION_ANALYSIS: '跨功能检查', SNAPSHOT_RELATION_REVIEW: '衔接复核', CONTRIBUTOR: '人员贡献', DOCUMENT_CODE_REVIEW_V2: '独立复核' }
  return labels[purpose ?? ''] ?? '代码分析'
}
