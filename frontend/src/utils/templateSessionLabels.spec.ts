import { describe, expect, it } from 'vitest'
import { templateSessionTitle, templateSessionDetail } from './templateSessionLabels'
import type { TaskSessionSummary } from '@/types/domain'
const session: TaskSessionSummary = { key: 'execution:local', kind: 'IMPLEMENTATION', label: '执行会话', state: 'COMPLETED', localSessionId: 'local', createdAt: '', stageOrdinal: 2,
 templateBatch: { purpose: 'CONTRIBUTOR', ordinal: 2, total: 4, overallOrdinal: 12, overallTotal: 14, repairRound: 1, cleanup: false } }
describe('persisted batch labels', () => {
 it('uses global batch identity independently of list order, and retains repair context', () => {
  expect(templateSessionTitle(session)).toBe('阶段 2 · 分析批次 12/14')
  expect(templateSessionDetail(session)).toBe('人员贡献 · 第 2/4 批 · 第 1 轮返修')
 })
 it('does not label cleanup or missing history as a numbered batch', () => {
  expect(templateSessionTitle({ ...session, templateBatch: { ...session.templateBatch!, cleanup: true } })).toBe('阶段 2 · 会话清理')
  expect(templateSessionTitle({ ...session, templateBatch: { ...session.templateBatch!, ordinal: null } })).toContain('批次信息缺失')
  expect(templateSessionTitle({ ...session, templateBatch: undefined })).toBeUndefined()
 })
})
