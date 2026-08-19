import { describe, expect, it } from 'vitest'
import { displayLabel, errorCodeLabel, rolePackLabel, statusLabel, userFacingError } from './displayLabels'

describe('displayLabels', () => {
  it('translates workflow states, overrides and role packs for the UI', () => {
    expect(statusLabel('QUESTIONING_PACKAGE')).toBe('工作包提问')
    expect(displayLabel('MANUAL_OVERRIDE')).toBe('人工覆盖')
    expect(rolePackLabel('software-mixed')).toBe('混合技术栈')
  })

  it('translates known error codes without exposing protocol values', () => {
    expect(errorCodeLabel('ATTEMPT_LIMIT_EXHAUSTED')).toBe('已用完尝试次数')
    expect(errorCodeLabel('JAVA_UNIT_TEST_ACCEPTANCE_REQUIRED')).toBe('缺少 Java 聚焦单元测试验收')
    const message = userFacingError('ATTEMPT_LIMIT_EXHAUSTED: retry denied')
    expect(message).toContain('已用完尝试次数')
    expect(message).not.toContain('ATTEMPT_LIMIT_EXHAUSTED')
  })

  it('uses a safe Chinese fallback for unknown machine values', () => {
    expect(displayLabel('BRAND_NEW_INTERNAL_CODE')).toBe('未知')
    expect(userFacingError(new Error('connection reset'))).toBe('操作未完成，请重试')
  })
})
