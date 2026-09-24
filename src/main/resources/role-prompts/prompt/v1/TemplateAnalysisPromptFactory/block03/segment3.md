；新候选使用新的 idempotencyKey。
校验失败时在本会话读取 problems 和 submissionRevision，修正后重交；未知响应只精确重放原请求。
收到 ACCEPTED 后结束，不调用其他工具，不改变冻结证据、权限或报告标准。
