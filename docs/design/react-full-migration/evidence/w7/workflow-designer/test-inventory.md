# C 初始 E2E 源码盘点

基线 `1e9161e06c01b5aa76b6a2b19789ac8379423195`。方法：只解析 TypeScript AST 中的 test 声明及显式数组循环；没有执行测试定义或浏览器。40 文件、164 静态展开项；160 桌面合同、4 整项窄屏排除。混合用例的窄屏段只排布局/图片，桌面业务仍保。实际 Playwright 清单待统一环境核对。

所有定义标题保原样。每条标题下给出定义行及该项可静态识别的 viewport 行；无显式 viewport 使用原项目桌面默认。生产 W5 条目通过 prepared() 固定1440px，因此其 viewport 在 callback 内的静态列表为空，不能误解为窄屏。

## frontend/e2e/workflow-authoring.spec.ts

基线 SHA256：`0766ebe15b70dfc52d4fbb7d10e2cd1d4446732374865b462c9eacf47cab2686`。8 个声明，8 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 44 | 创建、连接、拖动、保存并重新打开流程；三种皮肤和窄屏可用 | 桌面保留 | 75 (390px) |
| 81 | 在画布上拒绝循环连接，并保留已有节点和连接 | 桌面保留 | — |
| 91 | 内置流程允许点击查看节点，但不能拖动修改 | 桌面保留 | — |
| 101 | 预设选择固定角色版本，绑定上游后添加节点并保存为可编辑流程 | 桌面保留 | 109 (390px) |
| 120 | 程序检查绑定固定代码，保存检查内容并重开，支持窄屏配置 | 桌面保留 | 142 (390px) |
| 147 | 命令预设支持独立参数、固定代码输入、保存重开及窄屏配置 | 桌面保留 | 161 (390px) |
| 166 | 冻结源码预设绑定路径，保存采集用途并保留完整采集规则 | 桌面保留 | — |
| 185 | 专业编写、复核和文档汇总绑定同版源码，自定义规则保存重开 | 桌面保留 | 218 (390px) |

## frontend/e2e/workflow-canvas-focus.spec.ts

基线 SHA256：`12f608336e994a55ef331a0f19c6ff6f256aede4c769e904e964813d7d05c32d`。6 个声明，12 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 15 | spdb 流程画布默认留白，选择、切换、连线和取消形成完整循环 | 桌面保留 | — |
| 65 | spdb 需求规划与执行均按选择展示详情，资料与辅助操作仍可达 | 桌面保留 | — |
| 15 | tech-blue 流程画布默认留白，选择、切换、连线和取消形成完整循环 | 桌面保留 | — |
| 65 | tech-blue 需求规划与执行均按选择展示详情，资料与辅助操作仍可达 | 桌面保留 | — |
| 15 | github-white 流程画布默认留白，选择、切换、连线和取消形成完整循环 | 桌面保留 | — |
| 65 | github-white 需求规划与执行均按选择展示详情，资料与辅助操作仍可达 | 桌面保留 | — |
| 99 | spdb 流程库、需求列表和新建需求的入口与视觉一致 | 桌面保留 | — |
| 99 | tech-blue 流程库、需求列表和新建需求的入口与视觉一致 | 桌面保留 | — |
| 99 | github-white 流程库、需求列表和新建需求的入口与视觉一致 | 桌面保留 | — |
| 126 | 跨标签换肤不丢失选中草稿，刷新恢复皮肤，窄屏面板可以关闭 | 桌面保留 | 144 (390px) |
| 156 | 导航键盘闭环、搜索画布外节点与高级设置保持可达且不造成布局修改 | 桌面保留 | — |
| 190 | 不展开需求与资料也会使用配置的默认模型，且不预读模型目录 | 桌面保留 | — |

## frontend/e2e/workflow-code-changes.spec.ts

基线 SHA256：`ce5e17194e79d0a8bf826831338ac41579da10c2388e07ffa4c27370b12e672d`。1 个声明，2 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 6 | 固定代码的累计改动和删除文件 1600 | 桌面保留 | — |
| 6 | 固定代码的累计改动和删除文件 390 | OUT_OF_SCOPE (整项390px) | 41 (390px) |

