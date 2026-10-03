# 桌面修订的范围排除与保留索引

**DESIGN-ONLY。** 用户明确取消全部手机、窄屏、触控设备设计/适配/验收；新桌面原型采用 1280×900、1440×960、三皮肤。本文仅新增范围索引，**没有删除、skip、改断言、改 fixture 或重新运行任何旧测试**。旧图和原断言是历史证据，不构成本轮继续窄屏开发的要求。W0 已冻结 `3c848261`，正确合同红与本索引分开。

`OUT_OF_SCOPE` 只标旧窄屏展示/布局/截图 variant；不取消仍适用于桌面的业务、权限、原请求身份、dirty/unknown、File bytes、105px/20px、keyboard/focus、订阅或即时资源清理合同。混合测试文件绝不整体标排除。触摸事件作为既有画布资源/取消协议的输入仍保留；不因此设计新触控设备界面。

取消窄屏也**不允许最终零 Vue 门禁遗留执行 Vue/Pinia/Router/.vue 的 fixture**。纯历史图/说明可留；仍执行的桌面/skin/公共语义测试必须迁到 React。纯窄屏原断言可归档为历史合同并保留测试源，但最终可执行 Vue import/入口仍按 W6 无 Vue 门槛清理或迁移，不能借 OUT_OF_SCOPE 白名单隐藏。本轮不执行这些生产/夹具修改。

## 1. 检索方法和计数限制

只读扫描 `frontend/src/**/*spec*`、`frontend/e2e/*.spec.ts` 的 viewport、390/375/320/640/768、mobile/窄屏/narrow 和旧原型 capture/verify。人工区分 viewport 与坐标/元素尺寸；没有把 grep 命中数量当运行用例数。下表覆盖 **58 份 E2E 文件的明确窄屏测试/子分支**，并单列包含缩窄测量的 React PPT 单测、误命中和旧原型。for 参数中的模式、失败状态、数据库厂商和皮肤维度保留；本轮不计算实例化运行总数，不声称重跑通过。

旧 planning 规范由组长在 README、ui-stack-and-page-spec、product-redesign、validation-and-ppt-design 顶端标为历史方案被 [desktop-redesign](desktop-redesign.md) 覆盖；本文不编辑主报告。旧 390/320 图和生成器原循环保留。

## 2. E2E 逐文件、逐窄屏子分支

路径相对 `frontend/e2e/`。下表每一行的窄屏 loop/viewport/截图部分均为 **OUT_OF_SCOPE（展示适配）**，对应桌面实例及其业务、键盘、权限和资源合同保留。测试声明名称仅作为静态定位，`${...}` 参数不是本轮已运行的数量。同文件其他声明默认保留，不能整文件退出准入。

