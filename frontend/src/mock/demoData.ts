import type { Artifact, DesignerMessage, LoopDraft, Project, RuntimeInfo, Task } from '@/types/domain'

const now = '2026-08-04T10:12:00+08:00'

export const demoProjects: Project[] = [
  { id: 'prj-loopper', name: 'OpenCode Loopper', rootPath: '/Users/wangyufeng/IdeaProjects/opencode-loopper', branch: 'main', description: '本地 OpenCode Loop 管理台', status: 'READY', updatedAt: now, taskCount: 3, openDesignerSessionCount: 1 },
  { id: 'prj-sandbox', name: 'E2E Sandbox', rootPath: '/Users/wangyufeng/IdeaProjects/loopper-sandbox', branch: 'main', description: '确定性验证沙盒', status: 'READY', updatedAt: '2026-08-04T09:36:00+08:00', taskCount: 1, openDesignerSessionCount: 0 },
]

export const demoRuntime: RuntimeInfo = {
  loopperVersion: '0.1.53', status: 'ONLINE', version: '1.18.12', managed: true, pid: 44892, endpoint: '127.0.0.1:40971', model: 'opencode/deepseek-v4-flash-free', checkedAt: now,
}

export const demoTasks: Task[] = [
  {
    id: 'tsk-019fc6ad', projectId: 'prj-loopper', projectName: 'OpenCode Loopper', title: '实现任务控制台的错误恢复',
    goal: '让验证失败、Session 错误与 Task 错误有明确、可审计的处理边界。', branch: 'loopper/tsk-019fc6ad',
    worktreePath: 'data/worktrees/tsk-019fc6ad', status: 'RUNNING', hasDesignHistory: true, activeStage: 2, attemptCount: 3, maxAttempts: 12,
    createdAt: '2026-08-04T09:42:00+08:00', updatedAt: now,
    stages: [
      { id: 'stg-context', ordinal: 1, objective: '冻结状态机与错误契约', status: 'SUCCEEDED', attempts: [{ id: 'att-01', ordinal: 1, stageId: 'stg-context', sessionId: 'ses-901', status: 'VERIFIED', startedAt: '2026-08-04T09:42:00+08:00', endedAt: '2026-08-04T09:49:00+08:00', summary: '契约及迁移已验证。', errors: [], verifiers: [{ id: 'v-1', name: '状态机单元测试', status: 'PASS', summary: '24 passed', elapsedMs: 1253 }] }] },
      { id: 'stg-runtime', ordinal: 2, objective: '实现 Session 恢复和停止保护', status: 'RUNNING', attempts: [
        { id: 'att-02', ordinal: 2, stageId: 'stg-runtime', sessionId: 'ses-902', status: 'SESSION_ERROR', startedAt: '2026-08-04T09:51:00+08:00', endedAt: '2026-08-04T09:57:00+08:00', summary: 'SSE 断线，Session 已关闭。', errors: [{ id: 'err-session-1', layer: 'SESSION', code: 'SSE_DISCONNECTED', message: '当前 Session 的事件流断开，已保留日志与 Diff。', retryable: true, occurredAt: '2026-08-04T09:57:00+08:00', sessionId: 'ses-902', evidenceId: 'art-log-1' }], verifiers: [] },
        { id: 'att-03', ordinal: 3, stageId: 'stg-runtime', sessionId: 'ses-903', status: 'RUNNING', startedAt: '2026-08-04T10:01:00+08:00', summary: '新的 Session 正在继续处理恢复逻辑。', errors: [], verifiers: [{ id: 'v-2', name: 'MCP 契约测试', status: 'PENDING', summary: '等待本轮响应' }] },
      ] },
      { id: 'stg-e2e', ordinal: 3, objective: '验收重试和取消流程', status: 'PENDING', attempts: [] },
    ],
  },
  { id: 'tsk-verifier', projectId: 'prj-sandbox', projectName: 'E2E Sandbox', title: '修复故意失败的单元测试', goal: '使沙盒测试稳定通过。', branch: 'loopper/tsk-verifier', worktreePath: 'data/worktrees/tsk-verifier', status: 'RETRY_WAIT', hasDesignHistory: true, activeStage: 1, attemptCount: 1, maxAttempts: 3, createdAt: '2026-08-04T09:16:00+08:00', updatedAt: '2026-08-04T10:05:00+08:00' },
  { id: 'tsk-blocked', projectId: 'prj-loopper', projectName: 'OpenCode Loopper', title: '路径边界演练', goal: '验证符号链接逃逸检测。', branch: 'loopper/tsk-blocked', worktreePath: 'data/worktrees/tsk-blocked', status: 'FAILED', hasDesignHistory: true, activeStage: 1, attemptCount: 1, maxAttempts: 2, createdAt: '2026-08-04T08:52:00+08:00', updatedAt: '2026-08-04T09:08:00+08:00', errors: [{ id: 'err-task-1', layer: 'TASK', code: 'PATH_ESCAPE', message: '检测到项目路径越过登记根目录，任务已终止。', retryable: false, occurredAt: '2026-08-04T09:08:00+08:00', evidenceId: 'art-system-1' }] },
]