## frontend/e2e/workflow-command.spec.ts

基线 SHA256：`282c3614f231603db49c048eb9a1b1883c742928dc874fe812f3d135beb88171`。3 个声明，3 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 45 | 原命令恢复后可查看失败报告和执行记录，按需读取且不显示模型操作 | 桌面保留 | 57 (390px) |
| 60 | 命令停止回执未知时重放原操作，收到回执后保持等待停止确认 | 桌面保留 | — |
| 67 | 依赖准备失败单独显示，检查命令未启动且输出可展开 | 桌面保留 | 79 (390px) |

## frontend/e2e/workflow-default.spec.ts

基线 SHA256：`951b2e4c91b7486495babbd461bb2a39f5fa33a5c8371754617c8b65fdbc112d`。2 个声明，3 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 45 | 内置默认流程显示八个模块，新需求读取默认流程版本 | 桌面保留 | — |
| 60 | 验收报告显示同批通过且窄屏可读 | 桌面保留 | 67 (390px) |
| 60 | 验收报告保留阻断意见且窄屏可读 | 桌面保留 | 67 (390px) |

## frontend/e2e/workflow-document-combination.spec.ts

基线 SHA256：`5b9964d6454ff42c41f8d8b8634f55a3ba034a6e8c7db8124d07e54dcb4ab124`。1 个声明，4 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 7 | 完整原文流程 plan 结果可读且不虚构执行事实 | 桌面保留 | 40 (390px) |
| 7 | 完整原文流程 reviewed 结果可读且不虚构执行事实 | 桌面保留 | 40 (390px) |
| 7 | 完整原文流程 unreviewed 结果可读且不虚构执行事实 | 桌面保留 | 40 (390px) |
| 7 | 完整原文流程 local-review 结果可读且不虚构执行事实 | 桌面保留 | 40 (390px) |

## frontend/e2e/workflow-document-review.spec.ts

基线 SHA256：`fa15b537b9ff6658ee3eac339c8c8e686f2b5c1739aa720d165aef6c1e9c6d7b`。1 个声明，3 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 8 | 需求代码评审 assessment 在画布按需读取，保留真实意见 | 桌面保留 | 40 (390px) |
| 8 | 需求代码评审 pass 在画布按需读取，保留真实意见 | 桌面保留 | 40 (390px) |
| 8 | 需求代码评审 revise 在画布按需读取，保留真实意见 | 桌面保留 | 40 (390px) |

## frontend/e2e/workflow-document.spec.ts

基线 SHA256：`e1f53fead89846eb8fcf03853b905572a967bd6ee409a1cda247eaeeb11d77cd`。1 个声明，3 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 10 | 文档汇总 reviewed 展示真实状态与固定下载 | 桌面保留 | 65 (390px) |
| 10 | 文档汇总 unreviewed 展示真实状态与固定下载 | 桌面保留 | 65 (390px) |
| 10 | 文档汇总 incomplete 展示真实状态与固定下载 | 桌面保留 | 65 (390px) |

## frontend/e2e/workflow-finish.spec.ts

基线 SHA256：`011a4017a416f83a5c147b7c643bfbe820d18b4fea29248d84e2566ae992f3b6`。2 个声明，2 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 46 | 画布明确确认人工成功，停止未知时仍可查看原节点并在重开后恢复结束记录 | 桌面保留 | 61 (390px) |
| 64 | 结束回执丢失后重试同一操作，用户决定不会重复应用 | 桌面保留 | — |

## frontend/e2e/workflow-history-analysis.spec.ts

基线 SHA256：`fde538c56059c411a2605956511f072079f8bf5741010f858c13367f97b7ae6a`。1 个声明，2 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 9 | 历史代码审查交付按需展开且窄屏可读 | 桌面保留 | 42 (390px) |
| 9 | 历史贡献评价交付按需展开且窄屏可读 | 桌面保留 | 42 (390px) |

## frontend/e2e/workflow-history-report.spec.ts

基线 SHA256：`7a16ec2c530e7462018945f90aa95a76af45b66b6c1e382c5b3fdff383ffaed3`。1 个声明，1 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 7 | 完整历史报告按需预览、明细跳转、返回缓存及桌面窄屏显示 | 桌面保留 | 45 (390px) |

