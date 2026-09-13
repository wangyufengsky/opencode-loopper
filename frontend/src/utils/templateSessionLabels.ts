import type { TaskSessionSummary } from '@/types/domain'
export function templateSessionTitle(session: TaskSessionSummary): string | undefined {
  const b = session.templateBatch
  if (!b) return undefined
  const stage = `阶段 ${session.stageOrdinal ?? 2}`
  if (b.cleanup) return `${stage} · 会话清理`
  if (b.ordinal == null) return `${stage} · 批次信息缺失`
  if (b.overallOrdinal != null && b.overallTotal != null) return `${stage} · 分析批次 ${b.overallOrdinal}/${b.overallTotal}`
  return `${stage} · ${b.purpose === 'CONTRIBUTOR' ? '人员贡献' : '代码分析'} · 第 ${b.ordinal} 批`
}
export function templateSessionDetail(session: TaskSessionSummary): string | undefined {
  const b = session.templateBatch
  if (!b || b.cleanup || b.ordinal == null) return undefined
  return `${b.purpose === 'CONTRIBUTOR' ? '人员贡献' : '代码分析'} · 第 ${b.ordinal}${b.total == null ? '' : `/${b.total}`} 批${b.repairRound ? ` · 第 ${b.repairRound} 轮返修` : ''}`
}