export const demoDraft: LoopDraft = {
  id: 'draft-01', status: 'DRAFT_READY', updatedAt: now,
  spec: { schemaVersion: 'v1', projectId: 'prj-loopper', goal: '实现一个可靠、本地优先的 OpenCode Loop 控制台。', context: 'Git 执行在登记项目目录的串行任务分支中；错误分层必须可审计。', stages: [
    { objective: '冻结领域状态及错误模型', allowedPaths: ['src/main/**'], forbiddenPaths: ['pom.xml'], deliverables: ['状态机', '迁移'], verifiers: [{ type: 'PROCESS', command: ['./mvnw', 'test'] }] },
    { objective: '实现运行时恢复与安全终止', allowedPaths: ['src/main/**'], forbiddenPaths: ['data/**'], deliverables: ['runtime adapter'], verifiers: [{ type: 'PROCESS', command: ['./mvnw', 'test'] }] },
  ], limits: { maxStageAttempts: 3, maxTaskAttempts: 12, maxDuration: 'PT2H', attemptTimeout: 'PT30M' } },
}

export const demoMessages: DesignerMessage[] = [
  { id: 'm-1', role: 'USER', actor: 'USER', content: '为错误恢复能力生成一个可验证的 LoopSpec。', createdAt: '10:02' },
  { id: 'm-2', role: 'ASSISTANT', actor: 'DESIGNER', content: `# 错误恢复 LoopSpec 提案

## 目标

建立可审计的错误边界：**Task 错误终止任务**，**Session 错误保留上下文并进入下一轮**，验证失败则按阶段预算重试。

## 执行流程

\`\`\`mermaid
flowchart LR
  A[冻结错误契约] --> B[实现 Session 恢复]
  B --> C{验证结果}
  C -->|通过| D[沙盒验收]
  C -->|失败且有预算| B
  C -->|Task 错误| E[终止并保留证据]
\`\`\`

## 验收重点

- 状态迁移与错误层级有单元测试覆盖。
- 恢复后的新 Session 能读取上一轮摘要与 Diff。
- Maven 测试和前端测试全部通过。

> 右侧 LoopSpec 结构化表单仍是最终执行契约，确认前可以继续编辑。`, createdAt: '10:03' },
]

export const demoArtifacts: Artifact[] = [
  { id: 'art-log-1', taskId: 'tsk-019fc6ad', kind: 'LOG', title: 'Session ses-902 事件日志', createdAt: '10:00', content: '[10:57:03] event stream disconnected\\n[10:57:04] polling session state → unknown\\n[10:57:05] session closed safely\\n[10:57:05] recovery queued: create a fresh session' },
  { id: 'art-diff-1', taskId: 'tsk-019fc6ad', kind: 'DIFF', title: '本轮 Git Diff', createdAt: '10:06', content: 'diff --git a/src/main/java/RuntimeManager.java b/src/main/java/RuntimeManager.java\\n@@ -84,6 +84,12 @@\\n+ if (session.isDisconnected()) {\\n+   return RecoveryResult.newSession();\\n+ }' },
  { id: 'art-verify-1', taskId: 'tsk-019fc6ad', kind: 'VERIFICATION', title: 'MCP 契约测试', createdAt: '10:07', content: 'Tests run: 12, Failures: 0, Errors: 0\\nSSE reconnect contract: PASS' },
  { id: 'art-system-1', taskId: 'tsk-blocked', kind: 'SYSTEM', title: '路径边界证据', createdAt: '09:08', content: 'PATH_ESCAPE\\nResolved path escapes the registered project root.\\nScheduling was stopped before any Session was created.' },
]
