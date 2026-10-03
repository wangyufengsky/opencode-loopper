# W2：项目、任务列表与设置

基线 `6ffc7dbef1302450e900979d5484d4f3b3ebfa80`，分支 `feat/react-full-migration`。本模块作者为既有 `/root/react_legacy_canvas`，历史启动记录指定 `gpt-6.1-sol / xhigh`；当前工具不提供实时平台模型配置。本文区分作者聚焦验证、非作者审查与组长统一浏览器/全量门禁。

## 实际生产入口与单一所有权

| 原路由/入口 | React 入口与 owner | 保留的协议与能力 |
| --- | --- | --- |
| `/projects`，`ProjectsView.vue` | `frontend/src/pages/w2/core/ProjectsPage.tsx:14`；`projectsController.ts:50` | 原项目列表/登记、原生目录选择、版本化文档路径、Git 账号、GitLab 保存/识别/检查、LOG/JUnit 来源、公约读取/生成/停止/确认写入、取消管理与历史保留 |
| `/tasks`，`TasksView.vue` | `TasksPage.tsx:19`；`tasksController.ts:52` | 原 TaskPort 列表、facets、游标、服务器筛选；演示本地筛选；项目分组、时间排序、深链、归档恢复及永久删除许可 |
| `/settings`，`SettingsView.vue` | `SettingsPage.tsx:40`；`settingsController.ts:32`；`credentialsController.ts` | 原 settings GET/PUT、CLI 合格模型目录、全部 runtime/openCode/limits/retry/publication 字段、独立全局 Git 账号、演示数据、皮肤与画布下次实例偏好 |

`index.tsx` 导出 `ProjectsPage/TasksPage/SettingsPage`，由组长注册真实路由。本模块不导入 Vue/Pinia，不创建 history，不修改 API/store/原 Vue/原测试。现有 TaskStore 唯一拥有任务列表数据与流；React 使用注入的 plain `TaskPort.getSnapshot/subscribe`，归档/删除及项目 add/replace/remove 仍调用该唯一 owner 端口。没有新增任务 SSE 或业务状态轮询。任务列表的 1s 时钟仅投影服务端 `retryAfter`；180ms 仅合并筛选读取。

`useCoreOwner.ts:5` 将 view lease 与真实 route 生命周期分开：StrictMode 清理释放读资源，`lifecycle.retain` 在实际退出时退休 owner。`owner.ts:30` 复用 W1 `createSnapshotController/createOperationOwner`，写入只能显式 `mutate`；退休后入口与 `ownOperation` 失败均拒绝写入。项目公约原 1s 草稿/活动 GET 仅在当前公约 context 的有效 lease 内执行，先失效再逐项清 timer/子账号 owner，迟到 await 不重新排队。

UI 默认列表摘要，选中后开放 context 与高级动作；Settings 数值与基础字段保留各分区草稿。W1 语义动作/确认框用于按钮与确认，`CoreContextPanel` 由业务 owner 捕获并复核 `draftRevision`，不能用旧确认丢弃新草稿。错误、dirty、UNKNOWN 与 accepted-readback 状态在 context 外保持可见。Markdown 继续 DOMPurify，Mermaid figure 由真正 React `MermaidDiagram` 渲染；不引入 Vue Markdown 桥。

## 原断言逐项映射

下表锚点均为 `frontend/src/` 相对路径；新测试均在 `pages/w2/core/`。原静态文件定义数为 Projects 9、Tasks 8、Settings 6、四个子组件 14，共 37 个标题；不是将误称 10/7 的起点数照抄为证据。原 Settings 第一标题叫 “opens role management”，实际断言是没有 `/settings/roles` 链接且无 PUT；迁移保留其实际断言，角色入口由独立 `/roles` 页面持有。