## frontend/e2e/workflow-history.spec.ts

基线 SHA256：`a6440a42ea5ba8fb3d81d50b414a6e43eaebdf99e0eb9afc7686ab0969038482`。2 个声明，3 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 8 | 开始前显式选择分支，启动请求保持完整来源身份 | 桌面保留 | 34 (390px) |
| 37 | 分支采集固定文件可读取且窄屏可读 | 桌面保留 | 74 (390px), 79 (390px) |
| 37 | 分支采集未完成可恢复且窄屏可读 | 桌面保留 | 74 (390px), 79 (390px) |

## frontend/e2e/workflow-input-reference.spec.ts

基线 SHA256：`473317ed4ed4d465f3e77f991e8c721815545cb8817df3534c0c276151022b5b`。1 个声明，1 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 5 | 固定正文按需分页、断线重读、历史内联输入与窄屏显示 | 桌面保留 | 47 (390px) |

## frontend/e2e/workflow-knowledge-evidence.spec.ts

基线 SHA256：`9fb7ec5bb190ba8fc7cf854fae8f15d7d6908d0bfe0323b10163070e11261892`。1 个声明，2 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 6 | 节点知识原文按需查看与恢复 1600 | 桌面保留 | — |
| 6 | 节点知识原文按需查看与恢复 390 | OUT_OF_SCOPE (整项390px) | 38 (390px) |

## frontend/e2e/workflow-knowledge-handoff.spec.ts

基线 SHA256：`de60294db6d0131c963ff7f5547c95b5ab53949f084a39aa6e9fe91eed15f2d2`。1 个声明，2 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 6 | 固定知识交付及后继输入 1600 | 桌面保留 | — |
| 6 | 固定知识交付及后继输入 390 | OUT_OF_SCOPE (整项390px) | 39 (390px) |

## frontend/e2e/workflow-native-test.spec.ts

基线 SHA256：`b5bb5ce728fbebbcf25ef879cbf8ad7696d5e26f465afe088b3010ccc804c1b1`。1 个声明，5 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 14 | 原生单测 passed | 桌面保留 | 62 (390px) |
| 14 | 原生单测 failed | 桌面保留 | 62 (390px) |
| 14 | 原生单测 input-changed | 桌面保留 | 62 (390px) |
| 14 | 原生单测 final-passed | 桌面保留 | 62 (390px) |
| 14 | 原生单测 final-failed | 桌面保留 | 62 (390px) |

## frontend/e2e/workflow-plan-review.spec.ts

基线 SHA256：`c00108bfa573d8bd8f6f675934547f083065e99805946b95595144fdc94da8c7`。1 个声明，1 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 9 | 候选先查看、编辑后确认、历史预览和手动后续调整 | 桌面保留 | 47 (390px) |

## frontend/e2e/workflow-pointer-contract.spec.ts

基线 SHA256：`085e3d98b5fa5c4e48715f3dfe65b1f5a5960dec99fb0b64e5a4d7fe3617f0fb`。5 个声明，6 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 19 | 应用指针连线继续由原业务所有者拒绝重复、循环与自身连接（模拟数据） | 桌面保留 | — |
| 38 | 反向端口指针连接只生成一次原方向意图与撤销记录，键盘连接继续可达（模拟数据） | 桌面保留 | — |
| 63 | 执行需求允许显示位置拖动但不能通过端口或键盘编辑业务图（模拟数据） | 桌面保留 | — |
| 84 | 正向端口两次真实点按保持原方向且只提交一次意图（模拟数据） | 桌面保留 | — |
| 84 | 反向端口两次真实点按保持原方向且只提交一次意图（模拟数据） | 桌面保留 | — |
| 104 | 端口点按与原加号连接模式互斥，不保留第二个连接意图（模拟数据） | 桌面保留 | — |

## frontend/e2e/workflow-publication-commit.spec.ts

基线 SHA256：`1956f8dc4b7eb19d8fa36039b86b1b9f1bda7e1d218bdaca92cc193207205f8d`。1 个声明，1 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 6 | 确认固定成果后恢复同一本地提交，并在重新打开画布后保留记录 | 桌面保留 | 52 (390px) |

