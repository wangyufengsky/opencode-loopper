# 可配置皮肤实施计划与改造清单

## 目标与范围

在保持业务流程、页面结构和科技蓝外观的前提下，建立配置驱动的全站皮肤。主页右上角选择科技蓝或 GitHub 白，立即生效并保存在当前浏览器；刷新与深层链接恢复选择，存储不可用仍可切换。默认科技蓝，不自动跟随系统。新增皮肤只注册一份配置，不在页面判断皮肤 ID。

## 执行顺序

1. [x] 只读盘点全部 Vue/CSS/TS 样式、主入口、静态首页图和第三方渲染器，形成本文。
2. [x] 定义主题配置、注册表、语义样式变量、初始化与保存逻辑；支持第三套配置复用同一接口。
3. [x] 将已有颜色与视觉装饰迁移到科技蓝配置，保留现有布局、尺寸与交互。
4. [x] 新增 GitHub 白配置；按 GitHub Primer 浅色风格适配首页、组件、文档、代码和流程图。
5. [x] 行为测试、双皮肤浏览器与可读性验收、完整 JAR 门禁、版本服务更新、交付记录及本地提交。

## 配置边界

- 集中配置颜色、状态色、排版字体、常用圆角、阴影、渐变装饰及首页插图呈现。页面布局、滚动约束、业务状态不属于皮肤配置。
- 语义变量是页面与配置的接口；现有细微色阶通过兼容色阶映射保留科技蓝原值，新皮肤自动映射到语义色，无需复制历史色阶。
- 主题作用于 html，覆盖挂载到 body 的弹层；切换更新样式与渲染器，不重挂载 RouterView，不清空输入或编辑器历史。
- 配置随前端离线打包，首期不增加后端配置、数据库迁移、远端主题下载或任意 CSS 上传。
- CodeMirror 使用运行时重新配置保留文档、光标与撤销历史；Mermaid 使用主题快照串行渲染，忽略过期结果并保留安全清理。

## 源码盘点（改造前）

扫描 frontend/src 的生产 Vue/CSS/TS，排除测试与 mock。颜色计数包括重复颜色和透明度；不是缺陷数量。下表列出所有匹配颜色或主要外观属性的文件；只有使用语义变量的文件需要验收，但不一定修改。