| 原测试锚点 | 新真实 React/纯 TS 行为锚点 | 保留的实际断言 |
| --- | --- | --- |
| `views/ProjectsView.spec.ts:28`、`:52` | `pages.spec.tsx:99`、`:108`；`controllers.spec.ts:139`、`:147` | 原生绝对路径与建议名称；取消/失败保留手动输入；非法根不发 POST；退休后 picker 不覆盖 context |
| `views/ProjectsView.spec.ts:78`、`:128` | `pages.spec.tsx:150`；`controllers.spec.ts:175` | 先读已有/空 AGENTS，不自动调用 AI；生成、READY 预览、normalizationNotice、apply 是三个显式阶段，完整公约仍可读 |
| `views/ProjectsView.spec.ts:145` | `controllers.spec.ts:183`、`:194`；`ProjectsPage.tsx` 公约活动区 | 当前 activity、累计 tokens、最新 part 与 STOPPING 真实停止证明；退休后无迟到 timer/读取。作者 controller 测得 stop 协议，未另声称单测完整执行 DOM stop-confirm 按钮链 |
| `views/ProjectsView.spec.ts:187`、`:205`、`:221` | `pages.spec.tsx:89` | 持久化 stack/counts；无磁盘重析；Task 与待继续 Designer 数分开；`/designs?projectId`、模板项目深链；WORKTREE/DIRECT 与 repositoryRoot 分开 |
| `views/ProjectsView.spec.ts:242` | `pages.spec.tsx:159`；`controllers.spec.ts:208` | 显式取消管理确认，DELETE 保持原端点；项目目录、历史 Task/Designer/LoopSpec/证据保留文案；只删除登记投影 |
| `components/DirectoryPathInput.spec.ts:13`、`:22`、`:34`、`:48` | `pages.spec.tsx:99`、`:108`、`:131`；`controllers.spec.ts:147` | pending 状态、cancel/failure 手动路径；context/原路径改变与退休后的迟到选择失效，不用 mock 组件事件冒充 React 输入 |
| `components/ProjectDocumentPathDialog.spec.ts:12`、`:22`、`:35` | `pages.spec.tsx:131`；`controllers.spec.ts:154`、`:160` | 原生目录选择、loaded version、替换项目卡投影；409 保留草稿/context；无版本不写；未知仅原 summary GET |
| `components/ProjectAssistDialog.spec.ts:10`、`:21`、`:30` | `pages.spec.tsx:140`；`controllers.spec.ts:167`、`:208` | version、外部 LOG scope 与 JUnit kind/pattern；不接受接口 token；旧项目迟到 GET 不覆盖新 context；409 留草稿 |
| `components/GitCredentialForm.spec.ts:12`、`:22`、`:36`、`:50` | `pages.spec.tsx:207`；`controllers.spec.ts:100`、`:107`、`:114`、`:130` | 已存 secret 不回填；空 secret 保留；GLOBAL/INHERIT/CUSTOM；未保存 draft 只读 test 不 save；输入变化失效旧成功；失败不伪称已保存 |
| `views/TasksView.spec.ts:79` | `pages.spec.tsx:51`；`controllers.spec.ts:266` | report COMPLETED 与 linked Task AWAITING_DECISION 分开，不错给归档许可；source/document 真实 run 深链 |
| `views/TasksView.spec.ts:96` | `pages.spec.tsx:43`；`controllers.spec.ts:260` | type/project/statusGroup/archive/q/order 真实 TaskSummaryQuery；URL 保持原过滤值；不添加第二订阅/流 |
| `views/TasksView.spec.ts:112`、`:127` | `pages.spec.tsx:28`；`controllers.spec.ts:260`、`:266` | 演示项目筛选和更新时间双向排序；搜索、分组、归档范围、状态的最小 URL 查询；分组只是表现变化 |
| `views/TasksView.spec.ts:152` | `pages.spec.tsx:62`；`controllers.spec.ts:286` | 仅已归档普通 Task 可永久 DELETE；真实 default-Stay 确认；源/文档 run 禁永久删除 |
| `views/TasksView.spec.ts:170`、`:185`、`:196` | `pages.spec.tsx:51`、`:69` | 登记项目 onboarding；冻结历史 `/tasks/:id/design`；服务端 RETRY_WAIT 倒计时，不伪造进度 |
| `views/SettingsView.spec.ts:25`、`:39` | `pages.spec.tsx:182`；`controllers.spec.ts:40` | 角色不并入 settings PUT；换分区保留 draft；绝对根/空 CLI 失败自动显示阻断字段所在分区 |
| `views/SettingsView.spec.ts:65` | `pages.spec.tsx:189`；`SettingsPage.tsx` limits-grid | 超时开关控制三个 duration 字段，全部 8 个执行限制与底部对齐类；模板并发只用于新模板任务 |
| `views/SettingsView.spec.ts:90` | `pages.spec.tsx:189`；`controllers.spec.ts:23`、`:48` | CLI 实际目录、provider+model 精确资格、模型保存与模板并发；刷新 runtime；已有会话模型不会改变 |
| `views/SettingsView.spec.ts:130` | `pages.spec.tsx:224`；`controllers.spec.ts:92` | 同唯一 owner activate/deactivate，实时概览恢复，无 settings 自动 PUT |
| `views/SettingsView.spec.ts:163` | `pages.spec.tsx:224`；`controllers.spec.ts:57`、`:67` | 明确 422 拒绝后表单可改且 draft 保留；运输未知改用硬 BLOCK，不能把通用响应丢失当未接受可重复 PUT |

### 本波新增安全负控

