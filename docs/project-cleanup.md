# 0.3.81 项目清理

本次检查覆盖生产 Java、测试、前端模块与导出引用、构建配置、当前合同、历史文档及本地生成目录。删除依据是源码/测试引用、框架入口检查与 GitNexus 调用图；无调用方的方法与类型经过全量编译和回归验证，不能将关键词 `legacy` 或旧版本号本身作为删除依据。

## 代码

- 删除 17 个无调用方的方法或查询入口，涉及 Designer 旧转发、候选 launch 查询、附件冻结包装、报告包装、交互刷新包装和路径匹配包装；保留当前编排入口与共享实现。
- 删除 `ProcessCommandPolicy` 中无人调用的旧测试跳过判定及其辅助方法；当前入口继续委托 `TestFrameworkPolicy`。补充 Maven、Gradle、npm 的跳过/容忍无测试参数与正常命令回归。
- 删除两个未使用 DTO（`WorkspacePreviewDto`、`TaskSseEvent`）、未使用枚举 `AcceptanceCandidateInternalLaunchCleanupPurpose`、两个常量及 32 个无用导入（生产代码 25 个、测试代码 7 个）。持久化字段、迁移和真实 API 返回类型保持。
- 将三个历史大类的物理行数门禁收紧至实际值：DesignerSessionService 5,373、TaskService 2,726、LocalSyncConflictService 1,159。前端继续由已有 TypeScript 未使用变量/参数检查、Vitest 与构建覆盖；未找到可证明无用的独立页面或组件。

## 当前语义与历史资料

MCP 默认无限提交与 V70/V71 可冻结的 2–16 次总提交策略在公约和五份合同中统一说明。验收闭集不再宣称生产候选固定“两次提交”；固定资格夹具的 `1/1/1..2` 仍作为该夹具的测量预期。所有既有 Flyway 迁移均不可改写，当前序号至 V75。

原 README 发布条目、AGENTS.md 维护表和 `.scratch/weak-model-compiler-v7/` 六份已完成工单迁至 [历史资料索引](history/README.md)。工单与维护记录保留原文，发布说明仅调整迁移后的相对链接。当前 README 与公约直接说明当前版本和规则。

## 文件清理及交付边界

根目录解包残留 `META-INF/`、根目录 `.DS_Store` 和过期 `frontend/test-results/.last-run.json` 移至仓库外的临时备份，保留清单和校验值以便恢复。`target/` 由正常 `clean verify` 重建，前端构建目录由正式构建刷新；运行数据库、日志、IDE 配置和 GitNexus 索引不作垃圾删除。

有限域/SAT/独立来源复核实验保持撤回；V74 原文、V75 拒绝含实验数据的启动守卫和全部历史评测证据继续保留。Legacy 读取、恢复、停止确认和历史预算身份也继续保留。

本次不调用真实模型、不启动或重启服务，不声明活动 JVM 或浏览器已更新。正式验证结果、JAR 校验值见 [维护记录](../AGENTS.md#12-维护记录)。