## frontend/e2e/workflow-publication-preview.spec.ts

基线 SHA256：`7c220538d4cfdcd2e34b0efa0359d131bea7c828f2dc0444c3d3dba5b25848d3`。1 个声明，1 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 6 | 从需求画布选择固定代码成果、查看真实失败状态和累计改动 | 桌面保留 | 34 (390px) |

## frontend/e2e/workflow-publication-push.spec.ts

基线 SHA256：`4337af0e55d51f2eff3ce1debf7ed649b2029d4fbc973b385462ef3161d597ef`。1 个声明，1 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 6 | 用户核对远端后明确推送，未知回执重试原请求并在刷新后恢复记录 | 桌面保留 | 44 (390px) |

## frontend/e2e/workflow-repository.spec.ts

基线 SHA256：`e1f64122ae55d47be3e37accbe1caf5ca27f0c4f790055e6ee3d1f7c18e981f3`。2 个声明，3 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 8 | 开始前显式选择分支，启动请求保持完整来源身份 | 桌面保留 | 33 (390px) |
| 36 | 分支采集固定文件可读取且窄屏可读 | 桌面保留 | 76 (390px) |
| 36 | 分支采集未完成可恢复且窄屏可读 | 桌面保留 | 76 (390px) |

## frontend/e2e/workflow-requirement.spec.ts

基线 SHA256：`738ee2d8b714fab2de0926e5a4164cf596ab192c7d3003a813c5ce032aba1a62`。4 个声明，4 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 48 | 创建需求、确认计划、单步人工执行、读取交付物与保存布局 | 桌面保留 | 61 (390px) |
| 64 | 重新打开检查点仍需明确确认交付物后才可继续 | 桌面保留 | — |
| 69 | 需求规划从预设添加任务、保存并重开，不自动确认或执行 | 桌面保留 | — |
| 78 | 失败的程序检查保留报告和重试入口，不显示仍在收尾或模型操作 | 桌面保留 | — |

## frontend/e2e/workflow-review-source.spec.ts

基线 SHA256：`77b284f90627c9d42a8bce7669208e82a6d040dfa8087e7e3db3c4df3f2d1a05`。3 个声明，4 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 8 | 开始前显式选择分支，启动请求保持完整来源身份 | 桌面保留 | 34 (390px) |
| 37 | 分支采集固定文件可读取且窄屏可读 | 桌面保留 | 74 (390px), 79 (390px) |
| 37 | 分支采集未完成可恢复且窄屏可读 | 桌面保留 | 74 (390px), 79 (390px) |
| 83 | 切换版本审查范围同步公共日期并保存配置 | 桌面保留 | 114 (390px) |

## frontend/e2e/workflow-save-template.spec.ts

基线 SHA256：`42c89652cce670a1da5a1d620f474ab50774c2293fcad6fa4f8d58e52bd081e9`。1 个声明，2 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 9 | 另存流程先预览、保留当前步骤及未知回执 1600 | 桌面保留 | — |
| 9 | 另存流程先预览、保留当前步骤及未知回执 390 | OUT_OF_SCOPE (整项390px) | 31 (390px) |

## frontend/e2e/workflow-snapshot-analysis.spec.ts

基线 SHA256：`f57957467629b31df15aebc513936a5de4463874e903f7c6e542c72b397b8902`。1 个声明，3 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 8 | 版本代码分析交付按需展开且窄屏可读 | 桌面保留 | 40 (390px) |
| 8 | 版本问题复核交付按需展开且窄屏可读 | 桌面保留 | 40 (390px) |
| 8 | 版本复用来源交付按需展开且窄屏可读 | 桌面保留 | 40 (390px) |

## frontend/e2e/workflow-snapshot-flow-report.spec.ts

基线 SHA256：`a4d0f3c6df226d64c2ab8fb04de38c5d1163c4f94e879c530bf68efe2c52a9dc`。1 个声明，1 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 7 | 完整版本报告按需预览、明细跳转、返回缓存及桌面窄屏显示 | 桌面保留 | 45 (390px) |

## frontend/e2e/workflow-snapshot-partial-report.spec.ts