- `controllers.spec.ts:31`：四个真实 controller 退休后显式/迟到 confirm 再调用 mutation，零 write；不是只以 UI disabled 作证明。
- `controllers.spec.ts:57`、`:67`、`:298`；`pages.spec.tsx:215`：无 key settings UNKNOWN 原全 DTO 不换，显式 GET 必须匹配全部可写组；接受后 runtime 读失败只恢复读，不再 PUT。皮肤及画布偏好变化不销毁请求身份、不刷新页面。
- `controllers.spec.ts:123`：secret-changing UNKNOWN 不用公开 metadata/version 假装证明 secret 写成功，不清原 secret、不重 PUT。
- `controllers.spec.ts:201`；`pages.spec.tsx:165`：公约 generate POST 丢失唯一 draftId，真实 API 无 by-request，不能再生成或通过 close/导航丢身份。
- `controllers.spec.ts:250` parameterized source/document：归档请求 key/body/expectedVersion 捕获；核对为 GET，不自动 POST；只有显式同原操作重试才发原 key/body；accepted 读失败保持只读。
- `controllers.spec.ts:75`、`:194`、`:208`、`:273`：旧 CLI/项目/根 scope 迟到读取不能发布新投影、复活 poll/debounce；最后 view lease 清显示时钟，不添加任务流。
- `controllers.spec.ts:84`、`:216`；`pages.spec.tsx:118`、`:238`：模型 fallback 推进 draftRevision；父资源 disposer 真抛错仍失效并退休真实账号子 owner；项目 switching 与 context-close 的旧确认均拒绝丢新草稿。

## 作者可复验结果及冻结

在 frontend cwd 执行：

```sh
./node_modules/.bin/vitest run src/pages/w2/core --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w2-evidence/core-final.json
```

实际 **exit 0，58/58 PASS，0 fail/skip**（controllers 35、真正 React page tests 23）。raw `/workspace/react-full-w2-evidence/core-final.log/json`。JSON SHA256 `aa0f3d2a1607b06350822acbe689b527684abc48490f4299da85ec0a52286099`；log `78594a187b25fdcd397abbc54db59b9c018b4015e85c8b7280482f78ead2a283`。临时限定目录类型命令 `./node_modules/.bin/tsc -p /tmp/loopper-core-tsconfig.json` 实际 exit 0，`core-types-final.log` 无输出；不是全工程类型门禁，未改 repo target/依赖。`git diff --check -- frontend/src/pages/w2/core` 无错误。

原始候选及失败保留：controllers-first 28=27/1 的唯一失败是无 key DTO 缺失属性却错误要求 own-property undefined；改为明确无 requestKey。core-first 52=51/1 的唯一失败是同名 context/textbox 的 RTL 选择歧义；改真实 textbox role，不动正文/version/权限断言。之后 54/54、57/57 与最后 58/58 分批通过，不将夹具红记产品回归。组长首轮全量并发期间两个 Settings case 超过默认 5s，作者独立聚焦相应 case 约 2.3–3.2s；未删断言或将全量超时改称已过，最终独占全量由组长确认。

源码冻结明细 SHA256 存 `/workspace/react-full-w2-evidence/core-freeze-hashes.json`。关键 source：Projects `aa2e46736e046eaa9c6c9f3ec14eb65a1ec3bb88eab0670af9007ffde8137dc3`，Tasks `7460414f302aad68ef27446bfb143558108a4326fb55b5fef2cb79bedd1eed15`，Settings `a87219774eb6a8145db60ff890bbf8008d801aaea609226fbd6dd7ad091b3c8d`；controllers spec `b2e204679c6664ccec62dddf6153b6e3c6b0fad4fba940888b52e28a269fed6b`，pages spec `bb70da395a170f9db573dc4614555fed8f6317835f7b456a3758983d7356cb13`。

本模块不声称 Vitest 证明真实系统选择器、AI 后台或文件写入；全部 API 确定性 mock，没有付费模型调用。没有运行 browser、全量 unit/build 或服务器。三皮肤/实际路由/焦点/reduced motion/桌面布局由组长与 C 的浏览器批次持有；源端能力与退休 identity 保留不等于跨刷新恢复承诺。

文档路径未知核对只接受安全可归一的 POSIX 绝对/项目相对/空值形式（`expectedDocumentPath`、`controllers.spec.ts:302`）；无法证明的 Windows/native canonical/symlink 形式保持 UNKNOWN/BLOCK，不伪造解析结果或新增恢复端点。公约生成丢失唯一 draftId、账号改变 secret 后无可验证原结果时保留真实阻断，是实际 API 能力边界，不宣称所有 unknown 均能自动恢复。

## 非作者 secondary 审查

