# 11 项基线浏览器失败：独立记录

这些失败不计为通过，也不因本轮 React 单测或关联浏览器通过而消失。上一轮在独立 detached 工作区、未经源码修改的 `c26bf3bf7590424bd95bf83c093ae5740a068572` 上逐项重跑，11 项全部复现相同失败。本轮由未实现这些测试的 `/root/react_ppt_canvas` 独立阅读原始失败记录并核对当前源码。原始日志和 trace 保留在 Cloud 证据目录，不进入仓库。

| 测试文件 | 数量 | 失败阶段与实际差异 | 本轮处理 |
| --- | --- | --- | --- |
| [database-progress.spec.ts](../../../frontend/e2e/database-progress.spec.ts) | 2 | 桌面与窄屏等待旧“主机”文本框超时；当前配置表单已采用 JDBC URL 等字段 | 未改断言；不计通过 |
| [document-template-tasks.spec.ts](../../../frontend/e2e/document-template-tasks.spec.ts) | 2 | 业务断言完成后保存 `/private/tmp/loopper-document-template-{1440,390}.png`，Linux Cloud 目录不存在，返回 ENOENT | 未改用例或伪造目标目录；不计通过 |
| [read-consistency.spec.ts](../../../frontend/e2e/read-consistency.spec.ts) | 1 | 等待旧“自动化检测状态”；`/automations` 已重定向 `/template-tasks` | 未恢复退役入口；不计通过 |
| [roles.spec.ts](../../../frontend/e2e/roles.spec.ts) | 6 | 三皮肤 × 桌面/窄屏，期待“仍需运行时核定”，实际为“调用条件待运行时核定” | 本轮 158 项关联 Chromium 中再次复现；原用例未修改；不计通过 |

另外 5 项本轮未再次运行；本轮未执行的 87 项全站用例包含这 5 项。它们保留为已知失败，不能当作已修复、通过或被跳过。

这份清单区分产品迁移回归、历史测试预期失配和运行环境输出路径问题。它不豁免后续全站门禁；需要在另一个明确维护范围中修复测试或确认相应产品合同。本文不把旧失败简单标成 skipped，也不声称其业务链路已由真实后端验证。