| 文件 | 颜色字面量 | 背景/阴影/圆角/字体声明 | 颜色所在行（改造前） |
| --- | ---: | ---: | --- |
| `frontend/src/components/AiActivityPanel.vue` | 10 | 9 | 47, 49, 50, 51, 53 |
| `frontend/src/components/CodeMergeEditor.vue` | 22 | 8 | 45, 46, 47, 48, 49, 50, 51, 112, 114, 115, 116, 117, 118, 119, 120 |
| `frontend/src/components/DatabaseConnectionDrawer.vue` | 0 | 3 | — |
| `frontend/src/components/DesignerCurrentActivity.vue` | 26 | 16 | 102, 103, 105, 106, 108, 114, 121, 122, 123 |
| `frontend/src/components/DesignerDiscussionHistory.vue` | 6 | 6 | 42, 53, 57, 61, 62 |
| `frontend/src/components/DesignerSystemMessageHistory.vue` | 5 | 3 | 43, 57, 58, 59 |
| `frontend/src/components/DesignerValidatorHistory.vue` | 10 | 3 | 36, 37, 43, 46, 48, 50, 51, 52 |
| `frontend/src/components/DirtyWorkspaceDialog.vue` | 5 | 6 | 202, 203, 205 |
| `frontend/src/components/DocumentFilePicker.vue` | 0 | 7 | — |
| `frontend/src/components/DocumentFileSummary.vue` | 0 | 4 | — |
| `frontend/src/components/DocumentRequirementsPanel.vue` | 0 | 1 | — |
| `frontend/src/components/DocumentSourcesPanel.vue` | 0 | 1 | — |
| `frontend/src/components/ExecutionAcceptancePanel.vue` | 8 | 10 | 93, 94, 95, 96 |
| `frontend/src/components/ExecutionEvidencePanel.vue` | 0 | 2 | — |
| `frontend/src/components/GitDiffScopeApprovalDialog.vue` | 54 | 40 | 225, 227, 228, 233, 238, 240, 242, 243, 245, 246, 248, 249, 252, 253, 256, 257, 258, 263, 264, 268, 269, 270, 274, 277, 278, 281, 282, 283, 284, 285, 286, 287, 290 |
| `frontend/src/components/JudgeReviewCard.vue` | 15 | 12 | 55, 56, 58, 68, 74, 76, 77, 78, 79 |
| `frontend/src/components/LayeredErrorPanel.vue` | 17 | 14 | 76, 77, 78, 81, 83, 87, 88, 94, 98 |
| `frontend/src/components/LoopSpecEditor.vue` | 40 | 30 | 370, 372, 374, 375, 380, 393, 394, 395, 401, 405, 407, 408, 409, 410, 415, 417, 424, 428, 430, 439, 445, 447 |
| `frontend/src/components/MarkdownDocument.vue` | 47 | 33 | 86, 87, 88, 89, 90, 91, 92, 305, 307, 308, 309, 310, 315, 316, 319, 320, 321, 327, 334, 335, 337, 343, 344, 346, 348, 349, 350, 354, 355, 356, 359 |
| `frontend/src/components/MetricCard.vue` | 0 | 1 | — |
| `frontend/src/components/OpenCodeTodoProgress.vue` | 8 | 18 | 69, 76, 79, 81, 83, 91 |
| `frontend/src/components/PackageGapNotice.vue` | 0 | 1 | — |
| `frontend/src/components/PageHeader.vue` | 3 | 4 | 35 |
| `frontend/src/components/PendingQuestionCard.vue` | 6 | 3 | 88, 89, 94 |
| `frontend/src/components/ProjectAssistDialog.vue` | 0 | 3 | — |
| `frontend/src/components/RollingPackageWorkbench.vue` | 7 | 7 | 339, 344, 345, 347, 351 |
| `frontend/src/components/SessionLifecyclePanel.vue` | 7 | 7 | 60 |
| `frontend/src/components/SessionMonitorPanel.vue` | 41 | 42 | 384, 387, 388, 389, 390, 391, 392, 393, 394 |
| `frontend/src/components/SkillBrowser.vue` | 0 | 1 | — |
| `frontend/src/components/StageRail.vue` | 48 | 24 | 70, 75, 77, 78, 79, 80, 81, 82, 85, 88, 90, 91, 93, 96, 97, 100, 101, 104, 105, 106, 107, 108, 109 |
| `frontend/src/components/StagedFileContextCard.vue` | 21 | 14 | 99, 100, 101, 102, 103, 108, 110, 115, 117 |
| `frontend/src/components/StoryAccountingDialog.vue` | 1 | 3 | 196 |
| `frontend/src/components/TaskAuditEvidencePanel.vue` | 53 | 47 | 218, 222, 224, 227, 229, 231, 232, 233, 234, 237, 238, 240 |
| `frontend/src/components/TaskDecisionPanel.vue` | 9 | 5 | 190, 192 |
| `frontend/src/components/TaskJudgeApprovalPanel.vue` | 0 | 1 | — |
| `frontend/src/components/TaskProfileRouterDialog.vue` | 1 | 2 | 177 |
| `frontend/src/components/TaskPublicationActions.vue` | 53 | 34 | 609, 610, 611, 613, 614, 615, 616, 617, 618 |
| `frontend/src/components/TemplateBatchRecoveryPanel.vue` | 0 | 1 | — |
| `frontend/src/components/TemplateSessionDiagnosticsPanel.vue` | 0 | 3 | — |
| `frontend/src/components/TemplateTaskProgressPanel.vue` | 0 | 10 | — |
| `frontend/src/components/TokenUsageWindow.vue` | 10 | 5 | 62, 64, 65, 66, 72, 79, 97 |
| `frontend/src/styles/app.css` | 112 | 60 | 7, 11, 13, 17, 18, 20, 24, 25, 31, 37, 51, 52, 53, 54, 55, 56, 59, 67, 69, 71, 95, 96, 100, 104, 105, 106, 107, 108, 109, 110, 111, 112, 114, 115, 116, 123, 124, 125, 126, 127, 128, 129, 130, 131, 136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 150, 151, 152, 153, 154, 155, 156, 157, 158, 163, 164, 165, 166, 167, 168, 169, 170, 171, 176, 177, 178, 179, 180, 181, 182, 183, 184, 189, 190, 191, 192, 193, 194, 195, 196, 197, 204, 206, 217 |
| `frontend/src/styles/knowledge.css` | 1 | 90 | 103 |
| `frontend/src/styles/tokens.css` | 21 | 2 | 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 41, 42, 43, 48 |
| `frontend/src/views/AutomationsView.vue` | 9 | 10 | 108 |
| `frontend/src/views/DatabaseView.vue` | 0 | 20 | — |
| `frontend/src/views/DesignerHistoryView.vue` | 2 | 3 | 278, 291 |
| `frontend/src/views/DesignerView.vue` | 146 | 118 | 1892, 1895, 1899, 1901, 1903, 1904, 1909, 1913, 1914, 1920, 1926, 1928, 1932, 1937, 1938, 1939, 1942, 1945, 1946, 1950, 1951, 1953, 1962, 1964, 1969, 1971, 1972, 1973, 1996, 1997, 1998, 2001, 2004, 2005, 2007, 2015, 2016, 2017, 2018, 2019, 2022, 2023, 2024, 2025, 2026, 2028, 2032, 2036, 2041, 2042, 2044, 2045, 2046, 2047, 2048, 2049, 2051, 2052, 2053, 2054, 2055, 2056, 2057, 2058, 2062, 2068, 2073, 2082, 2084, 2093, 2096, 2097, 2100, 2104, 2105, 2108, 2110, 2111, 2112 |
| `frontend/src/views/DocumentTemplateView.vue` | 0 | 1 | — |
| `frontend/src/views/HomeView.vue` | 5 | 17 | 75, 86, 87, 124 |
| `frontend/src/views/InboxView.vue` | 6 | 4 | 132 |
| `frontend/src/views/InsightsDashboardView.vue` | 17 | 8 | 66 |
| `frontend/src/views/ProjectsView.vue` | 23 | 14 | 375, 377, 378, 379, 380, 381 |
| `frontend/src/views/RecoveryStudioView.vue` | 28 | 20 | 115 |
| `frontend/src/views/RuntimeView.vue` | 4 | 5 | 53 |
| `frontend/src/views/SettingsView.vue` | 0 | 11 | — |
| `frontend/src/views/TaskDesignHistoryView.vue` | 27 | 29 | 169, 174, 176, 180, 182, 183, 193, 200, 201, 202, 203, 204, 205, 207, 210, 212, 216, 221, 224 |
| `frontend/src/views/TaskDetailView.vue` | 21 | 15 | 469, 470, 471, 472, 473, 476, 478, 480, 484, 485, 486 |
| `frontend/src/views/TasksView.vue` | 7 | 8 | 298, 299, 303 |
| `frontend/src/views/TemplateTasksView.vue` | 0 | 3 | — |

