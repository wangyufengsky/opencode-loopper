# 第一阶段前端画布改版

## 交付范围

基于远端 main `3fe0fc459fa21e196ce64655da11f3c3402929c6`，在独立 worktree `/workspace/opencode-loopper-canvas`、分支 `feat/canvas-first-phase1` 实现。原 `/workspace/opencode-loopper` 的 `work` 分支保持干净且未切换。按用户授权只交付前端，不升版本、不生成 JAR。

- 流程创作、需求规划/执行及模板另存预览：默认宽阔点阵画布，节点与连线选中时展示详情，重复点击不重复弹出。空白、Escape、关闭回到画布；平移与缩放不会误取消。高级节点参数按需展开。
- 入口页：流程库压缩常驻操作，需求列表改为紧凑行，新建需求按内容与工作方式分组。
- PPT 工作室：作品就绪后默认收起助手与属性，保留大预览、底部页面导航；选择对象才出现相关操作。支持缩放、拖动、键盘移动/调整尺寸和 Escape 撤销拖动；应用皮肤与作品主题独立。
- 工作流与 PPT 详情共用按需展开的应用导航，支持打开聚焦、Tab 循环、Escape 和关闭返焦；列表与普通页面继续使用既有导航。
- 使用现有 spdb 风、科技蓝、GitHub 白的语义变量、圆角与主按钮状态，不增加皮肤 ID 分支或外部字体/图标请求。

保留上传、节点表单、未知请求回执、版本冲突、确认后再执行、停止证明、历史/固定输入读取及发布恢复语义。本次未修改 API、store、后端或模型配置。

需求规划与执行分别覆盖，不用编辑器验收替代执行页验收。PPT 纳入是因为用户后续将范围扩展为所有交互画布；PPT 已有对象选择和属性编辑，适用同一按需展示原则。旧 Designer、任务和模板任务页在此前更新主要调整入口，本次只回归其导航与既有功能，不声称整站全部重新设计。

## 操作可达性

| 操作 | 改版后入口 |
| --- | --- |
| 自由节点、预设 | 添加节点 |
| 流程公共参数 | 流程设置 |
| 本次需求资料与模型 | 需求与资料 |
| 输入绑定、专用参数、交付物、完成规则 | 选中节点 → 更多节点设置 |
| 节点搜索定位 | 更多工具 → 节点列表 |
| 验证、排列、刷新 | 更多工具 |
| 另存模板、调整后续计划、候选、代码成果、提前结束 | 更多工具；待审核候选保留直接入口 |
| 单步、运行至所选、节点记录 | 选中节点 |
| PPT 插入、布局、主题、层级 | 手动编辑 → 插入对象 / 页面设置 |
| PPT 助手与对象属性 | 提修改意见 / 选中对象 |

## 验证

官方锁文件依赖安装成功：`npm --prefix frontend ci --cache /tmp/loopper-npm-cache --no-audit --no-fund`，安装 356 个包，未改 lockfile。

| 检查 | 结果 |
| --- | --- |
| `npm --prefix frontend test` | 最终 173 文件、1083 项通过 |
| `npm --prefix frontend run typecheck` | 通过 |
| `npm --prefix frontend run build` | 通过；仍有大于 500 kB 的 chunk 提示 |
| `npm --prefix frontend run test:tooling` | 7 项通过 |
| `node scripts/check-project.mjs` | 通过 |
| `git diff --check` | 通过 |
| 工作流 Chromium E2E | 最终 100 个不同场景通过 |
| PPT Chromium E2E | 12 项通过 |
| 主页、应用外壳、皮肤 Chromium E2E | 20 项通过 |

浏览器使用 `/usr/bin/chromium`，1600×1000 桌面、390px 窄屏，并覆盖既有皮肤测试的 1440/1280px。工作流覆盖空白、选中、重复点击、切换、边的 Space/Enter、取消、连接、拖动、保存重开、高级参数、搜索定位、导航键盘、跨标签换肤、恢复和发布入口。

首轮工作流 94/98 通过：3 个用例尚沿用常驻按钮/高级字段入口，已改为通过真实披露控件操作；另一个按需读取用例在请求到达前同步检查计数，改用 `expect.poll` 保持原断言。修正后复跑受影响的 25 项及独立读取的 2 项均通过。最后的画布手势与模板预览增量也由这 25 项覆盖。PPT 测试夹具同步现有 V3 讨论/版本回执，同时保留 V1 问题确认兼容用例；没有为通过测试修改业务协议。

可复现浏览器命令（先停止其他占用 41773 的开发服务，仓库 Playwright 配置会自行启动 Vite）：

```sh
cd frontend
PLAYWRIGHT_CHROME_EXECUTABLE=/usr/bin/chromium npm run test:e2e -- --workers=1 'workflow-.*spec.ts' ppt.spec.ts app-shell.spec.ts home.spec.ts skins.spec.ts
```

本次实际验收用 `/tmp/loopper-*-review.config.ts` 与 `/tmp/loopper-workflow-final.config.ts` 接入同一个已启动 Vite，分别保存输出，避免并行测试组争抢端口。该配置只指定本地服务、浏览器、单 worker 与证据目录。