基线 SHA256：`fb6c8fada850f1719e78c047eb6fabb866fc595b0b97e4f31b89fa2b39d1e30e`。1 个声明，1 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 7 | 取消后按需读取阶段报告、刷新下载及桌面窄屏 | 桌面保留 | 41 (390px) |

## frontend/e2e/workflow-source-design.spec.ts

基线 SHA256：`c65ece360cf1b72760ee0390bf79592f890f058db836b19eb1c4b4c94c4571a3`。1 个声明，3 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 9 | 专业设计 design 按需读取交付和依据，窄屏可读 | 桌面保留 | 42 (390px) |
| 9 | 专业设计 pass 按需读取交付和依据，窄屏可读 | 桌面保留 | 42 (390px) |
| 9 | 专业设计 revise 按需读取交付和依据，窄屏可读 | 桌面保留 | 42 (390px) |

## frontend/e2e/workflow-source-plan.spec.ts

基线 SHA256：`bfb929ab298ebb0501778546544208ebc1bf4e6890fdeb9d47445624bd37637f`。1 个声明，2 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 20 | 源码分批先查看批次再确认且不自动启动 | 桌面保留 | 64 (390px) |
| 20 | 源码分批超限保留原计划 | 桌面保留 | 64 (390px) |

## frontend/e2e/workflow-source.spec.ts

基线 SHA256：`9adf48268ae242466258172ed4492f9378c18608925db09dab4d129a9ff71277`。1 个声明，2 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 8 | 源码采集固定文件可读取且窄屏可读 | 桌面保留 | 44 (390px) |
| 8 | 源码采集未完成可恢复且窄屏可读 | 桌面保留 | 44 (390px) |

## frontend/e2e/workflow-test-design.spec.ts

基线 SHA256：`6caa926d25accbebac43edfd4fd9fdfbd2013a2d9451425a7d3cd4bd3f8e4d0f`。1 个声明，2 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 12 | 单测场景步骤期望和源码依据可查看 | 桌面保留 | 47 (390px) |
| 12 | 单测场景损坏格式明确提示 | 桌面保留 | 47 (390px) |

## frontend/e2e/workflow-test-profile.spec.ts

基线 SHA256：`10ee78d303e77987a2a96b67ece9a1a0da90797a759520d8e780a6e8a338310a`。1 个声明，2 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 11 | 测试配置固定范围和命令按需查看且不冒充测试通过 | 桌面保留 | 50 (390px) |
| 11 | 测试配置缺失原因按需查看且不冒充测试通过 | 桌面保留 | 50 (390px) |

## frontend/e2e/workflow-test-review.spec.ts

基线 SHA256：`27f42f44d8da8ade6f907603543e8b03d359b4630c0b16f3c5b4f646eaad4cab`。1 个声明，2 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 9 | 场景复核展示独立意见和固定断言 | 桌面保留 | 40 (390px) |
| 9 | 场景复核保留未执行及缺失 | 桌面保留 | 40 (390px) |

## frontend/e2e/workflow-test-summary.spec.ts

基线 SHA256：`cece46d9387acfd262472ab14bf342b33d9068a81c2b11f74bde8bf1f7e674b4`。1 个声明，3 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 10 | 完整单测汇总 passed | 桌面保留 | 47 (390px) |
| 10 | 完整单测汇总 none | 桌面保留 | 47 (390px) |
| 10 | 完整单测汇总 failed | 桌面保留 | 47 (390px) |

## frontend/e2e/workflow-test-write.spec.ts

基线 SHA256：`58b5ba3cec4ce02dcaeaf882ac8d55915811e754868fba649edf6ac478f6979f`。1 个声明，2 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 13 | 单测编写范围通过与测试结果分开 | 桌面保留 | 52 (390px) |
| 13 | 单测编写失败原因和固定成果仍可查看 | 桌面保留 | 52 (390px) |

## frontend/e2e/workflow-upload.spec.ts