本作者作为非作者只读 C 的 `secondary/{HomePage,RuntimePage,InsightsPage,KnowledgeHistoryPage,ToolsPage,DatabasePage,PptListPage}.tsx`、`state.ts/recovery.ts/databaseDraft.ts/pptCreation.ts/SkillMarkdown.tsx` 与四个相邻 spec，并对照原 Vue/API 行为链。命令 `./node_modules/.bin/vitest run src/pages/w2/secondary --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w2-evidence/secondary-independent.json` 实际 **exit 0，40/40 PASS**（page 18、state 7、databaseDraft 4、PPT 创建 11），raw 同名 log/json；此数与 core 作者 58 分开。

确认通过的范围：`state.ts` 读取只提交当前 mounted sequence，退休拒绝新 command；`DatabasePage.tsx:64`、`:75`、`ToolsPage.tsx:57` 与 `KnowledgeHistoryPage.tsx:60` 已显式检查 accepted 后的列表/清单读失败并保持 accepted-readback，恢复只读取、不再 PUT/POST；database secret 的公开 DTO 不可证明新 secret 接受，策略核对需完整目录与精确 CAS。`RuntimePage.tsx:17` 的显式 GET 即使 ONLINE 仍 UNCONFIRMED，不假造启动回执。`pptCreation.ts` 保存真实 File 与 SHA，消息接受后导航失败不重 POST；刷新接受只查询原 key/text/revision，临时允许精确作品目标，错误目标仍 BLOCK。读初始化/StrictMode/切主题不自动写入。

非作者进一步写两个临时、真正 React probe，**exit 1，0/2 PASS、2/2 REPRODUCED_FAIL**；源码与 raw 已归档 `/workspace/react-full-w2-evidence/secondary-review.probe.spec.tsx`、`secondary-review-probe.log/json`，临时工程文件已删除，core 16 个冻结 hash 不变。未改 C 源码/测试。

| 严重性/定位 | 实际失败与影响 | 状态 |
| --- | --- | --- |
| P2，修前 `KnowledgeHistoryPage.tsx:60`、`:63` | `archive=all` 下真实 POST `(原id,true,3)` 接受，列表已刷新、guard 已 ALLOW，但 context 仍握旧 selected，对应“恢复对话”按钮不存在。后续应显示恢复并用新 version 4；原 40 测试只验证回执/次数未检 selected 投影。probe :16 | **CLOSED**：作者用最新 items 派生 selected（最终 `:28`），当前 load apply 更新或清除 selected（`:32`）；新增实际负控 `secondary.spec.tsx:86`。原非作者 probe 原样通过且恢复真实发送 `(原id,false,4)` |
| P2，`HomePage.tsx:32`（同类 `InsightsPage/PptListPage` 原生链接） | Home 任务深链 Ctrl click 实际 `defaultPrevented=true` 且调用单页导航，吞原 RouterLink 新标签能力。probe :32，不是猜测只有静态 a。 | **CLOSED**：作者复用共享 PageLink，Home `:32`、`:37`，Insights `:32`，PPT `:35`；新增 `secondary.spec.tsx:52` 保持 Ctrl/Cmd/Shift 与普通 SPA 点击，原非作者 Ctrl probe 原样通过 |

修后冻结独立命令：`./node_modules/.bin/vitest run src/pages/w2/secondary src/pages/w2/core/secondary-review.probe.spec.tsx --maxWorkers=1 --reporter=json --outputFile=/workspace/react-full-w2-evidence/secondary-independent-final.json`，实际 **exit 0，44/44 PASS、0 fail/skip**（C 最终 42 + 同一非作者 probe 2）。原 probe SHA256 `9ba620471addd8e8617fcddb5bd746ec67ff8ed82c09a71ab92f54fb4ba71769`，修前/修后源码相同；临时工程文件再次删除。最终 JSON SHA256 `a62f211aad9062b84944e2de9c5d78d4e6c5111ae7f33fcec4145cdff861aabf`、log `298577f069529d7c2718347177d3031da6414616c1ae6019c3a1306eedbabe70`。C 的 `secondary-freeze-hashes.json` 24 项与 core 原 16 项复跑前后均零变化；未把只修作者 mock 或删除失败 probe 当关闭。修前 40 绿、额外 2 红与最终 44 绿分批保留，不相互抵消。

core 另由 A 非作者独立复跑 **58/58 PASS**，raw `/workspace/react-full-w2-evidence/core-independent-workflow.log/json`，A 在 `workflow-roles.md` 独立节记录。本文将其与作者 58、C 非作者 44 各自持有，不重复累计成新增业务用例。

上述非作者 Vitest 未证明真实浏览器系统行为，也不把单个 File 正控泛化为多附件顺序、所有 await 退出点或跨刷新原 File 实例均实测。C 浏览器资源/路由/三皮肤与组长全量门禁另持证据；未新增/执行窄屏验收。