审查修复后对稳定候选完整复核一次：35 项聚焦单测及 typecheck 通过，随后全量 1083 项单测、构建、7 项工具检查通过。Chromium 分两批完成 15 项专项和其余 117 项，无失败；合计 100 工作流 + 12 PPT + 20 外壳/主页/皮肤 = 132 个不同场景。最终命令配置为 `/tmp/loopper-review-final.config.ts`；日志为 `review-focused-e2e.log`、`review-regression-e2e.log`、`review-unit-tests.log`、`review-build.log`、`review-tooling-tests.log`、`review-project-check.log`。最终截图已重新采集，未用旧截图替代修复后证据。

## 独立审查与修复

两位未参与对应实现的 Astra 极高审查者分别检查工作流行为，以及 PPT / 三皮肤 / 可访问性。原实现提交为 `d434dbbfe03bf6b44d161af14be3ae611d4d98bd`。共发现并修复四项 P2：

1. 默认模型原先随按需挂载的资料面板初始化，直接执行会发送空模型。现由需求页独立初始化，保留控制快照和手动选择优先级，迟到结果不覆盖新需求；模型目录仍按需读取。读取期间不排队自动执行，单独执行人工或程序节点仍可用。
2. 未发送文件或上传回执未知时，关闭面板原会丢失文件和请求键。现将 pending 状态向上聚合，保护关闭、Escape、空白、节点切换、离页和执行；暂停可能卸载输入的状态刷新，保留原文件、请求键及版本重试，明确“改为新上传”可退出恢复状态。
3. 新增小字的 muted 色在科技蓝上不足 4.5:1，已改用现有 secondary 变量。科技蓝节点/工具提示对比度恢复至 7.62:1，对象摘要为 7.06:1。
4. 流程库选中按钮及 PPT 助手展开状态使用 action-primary 文字导致科技蓝 4.27:1、GitHub 白 4.02:1，已改为 text-primary，保留背景和边框表达状态。

两位审查者独立复测上述修复通过。额外核对了重复点击、草稿拒绝切换/关闭、未知完成回执的原身份重试、菜单键盘、导航焦点、390px 无横向溢出。PPT 离页后 beforeUnload、外部点击监听及 ResizeObserver 均清理。工作流最后的空文件选择早返回仅再做源码和 SHA 核对，完整浏览器复测证据沿用；主代理的最终回归包含该修正。

证据在 `independent-review/`：工作流 findings / fixcheck / smoke / final-source-check，PPT `report.md` / `contrast-original.json` / `contrast-fixed.json` / `lifecycle.json`。没有剩余的本次审查阻断；旧 PPT header、页数、聊天提示及旧 tabs 的低对比是基线问题，本次不宣称整站符合 WCAG。

## 视觉证据与限制

工作区证据目录 `/workspace/canvas-phase1-evidence/`：

- `index.html`：离线截图画廊，可切换三套皮肤；包含流程创作前后对照、空白/选择/取消、需求规划/执行、三个入口页，以及 PPT 状态。
- `screenshot-index.md`：全部代表性页面与状态的具体文件链接；核心三皮肤各 17 状态，共 51 张。
- `before-*-editor.png`、`before-*-selected.png`：基线提交截图。
- `workflow/`：最终三皮肤截图及窄屏证据。
- `ppt/`：三皮肤默认/选中/取消/窄屏截图及 geometry JSON。
- `*-e2e.log`、`unit-tests.log`、`typecheck.log`、`build.log`、`tooling-tests.log`：验证记录；首次失败的 trace 与截图保留在 `workflow-test-results/`。

1600px 桌面上工作流画布宽度大于 1200px、高度大于 750px；PPT 默认预览区宽 1600px，助手打开后为 1256px。PPT 作品按宽高比例适应，不为填满留白而拉伸。

核心截图状态（三皮肤均有）：改版前流程创作默认/选择；改版后空白/默认/选中/取消；需求规划默认/选中编辑；需求执行 PAUSED 默认/执行详情；流程库、需求列表、新建需求；PPT 默认/选中/取消/390px。另有 GitHub 白流程画布 390px 选中/取消，另存模板 1600px/390px，以及 PPT 进入工作室、讨论、问题确认、预览、手动编辑、修改中、暂停、详情、窄屏。

所有浏览器数据均为 HTTP 模拟夹具，核心三皮肤截图标题标注“模拟数据”；补充 PPT 过程截图部分标题未标注，已在索引明确其模拟性质。不代表真实后端或模型执行。PPT 截图的断线提示来自模拟 SSE 结束，未隐藏此真实前端提示。未运行 Java/Maven/JAR、真实模型或真实远端推送/发布；没有付费调用。项目没有独立 lint script，因此未声称 ESLint 通过，采用 TypeScript、构建和 diff 检查。未执行与本次前端范围无关的全部 E2E 文件，也未进行读屏软件人工验收。

## Library 交付阻塞

按当前 Library 技能使用其官方批量上传 helper，准备上传审查 ZIP、三皮肤各两张默认/选择截图及交付记录。调用在上传准备前的 hosted apps `tools/list` 阶段失败：`library upload failed: hosted apps tools/list request failed: network`，退出码 1，未返回 HTTP 状态或具体故障主机。普通 Library 只读查询成功，但不能替代上传证明。没有确认的 Library 文件 ID；没有改网络、代理、凭据或改用旁路写入。`independent-review/library-upload-status.json` 留存此结果。本地审查包与累计补丁仍可交付。

未推送、未创建 PR、未合并、未部署。