基线 SHA256：`750391b9f88b7d7a6ec96d3b5fd1303a8fb68e85097a8bcadc7852bf7c4cdd5a`。3 个声明，3 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 36 | 上传固定原文、预览并将其绑定到执行请求 | 桌面保留 | 43 (390px) |
| 47 | 上传失败后刷新页面，选择原记录补传并保留幂等身份 | 桌面保留 | — |
| 57 | 上传回执未知时拒绝关闭、Escape与离页，并按原身份重试 | 桌面保留 | — |

## frontend/e2e/workflow-writeback-preview.spec.ts

基线 SHA256：`bbf16362cc6ee36ea2b2e88b2398bb762548f78c192e7b2c3486a8a07cc97a95`。1 个声明，1 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 6 | 普通目录成果先检查冲突，明确确认后支持未知回执、阻断恢复和刷新完成 | 桌面保留 | 58 (390px), 73 (390px) |

## frontend/e2e/designer-discussion.spec.ts

基线 SHA256：`116624454fb4d811886831a1d2945102a178261226a2e9a997d0be91dd7fd8b1`。6 个声明，9 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 171 | 历史设计继续需求提问与逐包讨论，再确认为 PENDING_START 任务 | 桌面保留 | 200 (640px) |
| 212 | 附件设计投递失败时展示具体原因并在刷新后保留附件历史 | 桌面保留 | — |
| 253 | 已开启全自动的历史设计继续原冻结流程进入已启动任务 | 桌面保留 | — |
| 303 | document 制品确认保留断言与冻结执行身份 | 桌面保留 | 343 (640px) |
| 303 | table 制品确认保留断言与冻结执行身份 | 桌面保留 | 343 (640px) |
| 355 | spdb 历史Designer中的Mermaid由React渲染并保持主题与窄屏（模拟数据） | 桌面保留 | 371 (390px) |
| 355 | tech-blue 历史Designer中的Mermaid由React渲染并保持主题与窄屏（模拟数据） | 桌面保留 | 371 (390px) |
| 355 | github-white 历史Designer中的Mermaid由React渲染并保持主题与窄屏（模拟数据） | 桌面保留 | 371 (390px) |
| 379 | 历史Designer静态Mermaid三次真实SPA退出清理SVG渲染残留与观察器（模拟数据） | 桌面保留 | — |

## frontend/e2e/w5/production-routes.spec.ts

基线 SHA256：`51be39b1b44b997524a778fdaee333aca388476fd88fe181380afb3fa185be38`。10 个声明，49 个展开项。

