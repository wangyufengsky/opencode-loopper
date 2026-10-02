# 第一阶段前端画布改版

## 交付范围

基于远端 main `3fe0fc459fa21e196ce64655da11f3c3402929c6`，在独立 worktree `/workspace/opencode-loopper-canvas`、分支 `feat/canvas-first-phase1` 实现。原 `/workspace/opencode-loopper` 的 `work` 分支保持干净且未切换。按用户授权只交付前端，不升版本、不生成 JAR。

- 流程创作、需求规划/执行及模板另存预览：默认宽阔点阵画布，节点与连线选中时展示详情，重复点击不重复弹出。空白、Escape、关闭回到画布；平移与缩放不会误取消。高级节点参数按需展开。
- 入口页：流程库压缩常驻操作，需求列表改为紧凑行，新建需求按内容与工作方式分组。
- PPT 工作室：作品就绪后默认收起助手与属性，保留大预览、底部页面导航；选择对象才出现相关操作。支持缩放、拖动、键盘移动/调整尺寸和 Escape 撤销拖动；应用皮肤与作品主题独立。
- 工作流与 PPT 详情共用按需展开的应用导航，支持打开聚焦、Tab 循环、Escape 和关闭返焦；列表与普通页面继续使用既有导航。
- 使用现有 spdb 风、科技蓝、GitHub 白的语义变量、圆角与主按钮状态，不增加皮肤 ID 分支或外部字体/图标请求。

保留上传、节点表单、未知请求回执、版本冲突、确认后再执行、停止证明、历史/固定输入读取及发布恢复语义。本次未修改 API、store、后端或模型配置。

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
| `npm --prefix frontend test` | 172 文件、1070 项通过 |
| `npm --prefix frontend run typecheck` | 通过 |
| `npm --prefix frontend run build` | 通过；仍有大于 500 kB 的 chunk 提示 |
| `npm --prefix frontend run test:tooling` | 7 项通过 |
| `node scripts/check-project.mjs` | 通过 |
| `git diff --check` | 通过 |
| 工作流 Chromium E2E | 98 个不同场景最终通过 |
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

## 视觉证据与限制

工作区证据目录 `/workspace/canvas-phase1-evidence/`：

- `index.html`：离线截图画廊，可切换三套皮肤；包含流程创作前后对照、空白/选择/取消、需求规划/执行、三个入口页，以及 PPT 状态。
- `before-*-editor.png`、`before-*-selected.png`：基线提交截图。
- `workflow/`：最终三皮肤截图及窄屏证据。
- `ppt/`：三皮肤默认/选中/取消/窄屏截图及 geometry JSON。
- `*-e2e.log`、`unit-tests.log`、`typecheck.log`、`build.log`、`tooling-tests.log`：验证记录；首次失败的 trace 与截图保留在 `workflow-test-results/`。

1600px 桌面上工作流画布宽度大于 1200px、高度大于 750px；PPT 默认预览区宽 1600px，助手打开后为 1256px。PPT 作品按宽高比例适应，不为填满留白而拉伸。

所有浏览器数据均为明确的 HTTP 模拟夹具，展示标题标注“模拟数据”，不代表真实后端或模型执行。PPT 截图的断线提示来自模拟 SSE 结束，未隐藏此真实前端提示。未运行 Java/Maven/JAR、真实模型或真实远端推送/发布；没有付费调用。项目没有独立 lint script，因此未声称 ESLint 通过，采用 TypeScript、构建和 diff 检查。未执行与本次前端范围无关的全部 E2E 文件。

未推送、未创建 PR、未合并、未部署。
