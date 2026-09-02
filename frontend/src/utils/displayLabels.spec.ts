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

  it('shows the actionable Designer handoff failure instead of its system envelope', () => {
    const message = userFacingError('SYSTEM_ERROR[SESSION] OPENCODE_DESIGNER_HANDOFF_FAILED: 400 Bad Request: messageID must start with "msg"; Bearer private-test-value')
    expect(message).toContain('设计请求未能发送给 OpenCode')
    expect(message).toContain('版本')
    expect(message).toContain('连接')
    expect(message).not.toContain('SYSTEM_ERROR')
    expect(message).not.toContain('OPENCODE_DESIGNER_HANDOFF_FAILED')
    expect(message).not.toContain('private-test-value')
  })

  it('handles colon-delimited envelopes and preserves translated Chinese failure details', () => {
    expect(userFacingError('SYSTEM_ERROR[SESSION]: OPENCODE_DESIGNER_UNAVAILABLE: offline'))
      .toContain('OpenCode 当前不可用')
    const message = userFacingError('SYSTEM_ERROR[SESSION] OPENCODE_DESIGNER_HANDOFF_FAILED: 附件校验失败，请重新选择文件。')
    expect(message).toContain('附件校验失败，请重新选择文件。')
    expect(message).not.toContain('SYSTEM_ERROR')
    expect(message).not.toContain('[SESSION]')
    expect(message).not.toContain('OPENCODE_DESIGNER_HANDOFF_FAILED')
  })

  it('uses a safe layer-specific fallback when a legacy envelope has no error code', () => {
    expect(userFacingError('SYSTEM_ERROR[SESSION]: Runtime is unavailable.'))
      .toBe('会话出现错误，请检查运行环境后重试')
    expect(userFacingError('SYSTEM_ERROR[FIELD]: invalid request'))
      .toBe('输入有误，请检查填写内容后重试')
  })
})