## 配置及行为入口

| 位置 | 修改/检查内容 |
| --- | --- |
| frontend/index.html、frontend/src/main.ts | 首屏应用保存的主题、color-scheme、浏览器 theme-color，无暗色闪现 |
| frontend/src/styles/tokens.css | 保留语义接口，补全状态背景、按钮、链接、边框、排版与装饰配置 |
| frontend/src/styles/app.css | 侧栏/导航/页面背景、全部 Element Plus 按钮状态、表格/弹窗/提示、骨架屏 |
| frontend/src/views/HomeView.vue、components/PageHeader.vue | 主页右上角选择器；科技蓝图片保留，浅色欢迎区由配置控制 |
| frontend/src/components/CodeMergeEditor.vue | 语法高亮、背景、行号、光标、选择、差异与冲突装饰；运行时更新 |
| frontend/src/components/MarkdownDocument.vue | Markdown、代码块、表格、思考卡、Mermaid SVG 重绘与异步过期保护 |
| frontend/src/assets/home-orbit.png | 原素材不修改；其可见性、混合与遮罩由皮肤配置提供 |
| frontend/src/components/StatusBadge.vue、LayeredErrorPanel.vue | 确保成功/待处理/故障等语义和可读性不随皮肤改变 |
| frontend/src/router/index.ts、App.vue | 保持路由与组件生命周期；全站继承主题 |
| frontend/e2e/home.spec.ts、app-shell.spec.ts | 保留科技蓝基线，增加双皮肤、持久化、深层链接和窄屏验收 |
| frontend/AGENTS.md、docs/design-contract.md、README.md | 同步配置约束、默认皮肤、选择和扩展方法 |

## 验收标准

- 两套配置完整且使用同一结构；未来添加主题不修改已有页面。非法/过期保存值回到科技蓝，存储拒绝不影响启动。
- 主页选择即时生效，刷新、新开同源标签和深层链接恢复选择；同源标签接收主题变更。
- 科技蓝现有颜色基线保留；GitHub 白无意外深色面板、白字白底或警告不清晰。
- 下拉/弹窗/禁用与 hover/focus 状态、Markdown/代码/流程图都与主题一致。
- 改变配置不丢失表单/编辑器内容、选择和已有状态；Mermaid 安全配置与 DOMPurify 保持。
- 1440/1280/390 宽度、键盘操作、减少动态效果、离线资源可用。
- 先聚焦与浏览器验收，再稳定候选完整门禁；最终交付包含 JAR、哈希、运行版本/PID、备份及本地提交。

## 风险与处理

- 原样式分散：按以上清单集中迁移，并加入源码颜色检查防止漏项及回退。
- 同一颜色兼作按钮前景/正文：区分固定对比色与语义文字色，不能无条件反色。
- 流程图初始化为共享可变状态：渲染时冻结主题并串行调用，切换后重新渲染已经生成的图。
- 图像不能随 CSS 变色：科技蓝保留原图；GitHub 白使用配置控制的简洁欢迎区。

参考：GitHub 官方 [Primer Primitives](https://primer.style/product/primitives/)。

## 完成记录

以上五步已完成。测试、正式 JAR、服务替换与截图见 [0.4.59 交付记录](../deliveries/0.4.59.md)。
