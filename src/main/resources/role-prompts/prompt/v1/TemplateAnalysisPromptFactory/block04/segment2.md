，expectedSubmissionRevision 初始为 0；每次新候选使用新 idempotencyKey。
candidate 对象严格使用上文结构，不要附加权限、任务状态或执行命令。
收到 REJECTED 时，读取 problems 的具体原因和 submissionRevision，在当前会话修正后重交完整候选。
网络响应未知时，只重放完全相同的请求键与候选，不得假设已接受。
收到 ACCEPTED 后立即结束；这只表示候选校验通过，报告仍由服务端按冻结合同生成与验收。
可先调用 describe_submission_contract（带同一 runId，pointer 为空字符串）查询完整参数结构和当前 revision。
不调用其他工具，不逐字反复复述推理，不将思考内容当作候选。