| 定义行 | 精确展开标题 | 范围 | 窄屏段行 |
| --- | --- | --- | --- |
| 81 | spdb production workflow-new direct/reload/back/forward and 3 strict immediate route exits | 桌面保留 | — |
| 81 | spdb production workflow-edit direct/reload/back/forward and 3 strict immediate route exits | 桌面保留 | — |
| 81 | spdb production requirement-new direct/reload/back/forward and 3 strict immediate route exits | 桌面保留 | — |
| 81 | spdb production requirement-plan direct/reload/back/forward and 3 strict immediate route exits | 桌面保留 | — |
| 81 | spdb production designer direct/reload/back/forward and 3 strict immediate route exits | 桌面保留 | — |
| 81 | tech-blue production workflow-new direct/reload/back/forward and 3 strict immediate route exits | 桌面保留 | — |
| 81 | tech-blue production workflow-edit direct/reload/back/forward and 3 strict immediate route exits | 桌面保留 | — |
| 81 | tech-blue production requirement-new direct/reload/back/forward and 3 strict immediate route exits | 桌面保留 | — |
| 81 | tech-blue production requirement-plan direct/reload/back/forward and 3 strict immediate route exits | 桌面保留 | — |
| 81 | tech-blue production designer direct/reload/back/forward and 3 strict immediate route exits | 桌面保留 | — |
| 81 | github-white production workflow-new direct/reload/back/forward and 3 strict immediate route exits | 桌面保留 | — |
| 81 | github-white production workflow-edit direct/reload/back/forward and 3 strict immediate route exits | 桌面保留 | — |
| 81 | github-white production requirement-new direct/reload/back/forward and 3 strict immediate route exits | 桌面保留 | — |
| 81 | github-white production requirement-plan direct/reload/back/forward and 3 strict immediate route exits | 桌面保留 | — |
| 81 | github-white production designer direct/reload/back/forward and 3 strict immediate route exits | 桌面保留 | — |
| 101 | actual workflow-new root shutdown immediately releases every owned resource | 桌面保留 | — |
| 101 | actual workflow-edit root shutdown immediately releases every owned resource | 桌面保留 | — |
| 101 | actual requirement-new root shutdown immediately releases every owned resource | 桌面保留 | — |
| 101 | actual requirement-plan root shutdown immediately releases every owned resource | 桌面保留 | — |
| 101 | actual designer root shutdown immediately releases every owned resource | 桌面保留 | — |
| 107 | native Designer SSE exact reconnect cursor + REST authority + route strict cleanup | 桌面保留 | — |
| 107 | native Designer SSE exact reconnect cursor + REST authority + root strict cleanup | 桌面保留 | — |
| 119 | workflow-edit actual active pan route releases resources before any subsequent input | 桌面保留 | — |
| 119 | workflow-edit actual active pan root releases resources before any subsequent input | 桌面保留 | — |
| 119 | workflow-edit actual active drag route releases resources before any subsequent input | 桌面保留 | — |
| 119 | workflow-edit actual active drag root releases resources before any subsequent input | 桌面保留 | — |
| 119 | workflow-edit actual active connect route releases resources before any subsequent input | 桌面保留 | — |
| 119 | workflow-edit actual active connect root releases resources before any subsequent input | 桌面保留 | — |
| 119 | requirement-plan actual active pan route releases resources before any subsequent input | 桌面保留 | — |
| 119 | requirement-plan actual active pan root releases resources before any subsequent input | 桌面保留 | — |
| 119 | requirement-plan actual active drag route releases resources before any subsequent input | 桌面保留 | — |
| 119 | requirement-plan actual active drag root releases resources before any subsequent input | 桌面保留 | — |
| 119 | requirement-plan actual active connect route releases resources before any subsequent input | 桌面保留 | — |
| 119 | requirement-plan actual active connect root releases resources before any subsequent input | 桌面保留 | — |
| 139 | spdb actual new requirement UNKNOWN blocks departure, keeps four fields and explicitly retries identical POST | 桌面保留 | — |
| 139 | tech-blue actual new requirement UNKNOWN blocks departure, keeps four fields and explicitly retries identical POST | 桌面保留 | — |
| 139 | github-white actual new requirement UNKNOWN blocks departure, keeps four fields and explicitly retries identical POST | 桌面保留 | — |
| 170 | workflow-edit actual owner preserves first-frame 105px/20px at zoom 0.5, undo/redo and keyboard cancellation | 桌面保留 | — |
| 170 | workflow-edit actual owner preserves first-frame 105px/20px at zoom 1, undo/redo and keyboard cancellation | 桌面保留 | — |
| 170 | workflow-edit actual owner preserves first-frame 105px/20px at zoom 2, undo/redo and keyboard cancellation | 桌面保留 | — |
| 170 | requirement-plan actual owner preserves first-frame 105px/20px at zoom 0.5, undo/redo and keyboard cancellation | 桌面保留 | — |
| 170 | requirement-plan actual owner preserves first-frame 105px/20px at zoom 1, undo/redo and keyboard cancellation | 桌面保留 | — |
| 170 | requirement-plan actual owner preserves first-frame 105px/20px at zoom 2, undo/redo and keyboard cancellation | 桌面保留 | — |
| 188 | spdb actual workflow connects through React Flow handles, retains state on theme, then undo/redo without implicit write | 桌面保留 | — |
| 188 | tech-blue actual workflow connects through React Flow handles, retains state on theme, then undo/redo without implicit write | 桌面保留 | — |
| 188 | github-white actual workflow connects through React Flow handles, retains state on theme, then undo/redo without implicit write | 桌面保留 | — |
| 198 | actual workflow graph accepted/layout unknown/final GET failure each retains its own original receipt without rewriting accepted stages | 桌面保留 | — |
| 218 | actual workflow accepted graph plus definitive layout rejection retains partial identity and only explicitly retries that same layout | 桌面保留 | — |
| 243 | actual Designer UNKNOWN multipart Send retries original metadata/File bytes while retaining later message and extra attachment | 桌面保留 | — |