| 原文件/相关声明 | OUT_OF_SCOPE 的实际 loop/viewport 点 | 明确保留 |
| --- | --- | --- |
| `connection-management.spec.ts`<br>:6 连接管理工作区与统一 Git 入口 ${width}px | [:5](../../../frontend/e2e/connection-management.spec.ts#L5)（1024/768/390缩窄variants；1440保留） | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `database-driver-upgrade.spec.ts`<br>:4 openGauss 驱动升级与认证反馈 ${width}px<br>:40 新增 ${label} 并独立传递密码 ${width}px | [:3](../../../frontend/e2e/database-driver-upgrade.spec.ts#L3)、[:33](../../../frontend/e2e/database-driver-upgrade.spec.ts#L33) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `database-progress.spec.ts`<br>:9 数据库分区配置与真实步骤投影 ${width}px | [:8](../../../frontend/e2e/database-progress.spec.ts#L8) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `designer-discussion.spec.ts`<br>:171 历史设计继续需求提问与逐包讨论，再确认为 PENDING_START 任务<br>:303 ${kind} 制品确认保留断言与冻结执行身份<br>:355 ${skin} 历史Designer中的Mermaid由React渲染并保持主题与窄屏（模拟数据） | [:200](../../../frontend/e2e/designer-discussion.spec.ts#L200)、[:343](../../../frontend/e2e/designer-discussion.spec.ts#L343)、[:355](../../../frontend/e2e/designer-discussion.spec.ts#L355)、[:371](../../../frontend/e2e/designer-discussion.spec.ts#L371) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `document-template-tasks.spec.ts`<br>:4 文档评审上传重试、刷新与按需报告 ${width}px | [:3](../../../frontend/e2e/document-template-tasks.spec.ts#L3) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `home.spec.ts`<br>:53 主页在 ${width}px 下无水平溢出且图片离线可用 | [:52](../../../frontend/e2e/home.spec.ts#L52) | 1440/1280离线/skin/真实导航与输入；skin Vue fixture最终迁React |
| `knowledge.spec.ts`<br>:6 文件链接在当前页预览并保留气泡头像，失败不跳到 404<br>:33 统一检索分页、来源覆盖与数据库原文 ${width}px<br>:93 无思考内容时的动态等待与回复切换 ${width}px<br>:124 知识问答来源与引用面板 ${width}px | [:27](../../../frontend/e2e/knowledge.spec.ts#L27)、[:32](../../../frontend/e2e/knowledge.spec.ts#L32)、[:92](../../../frontend/e2e/knowledge.spec.ts#L92)、[:123](../../../frontend/e2e/knowledge.spec.ts#L123) | 1440/1280/1920来源/引用/等待/停止/SSE；桌面nonmodal/focus/resize；原768overlay不再设计 |
| `ppt.spec.ts`<br>:533 窄屏预览和助手分层呈现，无横向溢出，深链刷新保留作品<br>:550 ${skin} 画布按需展开、键盘缩放和取消选择（模拟数据） | [:533](../../../frontend/e2e/ppt.spec.ts#L533)、[:535](../../../frontend/e2e/ppt.spec.ts#L535)、[:611](../../../frontend/e2e/ppt.spec.ts#L611) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `react-all-canvas-cleanup.spec.ts`<br>:32 ${skin} readonly ${kind} 390px 正常pan缩放键盘定位与文字选择保持订阅（模拟数据） | [:32](../../../frontend/e2e/react-all-canvas-cleanup.spec.ts#L32)、[:36](../../../frontend/e2e/react-all-canvas-cleanup.spec.ts#L36) | 105/20、只读/长节点/文字选择/三skin、Pointer与即时listener/RAF/capture/RO/SSE清理合同全部保留；仅屏幕布局/图退出 |
| `react-canvas-migration.spec.ts`<br>:112 ${skin} ${template ? '模板步骤及阶段详情' : '任务阶段'}真实ReactFlow投影与窄屏（模拟数据） | [:112](../../../frontend/e2e/react-canvas-migration.spec.ts#L112)、[:128](../../../frontend/e2e/react-canvas-migration.spec.ts#L128) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `react-canvas-narrow.spec.ts`<br>:6 ${skin} React流程画布窄屏选择、键盘取消和上下文关闭（模拟数据） | [:6](../../../frontend/e2e/react-canvas-narrow.spec.ts#L6)、[:9](../../../frontend/e2e/react-canvas-narrow.spec.ts#L9) | 旧390场景展示归档；Enter/Escape选择关闭回焦、readonly和无业务写仍必须桌面验 |
| `react-canvas-pointer-lifecycle.spec.ts`<br>:322 ${skin} 390px活动指针拖动与退出清理（模拟数据） | [:322](../../../frontend/e2e/react-canvas-pointer-lifecycle.spec.ts#L322)、[:323](../../../frontend/e2e/react-canvas-pointer-lifecycle.spec.ts#L323) | 105/20、只读/长节点/文字选择/三skin、Pointer与即时listener/RAF/capture/RO/SSE清理合同全部保留；仅屏幕布局/图退出 |
| `react-diagram-accessibility.spec.ts`<br>:53 ${skin} 320px ${template ? '模板步骤' : '任务阶段'}长序列Tab定位与原生文字选择不重建订阅（模拟数据） | [:53](../../../frontend/e2e/react-diagram-accessibility.spec.ts#L53)、[:56](../../../frontend/e2e/react-diagram-accessibility.spec.ts#L56) | 105/20、只读/长节点/文字选择/三skin、Pointer与即时listener/RAF/capture/RO/SSE清理合同全部保留；仅屏幕布局/图退出 |
| `roles.spec.ts`<br>:142 ${skin} ${width}px 角色权限预估可读且布局不重叠<br>:219 ${skin} 角色流程采用真实ReactFlow且版本入口可达（模拟数据） | [:141](../../../frontend/e2e/roles.spec.ts#L141)、[:231](../../../frontend/e2e/roles.spec.ts#L231) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `skins.spec.ts`<br>:62 ${skin} 主页 ${width}px 皮肤与离线布局 | [:61](../../../frontend/e2e/skins.spec.ts#L61) | 1440/1280离线/skin/真实导航与输入；skin Vue fixture最终迁React |
| `source-template-form.spec.ts`<br>:13 ${skin} 源码表单 ${width}px 紧凑布局、预检和模板切换 | [:12](../../../frontend/e2e/source-template-form.spec.ts#L12) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `template-batch-recovery.spec.ts`<br>:4 第39批失败后继续后续批次，结束后统一选择重新触发 ${width}px | [:3](../../../frontend/e2e/template-batch-recovery.spec.ts#L3) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `template-batch-resilience.spec.ts`<br>:4 等待任务显示失败清单、原批次恢复与独立重查 ${width}px | [:3](../../../frontend/e2e/template-batch-resilience.spec.ts#L3) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `template-reports.spec.ts`<br>:7 ${type}主子报告跳转、命名下载与${width}px布局 | [:6](../../../frontend/e2e/template-reports.spec.ts#L6) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `template-session-diagnostics.spec.ts`<br>:4 已接受但忙碌的批次可独立收尾，未知请求复用命令标识 ${width}px | [:3](../../../frontend/e2e/template-session-diagnostics.spec.ts#L3) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `template-tasks.spec.ts`<br>:84 模板任务 ${width}px 布局与评分说明 | [:83](../../../frontend/e2e/template-tasks.spec.ts#L83) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-authoring.spec.ts`<br>:44 创建、连接、拖动、保存并重新打开流程；三种皮肤和窄屏可用<br>:101 预设选择固定角色版本，绑定上游后添加节点并保存为可编辑流程<br>:120 程序检查绑定固定代码，保存检查内容并重开，支持窄屏配置<br>:147 命令预设支持独立参数、固定代码输入、保存重开及窄屏配置<br>:185 专业编写、复核和文档汇总绑定同版源码，自定义规则保存重开 | [:44](../../../frontend/e2e/workflow-authoring.spec.ts#L44)、[:75](../../../frontend/e2e/workflow-authoring.spec.ts#L75)、[:109](../../../frontend/e2e/workflow-authoring.spec.ts#L109)、[:120](../../../frontend/e2e/workflow-authoring.spec.ts#L120)、[:142](../../../frontend/e2e/workflow-authoring.spec.ts#L142)、[:147](../../../frontend/e2e/workflow-authoring.spec.ts#L147)、[:161](../../../frontend/e2e/workflow-authoring.spec.ts#L161)、[:218](../../../frontend/e2e/workflow-authoring.spec.ts#L218) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-canvas-focus.spec.ts`<br>:126 跨标签换肤不丢失选中草稿，刷新恢复皮肤，窄屏面板可以关闭 | [:126](../../../frontend/e2e/workflow-canvas-focus.spec.ts#L126)、[:144](../../../frontend/e2e/workflow-canvas-focus.spec.ts#L144) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-code-changes.spec.ts`<br> | [:6](../../../frontend/e2e/workflow-code-changes.spec.ts#L6) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-command.spec.ts`<br>:45 原命令恢复后可查看失败报告和执行记录，按需读取且不显示模型操作<br>:67 依赖准备失败单独显示，检查命令未启动且输出可展开 | [:57](../../../frontend/e2e/workflow-command.spec.ts#L57)、[:79](../../../frontend/e2e/workflow-command.spec.ts#L79) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-default.spec.ts`<br>:60 验收报告${failed ? '保留阻断意见' : '显示同批通过'}且窄屏可读 | [:60](../../../frontend/e2e/workflow-default.spec.ts#L60)、[:67](../../../frontend/e2e/workflow-default.spec.ts#L67) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-document-combination.spec.ts`<br>:7 完整原文流程 ${mode} 结果可读且不虚构执行事实 | [:40](../../../frontend/e2e/workflow-document-combination.spec.ts#L40) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-document-review.spec.ts`<br>:8 需求代码评审 ${mode} 在画布按需读取，保留真实意见 | [:40](../../../frontend/e2e/workflow-document-review.spec.ts#L40) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-document.spec.ts`<br>:10 文档汇总 ${mode} 展示真实状态与固定下载 | [:65](../../../frontend/e2e/workflow-document.spec.ts#L65) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-finish.spec.ts`<br>:46 画布明确确认人工成功，停止未知时仍可查看原节点并在重开后恢复结束记录 | [:61](../../../frontend/e2e/workflow-finish.spec.ts#L61) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-history-analysis.spec.ts`<br>:9 历史${contribution ? '贡献评价' : '代码审查'}交付按需展开且窄屏可读 | [:9](../../../frontend/e2e/workflow-history-analysis.spec.ts#L9)、[:42](../../../frontend/e2e/workflow-history-analysis.spec.ts#L42) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-history-report.spec.ts`<br>:7 完整历史报告按需预览、明细跳转、返回缓存及桌面窄屏显示 | [:7](../../../frontend/e2e/workflow-history-report.spec.ts#L7)、[:45](../../../frontend/e2e/workflow-history-report.spec.ts#L45) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-history.spec.ts`<br>:8 开始前显式选择分支，启动请求保持完整来源身份<br>:37 分支采集${failed ? '未完成可恢复' : '固定文件可读取'}且窄屏可读 | [:34](../../../frontend/e2e/workflow-history.spec.ts#L34)、[:37](../../../frontend/e2e/workflow-history.spec.ts#L37)、[:74](../../../frontend/e2e/workflow-history.spec.ts#L74)、[:79](../../../frontend/e2e/workflow-history.spec.ts#L79) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-input-reference.spec.ts`<br>:5 固定正文按需分页、断线重读、历史内联输入与窄屏显示 | [:5](../../../frontend/e2e/workflow-input-reference.spec.ts#L5)、[:47](../../../frontend/e2e/workflow-input-reference.spec.ts#L47) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-knowledge-evidence.spec.ts`<br> | [:6](../../../frontend/e2e/workflow-knowledge-evidence.spec.ts#L6) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-knowledge-handoff.spec.ts`<br> | [:6](../../../frontend/e2e/workflow-knowledge-handoff.spec.ts#L6) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-native-test.spec.ts`<br>:14 原生单测 ${scenario} | [:62](../../../frontend/e2e/workflow-native-test.spec.ts#L62) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-plan-review.spec.ts`<br>:9 候选先查看、编辑后确认、历史预览和手动后续调整 | [:47](../../../frontend/e2e/workflow-plan-review.spec.ts#L47) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-publication-commit.spec.ts`<br>:6 确认固定成果后恢复同一本地提交，并在重新打开画布后保留记录 | [:52](../../../frontend/e2e/workflow-publication-commit.spec.ts#L52) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-publication-preview.spec.ts`<br>:6 从需求画布选择固定代码成果、查看真实失败状态和累计改动 | [:34](../../../frontend/e2e/workflow-publication-preview.spec.ts#L34) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-publication-push.spec.ts`<br>:6 用户核对远端后明确推送，未知回执重试原请求并在刷新后恢复记录 | [:44](../../../frontend/e2e/workflow-publication-push.spec.ts#L44) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-repository.spec.ts`<br>:8 开始前显式选择分支，启动请求保持完整来源身份<br>:36 分支采集${failed ? '未完成可恢复' : '固定文件可读取'}且窄屏可读 | [:33](../../../frontend/e2e/workflow-repository.spec.ts#L33)、[:36](../../../frontend/e2e/workflow-repository.spec.ts#L36)、[:76](../../../frontend/e2e/workflow-repository.spec.ts#L76) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-requirement.spec.ts`<br>:48 创建需求、确认计划、单步人工执行、读取交付物与保存布局 | [:61](../../../frontend/e2e/workflow-requirement.spec.ts#L61) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-review-source.spec.ts`<br>:8 开始前显式选择分支，启动请求保持完整来源身份<br>:37 分支采集${failed ? '未完成可恢复' : '固定文件可读取'}且窄屏可读<br>:83 切换版本审查范围同步公共日期并保存配置 | [:34](../../../frontend/e2e/workflow-review-source.spec.ts#L34)、[:37](../../../frontend/e2e/workflow-review-source.spec.ts#L37)、[:74](../../../frontend/e2e/workflow-review-source.spec.ts#L74)、[:79](../../../frontend/e2e/workflow-review-source.spec.ts#L79)、[:114](../../../frontend/e2e/workflow-review-source.spec.ts#L114) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-save-template.spec.ts`<br> | [:9](../../../frontend/e2e/workflow-save-template.spec.ts#L9) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-snapshot-analysis.spec.ts`<br>:8 版本${mode === 'review' ? '问题复核' : mode === 'reuse' ? '复用来源' : '代码分析'}交付按需展开且窄屏可读 | [:8](../../../frontend/e2e/workflow-snapshot-analysis.spec.ts#L8)、[:40](../../../frontend/e2e/workflow-snapshot-analysis.spec.ts#L40) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-snapshot-flow-report.spec.ts`<br>:7 完整版本报告按需预览、明细跳转、返回缓存及桌面窄屏显示 | [:7](../../../frontend/e2e/workflow-snapshot-flow-report.spec.ts#L7)、[:45](../../../frontend/e2e/workflow-snapshot-flow-report.spec.ts#L45) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-snapshot-partial-report.spec.ts`<br>:7 取消后按需读取阶段报告、刷新下载及桌面窄屏 | [:7](../../../frontend/e2e/workflow-snapshot-partial-report.spec.ts#L7)、[:41](../../../frontend/e2e/workflow-snapshot-partial-report.spec.ts#L41) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-source-design.spec.ts`<br>:9 专业设计 ${mode} 按需读取交付和依据，窄屏可读 | [:9](../../../frontend/e2e/workflow-source-design.spec.ts#L9)、[:42](../../../frontend/e2e/workflow-source-design.spec.ts#L42) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-source-plan.spec.ts`<br>:20 源码分批${failed ? '超限保留原计划' : '先查看批次再确认且不自动启动'} | [:64](../../../frontend/e2e/workflow-source-plan.spec.ts#L64) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-source.spec.ts`<br>:8 源码采集${failed ? '未完成可恢复' : '固定文件可读取'}且窄屏可读 | [:8](../../../frontend/e2e/workflow-source.spec.ts#L8)、[:44](../../../frontend/e2e/workflow-source.spec.ts#L44) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-test-design.spec.ts`<br>:12 单测场景${malformed ? '损坏格式明确提示' : '步骤期望和源码依据可查看'} | [:47](../../../frontend/e2e/workflow-test-design.spec.ts#L47) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-test-profile.spec.ts`<br>:11 测试配置${failed ? '缺失原因' : '固定范围和命令'}按需查看且不冒充测试通过 | [:50](../../../frontend/e2e/workflow-test-profile.spec.ts#L50) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-test-review.spec.ts`<br>:9 场景复核${failed ? '保留未执行及缺失' : '展示独立意见和固定断言'} | [:40](../../../frontend/e2e/workflow-test-review.spec.ts#L40) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-test-summary.spec.ts`<br>:10 完整单测汇总 ${mode} | [:47](../../../frontend/e2e/workflow-test-summary.spec.ts#L47) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-test-write.spec.ts`<br>:13 单测编写${passed ? '范围通过与测试结果分开' : '失败原因和固定成果仍可查看'} | [:52](../../../frontend/e2e/workflow-test-write.spec.ts#L52) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-upload.spec.ts`<br>:36 上传固定原文、预览并将其绑定到执行请求 | [:43](../../../frontend/e2e/workflow-upload.spec.ts#L43) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |
| `workflow-writeback-preview.spec.ts`<br>:6 普通目录成果先检查冲突，明确确认后支持未知回执、阻断恢复和刷新完成 | [:58](../../../frontend/e2e/workflow-writeback-preview.spec.ts#L58)、[:73](../../../frontend/e2e/workflow-writeback-preview.spec.ts#L73) | 对应1440/1600及其他桌面实例、同用例全部业务/权限/身份/keyboard/resource合同；这里只排窄屏布局/图 |

### 同文件多测试与局部切换的边界

以下容易被误作整文件排除；所有原断言保留：

| 测试声明与窄屏段 | OUT_OF_SCOPE 精确部分 | 明确保留部分 |
| --- | --- | --- |
| `designer-discussion:171 → 200–203` | 640px criterion description/statuses上下排列 | 历史逐包讨论/确认；:204回1280后冻结Task/待正式Start |
| `designer-discussion:303 → 343–344` 各制品kind | 640px断言卡截图 | 制品断言/执行身份；:345回1280后的确认 |
| `designer-discussion:355 → 371–374` 三skins | 390px Mermaid溢出/图 | :359–370真实React安全SVG/主题；:379独立桌面SPA清理不排除 |
| `knowledge:6 → 27–30` | 390px气泡溢出/截图 | 精确path/range、失败不跳404、同页只读预览、关闭回焦 |
| `knowledge:32/93/124` 各width loop | 768实例；:135/140–141小屏overlay/trap | 1440/1280/1920；:129默认隐藏、:147–157桌面分隔条keyboard/resize；消息/停止/SSE/引用 |
| `ppt:533 → 535–546` | 390px布局/图 | 深链刷新必须桌面恢复作品；不能删除通用恢复合同 |
| `ppt:550 → 611–614` 三skins | 最后390px图/溢出 | :554起1600选择/缩放/keyboard；:619拖缩/取消；:650起unknown/409；:782桌面清理 |
| `roles:141` width loop；`:219 → 231–235` 三skins | 390实例/最后窄屏角色图 | 1440正常图；1600主题与pan；ZIP/权限/历史行为不排除 |
| `workflow-authoring:44 → 75–78` | 最后390画布图/溢出 | 创建/连接/拖动/图与layout保存、重开和三skins |
| `workflow-authoring:101 → 109–110` | 390预设截图 | :111回1600后的角色版本/upstream绑定/保存 |
| `workflow-authoring:120 → 142–143` | 390固定代码配置图 | 检查程序固定输入/保存重开 |
| `workflow-authoring:147 → 161–162` | 390命令参数图 | 独立参数/固定代码/保存重开 |
| `workflow-authoring:185 → 218–219` | 390专业源码预设图 | :220回1600后的完整汇总设计节点规则 |
| `workflow-canvas-focus:126 → 144–152` | 窄屏overlay关闭/390图 | :128–143跨标签skin/草稿/刷新；:156导航/搜索/高级设置keyboard |
| `workflow-command:45 → 57`；`:67 → 79–80` | 两个最后390报告图/溢出 | 原命令恢复/按需报告/依赖准备；:60未知stop完全保留 |
| `workflow-review-source:37 → 74–80` failed/ready；`:104 → 114–116` mode | 390报告/审查范围图 | 1600来源/冻结报告/恢复；mode实际输入与许可 |
| `workflow-history:37 → 74–80` failed/ready | 390阶段/报告图 | 1600固定文件、未完成恢复、历史读取与缓存 |
| `workflow-writeback-preview:58–59 / 73–74` | preview/applied最后390图 | 原预览/写回许可/accepted，不自动commit或push |
| `react-all-canvas-cleanup:32 → 36–124` | 390布局/无溢出:108、图:115 | :43–50首帧105/20和ended资源；controls/wheel/Tab/文字选择；:117–123首采样退出RO/订阅/无写须桌面复验。其他桌面退出/触摸取消不排除 |
| `react-diagram-accessibility:53 → 56–120` | 320适配/无溢出:99/图:110 | :65–75长序列/readonly不写，:76–86皮肤焦点，:95–102文字选择/单SSE，:111–120流归属/局部卸载 |
| `react-canvas-pointer-lifecycle:322 → 323–340` | 390overlay/无溢出:331/图:333 | 真drag/capture/route首采样清理/无迟到写；:345中键循环/:359105/20/:375Ctrlwheel完全保留 |

## 3. 单测缩窄测量与误命中

| 原位置 | 分类 | 原因与保留 |
| --- | --- | --- |
| [PptCanvasView.spec.tsx:211](../../../frontend/src/react/ppt/PptCanvasView.spec.tsx#L211)，:224/:228 | **只排除指定窄viewport最终272px展示断言，不整case排除** | 拖动尺寸不变:225、真实patch坐标:229、observer卸载:231仍验，是drag/测量生命周期 |
| [PptCanvasView.spec.tsx:247](../../../frontend/src/react/ppt/PptCanvasView.spec.tsx#L247)，:281/:288 | **局部272px窄viewport展示不作新设计门槛；StrictMode/RO仍IN_SCOPE** | observer replay/每个disconnect、精确release、最新callback/一次patch、卸载后无写保留 |
| [PptCanvasImmediate.spec.tsx:59](../../../frontend/src/react/ppt/PptCanvasImmediate.spec.tsx#L59) | **IN_SCOPE** | queued ResizeObserver320×240是生命周期输入，不是手机界面，首采样cleanup保留 |
| [WorkflowCanvasImmediate.spec.tsx:310](../../../frontend/src/react/workflow/WorkflowCanvasImmediate.spec.tsx#L310)–314 | **IN_SCOPE** | 320为touch坐标，测试双指owned capture/资源，不是viewport |
| `ppt.spec.ts:638` width410/height90 | **IN_SCOPE** | 对象patch尺寸不是410px屏幕 |
| `useKnowledgeSplit` minWidth320 | **IN_SCOPE（桌面上下文）** | 面板min/max不是mobile viewport；桌面separatorkeyboard/focus/cleanup保留 |

## 4. 旧独立原型和 planning 历史

旧多文件、review-single.html、29 PNG、来源hash与capture/verify证据全部保留，不修改或重跑旧生成器。新目录 `prototype/desktop-v2/` 仍验桌面键盘、skin、dirty/unknown、模拟标记与零外部请求。

| 旧入口 | OUT_OF_SCOPE | 保留/替代 |
| --- | --- | --- |
| [capture.mjs:45](prototype/capture.mjs#L45)–54 | 五页×三skin的390/320及对应截图选择 | 1440原断言保留；新桌面5页×3skin×1280/1440独立取证 |
| [capture.mjs:78](prototype/capture.mjs#L78)–79 | 390详情layout fixture | 回答焦点、恢复/停止可达桌面仍验 |
| [capture.mjs:87](prototype/capture.mjs#L87)–88 | mobile navigation layout | 全16真实导航、Escape回焦、keyboard桌面仍验 |
| [capture.mjs:89](prototype/capture.mjs#L89)–91 | 390 reduced-motion图 | reduced-motion功能/焦点桌面仍验 |
| [verify-single.mjs:49](prototype/verify-single.mjs#L49)–86 | 390/320 DOM/像素等价/截图buffer分支 | 1440同源封装/离线原合同保留；新单文件用新源码hash |
| [verify-single.mjs:110](prototype/verify-single.mjs#L110)–111、:119–123 | 390详情/nav/reducedmotion layout | keyboard、高级可达、重复开关、dirty/unknown模拟继续桌面验 |
| [旧README](README.md)、[UI栈](ui-stack-and-page-spec.md)、[产品翻新](product-redesign.md)、[验证专题](validation-and-ppt-design.md) | 390/320规范为历史方案，由desktop-redesign覆盖 | 全31route功能、恢复/权限/单owner、旧失败和最终无Vue门槛保持 |

## 5. 后续准入边界

本轮没有改Playwright/Vitest配置，没有删除test、降断言或新增skip。如何归档纯窄屏历史测试、让混合测试只执行桌面且迁掉旧Vue fixture，是后续获准实施任务；不能把范围调整当通过。

W6/W7框架退出仍检查生产、入口、锁文件、工具链及**可执行测试fixture**。例如 [fixtures/skins.ts:1](../../../frontend/e2e/fixtures/skins.ts#L1)、[:7](../../../frontend/e2e/fixtures/skins.ts#L7)、[SkinsPreview.vue:2](../../../frontend/e2e/fixtures/SkinsPreview.vue#L2) 当前真createApp/Element/`.vue`，1280/三skin合同必须React化。OUT_OF_SCOPE不成为遗留执行Vue的例外。

本文件是静态逐分支盘点，不宣称运行结果。新桌面非作者浏览器由 [desktop-v2-review](desktop-v2-review.md) 记录；所有仍适用的W0正确红按相应波次修，原断言保留。
